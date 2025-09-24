export type LineEval = {
  pv: string[];        // principal variation, UCI-ходы
  depth: number;
  multiPv: number;
  cp?: number;         // centipawns
  mate?: number;       // мат в N (знак: + — мат белым, - — чёрным)
};

export type PositionEval = {
  lines: LineEval[];   // отсортировано по лучшей линии (MultiPV)
  bestMove?: string;   // из "bestmove ..."
  opening?: string;    // можно заполнить позже из book
};

export type GameEval = {
  positions: PositionEval[];
  accuracy?: { white: number; black: number };
  estimatedElo?: { white: number; black: number };
  settings: { engine: string; date: string; depth: number; multiPv: number };
};
