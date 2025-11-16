// src/index.ts
import "dotenv/config";
import Fastify, { FastifyInstance } from "fastify";
import os from "os";

import { EnginePool } from "./engine/pool";
import { AnalyzeService } from "./services/analyze.service";

// Роуты
import progressRoutes from "./api/routes/progress.routes";
import gameFullRoutes from "./api/routes/gameFull.routes";
import gameByFensRoutes from "./api/routes/gameByFens.routes";
import analyzeRoutes from "./api/routes/analyze.routes";

// -------------------- Конфиг --------------------
const PORT = Number(process.env.PORT || 8080);
// Для Android-эмулятора важно слушать на 0.0.0.0 (доступ через 10.0.2.2)
const HOST = process.env.HOST || "0.0.0.0";

// Путь к движку (Windows exe по умолчанию)
const ENGINE_PATH =
  process.env.ENGINE_PATH ||
  (process.platform === "win32"
    ? "./engines/stockfish.exe"
    : "./engines/stockfish");

const ENGINE_THREADS = Number(process.env.ENGINE_THREADS || 2);
const ENGINE_HASH = Number(process.env.ENGINE_HASH || 128);
const ENGINE_POOL_SIZE = Number(process.env.ENGINE_POOL_SIZE || 1);

// -------------------- Bootstrap --------------------
async function main() {
  const fastify: FastifyInstance = Fastify({
    logger: true,
    trustProxy: true,
    // не ограничиваем длительные запросы анализа
    requestTimeout: 0,
  });

  // Технические пинги для клиента (EngineClient.resolveBaseBlocking)
  fastify.get("/ping", async (_req, reply) => reply.send({ ok: true }));
  fastify.post("/ping", async (_req, reply) => reply.send({ ok: true }));

  // 1) Поднимаем пул движков ДО регистрации роутов
  const pool = new EnginePool({
    path: ENGINE_PATH,
    size: ENGINE_POOL_SIZE,
    options: {
      Threads: ENGINE_THREADS,
      Hash: ENGINE_HASH,
    },
  });

  await pool.start();

  // 2) Создаём сервис анализа
  const service = new AnalyzeService(pool);

  // 3) Регистрируем роут прогресса РОВНО ОДИН раз
  await fastify.register(progressRoutes);

  // 4) Регистрируем analyze routes (evaluate position)
  await fastify.register(analyzeRoutes);

  // 5) Регистрируем остальные роуты, которым нужен сервис
  await fastify.register(async (f) => {
    // полный анализ партии (если есть)
    await gameFullRoutes(f, service);
    // анализ по массиву FEN (асинхронный с прогрессом)
    await gameByFensRoutes(f, service);
  });

  // 6) Стартуем сервер
  await fastify.listen({ port: PORT, host: HOST });

  // 7) Выведем удобные адреса
  const nets = os.networkInterfaces();
  const addrs: string[] = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === "IPv4" && !net.internal) addrs.push(net.address);
    }
  }
  fastify.log.info(`Server listening at http://127.0.0.1:${PORT}`);
  for (const a of addrs) fastify.log.info(`Server listening at http://${a}:${PORT}`);

  // 8) Грейсфул-шатдаун
  const shutdown = async (signal: string) => {
    try {
      fastify.log.info({ signal }, "Shutting down...");
      await fastify.close();
    } finally {
      try {
        await pool.stop?.();
      } catch {
        /* ignore */
      }
      process.exit(0);
    }
  };

  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error(err);
  process.exit(1);
});
