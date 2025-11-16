export type MoveClass =
  | "OPENING" | "FORCED" | "BEST" | "PERFECT" | "SPLENDID" | "EXCELLENT"
  | "OKAY" | "INACCURACY" | "MISTAKE" | "BLUNDER";

export type LineEval = { pv: string[]; cp?: number; mate?: number; best?: string };
export type PositionEval = { fen: string; idx: number; lines: LineEval[] };

export type MoveReport = {
  san: string; uci: string; beforeFen: string; afterFen: string;
  winBefore: number; winAfter: number; accuracy: number;
  classification: MoveClass; tags: string[];
};

export type AccByColor = { itera: number; harmonic: number; weighted: number };
export type AccuracySummary = { whiteMovesAcc: AccByColor; blackMovesAcc: AccByColor };
export type Acpl = { white: number; black: number };
export type EstimatedElo = { whiteEst?: number; blackEst?: number };

export type GameHeader = {
  site?: "LICHESS" | "CHESSCOM";
  white?: string | null; black?: string | null; result?: string | null;
  date?: string | null; eco?: string | null; opening?: string | null;
  pgn: string; whiteElo?: number | null; blackElo?: number | null;
};

export type FullReport = {
  header: GameHeader;
  positions: PositionEval[];
  moves: MoveReport[];
  accuracy: AccuracySummary;
  acpl: Acpl;
  estimatedElo: EstimatedElo;
  analysisLog: string[];
};
