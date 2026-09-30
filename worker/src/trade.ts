/**
 * TradingAgents-style trading signal engine.
 *
 * Inspired by TauricResearch/TradingAgents (multi-agent LLM trading framework):
 * an analyst team (technical / news / sentiment) briefs a trader agent that
 * weighs the bull vs bear cases with a risk manager before emitting a final
 * BUY / SELL / HOLD signal.
 *
 * Endpoint: GET|POST /v1/trade/signal?symbol=AAPL[&days=90][&risk=medium][&position=none]
 * Data source: Yahoo Finance public chart + search (news) endpoints.
 * LLM: Workers AI via the [ai] binding - no external API key needed.
 */

import type { BrainEnv } from "./llm";
import { err, ok } from "./proto";

const SIGNAL_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

interface Candle {
  t: number;
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;
}

interface Indicators {
  price: number;
  change_1d_pct: number;
  change_5d_pct: number;
  change_20d_pct: number;
  sma20: number | null;
  sma50: number | null;
  sma200: number | null;
  rsi14: number;
  volatility_20d_pct: number;
  volume_vs_avg20: number;
  high_52w: number;
  low_52w: number;
  range_position_pct: number;
}

function rangeFor(days: number): string {
  if (days <= 7) return "5d";
  if (days <= 30) return "1mo";
  if (days <= 90) return "3mo";
  if (days <= 200) return "6mo";
  return "1y";
}

function sma(values: number[], period: number): number | null {
  if (values.length < period) return null;
  const slice = values.slice(-period);
  return slice.reduce((a, b) => a + b, 0) / period;
}

function rsi(closes: number[], period = 14): number {
  if (closes.length < period + 1) return 50;
  let gain = 0;
  let loss = 0;
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1];
    if (d >= 0) gain += d;
    else loss -= d;
  }
  let avgGain = gain / period;
  let avgLoss = loss / period;
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1];
    avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period;
    avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period;
  }
  if (avgLoss === 0) return 100;
  return 100 - 100 / (1 + avgGain / avgLoss);
}

function stdev(values: number[]): number {
  if (values.length < 2) return 0;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  const variance =
    values.reduce((a, b) => a + (b - mean) * (b - mean), 0) / (values.length - 1);
  return Math.sqrt(variance);
}

function pctChange(closes: number[], back: number): number {
  if (closes.length <= back) return 0;
  const then = closes[closes.length - 1 - back];
  const now = closes[closes.length - 1];
  return then === 0 ? 0 : ((now - then) / then) * 100;
}

function round(n: number, d = 2): number {
  const f = Math.pow(10, d);
  return Math.round(n * f) / f;
}

function extractAiContent(out: unknown): string {
  const o = (out ?? {}) as Record<string, unknown>;
  if (Array.isArray(o.choices)) {
    const msg = ((o.choices as Array<Record<string, unknown>>)[0] ?? {}).message as
      | Record<string, unknown>
      | undefined;
    if (msg && typeof msg.content === "string") return msg.content;
  }
  if (typeof o.response === "string") return o.response;
  if (typeof o.result === "string") return o.result;
  if (typeof o.text === "string") return o.text;
  return "";
}

function extractJson(text: string): Record<string, unknown> | null {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start === -1 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

async function aiJson(
  env: BrainEnv,
  system: string,
  user: string,
  maxTokens: number,
): Promise<Record<string, unknown>> {
  const out = await env.AI!.run(SIGNAL_MODEL, {
    messages: [
      { role: "system", content: system },
      { role: "user", content: user },
    ],
    max_tokens: maxTokens,
    temperature: 0.3,
  });
  const content = extractAiContent(out);
  const parsed = extractJson(content);
  if (!parsed) {
    throw new Error("model returned non-JSON output: " + content.slice(0, 200));
  }
  return parsed;
}

export async function handleTradeSignal(
  request: Request,
  env: BrainEnv,
): Promise<Response> {
  if (!env.AI) {
    return err(500, "Workers AI binding is not configured (add [ai] to wrangler.toml)");
  }

  let symbol = "";
  let days = 90;
  let risk = "medium";
  let position = "none";
  if (request.method === "GET") {
    const url = new URL(request.url);
    symbol = (url.searchParams.get("symbol") || "").trim();
    days = Number(url.searchParams.get("days") || 90) || 90;
    risk = url.searchParams.get("risk") || "medium";
    position = url.searchParams.get("position") || "none";
  } else {
    try {
      const body = (await request.json()) as Record<string, unknown>;
      symbol = String(body.symbol ?? "").trim();
      days = Number(body.days ?? 90) || 90;
      risk = String(body.risk ?? "medium");
      position = String(body.position ?? "none");
    } catch {
      return err(400, "invalid JSON body");
    }
  }
  if (symbol.length < 1 || symbol.length > 20) {
    return err(400, "valid symbol required, e.g. AAPL, BTC-USD, ^GSPC, RELIANCE.NS");
  }
  days = Math.min(Math.max(days, 5), 365);
  const range = rangeFor(days);

  // ---- market data (Yahoo Finance public chart endpoint) ----
  let candles: Candle[] = [];
  let currency = "USD";
  let metaName = symbol;
  try {
    const res = await fetch(
      "https://query1.finance.yahoo.com/v8/finance/chart/" +
        encodeURIComponent(symbol) +
        "?range=" +
        range +
        "&interval=1d",
      { headers: { "user-agent": UA, accept: "application/json" } },
    );
    if (!res.ok) return err(502, "Yahoo chart HTTP " + res.status + " for " + symbol);
    const data = (await res.json()) as {
      chart?: {
        result?: Array<{
          meta?: Record<string, unknown>;
          timestamp?: number[];
          indicators?: {
            quote?: Array<{
              open?: (number | null)[];
              high?: (number | null)[];
              low?: (number | null)[];
              close?: (number | null)[];
              volume?: (number | null)[];
            }>;
          };
        }>;
      };
    };
    const r0 = data.chart?.result?.[0];
    const q = r0?.indicators?.quote?.[0];
    const ts = r0?.timestamp ?? [];
    if (!r0 || !q || ts.length === 0) return err(502, "no chart data for " + symbol);
    const m = r0.meta ?? {};
    if (typeof m.currency === "string") currency = m.currency;
    if (typeof m.shortName === "string" && m.shortName.length > 0) metaName = m.shortName;
    for (let i = 0; i < ts.length; i++) {
      const c = q.close?.[i];
      if (c === null || c === undefined) continue;
      candles.push({
        t: ts[i],
        o: q.open?.[i] ?? c,
        h: q.high?.[i] ?? c,
        l: q.low?.[i] ?? c,
        c: c,
        v: q.volume?.[i] ?? 0,
      });
    }
  } catch (e) {
    return err(502, "chart fetch failed: " + (e as Error).message);
  }
  if (candles.length < 10) return err(502, "not enough history for " + symbol);

  const closes = candles.map((x) => x.c);
  const price = closes[closes.length - 1];
  const vols = candles.map((x) => x.v);
  const avgVol20 = vols.slice(-20).reduce((a, b) => a + b, 0) / Math.min(20, vols.length);
  const dailyReturns: number[] = [];
  for (let i = 1; i < closes.length; i++) {
    dailyReturns.push((closes[i] - closes[i - 1]) / closes[i - 1]);
  }
  const year = candles.slice(-252);
  const high52 = Math.max(...year.map((x) => x.h));
  const low52 = Math.min(...year.map((x) => x.l));
  const rangePos = high52 === low52 ? 50 : ((price - low52) / (high52 - low52)) * 100;

  const indicators: Indicators = {
    price: round(price, 4),
    change_1d_pct: round(pctChange(closes, 1)),
    change_5d_pct: round(pctChange(closes, 5)),
    change_20d_pct: round(pctChange(closes, 20)),
    sma20: sma(closes, 20) === null ? null : round(sma(closes, 20)!, 4),
    sma50: sma(closes, 50) === null ? null : round(sma(closes, 50)!, 4),
    sma200: sma(closes, 200) === null ? null : round(sma(closes, 200)!, 4),
    rsi14: round(rsi(closes), 1),
    volatility_20d_pct: round(stdev(dailyReturns.slice(-20)) * 100, 2),
    volume_vs_avg20: avgVol20 > 0 ? round(vols[vols.length - 1] / avgVol20, 2) : 1,
    high_52w: round(high52, 4),
    low_52w: round(low52, 4),
    range_position_pct: round(rangePos, 1),
  };

  // ---- recent news headlines (best effort, Yahoo search endpoint) ----
  const headlines: string[] = [];
  try {
    const nres = await fetch(
      "https://query1.finance.yahoo.com/v1/finance/search?q=" +
        encodeURIComponent(symbol) +
        "&newsCount=6&quotesCount=0",
      { headers: { "user-agent": UA, accept: "application/json" } },
    );
    if (nres.ok) {
      const nd = (await nres.json()) as {
        news?: Array<{ title?: string; publisher?: string }>;
      };
      for (const n of nd.news ?? []) {
        if (typeof n.title === "string" && n.title.length > 0) {
          headlines.push(n.title + (n.publisher ? " [" + n.publisher + "]" : ""));
        }
      }
    }
  } catch {
    // news is optional - ignore failures
  }

  // ---- analyst team briefing (TradingAgents analyst layer) ----
  const marketBrief =
    "Symbol: " + symbol + " (" + metaName + ", " + currency + ")\n" +
    "Latest price: " + indicators.price + "\n" +
    "Performance: 1d " + indicators.change_1d_pct + "% | 5d " + indicators.change_5d_pct +
    "% | 20d " + indicators.change_20d_pct + "%\n" +
    "SMA20: " + indicators.sma20 + " | SMA50: " + indicators.sma50 +
    " | SMA200: " + indicators.sma200 + "\n" +
    "RSI14: " + indicators.rsi14 + " | 20d daily volatility: " +
    indicators.volatility_20d_pct + "%\n" +
    "Volume vs 20d avg: " + indicators.volume_vs_avg20 + "x\n" +
    "52w range: " + indicators.low_52w + " - " + indicators.high_52w +
    " (price at " + indicators.range_position_pct + "% of range)\n" +
    "Recent news headlines:\n" +
    (headlines.length > 0 ? headlines.map((h) => "- " + h).join("\n") : "(none available)");

  let analyst: Record<string, unknown>;
  try {
    analyst = await aiJson(
      env,
      "You are the analyst team of a multi-agent LLM trading framework " +
        "(in the style of TauricResearch/TradingAgents). Your team covers: " +
        "technical analysis, news analysis, and sentiment/fundamentals. " +
        "Study the market data and return ONLY a JSON object (no prose) with keys: " +
        "technical_assessment (string), news_assessment (string), " +
        "sentiment_assessment (string), bull_case (string), bear_case (string), " +
        "confidence_score (integer 0-100 for data quality/conviction).",
      marketBrief,
      1400,
    );
  } catch (e) {
    return err(502, "analyst stage failed: " + (e as Error).message);
  }

  // ---- trader + risk manager verdict (TradingAgents trader layer) ----
  let trade: Record<string, unknown>;
  try {
    trade = await aiJson(
      env,
      "You are the trader agent of a multi-agent LLM trading framework " +
        "(in the style of TauricResearch/TradingAgents), advised by a risk " +
        "management team. Weigh the analyst briefing, argue the bull vs bear " +
        "case briefly, then commit to a final decision. Return ONLY a JSON " +
        "object (no prose) with keys: action (string, exactly one of " +
        "BUY/SELL/HOLD), confidence (integer 0-100), entry_zone (string), " +
        "stop_loss (string), take_profit (string), time_horizon (string), " +
        "bull_vs_bear (string), rationale (string), key_risks (string). " +
        "Be decisive but honest about uncertainty; never invent data you " +
        "were not given.",
      "Market data brief:\n" + marketBrief +
        "\n\nAnalyst team briefing (JSON):\n" + JSON.stringify(analyst) +
        "\n\nUser context: existing position = " + position +
        "; risk appetite = " + risk + ".",
      1400,
    );
  } catch (e) {
    return err(502, "trader stage failed: " + (e as Error).message);
  }

  return ok({
    framework: "TradingAgents-style multi-agent signal (TauricResearch/TradingAgents inspired)",
    symbol: symbol,
    name: metaName,
    currency: currency,
    generated_at: new Date().toISOString(),
    indicators: indicators,
    news_headlines: headlines,
    analyst_briefing: analyst,
    signal: trade,
    model: SIGNAL_MODEL,
    disclaimer: "Research/education only - not financial advice.",
  });
}
