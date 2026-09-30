/**
 * TradingAgents-style trading signal engine (v2).
 *
 * Inspired by TauricResearch/TradingAgents (multi-agent LLM trading framework):
 * an analyst team (technical / news / sentiment / fundamentals) briefs a
 * trader agent that weighs the bull vs bear cases with a risk manager before
 * emitting a final BUY / SELL / HOLD signal.
 *
 * v2 additions:
 *  - Fundamentals: P/E, margins, revenue growth, analyst targets via Yahoo
 *    quoteSummary (cookie+crumb flow).
 *  - Memory log: every signal is saved to the TradeMemory Durable Object;
 *    matured signals are resolved against realized returns (hit/miss) and the
 *    lessons are injected into future trader prompts, so the agent learns
 *    from its own past decisions.
 *
 * Endpoint: GET|POST /v1/trade/signal?symbol=AAPL[&days=90][&risk=medium][&position=none]
 * Data source: Yahoo Finance public chart + search + quoteSummary endpoints.
 * LLM: Workers AI via the [ai] binding - no external API key needed.
 */

import type { BrainEnv } from "./llm";
import { err, ok } from "./proto";
import type { TradeSignalRecord } from "./tradeMemory";

const SIGNAL_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

export interface Candle {
  t: number;
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;
}

export interface Indicators {
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

export interface Fundamentals {
  trailing_pe: number | null;
  forward_pe: number | null;
  peg: number | null;
  price_to_book: number | null;
  revenue_growth_pct: number | null;
  earnings_growth_pct: number | null;
  gross_margin_pct: number | null;
  operating_margin_pct: number | null;
  net_margin_pct: number | null;
  debt_to_equity: number | null;
  dividend_yield_pct: number | null;
  analyst_consensus: string;
  analyst_target_mean: number | null;
  analyst_count: number | null;
}

export interface ChartData {
  candles: Candle[];
  currency: string;
  name: string;
}

function rangeFor(days: number): string {
  if (days <= 7) return "5d";
  if (days <= 30) return "1mo";
  if (days <= 90) return "3mo";
  if (days <= 200) return "6mo";
  return "1y";
}

export function sma(values: number[], period: number): number | null {
  if (values.length < period) return null;
  const slice = values.slice(-period);
  return slice.reduce((a, b) => a + b, 0) / period;
}

export function rsi(closes: number[], period = 14): number {
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

export function stdev(values: number[]): number {
  if (values.length < 2) return 0;
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  const variance =
    values.reduce((a, b) => a + (b - mean) * (b - mean), 0) / (values.length - 1);
  return Math.sqrt(variance);
}

export function pctChange(closes: number[], back: number): number {
  if (closes.length <= back) return 0;
  const then = closes[closes.length - 1 - back];
  const now = closes[closes.length - 1];
  return then === 0 ? 0 : ((now - then) / then) * 100;
}

export function round(n: number, d = 2): number {
  const f = Math.pow(10, d);
  return Math.round(n * f) / f;
}

export function extractAiContent(out: unknown): string {
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

export function extractJson(text: string): Record<string, unknown> | null {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start === -1 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export async function aiJson(
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

// ---------------------------------------------------------------- memory DO

function memStub(env: BrainEnv): DurableObjectStub {
  return env.TRADE_MEMORY.get(env.TRADE_MEMORY.idFromName("global"));
}

const DO_BASE = "https://do.internal";

async function memHistory(env: BrainEnv, symbol: string, limit: number): Promise<TradeSignalRecord[]> {
  try {
    const res = await memStub(env).fetch(
      DO_BASE + "/history?symbol=" + encodeURIComponent(symbol) + "&limit=" + limit,
    );
    if (!res.ok) return [];
    return (await res.json()) as TradeSignalRecord[];
  } catch {
    return [];
  }
}

async function memStats(env: BrainEnv, symbol: string): Promise<Record<string, unknown> | null> {
  try {
    const res = await memStub(env).fetch(
      DO_BASE + "/stats?symbol=" + encodeURIComponent(symbol),
    );
    if (!res.ok) return null;
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

async function memSave(env: BrainEnv, rec: Record<string, unknown>): Promise<void> {
  try {
    await memStub(env).fetch(DO_BASE + "/save", {
      method: "POST",
      body: JSON.stringify(rec),
    });
  } catch {
    // memory is best-effort - never fail the signal because of it
  }
}

async function memOutcome(
  env: BrainEnv,
  id: string,
  realized_pct: number,
  hit: boolean | null,
  outcome_note: string,
): Promise<void> {
  try {
    await memStub(env).fetch(DO_BASE + "/outcome", {
      method: "POST",
      body: JSON.stringify({ id, realized_pct, hit, outcome_note }),
    });
  } catch {
    // best-effort
  }
}

async function memDue(env: BrainEnv): Promise<TradeSignalRecord[]> {
  try {
    const res = await memStub(env).fetch(DO_BASE + "/due?now=" + Date.now());
    if (!res.ok) return [];
    return (await res.json()) as TradeSignalRecord[];
  } catch {
    return [];
  }
}

/**
 * Resolve matured signals: fetch price data around created_at and resolve_at,
 * compute the realized return and record hit/miss. BUY hits when the price
 * rose > +0.5%, SELL hits when it fell > -0.5%, HOLD is scored flat.
 * Returns how many signals were resolved.
 */
export async function resolveDueSignals(env: BrainEnv): Promise<number> {
  const due = await memDue(env);
  if (due.length === 0) return 0;
  const bySymbol = new Map<string, TradeSignalRecord[]>();
  for (const r of due) {
    const list = bySymbol.get(r.symbol) ?? [];
    list.push(r);
    bySymbol.set(r.symbol, list);
  }
  let resolved = 0;
  for (const [symbol, recs] of bySymbol) {
    try {
      const earliest = Math.min(...recs.map((r) => Date.parse(r.created_at)));
      const p1 = Math.floor(earliest / 1000) - 86400;
      const p2 = Math.floor(Date.now() / 1000) + 86400;
      const res = await fetch(
        "https://query1.finance.yahoo.com/v8/finance/chart/" +
          encodeURIComponent(symbol) +
          "?period1=" + p1 + "&period2=" + p2 + "&interval=1d",
        { headers: { "user-agent": UA, accept: "application/json" } },
      );
      if (!res.ok) continue;
      const data = (await res.json()) as {
        chart?: {
          result?: Array<{
            timestamp?: number[];
            indicators?: { quote?: Array<{ close?: (number | null)[] }> };
          }>;
        };
      };
      const r0 = data.chart?.result?.[0];
      const ts = r0?.timestamp ?? [];
      const cl = r0?.indicators?.quote?.[0]?.close ?? [];
      if (ts.length === 0) continue;
      const priceAt = (targetMs: number, before: boolean): number | null => {
        let bestTs = -1;
        let bestPrice: number | null = null;
        for (let i = 0; i < ts.length; i++) {
          const c = cl[i];
          if (c === null || c === undefined) continue;
          const tMs = ts[i] * 1000;
          if (before ? tMs <= targetMs : tMs >= targetMs) {
            if (bestTs === -1 || (before ? tMs > bestTs : tMs < bestTs)) {
              bestTs = tMs;
              bestPrice = c;
            }
          }
        }
        return bestPrice;
      };
      for (const r of recs) {
        const createdMs = Date.parse(r.created_at);
        const pStart = priceAt(createdMs, true);
        const pEnd = priceAt(r.resolve_at, false);
        if (pStart === null || pEnd === null || pStart === 0) {
          await memOutcome(env, r.id, 0, null, "Could not resolve: missing price data");
          resolved++;
          continue;
        }
        const realized = ((pEnd - pStart) / pStart) * 100;
        let hit: boolean | null = null;
        let verdict = "FLAT";
        if (r.action === "BUY") {
          hit = realized > 0.5;
          verdict = hit ? "HIT" : realized < -0.5 ? "MISS" : "FLAT";
        } else if (r.action === "SELL") {
          hit = realized < -0.5;
          verdict = hit ? "HIT" : realized > 0.5 ? "MISS" : "FLAT";
        }
        const note =
          r.action + " @ " + round(pStart, 4) + " on " + r.created_at.slice(0, 10) +
          " -> " + round(pEnd, 4) + " after " + r.horizon_days + "d: " +
          round(realized, 2) + "% (" + verdict + ")";
        await memOutcome(env, r.id, round(realized, 2), hit, note);
        resolved++;
      }
    } catch {
      // skip symbol on data failure
    }
  }
  return resolved;
}

// ------------------------------------------------------------ data fetchers

export async function fetchCandles(
  symbol: string,
  range: string,
): Promise<ChartData> {
  const res = await fetch(
    "https://query1.finance.yahoo.com/v8/finance/chart/" +
      encodeURIComponent(symbol) +
      "?range=" + range + "&interval=1d",
    { headers: { "user-agent": UA, accept: "application/json" } },
  );
  if (!res.ok) throw new Error("Yahoo chart HTTP " + res.status + " for " + symbol);
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
  if (!r0 || !q || ts.length === 0) throw new Error("no chart data for " + symbol);
  const m = r0.meta ?? {};
  const currency = typeof m.currency === "string" ? m.currency : "USD";
  const name =
    typeof m.shortName === "string" && m.shortName.length > 0 ? m.shortName : symbol;
  const candles: Candle[] = [];
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
  if (candles.length < 10) throw new Error("not enough history for " + symbol);
  return { candles, currency, name };
}

export async function fetchNews(symbol: string): Promise<string[]> {
  const headlines: string[] = [];
  try {
    const nres = await fetch(
      "https://query1.finance.yahoo.com/v1/finance/search?q=" +
        encodeURIComponent(symbol) + "&newsCount=6&quotesCount=0",
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
    // news is optional
  }
  return headlines;
}

function fv(x: unknown): number | null {
  if (x && typeof x === "object" && "raw" in (x as Record<string, unknown>)) {
    const raw = (x as Record<string, unknown>).raw;
    return typeof raw === "number" ? raw : null;
  }
  return typeof x === "number" ? x : null;
}

/**
 * Fundamentals via Yahoo quoteSummary (needs cookie + crumb).
 * Best-effort: returns null for instruments without fundamentals
 * (crypto, forex, indices) or if Yahoo changes its gate.
 */
export async function fetchFundamentals(symbol: string): Promise<Fundamentals | null> {
  try {
    const s = new Request("https://fc.yahoo.com", { headers: { "user-agent": UA } });
    const cookieRes = await fetch(s);
    const setCookie = cookieRes.headers.get("set-cookie") || "";
    const mA3 = setCookie.match(/A3=[^;]+/);
    const headers: Record<string, string> = { "user-agent": UA, accept: "application/json" };
    if (mA3) headers.cookie = mA3[0];
    const crumbRes = await fetch(
      "https://query1.finance.yahoo.com/v1/test/getcrumb",
      { headers },
    );
    const crumb = (await crumbRes.text()).trim();
    if (!crumb || crumb.length > 20) return null;
    const qRes = await fetch(
      "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" +
        encodeURIComponent(symbol) +
        "?modules=financialData,defaultKeyStatistics,summaryDetail&crumb=" +
        encodeURIComponent(crumb),
      { headers },
    );
    if (!qRes.ok) return null;
    const qd = (await qRes.json()) as {
      quoteSummary?: {
        result?: Array<{
          financialData?: Record<string, unknown>;
          defaultKeyStatistics?: Record<string, unknown>;
          summaryDetail?: Record<string, unknown>;
        }>;
      };
    };
    const r0 = qd.quoteSummary?.result?.[0];
    if (!r0) return null;
    const fd = r0.financialData ?? {};
    const ks = r0.defaultKeyStatistics ?? {};
    const sd = r0.summaryDetail ?? {};
    const pct = (x: unknown): number | null => {
      const v = fv(x);
      return v === null ? null : round(v * 100, 2);
    };
    return {
      trailing_pe: fv(sd.trailingPE),
      forward_pe: fv(sd.forwardPE),
      peg: fv(sd.pegRatio) ?? fv(ks.pegRatio),
      price_to_book: fv(ks.priceToBook),
      revenue_growth_pct: pct(fd.revenueGrowth),
      earnings_growth_pct: pct(fd.earningsGrowth),
      gross_margin_pct: pct(fd.grossMargins),
      operating_margin_pct: pct(fd.operatingMargins),
      net_margin_pct: pct(fd.profitMargins),
      debt_to_equity: fv(fd.debtToEquity),
      dividend_yield_pct: pct(sd.dividendYield),
      analyst_consensus: typeof fd.recommendationKey === "string" ? fd.recommendationKey : "",
      analyst_target_mean: fv(fd.targetMeanPrice),
      analyst_count: fv(fd.numberOfAnalystOpinions),
    };
  } catch {
    return null;
  }
}

// ------------------------------------------------------------- indicators

export function computeIndicators(candles: Candle[]): Indicators {
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
  const s20 = sma(closes, 20);
  const s50 = sma(closes, 50);
  const s200 = sma(closes, 200);
  return {
    price: round(price, 4),
    change_1d_pct: round(pctChange(closes, 1)),
    change_5d_pct: round(pctChange(closes, 5)),
    change_20d_pct: round(pctChange(closes, 20)),
    sma20: s20 === null ? null : round(s20, 4),
    sma50: s50 === null ? null : round(s50, 4),
    sma200: s200 === null ? null : round(s200, 4),
    rsi14: round(rsi(closes), 1),
    volatility_20d_pct: round(stdev(dailyReturns.slice(-20)) * 100, 2),
    volume_vs_avg20: avgVol20 > 0 ? round(vols[vols.length - 1] / avgVol20, 2) : 1,
    high_52w: round(high52, 4),
    low_52w: round(low52, 4),
    range_position_pct: round(rangePos, 1),
  };
}

export function buildMarketBrief(
  symbol: string,
  name: string,
  currency: string,
  ind: Indicators,
  headlines: string[],
  fundamentals: Fundamentals | null,
): string {
  let brief =
    "Symbol: " + symbol + " (" + name + ", " + currency + ")\n" +
    "Latest price: " + ind.price + "\n" +
    "Performance: 1d " + ind.change_1d_pct + "% | 5d " + ind.change_5d_pct +
    "% | 20d " + ind.change_20d_pct + "%\n" +
    "SMA20: " + ind.sma20 + " | SMA50: " + ind.sma50 +
    " | SMA200: " + ind.sma200 + "\n" +
    "RSI14: " + ind.rsi14 + " | 20d daily volatility: " + ind.volatility_20d_pct + "%\n" +
    "Volume vs 20d avg: " + ind.volume_vs_avg20 + "x\n" +
    "52w range: " + ind.low_52w + " - " + ind.high_52w +
    " (price at " + ind.range_position_pct + "% of range)\n";
  if (fundamentals) {
    brief +=
      "Fundamentals:\n" +
      "P/E trailing: " + fundamentals.trailing_pe + " | forward: " + fundamentals.forward_pe +
      " | PEG: " + fundamentals.peg + " | P/B: " + fundamentals.price_to_book + "\n" +
      "Revenue growth: " + fundamentals.revenue_growth_pct + "% | earnings growth: " +
      fundamentals.earnings_growth_pct + "%\n" +
      "Gross margin: " + fundamentals.gross_margin_pct + "% | operating: " +
      fundamentals.operating_margin_pct + "% | net: " + fundamentals.net_margin_pct + "%\n" +
      "Debt/equity: " + fundamentals.debt_to_equity + " | dividend yield: " +
      fundamentals.dividend_yield_pct + "%\n" +
      "Analyst consensus: " + fundamentals.analyst_consensus +
      " (" + fundamentals.analyst_count + " analysts, mean target " +
      fundamentals.analyst_target_mean + ")\n";
  } else {
    brief += "Fundamentals: not available for this instrument type.\n";
  }
  brief += "Recent news headlines:\n" +
    (headlines.length > 0 ? headlines.map((h) => "- " + h).join("\n") : "(none available)");
  return brief;
}

function buildMemoryContext(
  history: TradeSignalRecord[],
  stats: Record<string, unknown> | null,
): string {
  if (history.length === 0) return "";
  const lines = history
    .slice(0, 6)
    .map((r) => {
      const base = "- " + r.created_at.slice(0, 10) + " " + r.action + " @ " + r.price +
        " (confidence " + r.confidence + ")";
      if (r.resolved) return base + " -> " + r.outcome_note;
      return base + " -> still open (not yet resolved)";
    });
  let ctx =
    "\n\nYour memory log for this symbol (past decisions and outcomes):\n" +
    lines.join("\n");
  if (stats) {
    ctx +=
      "\nTrack record: " + stats.win_rate_pct + "% win rate over " + stats.resolved +
      " resolved decisions (" + stats.wins + " hit / " + stats.losses +
      " miss / " + stats.flats + " flat), average realized return " +
      stats.avg_return_pct + "%.";
  }
  ctx += "\nLearn from these outcomes: repeat what worked, avoid repeating misses.";
  return ctx;
}

// ---------------------------------------------------------------- pipeline

export async function runAnalyst(
  env: BrainEnv,
  brief: string,
  maxTokens = 1400,
): Promise<Record<string, unknown>> {
  return aiJson(
    env,
    "You are the analyst team of a multi-agent LLM trading framework " +
      "(in the style of TauricResearch/TradingAgents). Your team covers: " +
      "technical analysis, news analysis, sentiment, and fundamentals. " +
      "Study the market data and return ONLY a JSON object (no prose) with keys: " +
      "technical_assessment (string), news_assessment (string), " +
      "sentiment_assessment (string), fundamentals_assessment (string), " +
      "bull_case (string), bear_case (string), " +
      "confidence_score (integer 0-100 for data quality/conviction).",
    brief,
    maxTokens,
  );
}

export async function runTrader(
  env: BrainEnv,
  brief: string,
  analyst: Record<string, unknown> | null,
  memoryContext: string,
  risk: string,
  position: string,
  maxTokens = 1400,
): Promise<Record<string, unknown>> {
  let user =
    "Market data brief:\n" + brief;
  if (analyst) {
    user += "\n\nAnalyst team briefing (JSON):\n" + JSON.stringify(analyst);
  }
  user += memoryContext;
  user +=
    "\n\nUser context: existing position = " + position +
    "; risk appetite = " + risk + ".";
  return aiJson(
    env,
    "You are the trader agent of a multi-agent LLM trading framework " +
      "(in the style of TauricResearch/TradingAgents), advised by a risk " +
      "management team. You keep a memory log of your past decisions and " +
      "their realized outcomes - weigh it before committing. Weigh the " +
      "briefing, argue the bull vs bear case briefly, then commit to a final " +
      "decision. Return ONLY a JSON object (no prose) with keys: action " +
      "(string, exactly one of BUY/SELL/HOLD), confidence (integer 0-100), " +
      "entry_zone (string), stop_loss (string), take_profit (string), " +
      "time_horizon (string), bull_vs_bear (string), rationale (string), " +
      "key_risks (string). Be decisive but honest about uncertainty; never " +
      "invent data you were not given.",
    user,
    maxTokens,
  );
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

  // 1. resolve any matured signals for this symbol (lazy memory pass)
  const resolvedNow = await resolveDueSignals(env);

  // 2. gather data
  let chart: ChartData;
  try {
    chart = await fetchCandles(symbol, range);
  } catch (e) {
    return err(502, (e as Error).message);
  }
  const indicators = computeIndicators(chart.candles);
  const [headlines, fundamentals, memHistory, memStats] = await Promise.all([
    fetchNews(symbol),
    fetchFundamentals(symbol),
    memHistory(env, symbol, 8),
    memStats(env, symbol),
  ]);

  // 3. analyst team
  const brief = buildMarketBrief(symbol, chart.name, chart.currency, indicators, headlines, fundamentals);
  let analyst: Record<string, unknown>;
  try {
    analyst = await runAnalyst(env, brief);
  } catch (e) {
    return err(502, "analyst stage failed: " + (e as Error).message);
  }

  // 4. trader + risk manager, with memory injected
  const memoryContext = buildMemoryContext(memHistory, memStats);
  let trade: Record<string, unknown>;
  try {
    trade = await runTrader(env, brief, analyst, memoryContext, risk, position);
  } catch (e) {
    return err(502, "trader stage failed: " + (e as Error).message);
  }

  // 5. save to memory log (best-effort)
  await memSave(env, {
    symbol,
    action: String(trade.action ?? "HOLD"),
    confidence: Number(trade.confidence ?? 0),
    price: indicators.price,
    entry: String(trade.entry_zone ?? ""),
    stop_loss: String(trade.stop_loss ?? ""),
    take_profit: String(trade.take_profit ?? ""),
    horizon_days: 7,
    rationale: String(trade.rationale ?? ""),
    created_at: new Date().toISOString(),
  });

  return ok({
    framework: "TradingAgents-style multi-agent signal (TauricResearch/TradingAgents inspired)",
    symbol: symbol,
    name: chart.name,
    currency: chart.currency,
    generated_at: new Date().toISOString(),
    indicators: indicators,
    fundamentals: fundamentals,
    news_headlines: headlines,
    memory: {
      resolved_now: resolvedNow,
      track_record: memStats,
      recent: memHistory.slice(0, 5),
    },
    analyst_briefing: analyst,
    signal: trade,
    model: SIGNAL_MODEL,
    disclaimer: "Research/education only - not financial advice.",
  });
}
