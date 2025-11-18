// src/server.ts
// ОПТИМИЗИРОВАНО: Мгновенный старт сервера, фоновая загрузка движков

import "dotenv/config";
import express from "express";
import cors from "cors";
import path from "node:path";
import os from "node:os";
import pino from "pino";
import pinoHttp from "pino-http";
import { Chess } from "chess.js";

import { EngineName } from "@/types/enums";
import type {
  GameEval,
  EvaluateGameParams,
  EvaluatePositionWithUpdateParams,
} from "@/types/eval";

import { UciEngine } from "@/lib/engine/uciEngine";

// -------------------- ENV --------------------
const PORT = Number(process.env.PORT ?? 8080);
const ENGINE_NAME: EngineName =
  (process.env.ENGINE_NAME as EngineName) ?? EngineName.Stockfish17Lite;
const DEFAULT_DEPTH = Number(process.env.ENGINE_DEPTH ?? 16);
const DEFAULT_MULTIPV = Number(process.env.ENGINE_MULTIPV ?? 3);

const CPU_CORES = Math.max(1, os.cpus()?.length ?? 1);

const ENGINE_THREADS = Math.max(
  1,
  Number(process.env.ENGINE_THREADS ?? Math.min(8, CPU_CORES)),
);

const ENGINE_HASH_MB = Math.max(16, Number(process.env.ENGINE_HASH_MB ?? 128));

const ENGINE_WORKERS_MAX = Math.max(
  1,
  Number(process.env.ENGINE_WORKERS_MAX ?? Math.min(4, Math.floor(CPU_CORES / 2))),
);

const ENGINE_MAX_CONCURRENT_JOBS = Math.max(
  1,
  Number(process.env.ENGINE_MAX_CONCURRENT_JOBS ?? 2),
);

// -------------------- Server --------------------
const app = express();
const log = pino({ level: process.env.LOG_LEVEL ?? "info" });

app.use(
  cors({
    origin: (_o, cb) => cb(null, true),
    credentials: true,
  }),
);
app.use(express.json({ limit: "10mb" }));
app.use(
  pinoHttp({
    logger: log,
    customProps: () => ({ srv: "chess-backend" }),
  }),
);

const publicDir = path.join(process.cwd(), "public");
app.use("/engines", express.static(path.join(publicDir, "engines")));

// -------------------- Progress --------------------
type ProgressStage = "queued" | "preparing" | "evaluating" | "done";
type Progress = {
  id: string;
  total: number;
  done: number;
  percent?: number;
  stage?: ProgressStage;
  startedAt?: number;
  updatedAt?: number;
  fen?: string;
  currentUci?: string;
};
const PROGRESS = new Map<string, Progress>();

function initProgress(id: string, total: number) {
  const now = Date.now();
  PROGRESS.set(id, {
    id,
    total,
    done: 0,
    percent: 0,
    stage: "preparing",
    startedAt: now,
    updatedAt: now,
  });
}

function setProgress(id: string, upd: Partial<Progress>) {
  const prev = PROGRESS.get(id);
  if (!prev) return;
  const now = Date.now();
  const next: Progress = { ...prev, ...upd, updatedAt: now };
  if (next.total > 0) {
    next.percent = Math.max(0, Math.min(100, (next.done / next.total) * 100));
  }
  PROGRESS.set(id, next);
}

// -------------------- Types --------------------
interface ClientLine {
  pv: string[];
  cp?: number;
  mate?: number;
  best?: string;
}

interface ClientPosition {
  fen: string;
  idx: number;
  lines: ClientLine[];
}

interface PositionsResponse {
  positions: ClientPosition[];
  settings: {
    engine: string;
    depth: number;
    multiPv: number;
  };
}

// -------------------- Engine helpers --------------------
type EngineIface = {
  evaluatePositionWithUpdate: (
    p: EvaluatePositionWithUpdateParams
  ) => Promise<{ lines: any[]; bestMove?: string }>;
  evaluateGame: (
    p: EvaluateGameParams,
    onProgress?: (p: number) => void,
  ) => Promise<GameEval>;
  shutdown?: () => void;
};

let singletonEngine: EngineIface | null = null;

// -------------------- Worker Pool --------------------
const workerPool: EngineIface[] = [];
let workerPoolReady = false;

async function createEngineInstance(opts?: {
  threads?: number;
  hashMb?: number;
  multiPv?: number;
}): Promise<EngineIface> {
  const eng = await UciEngine.create(ENGINE_NAME, "");
  try {
    const threads = Math.max(1, Math.floor(opts?.threads ?? ENGINE_THREADS));
    const hashMb = Math.max(16, Math.floor(opts?.hashMb ?? ENGINE_HASH_MB));
    const multiPv = Math.max(1, Math.floor(opts?.multiPv ?? DEFAULT_MULTIPV));
    if (typeof (eng as any).setOption === "function") {
      await (eng as any).setOption("Threads", threads);
      await (eng as any).setOption("Hash", hashMb);
      await (eng as any).setOption("Ponder", false);
      await (eng as any).setOption("MultiPV", multiPv);
    }
  } catch (e) {
    log.warn({ err: e }, "Engine option setup warning");
  }
  return eng;
}

/** Инициализирует пул воркеров В ФОНЕ */
async function initializeWorkerPool() {
  if (workerPoolReady) return;

  const threadsPer = Math.max(1, Math.floor(ENGINE_THREADS / ENGINE_WORKERS_MAX));
  const hashPer = Math.max(128, Math.floor(ENGINE_HASH_MB / ENGINE_WORKERS_MAX));

  log.info({ workers: ENGINE_WORKERS_MAX }, "🔥 Initializing worker pool (background)...");

  const tasks = [];
  for (let i = 0; i < ENGINE_WORKERS_MAX; i++) {
    tasks.push(
      createEngineInstance({
        threads: threadsPer,
        hashMb: hashPer,
        multiPv: DEFAULT_MULTIPV,
      }).then(async (eng) => {
        // Легкий прогрев
        try {
          await eng.evaluatePositionWithUpdate({
            fen: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            depth: 6,
            multiPv: 1,
          } as any);
        } catch (e) { /* ignore */ }
        return eng;
      })
    );
  }

  const workers = await Promise.all(tasks);
  workerPool.push(...workers);
  workerPoolReady = true;

  log.info({ count: workerPool.length }, "✅ Worker pool ready");
}

async function getSingletonEngine(): Promise<EngineIface> {
  if (singletonEngine) return singletonEngine;
  // Создаем движок "на лету", если он еще не прогрет
  singletonEngine = await createEngineInstance({
    threads: ENGINE_THREADS,
    hashMb: ENGINE_HASH_MB,
    multiPv: DEFAULT_MULTIPV,
  });
  return singletonEngine;
}

// -------------------- Async queue --------------------
class AsyncQueue {
  private concurrency: number;
  private running = 0;
  private q: Array<() => void> = [];

  constructor(concurrency: number) {
    this.concurrency = Math.max(1, concurrency);
  }

  enqueue<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const run = () => {
        this.running++;
        task()
          .then(resolve)
          .catch(reject)
          .finally(() => {
            this.running--;
            const next = this.q.shift();
            if (next) next();
          });
      };
      if (this.running < this.concurrency) run();
      else this.q.push(run);
    });
  }
}

const jobQueue = new AsyncQueue(ENGINE_MAX_CONCURRENT_JOBS);

// -------------------- Parallel evaluation --------------------
async function evaluateGameParallel(
  baseParams: EvaluateGameParams,
  workersRequested: number,
  progressId: string | null,
  onProgress?: (p: number) => void,
): Promise<GameEval> {
  const fens = baseParams.fens ?? [];
  const uciMoves = baseParams.uciMoves ?? [];
  const total = fens.length;

  // Исправлено: убрана неиспользуемая переменная startTime

  let workers: number;
  if (total <= 20) {
    workers = 1;
  } else if (total <= 40) {
    workers = Math.min(2, ENGINE_WORKERS_MAX);
  } else {
    workers = Number.isFinite(workersRequested) && workersRequested > 0
      ? Math.min(Math.max(1, Math.floor(workersRequested)), ENGINE_WORKERS_MAX)
      : ENGINE_WORKERS_MAX;
  }

  // Если пул еще не готов, используем меньше воркеров или 1, чтобы не ждать
  if (!workerPoolReady) {
    log.info("⚠️ Pool not ready, falling back to on-demand worker");
    workers = 1;
  }

  if (workers === 1) {
    const eng = await getSingletonEngine();
    const result = await eng.evaluateGame(baseParams, onProgress);
    return result;
  }

  const threadsPer = Math.max(1, Math.floor(ENGINE_THREADS / workers));
  const hashPer = Math.max(128, Math.floor(ENGINE_HASH_MB / workers));
  const multiPvPer = baseParams.multiPv ?? DEFAULT_MULTIPV;

  const indexes: number[][] = Array.from({ length: workers }, () => []);
  for (let i = 0; i < total; i++) indexes[i % workers].push(i);

  const perWorkerDone = new Array(workers).fill(0);
  const reportProgress = () => {
    if (!onProgress) return;
    const done = perWorkerDone.reduce((a, b) => a + b, 0);
    const pct = Math.min(100, (done / Math.max(1, total)) * 100);
    onProgress(pct);
  };

  const tasks = indexes.map(async (idxs, wi) => {
    if (idxs.length === 0) return { positions: [], settings: {} } as any as GameEval;

    const shardFens = idxs.map((i) => baseParams.fens![i]);
    const shardUci = idxs.map((i) => baseParams.uciMoves![i]);

    let eng: EngineIface;

    // ✅ УМНАЯ ЛОГИКА: Если пул готов, берем из пула. Если нет - создаем новый (fallback).
    if (workerPoolReady && wi < workerPool.length) {
      eng = workerPool[wi];
    } else {
      eng = await createEngineInstance({
        threads: threadsPer,
        hashMb: hashPer,
        multiPv: multiPvPer,
      });
    }

    const onShardProgress = (p: number) => {
      const shardDone = Math.round((p / 100) * shardFens.length);
      if (shardDone > perWorkerDone[wi]) {
        perWorkerDone[wi] = shardDone;
        reportProgress();

        if (progressId && shardDone > 0 && shardDone <= shardFens.length) {
          const currentIdx = idxs[shardDone - 1];
          setProgress(progressId, {
            fen: currentIdx < fens.length ? fens[currentIdx] : undefined,
            currentUci: currentIdx < uciMoves.length ? uciMoves[currentIdx] : undefined,
          });
        }
      }
    };

    const out = await eng.evaluateGame(
      { ...baseParams, fens: shardFens, uciMoves: shardUci },
      onShardProgress,
    );

    const positionsWithIdx = (out.positions as any[]).map((pos, k) => ({
      __idx: idxs[k],
      ...pos,
    }));

    return { ...out, positions: positionsWithIdx, __engine: eng };
  });

  const shards = await Promise.all(tasks);

  // Закрываем только временные воркеры
  await Promise.allSettled(
    shards.map(async (shard, idx) => {
      return new Promise<void>((resolve) => {
        setTimeout(() => {
          try {
            const worker = (shard as any)?.__engine;
            const isPoolWorker = workerPoolReady && idx < workerPool.length && worker === workerPool[idx];
            if (worker && typeof worker.shutdown === "function" && !isPoolWorker) {
              worker.shutdown();
            }
          } catch (e) { /* ignore */ }
          resolve();
        }, 50);
      });
    })
  );

  const positionsMerged: any[] = new Array(total);
  for (const s of shards) {
    for (const p of s.positions as any[]) {
      positionsMerged[p.__idx] = { fen: p.fen, idx: p.idx, lines: p.lines };
    }
  }

  const first = shards.find(
    (s) => Array.isArray(s.positions) && s.positions.length > 0,
  );
  const settings = (first as any)?.settings ?? {};

  return { positions: positionsMerged, settings } as any as GameEval;
}

// -------------------- Helper: Convert to client format --------------------
function toClientPosition(
  posAny: any,
  fen: string,
  idx: number,
  isLastPosition: boolean,
  gameResult?: string
): ClientPosition {
  const rawLines: any[] = Array.isArray(posAny?.lines) ? posAny.lines : [];

  const lines: ClientLine[] = rawLines.map((l: any) => {
    const pv: string[] = Array.isArray(l?.pv)
      ? l.pv
      : Array.isArray(l?.pv?.moves)
      ? l.pv.moves
      : [];

    return {
      pv: pv,
      cp: typeof l?.cp === "number" ? l.cp : undefined,
      mate: typeof l?.mate === "number" ? l.mate : undefined,
    };
  });

  if (lines.length === 0) {
    if (isLastPosition && gameResult) {
      if (gameResult === "1-0" || gameResult === "0-1") {
        lines.push({ pv: [], mate: 0 });
      } else {
        lines.push({ pv: [], cp: 0 });
      }
    } else {
      lines.push({ pv: [], cp: 0 });
    }
  }

  const firstPv = lines[0]?.pv;
  const best =
    (posAny as any)?.bestMove ??
    (Array.isArray(firstPv) && firstPv.length > 0 ? firstPv[0] : undefined) ??
    "";

  if (lines[0]) {
    lines[0].best = String(best);
  }

  return {
    fen: String(fen ?? ""),
    idx,
    lines,
  };
}

// -------------------- Endpoints --------------------
app.get("/health", (_req, res) => res.json({ ok: true }));
app.get("/ping", (_req, res) => res.json({ ok: true }));
app.post("/ping", (_req, res) => res.json({ ok: true }));

app.get("/api/v1/progress/:id", (req, res) => {
  const id = req.params.id;
  let p = PROGRESS.get(id);
  if (!p) {
    const now = Date.now();
    p = {
      id,
      total: 0,
      done: 0,
      percent: 0,
      stage: "queued" as ProgressStage,
      startedAt: now,
      updatedAt: now,
    };
    PROGRESS.set(id, p);
  }
  res.json(p);
});

app.post("/api/v1/evaluate/positions", async (req, res) => {
  const progressId = String(
    (req.query as any)?.progressId ?? req.body?.progressId ?? "",
  );

  try {
    const body = req.body ?? {};
    const fens = Array.isArray(body.fens) ? body.fens : [];
    const uciMoves = Array.isArray(body.uciMoves) ? body.uciMoves : [];
    const gameResult = typeof body.gameResult === "string" ? body.gameResult : undefined;

    if (progressId) {
      initProgress(progressId, fens.length || 0);
      setProgress(progressId, { stage: "queued" });
    }

    const depth = Number.isFinite(body.depth) ? Number(body.depth) : DEFAULT_DEPTH;
    const multiPv = Number.isFinite(body.multiPv) ? Number(body.multiPv) : DEFAULT_MULTIPV;

    if (!Array.isArray(fens) || fens.length < 1) {
      if (progressId) setProgress(progressId, { stage: "done" });
      return res.status(400).json({ error: "invalid_fens" });
    }

    const baseParams: EvaluateGameParams = {
      fens,
      uciMoves,
      depth,
      multiPv,
      ...(body.useNNUE !== undefined ? { useNNUE: body.useNNUE } : {}),
      ...(body.elo !== undefined ? { elo: body.elo } : {}),
      ...(body.skillLevel !== undefined ? { skillLevel: body.skillLevel } : {}),
    } as any;

    const result = await jobQueue.enqueue(async () => {
      if (progressId) {
        setProgress(progressId, { stage: "evaluating" as ProgressStage, done: 0 });
      }

      const out: GameEval = await evaluateGameParallel(
        baseParams,
        Number(body.workersNb ?? 0),
        progressId || null,
        (p) => {
          if (progressId) {
            const done = Math.floor((p / 100) * fens.length);
            setProgress(progressId, {
              done: Math.min(done, fens.length),
              stage: "evaluating" as ProgressStage
            });
          }
        },
      );

      if (progressId) {
        setProgress(progressId, { stage: "done" as ProgressStage, done: fens.length });
      }

      const positions: ClientPosition[] = fens.map((fen: string, idx: number) => {
        const posAny: any = (out.positions as any[])[idx] ?? {};
        const isLast = idx === fens.length - 1;
        return toClientPosition(posAny, fen, idx, isLast, gameResult);
      });

      return {
        positions,
        settings: {
          engine: (out as any)?.settings?.engine ?? "stockfish-native",
          depth: (out as any)?.settings?.depth ?? depth,
          multiPv: (out as any)?.settings?.multiPv ?? multiPv,
        },
      } as PositionsResponse;
    });

    return res.json(result);
  } catch (e: any) {
    if (progressId) setProgress(progressId, { stage: "done" as ProgressStage });
    log.error({ err: e }, "Evaluation failed");
    return res.status(500).json({
      error: "evaluate_positions_failed",
      details: String(e?.message ?? e),
    });
  }
});

app.post("/api/v1/evaluate/position", async (req, res) => {
  try {
    const {
      fen,
      depth,
      multiPv,
      useNNUE,
      elo,
      skillLevel,
      beforeFen,
      afterFen,
      uciMove,
    } = req.body ?? {};

    // РЕЖИМ: анализ ОДНОГО хода
    if (
      typeof beforeFen === "string" &&
      typeof afterFen === "string" &&
      typeof uciMove === "string"
    ) {
      const effDepth = Number.isFinite(depth) ? Number(depth) : DEFAULT_DEPTH;
      const effMultiPv = Number.isFinite(multiPv) ? Number(multiPv) : DEFAULT_MULTIPV;

      const baseParams: EvaluateGameParams = {
        fens: [String(beforeFen), String(afterFen)],
        uciMoves: [String(uciMove)],
        depth: effDepth,
        multiPv: effMultiPv,
        ...(useNNUE !== undefined ? { useNNUE } : {}),
        ...(elo !== undefined ? { elo } : {}),
        ...(skillLevel !== undefined ? { skillLevel } : {}),
      } as any;

      // Здесь используем evaluateGameParallel, который сам решит, использовать пул или создать новый
      const out: GameEval = await evaluateGameParallel(baseParams, 1, null);

      const rawPositions: any[] = Array.isArray((out as any)?.positions)
        ? (out as any).positions
        : [];

      const fens2 = [String(beforeFen), String(afterFen)];
      const positions: ClientPosition[] = fens2.map((fenStr: string, idx: number) => {
        const posAny: any = rawPositions[idx] ?? {};
        return toClientPosition(posAny, fenStr, idx, idx === 1);
      });

      try {
        const ch = new Chess();
        ch.load(String(beforeFen));
        const from = String(uciMove).slice(0, 2);
        const to = String(uciMove).slice(2, 4);
        const prom = String(uciMove).slice(4) || undefined;
        const mv = ch.move({ from, to, promotion: prom as any });

        if (mv && ch.isCheckmate && ch.isCheckmate()) {
          if (positions[1].lines.length === 0) {
            positions[1].lines.push({ pv: [], mate: 0 });
          } else {
            positions[1].lines[0] = {
              ...(positions[1].lines[0] || {}),
              mate: 0,
            };
            delete (positions[1].lines[0] as any).cp;
          }
        }
      } catch (e) { /* ignore */ }

      const bestFromBefore = String(
        positions[0]?.lines?.[0]?.best ??
        positions[0]?.lines?.[0]?.pv?.[0] ??
        "",
      ) || undefined;

      return res.json({
        lines: positions[1].lines,
        bestMove: bestFromBefore,
      });
    }

    // РЕЖИМ: оценка обычной позиции (Single Engine)
    if (!fen || typeof fen !== "string") {
      return res.status(400).json({ error: "fen_required" });
    }

    const engine = await getSingletonEngine();
    const params: EvaluatePositionWithUpdateParams = {
      fen,
      depth: Number.isFinite(depth) ? Number(depth) : DEFAULT_DEPTH,
      multiPv: Number.isFinite(multiPv) ? Number(multiPv) : DEFAULT_MULTIPV,
      useNNUE,
      elo,
      ...(skillLevel !== undefined ? { skillLevel } : {}),
    } as any;

    const rawEval = await engine.evaluatePositionWithUpdate(params);

    const rawLines = Array.isArray(rawEval?.lines)
      ? rawEval.lines.map((line: any) => ({
          pv: line.pv,
          cp: line.cp,
          mate: line.mate,
        }))
      : [];

    return res.json({
      lines: rawLines,
      bestMove: (rawEval as any)?.bestMove,
    });
  } catch (e: any) {
    log.error({ err: e }, "Position evaluation failed");
    return res.status(500).json({
      error: "evaluate_position_failed",
      details: String(e?.message ?? e),
    });
  }
});

app.use((req, res) => {
  res
    .status(404)
    .json({ error: "not_found", path: `${req.method} ${req.originalUrl}` });
});

// ✅ 1. ЗАПУСКАЕМ СЕРВЕР СРАЗУ (Мгновенный ответ на Health Checks)
// Исправлено: убрано присваивание const server =, так как переменная не использовалась
app.listen(PORT, () => {
  log.info(
    {
      port: PORT,
      threads: ENGINE_THREADS,
      hashMB: ENGINE_HASH_MB,
      maxWorkers: ENGINE_WORKERS_MAX,
      concurrentJobs: ENGINE_MAX_CONCURRENT_JOBS,
    },
    "🚀 Server started (Listening immediately)"
  );
});

// ✅ 2. ПРОГРЕВАЕМ ДВИЖКИ В ФОНЕ (Не блокируя порт)
(async () => {
  try {
    // Прогреваем singleton (основной движок для быстрых запросов)
    await getSingletonEngine();
    log.info("✅ Singleton engine ready");

    // Прогреваем тяжелый пул
    await initializeWorkerPool();

  } catch (e) {
    log.warn({ err: e }, "⚠️ Engine warmup warning (server is still usable)");
  }

  // Keep-alive ping
  if (process.env.RAILWAY_ENVIRONMENT || process.env.NODE_ENV === "production") {
    setInterval(() => {
      fetch(`http://localhost:${PORT}/health`)
        .catch((e) => log.warn({ err: String(e) }, "Keep-alive ping failed"));
    }, 5 * 60 * 1000);
    log.info("💚 Keep-alive enabled");
  }
})();