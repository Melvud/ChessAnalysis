import { ceilsNumber } from "@/lib/math";
import { EstimatedElo, PositionEval } from "@/types/eval";

/**
 * Оценивает примерный рейтинг по среднему centipawn loss (CPL) игры.
 * Берёт среднее CPL для белых/чёрных и преобразует в рейтинг, используя формулы Chesskit.
 */
export const computeEstimatedElo = (
  positions: PositionEval[],
  whiteElo?: number,
  blackElo?: number
): EstimatedElo | undefined => {
  if (positions.length < 2) {
    return undefined;
  }

  const { whiteCpl, blackCpl } = getPlayersAverageCpl(positions);

  const whiteEstimatedElo = getEloFromRatingAndCpl(
    whiteCpl,
    whiteElo ?? blackElo
  );
  const blackEstimatedElo = getEloFromRatingAndCpl(
    blackCpl,
    blackElo ?? whiteElo
  );

  return { white: whiteEstimatedElo, black: blackEstimatedElo };
};

// Получаем оценку позиции в центимату по логике Chesskit
const getPositionCp = (position: PositionEval): number => {
  const line0 = position?.lines?.[0];
  if (!line0) {
    // Явно сигнализируем, что движок не вернул линию для позиции
    throw new Error("missing_primary_line");
  }

  if (line0.cp !== undefined) {
    return ceilsNumber(line0.cp, -1000, 1000);
  }

  if (line0.mate !== undefined) {
    // Используем «бесконечность» и клиппим до [-1000;1000], как в Chesskit
    return ceilsNumber(line0.mate * Infinity, -1000, 1000);
  }

  throw new Error("missing_cp_and_mate");
};


/**
 * Для каждой стороны суммирует потери (cpl) и делит на фиксированное количество ходов.
 * Не пропускает ходы — все позиции должны иметь cp или mate.
 */
const getPlayersAverageCpl = (
  positions: PositionEval[]
): { whiteCpl: number; blackCpl: number } => {
  // Начинаем со значения cp первой позиции
  let previousCp = getPositionCp(positions[0]);

  // Суммируем потери cp для каждой стороны. Не пропускаем ходы,
  // даже если оценок нет, поскольку getPositionCp бросит исключение
  const { whiteCpl, blackCpl } = positions.slice(1).reduce(
    (acc, position, index) => {
      const cp = getPositionCp(position);

      if (index % 2 === 0) {
        // белые ходили
        acc.whiteCpl += cp > previousCp ? 0 : Math.min(previousCp - cp, 1000);
      } else {
        // чёрные ходили
        acc.blackCpl += cp < previousCp ? 0 : Math.min(cp - previousCp, 1000);
      }

      previousCp = cp;
      return acc;
    },
    { whiteCpl: 0, blackCpl: 0 }
  );

  // Делим на фиксированное количество ходов для каждой стороны
  return {
    whiteCpl: whiteCpl / Math.ceil((positions.length - 1) / 2),
    blackCpl: blackCpl / Math.floor((positions.length - 1) / 2),
  };
};

// Lichess formula: перевод среднего CPL в рейтинг ELO
const getEloFromAverageCpl = (averageCpl: number) =>
  3100 * Math.exp(-0.01 * averageCpl);

// Обратная функция: ожидаемое CPL для заданного рейтинга
const getAverageCplFromElo = (elo: number) =>
  -100 * Math.log(Math.min(elo, 3100) / 3100);

/**
 * Комбинирует CPL и известный рейтинг игрока.
 * Если рейтинг известен, корректируем оценку в сторону игрока.
 */
const getEloFromRatingAndCpl = (
  gameCpl: number,
  rating: number | undefined
): number => {
  const eloFromCpl = getEloFromAverageCpl(gameCpl);
  if (!rating) return eloFromCpl;

  const expectedCpl = getAverageCplFromElo(rating);
  const cplDiff = gameCpl - expectedCpl;
  if (cplDiff === 0) return eloFromCpl;

  if (cplDiff > 0) {
    return rating * Math.exp(-0.005 * cplDiff);
  } else {
    return rating / Math.exp(-0.005 * -cplDiff);
  }
};
