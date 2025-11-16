import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { AnalyzeService } from "../../services/analyze.service";
import { Chess } from "chess.js";
import {
  FullReport,
  GameHeader,
  PositionEval,
  LineEval,
} from "../../types/clientReport";

/** Нормализация входного PGN: BOM, переводы строк, «умные» кавычки и пр. */
function sanitizePgn(input: string): string {
  let pgn = input || "";

  // Удаляем BOM/нулевые
  pgn = pgn.replace(/^\uFEFF/, "").replace(/\u0000/g, "");

  // Если пришло «двойное экранирование» (\\n), превращаем в реальные переводы
  pgn = pgn.replace(/\\r\\n/g, "\n").replace(/\\n/g, "\n").replace(/\\r/g, "\n");

  // Нормализуем переводы строк
  pgn = pgn.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

  // Нормализуем «умные» символы
  pgn = pgn
    .replace(/[\u201C\u201D\u2033\u02BA]/g, '"') // “ ” ″ ʺ -> "
    .replace(/[\u2018\u2019]/g, "'")            // ‘ ’ -> '
    .replace(/\u00A0/g, " ");                   // NBSP -> space

  // Если прислали дамп из нескольких партий — берём первую
  const multi = pgn.match(/(?=\[Event\b)([\s\S]*?)(?=(?:\n\n\[Event\b)|$)/);
  if (multi && multi[0]) pgn = multi[0].trim();

  // Гарантируем пустую строку между тегами и ходами
  const tagLines = [...pgn.matchAll(/^\s*\[[^\]]+\]\s*$/gm)];
  if (tagLines.length > 0) {
    const last = tagLines[tagLines.length - 1];
    const cut = (last.index ?? 0) + last[0].length;
    const before = pgn.slice(0, cut);
    let after = pgn.slice(cut);
    if (!after.startsWith("\n\n")) {
      after = after.replace(/^\n?/, "\n\n");
    }
    pgn = (before + after).trim();
  }

  return pgn.trim();
}

function parseTags(pgn: string): Record<string, string> {
  const rx = /\[([A-Za-z0-9_]+)\s+"([^"]*)"\]/g;
  const out: Record<string, string> = {};
  let m: RegExpExecArray | null;
  while ((m = rx.exec(pgn))) out[m[1]] = m[2];
  return out;
}

/** Выделить чистую секцию ходов (после пустой строки и до конца/комментариев) */
function extractMovesSection(pgn: string): string {
  // Ищем разделитель «двойной перевод строки + номер хода»
  const sepIdx = pgn.search(/\n\n(?=\s*\d+\.\s)/);
  if (sepIdx >= 0) {
    return pgn.slice(sepIdx + 2).trim();
  }
  // Фолбэк — первая позиция, где начинается «N. »
  const m = pgn.match(/\b\d+\.\s/);
  if (m && typeof m.index === "number") {
    return pgn.slice(m.index).trim();
  }
  return pgn; // если не нашли — вернём как есть
}

/** Пробуем загрузить PGN в chess.js, есть фолбэки */
function tryLoadPgn(chess: Chess, pgn: string): { ok: boolean; variant: string } {
  // 1) как есть
  if (chess.loadPgn(pgn, { sloppy: true })) return { ok: true, variant: "full" };

  // 2) только секция ходов
  const movesOnly = extractMovesSection(pgn);
  if (movesOnly !== pgn) {
    chess.reset();
    if (chess.loadPgn(movesOnly, { sloppy: true })) return { ok: true, variant: "movesOnly" };
  }

  // 3) синтетический заголовок + ходы
  const synthetic = `[Event "?"]\n[Site "?"]\n[Date "????.??.??"]\n[Round "?"]\n[White "?"]\n[Black "?"]\n[Result "*"]\n\n${movesOnly}`;
  chess.reset();
  if (chess.loadPgn(synthetic, { sloppy: true })) return { ok: true, variant: "synthetic+moves" };

  return { ok: false, variant: "failed" };
}

export default async function routes(
  fastify: FastifyInstance,
  service: AnalyzeService
) {
  const Body = z.object({
    pgn: z.string().min(8),
    depth: z.coerce.number().min(6).max(40).default(14),
    multiPv: z.coerce.number().min(1).max(4).default(1),
  });

  // Alias without /full for backward compatibility
  const handler = async (req: any, reply: any) => {
    const { pgn: raw, depth, multiPv } = Body.parse((req as any).body);

    const hdrLen = (req.headers["x-pgn-length"] as string) || "";
    fastify.log.info({
      msg: "POST /game/full",
      clientLenHeader: hdrLen,
      bodyLen: typeof raw === "string" ? raw.length : -1,
      bodyPreview: String(raw).replace(/\r/g, "\\r").replace(/\n/g, "\\n").slice(0, 200),
    });

    // 1) Санитайз
    const pgn = sanitizePgn(raw);

    // 2) Диагностика после санитайза
    const hasMoves = /\b\d+\.\s/.test(pgn);
    fastify.log.info({
      msg: "PGN after sanitize",
      len: pgn.length,
      hasMoves,
      preview: pgn.replace(/\r/g, "\\r").replace(/\n/g, "\\n").slice(0, 240),
    });

    // 3) Теги
    const tags = parseTags(pgn);
    fastify.log.info({ msg: "PGN tags", tags });

    const header: GameHeader = {
      site: (tags["Site"]?.toLowerCase()?.includes("lichess")
        ? "LICHESS"
        : tags["Site"]?.toLowerCase()?.includes("chess.com")
        ? "CHESSCOM"
        : undefined) as any,
      white: tags["White"],
      black: tags["Black"],
      result: tags["Result"],
      date: tags["UTCDate"] || tags["Date"],
      eco: tags["ECO"],
      opening: tags["Opening"],
      pgn,
      whiteElo: Number(tags["WhiteElo"] || "") || undefined,
      blackElo: Number(tags["BlackElo"] || "") || undefined,
    };

    // 4) Парсинг PGN с фолбэками
    const chess = new Chess();
    const tried = tryLoadPgn(chess, pgn);
    fastify.log.info({ msg: "loadPgn result", tried });

    if (!tried.ok) {
      // В ответ добавим диагностическую выжимку, чтобы видеть, что реально было в секции ходов
      const movesOnly = extractMovesSection(pgn);
      fastify.log.warn({
        msg: "chess.loadPgn FAILED",
        sampleHeader: pgn.slice(0, 200),
        sampleMoves: movesOnly.slice(0, 200),
      });
      return reply.code(400).send({
        error: "Bad PGN",
        sample: pgn.slice(0, 200),
        movesSample: movesOnly.slice(0, 200),
      });
    }

    // 5) FEN/UCIs
    const fens: string[] = [];
    const uciMoves: string[] = [];
    const walk = new Chess();
    const startFen =
      tags["SetUp"] === "1" && tags["FEN"] ? (tags["FEN"] as string) : undefined;
    if (startFen) walk.load(startFen);

    fens.push(walk.fen());
    const history = chess.history({ verbose: true });
    fastify.log.info({ msg: "Moves parsed", movesCount: history.length });

    for (const mv of history) {
      const uci = mv.from + mv.to + (mv.promotion ? mv.promotion : "");
      uciMoves.push(uci);
      walk.move({ from: mv.from, to: mv.to, promotion: mv.promotion as any });
      fens.push(walk.fen());
    }

    // 6) Анализ
    const game = await service.evaluateGame({
      fens,
      uciMoves,
      depth,
      multiPv,
      playersRatings: { white: header.whiteElo as any, black: header.blackElo as any },
    });

    // 7) Ответ
    const positions: PositionEval[] = game.positions.map(
      (p: any, idx: number) => ({
        fen: fens[idx],
        idx,
        lines: p.lines.map(
          (l: any) =>
            ({ pv: l.pv ?? [], cp: l.cp, mate: l.mate, best: p.bestMove } as LineEval)
        ),
      })
    );

    const full: FullReport = {
      header,
      positions,
      moves: (game as any).moves ?? [],
      accuracy: (game as any).accuracy,
      acpl: (game as any).acpl,
      estimatedElo: (game as any).estimatedElo,
      analysisLog: (game as any).analysisLog ?? [],
    };

    return reply.send(full);
  };

  // Register both routes
  fastify.post("/api/v1/evaluate/game", handler);
  fastify.post("/api/v1/evaluate/game/full", handler);
}
