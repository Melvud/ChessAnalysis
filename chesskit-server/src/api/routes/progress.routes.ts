import { FastifyInstance } from "fastify";
import progressStore, { progressStore as namedStore } from "../progressStore";

const store = namedStore ?? progressStore; // гарантируем инстанс

export default async function progressRoutes(f: FastifyInstance) {
  f.get("/api/v1/progress/:id", async (req, reply) => {
    const { id } = req.params as { id: string };
    const p = store.get(id);
    if (!p) return reply.code(404).send({ error: "not_found" });
    return reply.send(p);
  });
}
