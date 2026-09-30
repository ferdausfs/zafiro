/**
 * LLM client: any OpenAI-compatible /chat/completions endpoint
 * (env.BRAIN_BASE_URL + env.BRAIN_API_KEY + env.BRAIN_MODEL).
 */

import type { LlmMessage, LlmToolCall, LlmToolDef } from "./proto";

export interface BrainEnv {
  /** Workers AI binding (wrangler.toml [ai]) — lets the worker run CF models
   *  without a separate API token. Used by the /v1/ai/* proxy endpoints. */
  AI?: {
    run(model: string, input: Record<string, unknown>): Promise<unknown>;
  };
  BRAIN_BASE_URL?: string;
  BRAIN_API_KEY?: string;
  BRAIN_MODEL?: string;
  TAVILY_API_KEY?: string;
  CLOUD_BRAIN_SECRET?: string;
  AGENT_SESSION: DurableObjectNamespace;
}

const DEFAULT_BASE_URL = "https://api.openai.com/v1";
const DEFAULT_MODEL = "gpt-4o-mini";

export interface LlmRound {
  content: string | null;
  toolCalls: LlmToolCall[];
  usageTokens: number;
}

export async function chatCompletion(
  env: BrainEnv,
  messages: LlmMessage[],
  tools: LlmToolDef[],
): Promise<LlmRound> {
  const baseUrl = (env.BRAIN_BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, "");
  const apiKey = env.BRAIN_API_KEY;
  if (!apiKey) {
    throw new Error("BRAIN_API_KEY secret is not set (wrangler secret put BRAIN_API_KEY)");
  }
  const model = env.BRAIN_MODEL || DEFAULT_MODEL;

  const res = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model,
      messages,
      tools: tools.length > 0 ? tools : undefined,
      tool_choice: tools.length > 0 ? "auto" : undefined,
      temperature: 0.4,
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`LLM HTTP ${res.status}: ${body.slice(0, 300)}`);
  }

  const data = await res.json() as {
    choices?: Array<{
      message?: { content?: string | null; tool_calls?: LlmToolCall[] };
    }>;
    usage?: { total_tokens?: number };
  };

  const message = data.choices?.[0]?.message;
  return {
    content: message?.content ?? null,
    toolCalls: message?.tool_calls ?? [],
    usageTokens: data.usage?.total_tokens ?? 0,
  };
}
