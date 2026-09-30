/**
 * Zafiro Cloud Brain — public Worker entry point.
 *
 * Endpoints (all require Authorization: Bearer <CLOUD_BRAIN_SECRET>):
 *   GET  /v1/health
 *   POST /v1/tasks                      {device_id, task, context?, allowed_tools, tools?, max_steps?}
 *   GET  /v1/sessions/:id?cursor=&wait=  (long-poll session snapshot)
 *   POST /v1/sessions/:id/results       {results: [{id, ok, message}]}
 *   POST /v1/sessions/:id/cancel
 *   GET  /v1/ai/models                   (Workers AI model catalogue)
 *   POST /v1/ai/chat/completions         (OpenAI-compatible Workers AI proxy)
 *   GET|POST /v1/trade/signal            (TradingAgents-style trading signal)
 *   GET|POST /v1/trade/backtest          (point-in-time backtest-lite)
 *   GET  /v1/trade/memory?symbol=        (signal memory log + track record)
 *   POST /v1/trade/memory/resolve        (resolve matured signals now)
 */

import type { BrainEnv } from "./llm";
import { err, ok, type CloudTaskSubmit } from "./proto";
import { handleTradeSignal, resolveDueSignals } from "./trade";
import { handleTradeBacktest } from "./backtest";

export { AgentSession } from "./brain";

const DO_BASE = "https://do.internal";

export default {
  async fetch(request: Request, env: BrainEnv): Promise<Response> {
    if (request.method !== "GET" && request.method !== "POST") {
      return err(405, "method not allowed");
    }

    if (!isAuthorized(request, env)) {
      return err(401, "unauthorized");
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/v1/health" && request.method === "GET") {
      return ok({ status: "ok", time: new Date().toISOString() });
    }

    if (path === "/v1/ai/models" && request.method === "GET") {
      return ok({
        object: "list",
        data: AI_MODELS.map((id) => ({ id, object: "model", owned_by: "cloudflare" })),
      });
    }

    if (path === "/v1/ai/chat/completions" && request.method === "POST") {
      if (!env.AI) {
        return err(500, "Workers AI binding is not configured (add [ai] to wrangler.toml)");
      }
      let body: {
        model?: string;
        messages?: unknown;
        max_tokens?: number;
        temperature?: number;
        tools?: unknown;
        tool_choice?: unknown;
        stream?: boolean;
        stream_options?: unknown;
      };
      try {
        body = await request.json() as typeof body;
      } catch {
        return err(400, "invalid JSON body");
      }
      const model = typeof body.model === "string" ? body.model.trim() : "";
      if (!model.startsWith("@cf/")) {
        return err(400, "model must be a @cf/ Workers AI model id");
      }
      const input: Record<string, unknown> = { messages: sanitizeMessages(body.messages) };
      if (typeof body.max_tokens === "number") input.max_tokens = body.max_tokens;
      if (typeof body.temperature === "number") input.temperature = body.temperature;
      // function calling: forward OpenAI-style tool definitions + choice so the
      // agent runtime can drive phone tools through Cloudflare models.
      if (Array.isArray(body.tools) && body.tools.length > 0) {
        input.tools = body.tools;
        input.tool_choice = body.tool_choice ?? "auto";
      }
      let out: unknown;
      try {
        out = await env.AI.run(model, input);
      } catch (e) {
        return err(502, `Workers AI error: ${(e as Error).message}`);
      }
      const completion = normalizeAiResult(out, model);
      if (body.stream === true) {
        return sseFromCompletion(completion, body.stream_options);
      }
      return ok(completion);
    }

    if (path === "/v1/trade/signal" && (request.method === "GET" || request.method === "POST")) {
      return handleTradeSignal(request, env);
    }

    if (path === "/v1/trade/backtest" && (request.method === "GET" || request.method === "POST")) {
      return handleTradeBacktest(request, env);
    }

    if (path === "/v1/trade/memory" && request.method === "GET") {
      const symbol = url.searchParams.get("symbol") || "";
      const limit = Number(url.searchParams.get("limit") || "20") || 20;
      const stub = env.TRADE_MEMORY.get(env.TRADE_MEMORY.idFromName("global"));
      const hist = await stub.fetch("https://do.internal/history?symbol=" + encodeURIComponent(symbol) + "&limit=" + limit);
      const stats = await stub.fetch("https://do.internal/stats?symbol=" + encodeURIComponent(symbol));
      return ok({ history: await hist.json(), stats: await stats.json() });
    }

    if (path === "/v1/trade/memory/resolve" && request.method === "POST") {
      const n = await resolveDueSignals(env);
      return ok({ resolved: n });
    }

    if (path === "/v1/tasks" && request.method === "POST") {
      let submit: CloudTaskSubmit;
      try {
        submit = await request.json() as CloudTaskSubmit;
      } catch {
        return err(400, "invalid JSON body");
      }
      if (!submit?.task || typeof submit.task !== "string") {
        return err(400, "task is required");
      }
      const sessionId = crypto.randomUUID();
      const stub = env.AGENT_SESSION.get(env.AGENT_SESSION.idFromName(sessionId));
      const res = await stub.fetch(`${DO_BASE}/start`, {
        method: "POST",
        body: JSON.stringify({ session_id: sessionId, submit }),
      });
      return res;
    }

    const sessionMatch = /^\/v1\/sessions\/([A-Za-z0-9-]+)$/.exec(path);
    if (sessionMatch && request.method === "GET") {
      const stub = stubFor(env, sessionMatch[1]);
      const cursor = url.searchParams.get("cursor") || "0";
      const wait = url.searchParams.get("wait") || "0";
      return stub.fetch(`${DO_BASE}/poll?cursor=${encodeURIComponent(cursor)}&wait=${encodeURIComponent(wait)}`);
    }

    const resultsMatch = /^\/v1\/sessions\/([A-Za-z0-9-]+)\/results$/.exec(path);
    if (resultsMatch && request.method === "POST") {
      const stub = stubFor(env, resultsMatch[1]);
      return stub.fetch(`${DO_BASE}/results`, { method: "POST", body: await request.text() });
    }

    const cancelMatch = /^\/v1\/sessions\/([A-Za-z0-9-]+)\/cancel$/.exec(path);
    if (cancelMatch && request.method === "POST") {
      const stub = stubFor(env, cancelMatch[1]);
      return stub.fetch(`${DO_BASE}/cancel`, { method: "POST", body: "{}" });
    }

    return err(404, "not found");
  },

  // Daily cron: resolve matured trading signals against realized returns.
  async scheduled(event: ScheduledController, env: BrainEnv, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(resolveDueSignals(env));
  },
} satisfies ExportedHandler<BrainEnv>;

function stubFor(env: BrainEnv, sessionId: string): DurableObjectStub {
  return env.AGENT_SESSION.get(env.AGENT_SESSION.idFromName(sessionId));
}

function isAuthorized(request: Request, env: BrainEnv): boolean {
  const expected = env.CLOUD_BRAIN_SECRET;
  if (!expected) return false;
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  return token.length > 0 && timingSafeEqual(token, expected);
}

/**
 * The Zafiro client always requests OpenAI-style SSE (`stream: true` +
 * `stream_options.include_usage`). The Workers AI binding returns a full JSON
 * completion, so we synthesise a spec-compliant SSE stream from it: one content
 * delta, one finish delta, optional usage chunk, then [DONE]. Any OpenAI
 * compatible client (including Okia's SSE parser) can consume this.
 */
function sseLine(obj: unknown): string {
  return `data: ${JSON.stringify(obj)}\n\n`;
}

function sseFromCompletion(
  completion: Record<string, unknown>,
  streamOptions: unknown,
): Response {
  const id = typeof completion.id === "string" ? completion.id : "chatcmpl-" + crypto.randomUUID();
  const model = typeof completion.model === "string" ? completion.model : "";
  const created = typeof completion.created === "number" ? completion.created : Math.floor(Date.now() / 1000);
  const choices = Array.isArray(completion.choices) ? completion.choices as Array<Record<string, unknown>> : [];
  const choice = choices[0] ?? {};
  const message = (choice.message ?? {}) as Record<string, unknown>;

  const delta: Record<string, unknown> = { role: "assistant" };
  if (typeof message.content === "string" && message.content.length > 0) {
    delta.content = message.content;
  }
  if (Array.isArray(message.tool_calls) && message.tool_calls.length > 0) {
    delta.tool_calls = message.tool_calls;
  }

  let body = sseLine({
    id, object: "chat.completion.chunk", created, model,
    choices: [{ index: 0, delta, finish_reason: null }],
  });
  body += sseLine({
    id, object: "chat.completion.chunk", created, model,
    choices: [{ index: 0, delta: {}, finish_reason: choice.finish_reason ?? "stop" }],
  });
  if (streamOptions && typeof streamOptions === "object" &&
      (streamOptions as Record<string, unknown>).include_usage) {
    body += sseLine({
      id, object: "chat.completion.chunk", created, model,
      choices: [], usage: completion.usage ?? {},
    });
  }
  body += "data: [DONE]\n\n";

  return new Response(body, {
    headers: { "content-type": "text/event-stream", "cache-control": "no-cache" },
  });
}

/**
 * Workers AI validates `messages[].content` against a strict string schema:
 * `null` (OpenAI's convention for an assistant message that only carries
 * tool_calls) and multimodal content arrays are both rejected. Normalise every
 * message to a plain string so multi-turn tool loops work end to end.
 */
function sanitizeMessages(messages: unknown): unknown {
  if (!Array.isArray(messages)) return messages;
  return messages.map((raw) => {
    const msg = (raw ?? {}) as Record<string, unknown>;
    let content = msg.content;
    if (content === null || content === undefined) {
      content = "";
    } else if (Array.isArray(content)) {
      content = content
        .map((part) => {
          if (part && typeof part === "object" && typeof (part as Record<string, unknown>).text === "string") {
            return (part as Record<string, unknown>).text as string;
          }
          return "";
        })
        .join("");
    } else if (typeof content !== "string") {
      content = String(content);
    }
    return { ...msg, content };
  });
}

/**
 * Workers AI models verified to work through this proxy (live-tested).
 * The four marked [tools] support function calling, i.e. they can drive the
 * agent loop; the rest are chat/text only. Any other "@cf/..." id can still be
 * passed directly to /v1/ai/chat/completions — this list only feeds /v1/ai/models.
 */
const AI_MODELS: string[] = [
  "@cf/meta/llama-3.3-70b-instruct-fp8-fast", // [tools]
  "@cf/meta/llama-4-scout-17b-16e-instruct",  // [tools]
  "@cf/meta/llama-3.1-8b-instruct-fast",      // [tools]
  "@cf/ibm-granite/granite-4.0-h-micro",      // [tools]
  "@cf/meta/llama-3.2-3b-instruct",
  "@cf/meta/llama-3.2-1b-instruct",
  "@cf/mistral/mistral-7b-instruct-v0.1",
  "@cf/qwen/qwq-32b",
  "@cf/qwen/qwen2.5-coder-32b-instruct",
  "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
];

/**
 * Workers AI models return either an OpenAI-shaped object (newer chat models)
 * or a legacy `{ response: "..." }` / `{ result: ... }` payload. Normalise both
 * into an OpenAI chat.completion so any OpenAI-compatible client can consume it.
 */
function normalizeAiResult(out: unknown, model: string): Record<string, unknown> {
  const o = (out ?? {}) as Record<string, unknown>;
  if (Array.isArray(o.choices)) return o;
  const content =
    typeof o.response === "string" ? o.response :
    typeof o.result === "string" ? o.result :
    typeof o.text === "string" ? o.text : "";
  return {
    id: "chatcmpl-" + crypto.randomUUID(),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, message: { role: "assistant", content }, finish_reason: "stop" }],
    usage: typeof o.usage === "object" && o.usage !== null ? o.usage : {},
  };
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}
