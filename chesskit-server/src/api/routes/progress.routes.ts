import { FastifyInstance } from "fastify";
import { progressStore } from "../progressStore";

export default async function progressRoutes(f: FastifyInstance) {
  f.get("/api/v1/progress/:id", async (req, reply) => {
    const { id } = req.params as { id: string };
    const p = progressStore.get(id);
    if (!p) return reply.code(404).send({ error: "not_found" });
    return reply.send(p);
  });
}