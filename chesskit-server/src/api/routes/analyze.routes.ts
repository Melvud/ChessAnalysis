import { FastifyInstance } from "fastify";
import { spawn } from "child_process";

// Совместимые с клиентом типы
type LineEval = { pv: string[]; cp?: number; mate?: number; best?: string | null };
type PositionEval = { fen: string; idx: number; lines: LineEval[] };

type EvalRequest = {
  fen: string;
  depth?: number;
  multiPv?: number;
};

type EvalResponse = {
  lines: Array<{ pv: string[]; cp?: number; mate?: number; depth: number; multiPv: number }>;
  bestMove: string | null;
};

// Локальный вызов Stockfish (uci) с MultiPV
async function evalFenWithStockfish(
  fen: string,
  depth = 14,
  multiPv = 1,
  enginePath = process.env.STOCKFISH_PATH || "stockfish"
): Promise<{ lines: { multipv: number; cp?: number; mate?: number; pv: string[] }[]; bestMove?: string | null }> {
  return new Promise((resolve, reject) => {
    const sf = spawn(enginePath, [], { stdio: ["pipe", "pipe", "pipe"] });
    const lines: { multipv: number; cp?: number; mate?: number; pv: string[] }[] = [];
    let bestMove: string | null = null;

    const onStdout = (buf: Buffer) => {
      const out = buf.toString("utf8");
      for (const row of out.split(/\r?\n/)) {
        if (!row) continue;

        // info ... multipv N score cp X | score mate Y ... pv a2a4 a7a6 ...
        if (row.startsWith("info ")) {
          const mMulti = / multipv (\d+)/.exec(row);
          const mCp = / score cp (-?\d+)/.exec(row);
          const mMate = / score mate (-?\d+)/.exec(row);
          const mPv = / pv (.+)$/.exec(row);
          if (!mPv) continue;

          const k = Number(mMulti?.[1] ?? "1");
          const pv = mPv[1].trim().split(/\s+/);
          const next = {
            multipv: k,
            cp: mCp ? Number(mCp[1]) : undefined,
            mate: mMate ? Number(mMate[1]) : undefined,
            pv,
          };

          const prev = lines.find((l) => l.multipv === k);
          if (prev) Object.assign(prev, next);
          else lines.push(next);
        }

        // bestmove e2e4 [ponder ...]
        if (row.startsWith("bestmove")) {
          const m = /^bestmove\s+([a-h][1-8][a-h][1-8][qrbn]?)/.exec(row);
          bestMove = m ? m[1] : null;
        }
      }
    };

    const finish = () => {
      lines.sort((a, b) => a.multipv - b.multipv);
      resolve({ lines, bestMove });
    };

    sf.stdout.on("data", onStdout);
    sf.stderr.on("data", () => {}); // игнор
    sf.on("error", reject);
    sf.on("close", finish);

    const send = (s: string) => sf.stdin.write(s + "\n");
    send("uci");
    send("ucinewgame");
    send(`setoption name MultiPV value ${Math.max(1, multiPv | 0)}`);
    send(`position fen ${fen}`);
    send(`go depth ${Math.max(1, depth | 0)}`);
  });
}

export default async function routes(f: FastifyInstance) {
  // POST /api/v1/evaluate/position — как ожидает EngineClient на Android
  f.post<{ Body: EvalRequest }>("/api/v1/evaluate/position", async (request, reply) => {
    const body = request.body || ({} as EvalRequest);
    const fen = body.fen;
    const depth = typeof body.depth === "number" ? body.depth : 14;
    const multiPv = typeof body.multiPv === "number" ? body.multiPv : 1;

    if (!fen) {
      return reply.code(400).send({ error: "bad_request", message: "fen is required" });
    }

    try {
      const { lines, bestMove } = await evalFenWithStockfish(fen, depth, multiPv);

      const resp: EvalResponse = {
        lines: lines.map((l) => ({
          pv: l.pv,
          cp: l.cp,
          mate: l.mate,
          depth,
          multiPv: l.multipv,
        })),
        bestMove: bestMove ?? (lines[0]?.pv?.[0] ?? null),
      };

      return reply.send(resp);
    } catch (err: any) {
      f.log.error(err);
      return reply.code(500).send({
        error: "engine_error",
        message: err?.message || "Stockfish evaluation failed",
      });
    }
  });
}
