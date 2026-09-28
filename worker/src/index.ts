/**
 * Zafiro Cloud Brain — public Worker entry point.
 *
 * Endpoints (all require Authorization: Bearer <CLOUD_BRAIN_SECRET>):
 *   GET  /v1/health
 *   POST /v1/tasks                      {device_id, task, context?, allowed_tools, tools?, max_steps?}
 *   GET  /v1/sessions/:id?cursor=&wait=  (long-poll session snapshot)
 *   POST /v1/sessions/:id/results       {results: [{id, ok, message}]}
 *   POST /v1/sessions/:id/cancel
 */

import type { BrainEnv } from "./llm";
import { err, ok, type CloudTaskSubmit } from "./proto";

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

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}
