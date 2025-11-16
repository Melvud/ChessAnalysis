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
  fen?: string;        // FEN позиции
  moveClassification?: string; // классификация хода
};

export type Accuracy = {
  white: number;
  black: number;
};

export type EstimatedElo = {
  white: number;
  black: number;
};

export type GameEval = {
  positions: PositionEval[];
  accuracy?: Accuracy;
  estimatedElo?: EstimatedElo;
  settings: { engine: string; date: string; depth: number; multiPv: number };
};