/**
 * TradeMemory Durable Object - persistent memory log for the trading agent.
 *
 * Stores every emitted signal, and once its horizon matures, records the
 * realized return and whether the call was a hit or a miss. The signal engine
 * injects this history into future trader prompts, so the agent learns from
 * its own past decisions (the most valuable feature of TauricResearch's
 * TradingAgents).
 *
 * One global instance ("global") holds all symbols in SQLite-backed KV.
 */

export interface TradeSignalRecord {
  id: string;
  symbol: string;
  action: string;
  confidence: number;
  price: number;
  entry: string;
  stop_loss: string;
  take_profit: string;
  horizon_days: number;
  rationale: string;
  created_at: string;
  resolve_at: number;
  resolved: boolean;
  realized_pct: number | null;
  hit: boolean | null;
  outcome_note: string;
}

export class TradeMemory {
  private readonly state: DurableObjectState;

  constructor(state: DurableObjectState) {
    this.state = state;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/save" && request.method === "POST") {
      const body = (await request.json()) as Partial<TradeSignalRecord>;
      return Response.json(await this.save(body));
    }
    if (path === "/history" && request.method === "GET") {
      const symbol = url.searchParams.get("symbol") || "";
      const limit = Number(url.searchParams.get("limit") || "20");
      return Response.json(await this.history(symbol, limit));
    }
    if (path === "/due" && request.method === "GET") {
      const now = Number(url.searchParams.get("now") || Date.now());
      return Response.json(await this.due(now));
    }
    if (path === "/outcome" && request.method === "POST") {
      const body = (await request.json()) as {
        id: string;
        realized_pct: number;
        hit: boolean | null;
        outcome_note: string;
      };
      return Response.json(await this.outcome(body));
    }
    if (path === "/stats" && request.method === "GET") {
      const symbol = url.searchParams.get("symbol") || "";
      return Response.json(await this.stats(symbol));
    }
    return new Response("not found", { status: 404 });
  }

  private async nextId(): Promise<string> {
    const seq = (await this.state.storage.get<number>("seq")) ?? 0;
    await this.state.storage.put("seq", seq + 1);
    return "sig-" + String(seq + 1).padStart(5, "0");
  }

  private async save(body: Partial<TradeSignalRecord>): Promise<TradeSignalRecord> {
    const id = await this.nextId();
    const horizon = Math.min(Math.max(body.horizon_days ?? 7, 1), 90);
    const rec: TradeSignalRecord = {
      id,
      symbol: String(body.symbol ?? ""),
      action: String(body.action ?? "HOLD"),
      confidence: Number(body.confidence ?? 0),
      price: Number(body.price ?? 0),
      entry: String(body.entry ?? ""),
      stop_loss: String(body.stop_loss ?? ""),
      take_profit: String(body.take_profit ?? ""),
      horizon_days: horizon,
      rationale: String(body.rationale ?? "").slice(0, 400),
      created_at: String(body.created_at ?? new Date().toISOString()),
      resolve_at: Date.now() + horizon * 86400_000,
      resolved: false,
      realized_pct: null,
      hit: null,
      outcome_note: "",
    };
    await this.state.storage.put("sig:" + id, rec);
    return rec;
  }

  private async all(): Promise<TradeSignalRecord[]> {
    const map = await this.state.storage.list<TradeSignalRecord>({ prefix: "sig:" });
    return Array.from(map.values());
  }

  private async history(symbol: string, limit: number): Promise<TradeSignalRecord[]> {
    const recs = (await this.all())
      .filter((r) => !symbol || r.symbol === symbol)
      .sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
    return recs.slice(0, Math.min(Math.max(limit, 1), 100));
  }

  private async due(now: number): Promise<TradeSignalRecord[]> {
    return (await this.all()).filter((r) => !r.resolved && r.resolve_at <= now);
  }

  private async outcome(body: {
    id: string;
    realized_pct: number;
    hit: boolean | null;
    outcome_note: string;
  }): Promise<TradeSignalRecord | { error: string }> {
    const rec = await this.state.storage.get<TradeSignalRecord>("sig:" + body.id);
    if (!rec) return { error: "unknown id " + body.id };
    rec.resolved = true;
    rec.realized_pct = body.realized_pct;
    rec.hit = body.hit;
    rec.outcome_note = String(body.outcome_note ?? "").slice(0, 400);
    await this.state.storage.put("sig:" + body.id, rec);
    return rec;
  }

  private async stats(symbol: string): Promise<{
    total: number;
    resolved: number;
    wins: number;
    losses: number;
    flats: number;
    win_rate_pct: number;
    avg_return_pct: number;
    lessons: string[];
  }> {
    const recs = (await this.all())
      .filter((r) => !symbol || r.symbol === symbol)
      .sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
    const resolved = recs.filter((r) => r.resolved);
    const wins = resolved.filter((r) => r.hit === true).length;
    const losses = resolved.filter((r) => r.hit === false).length;
    const flats = resolved.filter((r) => r.hit === null).length;
    const rets = resolved.filter((r) => r.realized_pct !== null) as Array<
      TradeSignalRecord & { realized_pct: number }
    >;
    const avg =
      rets.length > 0 ? rets.reduce((a, r) => a + r.realized_pct, 0) / rets.length : 0;
    return {
      total: recs.length,
      resolved: resolved.length,
      wins,
      losses,
      flats,
      win_rate_pct: wins + losses > 0 ? Math.round((wins / (wins + losses)) * 100) : 0,
      avg_return_pct: Math.round(avg * 100) / 100,
      lessons: resolved.slice(0, 6).map((r) => r.outcome_note),
    };
  }
}
