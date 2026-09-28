/**
 * AgentSession Durable Object: the server-side brain for one cloud task.
 *
 * Drives an OpenAI-compatible tool loop. Server tools (web_search/web_fetch)
 * run inline; phone tools become pending actions — the DO parks in
 * "waiting_phone" until the device posts results, then continues reasoning.
 * State is persisted to DO storage so the session survives restarts and the
 * phone being offline for hours.
 */

import { chatCompletion, type BrainEnv } from "./llm";
import { SERVER_TOOLS, isServerTool, runServerTool } from "./tools";
import type {
  CloudAction,
  CloudActionResult,
  CloudEvent,
  CloudSessionSnapshot,
  CloudTaskSubmit,
  LlmMessage,
  LlmToolDef,
  SessionStatus,
} from "./proto";

const SESSION_TIMEOUT_MS = 10 * 60 * 1000;
const MAX_STEPS_HARD_CAP = 40;
const TOOL_RESULT_LIMIT = 8000;
const EVENT_TEXT_LIMIT = 400;

interface PersistedSession {
  status: SessionStatus;
  messages: LlmMessage[];
  toolsMeta: CloudTaskSubmit["tools"];
  allowedTools: string[];
  pendingActions: CloudAction[];
  events: CloudEvent[];
  stepCount: number;
  maxSteps: number;
  reply: string | null;
  error: string | null;
  startedAtMs: number;
}

export class AgentSession implements DurableObject {
  private readonly state: DurableObjectState;
  private readonly env: BrainEnv;

  private loaded = false;
  private loopRunning = false;
  private sessionId = "";
  private s: PersistedSession = emptySession();
  private waiters: Array<() => void> = [];

  constructor(state: DurableObjectState, env: BrainEnv) {
    this.state = state;
    this.env = env;
  }

  // ------------------------------------------------------------- storage

  private async load(): Promise<void> {
    if (this.loaded) return;
    const saved = await this.state.storage.get<PersistedSession>("session");
    if (saved) {
      this.s = { ...emptySession(), ...saved };
    }
    this.loaded = true;
  }

  private async persist(): Promise<void> {
    await this.state.storage.put("session", this.s);
    this.signal();
  }

  private signal(): void {
    for (const w of this.waiters.splice(0)) w();
  }

  // --------------------------------------------------------------- entry

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/start" && request.method === "POST") {
      const body = await request.json() as { session_id: string; submit: CloudTaskSubmit };
      return Response.json(await this.start(body.session_id, body.submit));
    }
    if (path === "/results" && request.method === "POST") {
      const body = await request.json() as { results: CloudActionResult[] };
      return Response.json(await this.onResults(body.results || []));
    }
    if (path === "/poll" && request.method === "GET") {
      const cursor = Number(url.searchParams.get("cursor") || "0");
      const wait = Number(url.searchParams.get("wait") || "0");
      return Response.json(await this.poll(cursor, wait));
    }
    if (path === "/cancel" && request.method === "POST") {
      return Response.json(await this.cancel());
    }
    return new Response("not found", { status: 404 });
  }

  // ------------------------------------------------------------- actions

  private async start(sessionId: string, submit: CloudTaskSubmit): Promise<CloudSessionSnapshot> {
    await this.load();
    this.sessionId = sessionId;

    if (this.s.status === "completed" || this.s.status === "failed") {
      return this.snapshot();
    }

    // Fresh session (idempotent on duplicate submits)
    if (this.s.messages.length === 0) {
      const phoneTools: CloudTaskSubmit["tools"] = submit.tools || [];
      const allowed = (submit.allowed_tools || []).filter((name) =>
        phoneTools.some((t) => t.name === name),
      );
      this.s = {
        ...emptySession(),
        maxSteps: Math.min(Math.max(submit.max_steps || 20, 1), MAX_STEPS_HARD_CAP),
        allowedTools: allowed,
        toolsMeta: phoneTools.filter((t) => allowed.includes(t.name)),
        messages: [
          {
            role: "system",
            content: systemPrompt(),
          },
          {
            role: "user",
            content: [
              submit.task,
              submit.context ? `\n\n[event context]\n${submit.context}` : "",
            ].join(""),
          },
        ],
        startedAtMs: Date.now(),
      };
      await this.state.storage.setAlarm(Date.now() + SESSION_TIMEOUT_MS);
      await this.persist();
    }

    void this.runLoop();
    return this.snapshot();
  }

  private async onResults(results: CloudActionResult[]): Promise<CloudSessionSnapshot> {
    await this.load();
    if (this.s.status !== "waiting_phone") return this.snapshot();

    for (const result of results) {
      const action = this.s.pendingActions.find((a) => a.id === result.id);
      if (!action) continue;
      this.s.messages.push({
        role: "tool",
        tool_call_id: result.id,
        content: JSON.stringify({ ok: result.ok, message: result.message }).slice(
          0,
          TOOL_RESULT_LIMIT,
        ),
      });
      this.s.events.push({
        kind: "step",
        text: `phone ${action.tool} -> ${result.ok ? "ok" : "failed"}: ${
          (result.message || "").slice(0, 120)
        }`,
      });
    }
    const doneIds = new Set(results.map((r) => r.id));
    this.s.pendingActions = this.s.pendingActions.filter((a) => !doneIds.has(a.id));
    this.s.status = "running";
    await this.persist();

    // Drive the loop to its next stop point before answering.
    await this.runLoop();
    return this.snapshot();
  }

  private async poll(cursor: number, waitSeconds: number): Promise<CloudSessionSnapshot> {
    await this.load();
    const hasNew = this.s.events.length > cursor;
    const terminal = this.s.status === "completed" || this.s.status === "failed";
    if (!hasNew && !terminal && waitSeconds > 0) {
      await waitFor(this.waiters, Math.min(waitSeconds, 30) * 1000);
    }
    return this.snapshot(cursor);
  }

  private async cancel(): Promise<CloudSessionSnapshot> {
    await this.load();
    if (this.s.status !== "completed" && this.s.status !== "failed") {
      this.s.status = "failed";
      this.s.error = "cancelled by device";
      this.s.events.push({ kind: "error", text: "cancelled by device" });
      await this.state.storage.deleteAlarm().catch(() => {});
      await this.persist();
    }
    return this.snapshot();
  }

  /** Timeout guard: fail the session if it ran too long. */
  async alarm(): Promise<void> {
    await this.load();
    if (this.s.status === "completed" || this.s.status === "failed") return;
    this.s.status = "failed";
    this.s.error = "session timeout";
    this.s.events.push({ kind: "error", text: "session timeout" });
    await this.persist();
  }

  // ---------------------------------------------------------------- loop

  private toolDefs(): LlmToolDef[] {
    const phoneDefs: LlmToolDef[] = (this.s.toolsMeta || []).map((t) => ({
      type: "function",
      function: {
        name: t.name,
        description: t.description,
        parameters: safeParseSchema(t.schema_json),
      },
    }));
    const serverDefs: LlmToolDef[] = Object.entries(SERVER_TOOLS).map(([name, def]) => ({
      type: "function",
      function: { name, description: def.description, parameters: def.parameters },
    }));
    return [...phoneDefs, ...serverDefs];
  }

  private async runLoop(): Promise<void> {
    if (this.loopRunning) return;
    this.loopRunning = true;
    try {
      while (this.s.status === "running") {
        if (this.s.stepCount >= this.s.maxSteps) {
          this.failWith("max reasoning steps reached");
          break;
        }
        this.s.stepCount++;

        let content: string | null = null;
        let toolCalls: Array<{ id: string; function: { name: string; arguments: string } }> = [];
        try {
          const round = await chatCompletion(this.env, this.s.messages, this.toolDefs());
          content = round.content;
          toolCalls = round.toolCalls;
        } catch (e) {
          this.failWith(`LLM error: ${(e as Error).message}`);
          break;
        }

        if (toolCalls.length === 0) {
          this.s.reply = content ?? "";
          this.s.status = "completed";
          this.s.messages.push({ role: "assistant", content: content ?? "" });
          this.s.events.push({ kind: "reply", text: (content ?? "").slice(0, EVENT_TEXT_LIMIT) });
          await this.state.storage.deleteAlarm().catch(() => {});
          await this.persist();
          break;
        }

        this.s.messages.push({
          role: "assistant",
          content,
          tool_calls: toolCalls as never,
        });

        // Server tools run inline; phone tools queue for the device.
        const phoneCalls = [];
        for (const call of toolCalls) {
          if (isServerTool(call.function.name)) {
            try {
              const result = await runServerTool(
                call.function.name,
                call.function.arguments,
                this.env.TAVILY_API_KEY,
              );
              this.s.messages.push({
                role: "tool",
                tool_call_id: call.id,
                content: result.slice(0, TOOL_RESULT_LIMIT),
              });
              this.s.events.push({
                kind: "step",
                text: `${call.function.name} ok`,
              });
            } catch (e) {
              this.s.messages.push({
                role: "tool",
                tool_call_id: call.id,
                content: JSON.stringify({ ok: false, error: (e as Error).message }),
              });
              this.s.events.push({
                kind: "step",
                text: `${call.function.name} failed`,
              });
            }
          } else {
            phoneCalls.push(call);
          }
        }

        if (phoneCalls.length > 0) {
          for (const call of phoneCalls) {
            if (this.s.pendingActions.some((a) => a.id === call.id)) continue;
            this.s.pendingActions.push({
              id: call.id,
              tool: call.function.name,
              arguments_json: call.function.arguments,
            });
          }
          this.s.status = "waiting_phone";
          this.s.events.push({
            kind: "step",
            text: `waiting for phone: ${phoneCalls.map((c) => c.function.name).join(", ")}`,
          });
          await this.persist();
          return; // resume from onResults
        }
        await this.persist();
      }
    } finally {
      this.loopRunning = false;
    }
  }

  private failWith(message: string): void {
    this.s.status = "failed";
    this.s.error = message.slice(0, 500);
    this.s.events.push({ kind: "error", text: this.s.error });
    this.state.storage.deleteAlarm().catch(() => {});
    void this.persist();
  }

  private snapshot(fromCursor = 0): CloudSessionSnapshot {
    return {
      session_id: this.sessionId,
      status: this.s.status,
      cursor: this.s.events.length,
      pending_actions: this.s.pendingActions,
      reply: this.s.reply,
      error: this.s.error,
      new_events: this.s.events.slice(fromCursor),
    };
  }
}

function emptySession(): PersistedSession {
  return {
    status: "running",
    messages: [],
    toolsMeta: [],
    allowedTools: [],
    pendingActions: [],
    events: [],
    stepCount: 0,
    maxSteps: 20,
    reply: null,
    error: null,
    startedAtMs: 0,
  };
}

function safeParseSchema(schemaJson: string): Record<string, unknown> {
  if (!schemaJson) return { type: "object", properties: {} };
  try {
    const parsed = JSON.parse(schemaJson);
    return typeof parsed === "object" && parsed !== null ? parsed : { type: "object", properties: {} };
  } catch {
    return { type: "object", properties: {} };
  }
}

function waitFor(waiters: Array<() => void>, ms: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(done, ms);
    function done(): void {
      clearTimeout(timer);
      const idx = waiters.indexOf(done);
      if (idx >= 0) waiters.splice(idx, 1);
      resolve();
    }
    waiters.push(done);
  });
}

function systemPrompt(): string {
  return [
    "You are the Cloud Brain of an Android device agent (Zafiro).",
    "You plan and decide; the PHONE executes device actions for you.",
    "Phone tools run on the device (terminal = Android shell, launch_app, open_uri, notify, screen_operation_accessibility/screen_operation_shell = UI automation, memory, todo_write, find_installed_apps).",
    "Server tools (web_search, web_fetch) run where you are.",
    "Rules:",
    "- Prefer the smallest set of steps that completes the task.",
    "- After each phone result, check ok; on failure adapt or report instead of retrying blindly.",
    "- When the task is done, reply with a concise final answer in the user's language (no tool call).",
    "- Never ask the phone for secrets or tokens.",
  ].join("\n");
}
