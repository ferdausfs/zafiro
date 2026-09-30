/**
 * Backtest-lite: replay the signal pipeline over a grid of past decision
 * dates using only point-in-time data (candles before the decision date),
 * then score each decision against the realized return over the following
 * horizon. Inspired by TauricResearch/TradingAgents `run_backtest`.
 *
 * Endpoint: GET|POST /v1/trade/backtest?symbol=AAPL&start=2026-06-01&end=2026-09-01&every=7&horizon=7
 *
 * To keep latency bounded the backtest uses a reduced pipeline: indicators +
 * one trader call per decision (no analyst stage, no news - point-in-time
 * news is not available), decisions run in parallel batches.
 */

import type { BrainEnv } from "./llm";
import { err, ok } from "./proto";
import {
  type Candle,
  type Indicators,
  buildMarketBrief,
  computeIndicators,
  fetchCandles,
  round,
  runTrader,
} from "./trade";

interface Decision {
  date: string;
  price: number;
  action: string;
  confidence: number;
  rationale: string;
  realized_pct: number | null;
  correct: boolean | null;
}

function parseDate(s: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim());
  if (!m) return null;
  const t = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return isNaN(t) ? null : t;
}

function dateStr(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export async function handleTradeBacktest(
  request: Request,
  env: BrainEnv,
): Promise<Response> {
  if (!env.AI) {
    return err(500, "Workers AI binding is not configured (add [ai] to wrangler.toml)");
  }

  let symbol = "";
  let startS = "";
  let endS = "";
  let every = 7;
  let horizon = 7;
  if (request.method === "GET") {
    const url = new URL(request.url);
    symbol = (url.searchParams.get("symbol") || "").trim();
    startS = url.searchParams.get("start") || "";
    endS = url.searchParams.get("end") || "";
    every = Number(url.searchParams.get("every") || 7) || 7;
    horizon = Number(url.searchParams.get("horizon") || 7) || 7;
  } else {
    try {
      const body = (await request.json()) as Record<string, unknown>;
      symbol = String(body.symbol ?? "").trim();
      startS = String(body.start ?? "");
      endS = String(body.end ?? "");
      every = Number(body.every ?? 7) || 7;
      horizon = Number(body.horizon ?? 7) || 7;
    } catch {
      return err(400, "invalid JSON body");
    }
  }
  const startMs = parseDate(startS);
  const endMs = parseDate(endS);
  if (symbol.length < 1 || symbol.length > 20) {
    return err(400, "valid symbol required");
  }
  if (startMs === null || endMs === null || endMs <= startMs) {
    return err(400, "start/end must be YYYY-MM-DD with end > start");
  }
  every = Math.min(Math.max(every, 3), 30);
  horizon = Math.min(Math.max(horizon, 1), 30);

  // decision dates (capped so one request stays bounded)
  const maxDecisions = 15;
  const dates: number[] = [];
  for (let t = startMs; t <= endMs && dates.length < maxDecisions; t += every * 86400_000) {
    dates.push(t);
  }
  if (dates.length === 0) return err(400, "no decision dates in range");

  // one chart covering warmup + last decision + horizon
  const p1 = Math.floor((startMs - 320 * 86400_000) / 1000);
  const p2 = Math.floor((endMs + (horizon + 5) * 86400_000) / 1000);
  let candles: Candle[];
  let name = symbol;
  let currency = "USD";
  try {
    const res = await fetch(
      "https://query1.finance.yahoo.com/v8/finance/chart/" +
        encodeURIComponent(symbol) +
        "?period1=" + p1 + "&period2=" + p2 + "&interval=1d",
      { headers: { "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0", accept: "application/json" } },
    );
    if (!res.ok) return err(502, "Yahoo chart HTTP " + res.status);
    const data = (await res.json()) as {
      chart?: {
        result?: Array<{
          meta?: Record<string, unknown>;
          timestamp?: number[];
          indicators?: { quote?: Array<{ close?: (number | null)[] }> };
        }>;
      };
    };
    const r0 = data.chart?.result?.[0];
    const ts = r0?.timestamp ?? [];
    const cl = r0?.indicators?.quote?.[0]?.close ?? [];
    if (!r0 || ts.length === 0) return err(502, "no chart data for " + symbol);
    const m = r0.meta ?? {};
    if (typeof m.shortName === "string" && m.shortName.length > 0) name = m.shortName;
    if (typeof m.currency === "string") currency = m.currency;
    candles = [];
    for (let i = 0; i < ts.length; i++) {
      const c = cl[i];
      if (c === null || c === undefined) continue;
      candles.push({ t: ts[i], o: c, h: c, l: c, c: c, v: 0 });
    }
  } catch (e) {
    return err(502, "chart fetch failed: " + (e as Error).message);
  }

  // per-decision evaluation (point-in-time)
  const priceAtOrBefore = (targetMs: number): { idx: number; price: number } | null => {
    let found: { idx: number; price: number } | null = null;
    for (let i = 0; i < candles.length; i++) {
      if (candles[i].t * 1000 <= targetMs) found = { idx: i, price: candles[i].c };
      else break;
    }
    return found;
  };
  const priceAtOrAfter = (targetMs: number): number | null => {
    for (const c of candles) {
      if (c.t * 1000 >= targetMs) return c.c;
    }
    return null;
  };

  type Job = { dateMs: number; upto: Candle[]; ind: Indicators; price: number; future: number | null };
  const jobs: Job[] = [];
  for (const dMs of dates) {
    const at = priceAtOrBefore(dMs);
    if (!at || at.idx < 30) continue; // need warmup for indicators
    const future = priceAtOrAfter(dMs + horizon * 86400_000);
    if (future === null) continue;
    const futurePrice: number = future;
    jobs.push({
      dateMs: dMs,
      upto: candles.slice(0, at.idx + 1),
      ind: computeIndicators(candles.slice(0, at.idx + 1)),
      price: at.price,
      future: futurePrice,
    });
  }
  if (jobs.length === 0) return err(400, "not enough history before start date");

  const runOne = async (job: Job): Promise<Decision> => {
    const brief = buildMarketBrief(symbol, name, currency, job.ind, [], null);
    let action = "HOLD";
    let confidence = 0;
    let rationale = "";
    try {
      const trade = await runTrader(env, brief, null, "", "medium", "none", 700);
      action = String(trade.action ?? "HOLD").toUpperCase();
      confidence = Number(trade.confidence ?? 0);
      rationale = String(trade.rationale ?? "").slice(0, 300);
    } catch (e) {
      rationale = "trader call failed: " + (e as Error).message.slice(0, 120);
    }
    const realized = job.price === 0 ? 0 : ((job.future - job.price) / job.price) * 100;
    let correct: boolean | null = null;
    if (action === "BUY") correct = realized > 0.5;
    else if (action === "SELL") correct = realized < -0.5;
    else correct = Math.abs(realized) <= 1;
    return {
      date: dateStr(job.dateMs),
      price: round(job.price, 4),
      action,
      confidence,
      rationale,
      realized_pct: round(realized, 2),
      correct,
    };
  };

  // parallel batches of 5 to bound concurrency
  const decisions: Decision[] = [];
  for (let i = 0; i < jobs.length; i += 5) {
    const batch = jobs.slice(i, i + 5).map(runOne);
    decisions.push(...(await Promise.all(batch)));
  }

  // scoring
  const scored = decisions.filter((d) => d.correct !== null);
  const wins = scored.filter((d) => d.correct === true).length;
  const byAction: Record<string, { n: number; wins: number; avg_return_pct: number }> = {};
  for (const d of decisions) {
    const b = (byAction[d.action] ??= { n: 0, wins: 0, avg_return_pct: 0 });
    b.n++;
    if (d.correct === true) b.wins++;
  }
  for (const key of Object.keys(byAction)) {
    const ds = decisions.filter((d) => d.action === key && d.realized_pct !== null);
    byAction[key].avg_return_pct =
      ds.length > 0
        ? round(ds.reduce((a, d) => a + (d.realized_pct as number), 0) / ds.length, 2)
        : 0;
  }
  const avgRet =
    scored.length > 0
      ? round(scored.reduce((a, d) => a + (d.realized_pct as number), 0) / scored.length, 2)
      : 0;

  return ok({
    framework: "TradingAgents-style backtest-lite (TauricResearch/TradingAgents inspired)",
    symbol,
    name,
    currency,
    params: { start: startS, end: endS, every, horizon, decisions: dates.length, ran: decisions.length },
    summary: {
      scored: scored.length,
      wins,
      accuracy_pct: scored.length > 0 ? Math.round((wins / scored.length) * 100) : 0,
      avg_realized_return_pct: avgRet,
      by_action: byAction,
    },
    decisions,
    disclaimer: "Research/education only - not financial advice.",
  });
}
