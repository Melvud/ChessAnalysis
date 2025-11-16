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

  try {
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
  } catch (error) {
    // Если не удалось вычислить CPL, возвращаем undefined
    console.warn("Failed to compute estimated ELO:", error);
    return undefined;
  }
};

// Получаем оценку позиции в центипешках по логике Chesskit
const getPositionCp = (position: PositionEval): number | null => {
  const line0 = position?.lines?.[0];
  if (!line0) {
    // Возвращаем null вместо выброса исключения
    return null;
  }

  if (line0.cp !== undefined) {
    return ceilsNumber(line0.cp, -1000, 1000);
  }

  if (line0.mate !== undefined) {
    // Используем «бесконечность» и клиппим до [-1000;1000], как в Chesskit
    return ceilsNumber(line0.mate * Infinity, -1000, 1000);
  }

  return null;
};


/**
 * Для каждой стороны суммирует потери (cpl) и делит на фиксированное количество ходов.
 * Пропускает позиции без оценок.
 */
const getPlayersAverageCpl = (
  positions: PositionEval[]
): { whiteCpl: number; blackCpl: number } => {
  // Начинаем со значения cp первой позиции
  const firstCp = getPositionCp(positions[0]);
  if (firstCp === null) {
    // Если первая позиция не имеет оценки, возвращаем нулевые потери
    return { whiteCpl: 0, blackCpl: 0 };
  }
  
  let previousCp = firstCp;
  let whiteCount = 0;
  let blackCount = 0;

  // Суммируем потери cp для каждой стороны, пропуская позиции без оценок
  const { whiteCpl, blackCpl } = positions.slice(1).reduce(
    (acc, position, index) => {
      const cp = getPositionCp(position);
      
      // Пропускаем позицию если нет оценки
      if (cp === null) {
        return acc;
      }

      if (index % 2 === 0) {
        // белые ходили
        acc.whiteCpl += cp > previousCp ? 0 : Math.min(previousCp - cp, 1000);
        whiteCount++;
      } else {
        // чёрные ходили
        acc.blackCpl += cp < previousCp ? 0 : Math.min(cp - previousCp, 1000);
        blackCount++;
      }

      previousCp = cp;
      return acc;
    },
    { whiteCpl: 0, blackCpl: 0 }
  );

  // Делим на количество учтенных ходов для каждой стороны
  return {
    whiteCpl: whiteCount > 0 ? whiteCpl / whiteCount : 0,
    blackCpl: blackCount > 0 ? blackCpl / blackCount : 0,
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