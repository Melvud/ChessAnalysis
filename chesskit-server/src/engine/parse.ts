// src/engine/parse.ts
// Надёжный парсер UCI-ответов без регулярок (вариант B).
// Совместим с остальным кодом: экспортирует parseEvaluationResults(lines, fen, multiPv)
// Возвращает pv в виде string[] (как ожидает клиент), cp/mate "как у движка".

import { PositionEval, LineEval } from "../types/eval";

/** Простейшая проверка формата UCI-хода (e2e4, e7e8q, e1g1 и т.п.). */
function isUciMoveToken(tok: string): boolean {
  return /^(?:[a-h][1-8][a-h][1-8][qrbn]?|0000)$/i.test(tok);
}

/** Разбить хвост после 'pv' в массив UCI-ходов. */
function splitPvToArray(pvTail: string): string[] {
  const tokens = pvTail.trim().split(/\s+/).filter(Boolean);
  const moves = tokens.filter(isUciMoveToken);
  return moves.length ? moves : tokens; // на всякий случай
}

/** Распарсить одну строку `info ...` UCI. */
function parseInfoLine(line: string): {
  depth?: number;
  multipv?: number; // если отсутствует — считаем 1
  cp?: number;
  mate?: number;
  pv?: string[];    // уже массив UCI-ходов
} | undefined {
  if (!line.startsWith("info ")) return undefined;

  const t = line.trim().split(/\s+/);
  const out: { depth?: number; multipv?: number; cp?: number; mate?: number; pv?: string[] } = {};

  for (let i = 1; i < t.length; i++) {
    const tok = t[i];

    if (tok === "depth" && i + 1 < t.length) {
      const v = Number(t[++i]);
      if (!Number.isNaN(v)) out.depth = v;
      continue;
    }

    if (tok === "multipv" && i + 1 < t.length) {
      const v = Number(t[++i]);
      if (!Number.isNaN(v)) out.multipv = v;
      continue;
    }

    if (tok === "score" && i + 2 < t.length) {
      const kind = t[++i]; // 'cp' | 'mate'
      const val = Number(t[++i]);
      if (kind === "cp" && !Number.isNaN(val)) {
        out.cp = val;
        continue;
      }
      if (kind === "mate" && !Number.isNaN(val)) {
        out.mate = val;
        continue;
      }
      // если формат неожиданный — не двигаем i вручную
    }

    if (tok === "pv") {
      const pvTail = t.slice(i + 1).join(" ");
      const arr = splitPvToArray(pvTail);
      if (arr.length) out.pv = arr;
      break; // всё полезное уже есть
    }

    // Прочие поля (seldepth, nodes, nps, hashfull, wdl, ...) игнорируем
  }

  if (!out.pv) return undefined;
  if (out.cp === undefined && out.mate === undefined) return undefined;
  if (out.multipv === undefined) out.multipv = 1;

  return out;
}

/** Распарсить строку `bestmove ... [ponder ...]`. */
function parseBestmove(line: string): { bestmove?: string; ponder?: string } {
  if (!line.startsWith("bestmove")) return {};
  const t = line.trim().split(/\s+/);
  const bestmove = t[1];
  const pIndex = t.indexOf("ponder");
  const ponder = pIndex >= 0 && pIndex + 1 < t.length ? t[pIndex + 1] : undefined;
  return { bestmove, ponder };
}

/**
 * Главная функция: принимает сырые UCI-строки для одной позиции и собирает итог.
 * @param uciLines все строки движка для позиции (info ..., bestmove ...)
 * @param fen      FEN позиции
 * @param requestedMultiPv сколько линий нужно вернуть (обычно meta.multiPv)
 */
export function parseEvaluationResults(
  uciLines: string[],
  fen: string,
  requestedMultiPv: number
): PositionEval {
  // Храним «лучшую по глубине» линию для каждого multipv.
  const byMp = new Map<number, LineEval>();
  let bestmove: string | undefined;
  let ponder: string | undefined;

  for (const raw of uciLines) {
    if (!raw || typeof raw !== "string") continue;
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith("info ")) {
      const parsed = parseInfoLine(line);
      if (!parsed) continue;

      const mv = parsed.multipv ?? 1;
      const depth = parsed.depth ?? 0;

      const next: LineEval = {
        multipv: mv,
        depth,
        pv: parsed.pv!, // массив UCI-ходов
        ...(typeof parsed.cp === "number" ? { cp: parsed.cp } : null),
        ...(typeof parsed.mate === "number" ? { mate: parsed.mate } : null),
        best: parsed.pv && parsed.pv.length ? parsed.pv[0] : undefined,
      };

      const prev = byMp.get(mv);
      if (!prev || depth > prev.depth) {
        byMp.set(mv, next);
      }
      continue;
    }

    if (line.startsWith("bestmove")) {
      const bm = parseBestmove(line);
      if (bm.bestmove) bestmove = bm.bestmove;
      if (bm.ponder) ponder = bm.ponder;
      continue;
    }

    // uciok/id/option/isready/readyok — игнорируем
  }

  const limit = Math.max(1, requestedMultiPv || 1);
  const lines = Array.from(byMp.values())
    .sort((a, b) => a.multiPv - b.multiPv)
    .slice(0, limit);

  return {
    fen,
    lines,
    bestmove,
    ponder,
  } as unknown as PositionEval; // совместимость по типам
}
