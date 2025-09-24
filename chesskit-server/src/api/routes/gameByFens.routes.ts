import { FastifyInstance } from "fastify";
import { AnalyzeService } from "../../services/analyze.service";
import { randomUUID } from "node:crypto";

export default async function gameByFensRoutes(
  f: FastifyInstance,
  service: AnalyzeService
) {
  f.post("/api/v1/evaluate/game/by-fens", async (req, reply) => {
    const q = (req.query ?? {}) as { 
      progressId?: string; 
      depth?: string; 
      movetime?: string;
      multiPv?: string;
    };
    const body = (req.body ?? {}) as { 
      fens?: string[]; 
      uciMoves?: string[];
      header?: any;
    };

    const fens = body.fens || [];
    const uciMoves = body.uciMoves || [];
    
    if (!fens.length) {
      return reply.code(400).send({ 
        error: "bad_request", 
        message: "fens is empty" 
      });
    }

    const progressId = q.progressId || randomUUID();
    const depth = q.depth ? Number(q.depth) : 15;
    const movetime = q.movetime ? Number(q.movetime) : undefined;
    const multiPv = q.multiPv ? Number(q.multiPv) : 3;

    try {
      // Запускаем анализ и ждем результат
      const report = await service.evaluateFens(
        progressId,
        { fens, uciMoves },
        { depth, movetimeMs: movetime, multiPv }
      );
      
      // Добавляем заголовок если передан
      if (body.header) {
        report.header = body.header;
      }
      
      return reply.send(report);
    } catch (err: any) {
      f.log.error(err);
      return reply.code(500).send({
        error: "analysis_failed",
        message: err.message || "Unknown error"
      });
    }
  });
}