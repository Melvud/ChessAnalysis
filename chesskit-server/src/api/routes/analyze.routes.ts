import { FastifyInstance } from "fastify";
import { z } from "zod";
import { AnalyzeService } from "../../services/analyze.service";

export default async function routes(fastify: FastifyInstance, service: AnalyzeService) {
  const PositionReq = z.object({
    fen: z.string(),
    depth: z.coerce.number().min(1).max(60).default(16),
    multiPv: z.coerce.number().min(1).max(6).default(3)
  });

  fastify.post("/api/v1/evaluate/position", async (req, reply) => {
    const { fen, depth, multiPv } = PositionReq.parse(req.body);
    const pos = await service.evaluatePosition(fen, depth, multiPv);
    return reply.send(pos);
  });

  fastify.get("/api/v1/evaluate/position/stream", async (req, reply) => {
    const q = PositionReq.parse((req as any).query);
    reply.raw.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    const onInfo = (line: string) => reply.raw.write(`data: ${JSON.stringify({ line })}\n\n`);
    try {
      const pos = await service.evaluatePosition(q.fen, q.depth, q.multiPv, onInfo);
      reply.raw.write(`event: done\ndata: ${JSON.stringify(pos)}\n\n`);
    } catch (e: any) {
      reply.raw.write(`event: error\ndata: ${JSON.stringify({ error: e.message })}\n\n`);
    } finally {
      reply.raw.end();
    }
  });
}
