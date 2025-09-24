import { ceilsNumber } from "@/lib/math";
import { LineEval, PositionEval } from "@/types/eval";

/**
 * Robustly compute win% for a position.
 * Some engine outputs may omit cp for mate lines or return fewer MultiPV lines than requested,
 * leaving holes in arrays. We fall back to 50% if no usable line is present.
 */
export const getPositionWinPercentage = (position: PositionEval): number => {
  if (!position || !Array.isArray(position.lines) || position.lines.length === 0) {
    return 50;
  }
  // pick first defined line with either cp or mate; otherwise first defined line
  const firstDefined = position.lines.find((l) => !!l) ?? position.lines[0];
  return getLineWinPercentage(firstDefined);
};

/**
 * Safe per-line win%: accepts possibly undefined line.
 */
export const getLineWinPercentage = (line?: LineEval): number => {
  if (!line) return 50;

  if (typeof line.cp === "number") {
    return getWinPercentageFromCp(line.cp);
  }

  if (typeof line.mate === "number") {
    return getWinPercentageFromMate(line.mate);
  }

  // no cp or mate -> neutral fallback
  return 50;
};

const getWinPercentageFromMate = (mate: number): number => {
  return mate > 0 ? 100 : 0;
};

// Source: https://github.com/lichess-org/lila/blob/a320a93b68da...3b1b2acdfe132b9966/modules/analyse/src/main/WinPercent.scala#L27
const getWinPercentageFromCp = (cp: number): number => {
  const cpCeiled = ceilsNumber(cp, -1000, 1000);
  const MULTIPLIER = -0.00368208; // Source : https://github.com/lichess-org/lila/pull/11148
  const winChances = 2 / (1 + Math.exp(MULTIPLIER * cpCeiled)) - 1;
  return 50 + 50 * winChances;
};
