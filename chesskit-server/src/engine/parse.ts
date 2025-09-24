import { PositionEval, LineEval } from "../types/eval";

const RE_INFO = /info .*?depth (\d+).*?(?:multipv (\d+))?.*?score (cp (-?\d+)|mate (-?\d+)).*?pv (.+)$/;

export function parseEvaluationResults(
  lines: string[], 
  fen: string, 
  multiPv: number
): PositionEval {
  const map = new Map<number, LineEval>();
  let bestMove: string | undefined;

  for (const line of lines) {
    if (line.startsWith("bestmove")) {
      const m = /bestmove\s+(\S+)/.exec(line);
      bestMove = m?.[1];
      continue;
    }
    
    const m = RE_INFO.exec(line);
    if (!m) continue;

    const depth = Number(m[1]);
    const mpv = Number(m[2] ?? 1);
    const cp = m[4] !== undefined ? Number(m[4]) : undefined;
    const mate = m[5] !== undefined ? Number(m[5]) : undefined;
    const pv = m[6].trim().split(/\s+/);

    const existing = map.get(mpv);
    if (!existing || depth >= existing.depth) {
      map.set(mpv, { depth, multiPv: mpv, cp, mate, pv });
    }
  }

  const linesSorted = Array.from(map.values())
    .sort((a, b) => {
      // Сортируем по multiPv (1 - лучший)
      return a.multiPv - b.multiPv;
    })
    .slice(0, multiPv);

  return { 
    lines: linesSorted, 
    bestMove,
    fen 
  };
}