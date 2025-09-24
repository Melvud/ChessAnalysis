import {
  ceilsNumber,
  getHarmonicMean,
  getStandardDeviation,
  getWeightedMean,
} from "@/lib/math";
import { Accuracy, PositionEval } from "@/types/eval";
import { getPositionWinPercentage } from "./winPercentage";

/**
 * Вычисляет точность для каждой стороны по всем полуходам.
 * Для каждого хода определяется потеря win% и преобразуется в 0–100.
 */
export const computeAccuracy = (positions: PositionEval[]): Accuracy => {
  // Фильтруем позиции с валидными линиями
  const validPositions = positions.filter(p => p.lines && p.lines.length > 0);
  
  if (validPositions.length < 2) {
    return {
      white: 0,
      black: 0,
    };
  }

  const positionsWinPercentage = validPositions.map(getPositionWinPercentage);

  const weights = getAccuracyWeights(positionsWinPercentage);
  const movesAccuracy = getMovesAccuracy(positionsWinPercentage);

  const whiteAccuracy = getPlayerAccuracy(movesAccuracy, weights, "white");
  const blackAccuracy = getPlayerAccuracy(movesAccuracy, weights, "black");

  return {
    white: whiteAccuracy,
    black: blackAccuracy,
  };
};

/**
 * Возвращает средневзвешенную и гармоническую среднюю для игрока.
 * В Chesskit всегда рассчитываются оба вида средней даже при отсутствии ходов.
 */
const getPlayerAccuracy = (
  movesAccuracy: number[],
  weights: number[],
  player: "white" | "black"
): number => {
  const remainder = player === "white" ? 0 : 1;
  const playerAccuracies = movesAccuracy.filter(
    (_, index) => index % 2 === remainder
  );
  const playerWeights = weights.filter((_, index) => index % 2 === remainder);

  if (playerAccuracies.length === 0) {
    return 0;
  }

  const weightedMean = getWeightedMean(playerAccuracies, playerWeights);
  const harmonicMean = getHarmonicMean(playerAccuracies);

  return (weightedMean + harmonicMean) / 2;
};

/**
 * Генерирует веса для средней точности: каждый вес — это округлённое
 * стандартное отклонение в окне, центрированном на ходе.
 */
const getAccuracyWeights = (movesWinPercentage: number[]): number[] => {
  const windowSize = ceilsNumber(
    Math.ceil(movesWinPercentage.length / 10),
    2,
    8
  );

  const windows: number[][] = [];
  const halfWindowSize = Math.round(windowSize / 2);

  for (let i = 1; i < movesWinPercentage.length; i++) {
    const startIdx = i - halfWindowSize;
    const endIdx = i + halfWindowSize;

    if (startIdx < 0) {
      windows.push(movesWinPercentage.slice(0, windowSize));
      continue;
    }

    if (endIdx > movesWinPercentage.length) {
      windows.push(movesWinPercentage.slice(-windowSize));
      continue;
    }

    windows.push(movesWinPercentage.slice(startIdx, endIdx));
  }

  const weights = windows.map((window) => {
    const std = getStandardDeviation(window);
    return ceilsNumber(std, 0.5, 12);
  });

  return weights;
};

/**
 * Для каждой пары позиций вычисляет точность хода (0–100).
 * В отличие от gain, потери измеряются с учётом того, кто делает ход.
 */
const getMovesAccuracy = (movesWinPercentage: number[]): number[] =>
  movesWinPercentage.slice(1).map((winPercent, index) => {
    const lastWinPercent = movesWinPercentage[index];
    const isWhiteMove = index % 2 === 0;
    const winDiff = isWhiteMove
      ? Math.max(0, lastWinPercent - winPercent)
      : Math.max(0, winPercent - lastWinPercent);

    // Lichess accuracy formula
    const rawAccuracy =
      103.1668100711649 * Math.exp(-0.04354415386753951 * winDiff) -
      3.166924740191411;

    return Math.min(100, Math.max(0, rawAccuracy + 1));
  });