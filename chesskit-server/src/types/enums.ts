// chesskit-server/src/core/types.ts
export enum MoveClass {
  OPENING = 'OPENING',
  FORCED = 'FORCED',
  BEST = 'BEST',
  PERFECT = 'PERFECT',
  SPLENDID = 'SPLENDID',
  EXCELLENT = 'EXCELLENT',
  OKAY = 'OKAY',
  INACCURACY = 'INACCURACY',
  MISTAKE = 'MISTAKE',
  BLUNDER = 'BLUNDER',
}

export type LineEval = { pv: string[]; cp?: number; mate?: number; best?: string | null };
export type PositionEval = { fen: string; idx: number; lines: LineEval[] };

export type GameHeader = {
  site?: 'LICHESS' | 'CHESSCOM';
  white?: string | null;
  black?: string | null;
  result?: string | null;
  date?: string | null;
  eco?: string | null;
  opening?: string | null;
  pgn?: string | null;
  whiteElo?: number | null;
  blackElo?: number | null;
};

export type MoveReport = {
  san: string;        // если SAN нет — можно дублировать uci в san
  uci: string;
  beforeFen: string;
  afterFen: string;
  winBefore: number;  // 0..100
  winAfter: number;   // 0..100
  accuracy: number;   // 0..100
  classification: MoveClass;
  tags: string[];
};

export type AccByColor = { itera: number; harmonic: number; weighted: number };
export type AccuracySummary = { whiteMovesAcc: AccByColor; blackMovesAcc: AccByColor };
export type Acpl = { white: number; black: number };
export type EstimatedElo = { whiteEst?: number | null; blackEst?: number | null };

export type FullReport = {
  header: GameHeader;
  positions: PositionEval[];
  moves: MoveReport[];
  accuracy: AccuracySummary;
  acpl: Acpl;
  estimatedElo: EstimatedElo;
  analysisLog: string[];
};
