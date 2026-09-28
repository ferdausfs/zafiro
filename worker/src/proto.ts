/**
 * Shared wire types (must stay in sync with the Android app's
 * com.niki914.zafiro.app.cloud.CloudBrainModels — snake_case JSON).
 */

export interface CloudToolSpec {
  name: string;
  description: string;
  schema_json: string;
}

export interface CloudTaskSubmit {
  device_id: string;
  task: string;
  context?: string;
  allowed_tools: string[];
  tools?: CloudToolSpec[];
  max_steps?: number;
}

export interface CloudAction {
  id: string;
  tool: string;
  arguments_json: string;
}

export interface CloudActionResult {
  id: string;
  ok: boolean;
  message: string;
}

export interface CloudEvent {
  kind: "step" | "reply" | "error";
  text: string;
}

export type SessionStatus = "running" | "waiting_phone" | "completed" | "failed";

export interface CloudSessionSnapshot {
  session_id: string;
  status: SessionStatus;
  cursor: number;
  pending_actions: CloudAction[];
  reply?: string | null;
  error?: string | null;
  new_events: CloudEvent[];
}

/** OpenAI-compatible chat completion types (subset we use). */
export interface LlmToolDef {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

export interface LlmToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

export type LlmMessage =
  | { role: "system"; content: string }
  | { role: "user"; content: string }
  | { role: "assistant"; content: string | null; tool_calls?: LlmToolCall[] }
  | { role: "tool"; tool_call_id: string; content: string };

export function ok(data: unknown): Response {
  return new Response(JSON.stringify(data), {
    headers: { "content-type": "application/json" },
  });
}

export function err(status: number, message: string): Response {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "content-type": "application/json" },
  });
}
