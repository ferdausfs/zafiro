/**
 * Forex majors daily scan - one call, all 7 major pairs.
 *
 * Reduced pipeline per pair (fast): chart indicators + memory track record +
 * one trader call. Use /v1/trade/signal?symbol=EURUSD for the full
 * multi-agent analysis (news + fundamentals + analyst team) on a single pair.
 *
 * Endpoint: GET /v1/trade/forex
 */

import type { BrainEnv } from "./llm";
import { err, ok } from "./proto";
import {
  type ChartData,
  buildMarketBrief,
  buildMemoryContext,
  computeIndicators,
  fetchCandles,
  memHistory,
  memStats,
  runTrader,
} from "./trade";

const MAJORS = [
  "EURUSD=X",
  "GBPUSD=X",
  "USDJPY=X",
  "USDCHF=X",
  "AUDUSD=X",
  "NZDUSD=X",
  "USDCAD=X",
];

interface PairResult {
  pair: string;
  price: number | null;
  change_1d_pct: number | null;
  rsi14: number | null;
  action: string;
  confidence: number;
  entry_zone: string;
  stop_loss: string;
  take_profit: string;
  rationale: string;
  win_rate_pct: number | null;
  error?: string;
}

export async function handleForexScan(
  _request: Request,
  env: BrainEnv,
): Promise<Response> {
  if (!env.AI) {
    return err(500, "Workers AI binding is not configured (add [ai] to wrangler.toml)");
  }

  // 1. charts in parallel
  const charts = await Promise.all(
    MAJORS.map(async (p) => {
      try {
        return { pair: p, chart: (await fetchCandles(p, "3mo")) as ChartData };
      } catch {
        return { pair: p, chart: null as ChartData | null };
      }
    }),
  );
  const valid = charts.filter((c) => c.chart !== null) as Array<{
    pair: string;
    chart: ChartData;
  }>;
  if (valid.length === 0) return err(502, "no forex data available");

  // 2. memory track record per pair (parallel)
  const mems = await Promise.all(
    valid.map(async (c) => ({
      pair: c.pair,
      stats: await memStats(env, c.pair),
      recent: await memHistory(env, c.pair, 3),
    })),
  );
  const memByPair = new Map(mems.map((m) => [m.pair, m]));

  // 3. trader calls in batches of 4
  const results: PairResult[] = [];
  const runPair = async (c: { pair: string; chart: ChartData }): Promise<PairResult> => {
    const ind = computeIndicators(c.chart.candles);
    const brief = buildMarketBrief(
      c.pair.replace("=X", ""),
      c.pair.replace("=X", "") + " (forex)",
      c.chart.currency,
      ind,
      [],
      null,
    );
    const m = memByPair.get(c.pair);
    const memoryContext = buildMemoryContext(m?.recent ?? [], m?.stats ?? null);
    const out: PairResult = {
      pair: c.pair.replace("=X", ""),
      price: ind.price,
      change_1d_pct: ind.change_1d_pct,
      rsi14: ind.rsi14,
      action: "HOLD",
      confidence: 0,
      entry_zone: "",
      stop_loss: "",
      take_profit: "",
      rationale: "",
      win_rate_pct: typeof m?.stats?.win_rate_pct === "number" ? (m!.stats!.win_rate_pct as number) : null,
    };
    try {
      const trade = await runTrader(env, brief, null, memoryContext, "medium", "none", 800);
      out.action = String(trade.action ?? "HOLD").toUpperCase();
      out.confidence = Number(trade.confidence ?? 0);
      out.entry_zone = String(trade.entry_zone ?? "");
      out.stop_loss = String(trade.stop_loss ?? "");
      out.take_profit = String(trade.take_profit ?? "");
      out.rationale = String(trade.rationale ?? "").slice(0, 220);
    } catch (e) {
      out.error = (e as Error).message.slice(0, 120);
    }
    return out;
  };
  for (let i = 0; i < valid.length; i += 4) {
    const batch = valid.slice(i, i + 4).map(runPair);
    results.push(...(await Promise.all(batch)));
  }
  results.sort((a, b) => (a.pair < b.pair ? -1 : 1));

  return ok({
    framework: "TradingAgents-style forex majors scan",
    generated_at: new Date().toISOString(),
    pairs: results,
    note: "Quick scan (indicators + memory). For the full multi-agent analysis with news, say: <pair> signal - e.g. EURUSD signal.",
    disclaimer: "Research/education only - not financial advice.",
  });
}
