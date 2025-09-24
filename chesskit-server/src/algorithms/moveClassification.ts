import { LineEval, PositionEval } from "@/types/eval";
import {
  getLineWinPercentage,
  getPositionWinPercentage,
} from "./winPercentage";
import { openings } from "@/data/openings";
import { Chess, PieceSymbol } from "chess.js";

/**
 * Классификация ходов, соответствующая Chesskit.
 * Метки совпадают с оригинальными строками, но соответствуют вашим именам.
 */
export enum MoveClassification {
  Opening = "OPENING",
  Forced = "FORCED",
  Best = "BEST",
  Perfect = "PERFECT",
  Splendid = "SPLENDID",
  Excellent = "EXCELLENT",
  Okay = "OKAY",
  Inaccuracy = "INACCURACY",
  Mistake = "MISTAKE",
  Blunder = "BLUNDER"
}

/**
 * Функция нормализует FEN для поиска дебюта (оставляет только расположение фигур).
 */
function normalizeFen(fen: string): string {
  return fen.split(' ')[0];
}

/**
 * Ищет дебютную позицию в списке openings. Сравнение проводится только
 * по расстановке фигур (без хода, рокировок и т.п.).
 */
function findOpening(fen: string): string | undefined {
  const normalizedFen = normalizeFen(fen);
  const opening = openings.find(o => normalizeFen(o.fen) === normalizedFen);
  return opening?.name;
}

/**
 * Главная функция классификации всех полуходов.
 * Принимает массив оценок позиций, массив ходов UCI и массив FEN'ов.
 * Возвращает массив тех же позиций, но с добавленными полями opening и moveClassification.
 */
export const getMovesClassification = (
  rawPositions: PositionEval[],
  uciMoves: string[],
  fens: string[]
): PositionEval[] => {
  const positionsWinPercentage = rawPositions.map(getPositionWinPercentage);
  let currentOpening: string | undefined = undefined;

  const positions = rawPositions.map((rawPosition, index) => {
    // Первая запись — начальная позиция, её не классифицируем
    if (index === 0) return rawPosition;

    // Проверяем, является ли позиция известным дебютом (только размещение фигур)
    const currentFenPlacement = fens[index].split(" ")[0];
    const opening = openings.find((o) => o.fen === currentFenPlacement);
    if (opening) {
      currentOpening = opening.name;
      return {
        ...rawPosition,
        opening: opening.name,
        moveClassification: MoveClassification.Opening,
      };
    }

    const prevPosition = rawPositions[index - 1];

    // Проверяем наличие линий в предыдущей позиции
    if (!prevPosition.lines || prevPosition.lines.length === 0) {
      return {
        ...rawPosition,
        opening: currentOpening,
        moveClassification: MoveClassification.Okay,
      };
    }

    // Если у предыдущей позиции только одна линия, ход форсированный
    if (prevPosition.lines.length === 1) {
      return {
        ...rawPosition,
        opening: currentOpening,
        moveClassification: MoveClassification.Forced,
      };
    }

    const playedMove = uciMoves[index - 1];
    // Альтернативный ход — первый ход в линиях предыдущей позиции, который не совпадает с сыгранным
    const lastPositionAlternativeLine: LineEval | undefined =
      prevPosition.lines.filter((line) => line.pv[0] !== playedMove)?.[0];
    const lastPositionAlternativeLineWinPercentage = lastPositionAlternativeLine
      ? getLineWinPercentage(lastPositionAlternativeLine)
      : undefined;

    // Проверяем наличие линий в текущей позиции
    if (!rawPosition.lines || rawPosition.lines.length === 0) {
      return {
        ...rawPosition,
        opening: currentOpening,
        moveClassification: MoveClassification.Okay,
      };
    }

    // Лучший вариант для текущей позиции (пв первой линии)
    const bestLinePvToPlay = rawPosition.lines[0].pv;

    const lastPositionWinPercentage = positionsWinPercentage[index - 1];
    const positionWinPercentage = positionsWinPercentage[index];
    // index = позиция после хода; index % 2 === 1 означает ход белых
    const isWhiteMove = index % 2 === 0;

    // Проверка на Splendid (жертва фигуры с компенсацией)
    if (
      isSplendidMove(
        lastPositionWinPercentage,
        positionWinPercentage,
        isWhiteMove,
        playedMove,
        bestLinePvToPlay,
        fens[index - 1],
        lastPositionAlternativeLineWinPercentage
      )
    ) {
      return {
        ...rawPosition,
        opening: currentOpening,
        moveClassification: MoveClassification.Splendid,
      };
    }

    // Подготовка данных для Perfect
    const fenTwoMovesAgo = index > 1 ? fens[index - 2] : null;
    const uciNextTwoMoves: [string, string] | null =
      index > 1 ? [uciMoves[index - 2], uciMoves[index - 1]] : null;

    // Проверка на Perfect (единственный хороший ход или изменение исхода партии)
    if (
      isPerfectMove(
        lastPositionWinPercentage,
        positionWinPercentage,
        isWhiteMove,
        lastPositionAlternativeLineWinPercentage,
        fenTwoMovesAgo,
        uciNextTwoMoves
      )
    ) {
      return {
        ...rawPosition,
        opening: currentOpening,
        moveClassification: MoveClassification.Perfect,
      };
    }

    // Сыгран лучший ход?
    if (playedMove === prevPosition.bestMove) {
      return {
        ...rawPosition,
        opening: currentOpening,
        moveClassification: MoveClassification.Best,
      };
    }

    // Базовая классификация по потере оценки
    const moveClassification = getMoveBasicClassification(
      lastPositionWinPercentage,
      positionWinPercentage,
      isWhiteMove
    );

    return {
      ...rawPosition,
      opening: currentOpening,
      moveClassification,
    };
  });

  return positions;
};

/**
 * Базовая классификация по разнице win% (для простых случаев).
 * Значения порогов взяты из Chesskit.
 */
const getMoveBasicClassification = (
  lastPositionWinPercentage: number,
  positionWinPercentage: number,
  isWhiteMove: boolean
): MoveClassification => {
  const winPercentageDiff =
    (positionWinPercentage - lastPositionWinPercentage) *
    (isWhiteMove ? 1 : -1);

  if (winPercentageDiff < -20) return MoveClassification.Blunder;
  if (winPercentageDiff < -10) return MoveClassification.Mistake;
  if (winPercentageDiff < -5) return MoveClassification.Inaccuracy;
  if (winPercentageDiff < -2) return MoveClassification.Okay;
  return MoveClassification.Excellent;
};

/**
 * Определяет, является ли ход «perfect» согласно логике Chesskit.
 * Ход должен либо менять исход партии (проигрыш/ничья/выигрыш), либо быть
 * единственным, сохраняющим серьёзное преимущество относительно альтернативы.
 */
const isPerfectMove = (
  lastPositionWinPercentage: number,
  positionWinPercentage: number,
  isWhiteMove: boolean,
  lastPositionAlternativeLineWinPercentage: number | undefined,
  fenTwoMovesAgo: string | null,
  uciMoves: [string, string] | null
): boolean => {
  if (lastPositionAlternativeLineWinPercentage === undefined) return false;

  // Потеря оценки относительно стороны, сделавшей ход
  const winPercentageDiff =
    (positionWinPercentage - lastPositionWinPercentage) * (isWhiteMove ? 1 : -1);
  if (winPercentageDiff < -2) return false;

  // Исключаем простые взятия одной и той же фигуры (recapture)
  if (
    fenTwoMovesAgo &&
    uciMoves &&
    isSimplePieceRecapture(fenTwoMovesAgo, uciMoves)
  ) {
    return false;
  }

  // Исключаем случаи, когда позиция проиграна или альтернатива полностью выигрышна
  if (
    isLosingOrAlternateCompletelyWinning(
      positionWinPercentage,
      lastPositionAlternativeLineWinPercentage,
      isWhiteMove
    )
  ) {
    return false;
  }

  // Ход поменял исход партии (высокое изменение и переход через 50%)
  const hasChangedGameOutcome = getHasChangedGameOutcome(
    lastPositionWinPercentage,
    positionWinPercentage,
    isWhiteMove
  );

  // Ход — единственный хороший (превосходит альтернативу более чем на 10%)
  const isTheOnlyGoodMove = getIsTheOnlyGoodMove(
    positionWinPercentage,
    lastPositionAlternativeLineWinPercentage,
    isWhiteMove
  );

  return hasChangedGameOutcome || isTheOnlyGoodMove;
};

/**
 * Определяет, является ли ход «splendid» (жертва с компенсацией).
 * Жертва должна быть неочевидной, улучшать позицию и не быть полностью
 * проигранной альтернативой.
 */
const isSplendidMove = (
  lastPositionWinPercentage: number,
  positionWinPercentage: number,
  isWhiteMove: boolean,
  playedMove: string,
  bestLinePvToPlay: string[],
  fen: string,
  lastPositionAlternativeLineWinPercentage: number | undefined
): boolean => {
  if (lastPositionAlternativeLineWinPercentage === undefined) return false;
  // Потеря оценки относительно стороны, сделавшей ход
  const winPercentageDiff =
    (positionWinPercentage - lastPositionWinPercentage) * (isWhiteMove ? 1 : -1);
  if (winPercentageDiff < -2) return false;

  // Должна быть жертва материала
  const isPieceSacrifice = getIsPieceSacrifice(
    fen,
    playedMove,
    bestLinePvToPlay
  );
  if (!isPieceSacrifice) return false;

  // Исключаем случаи, когда позиция проиграна или альтернатива полностью выигрышна
  if (
    isLosingOrAlternateCompletelyWinning(
      positionWinPercentage,
      lastPositionAlternativeLineWinPercentage,
      isWhiteMove
    )
  ) {
    return false;
  }

  return true;
};

/**
 * Проверяет, приводит ли ход к проигрышу или имеет ли альтернатива полностью
 * выигрышную позицию для соперника. Используется для фильтрации perfect и splendid.
 */
const isLosingOrAlternateCompletelyWinning = (
  positionWinPercentage: number,
  lastPositionAlternativeLineWinPercentage: number,
  isWhiteMove: boolean
): boolean => {
  const isLosing = isWhiteMove
    ? positionWinPercentage < 50
    : positionWinPercentage > 50;
  const isAlternateCompletelyWinning = isWhiteMove
    ? lastPositionAlternativeLineWinPercentage > 97
    : lastPositionAlternativeLineWinPercentage < 3;
  return isLosing || isAlternateCompletelyWinning;
};

const getHasChangedGameOutcome = (
  lastPositionWinPercentage: number,
  positionWinPercentage: number,
  isWhiteMove: boolean
): boolean => {
  const winPercentageDiff =
    (positionWinPercentage - lastPositionWinPercentage) * (isWhiteMove ? 1 : -1);
  return (
    winPercentageDiff > 10 &&
    ((lastPositionWinPercentage < 50 && positionWinPercentage > 50) ||
      (lastPositionWinPercentage > 50 && positionWinPercentage < 50))
  );
};

const getIsTheOnlyGoodMove = (
  positionWinPercentage: number,
  lastPositionAlternativeLineWinPercentage: number,
  isWhiteMove: boolean
): boolean => {
  const winPercentageDiff =
    (positionWinPercentage - lastPositionAlternativeLineWinPercentage) *
    (isWhiteMove ? 1 : -1);
  return winPercentageDiff > 10;
};

/**
 * Вспомогательная функция: разбивает UCI строку на from/to и promotion
 */
const uciMoveParams = (
  uciMove: string
): { from: string; to: string; promotion?: string } => {
  return {
    from: uciMove.slice(0, 2),
    to: uciMove.slice(2, 4),
    promotion: uciMove.slice(4) || undefined,
  };
};

/**
 * Проверяет, является ли последовательность из двух UCI‑ходов простым
 * перехватом (обоюдным взятием на одном поле). Используется для perfect.
 */
const isSimplePieceRecapture = (
  fen: string,
  uciMoves: [string, string]
): boolean => {
  try {
    const game = new Chess(fen);
    const moves = uciMoves.map((uciMove) => uciMoveParams(uciMove));
    if (moves[0].to !== moves[1].to) return false;
    const piece = game.get(moves[0].to as any);
    return !!piece;
  } catch {
    return false;
  }
};

/**
 * Возвращает материальное соотношение (плюсовое, если белые имеют
 * преимущество). Используется в getIsPieceSacrifice.
 */
const getMaterialDifference = (fen: string): number => {
  try {
    const game = new Chess(fen);
    const board = game.board().flat();
    return board.reduce((acc, square: any) => {
      if (!square) return acc;
      const value = getPieceValue(square.type as PieceSymbol);
      return square.color === "w" ? acc + value : acc - value;
    }, 0);
  } catch {
    return 0;
  }
};

// Оценка стоимости фигуры по типу
const getPieceValue = (piece: PieceSymbol): number => {
  switch (piece) {
    case "p":
      return 1;
    case "n":
    case "b":
      return 3;
    case "r":
      return 5;
    case "q":
      return 9;
    default:
      return 0;
  }
};

/**
 * Проверяет, является ли ход жертвой материала с компенсацией. Алгоритм из Chesskit.
 */
const getIsPieceSacrifice = (
  fen: string,
  playedMove: string,
  bestLinePvToPlay: string[]
): boolean => {
  try {
    if (!bestLinePvToPlay.length) return false;
    const game = new Chess(fen);
    const whiteToPlay = game.turn() === "w";
    const startingMaterialDifference = getMaterialDifference(fen);
    let moves: string[] = [playedMove, ...bestLinePvToPlay];
    if (moves.length % 2 === 1) {
      moves = moves.slice(0, -1);
    }
    let nonCapturingMovesTemp = 1;
    const capturedPieces: { w: PieceSymbol[]; b: PieceSymbol[] } = { w: [], b: [] };
    for (const move of moves) {
      try {
        const fullMove = game.move(uciMoveParams(move) as any);
        if (fullMove && fullMove.captured) {
          capturedPieces[fullMove.color as "w" | "b"].push(fullMove.captured as PieceSymbol);
          nonCapturingMovesTemp = 1;
        } else {
          nonCapturingMovesTemp--;
          if (nonCapturingMovesTemp < 0) break;
        }
      } catch {
        return false;
      }
    }
    // Удаляем взаимные размены
    for (const p of capturedPieces["w"].slice()) {
      const idx = capturedPieces["b"].indexOf(p);
      if (idx >= 0) {
        capturedPieces["b"].splice(idx, 1);
        capturedPieces["w"].splice(capturedPieces["w"].indexOf(p), 1);
      }
    }
    // Если разница захваченных фигур не более 1 и все они пешки, не считаем жертвой
    if (
      Math.abs(capturedPieces["w"].length - capturedPieces["b"].length) <= 1 &&
      capturedPieces["w"].concat(capturedPieces["b"]).every((p) => p === "p")
    ) {
      return false;
    }
    const endingMaterialDifference = getMaterialDifference(game.fen());
    const materialDiff = endingMaterialDifference - startingMaterialDifference;
    const materialDiffPlayerRelative = whiteToPlay ? materialDiff : -materialDiff;
    return materialDiffPlayerRelative < 0;
  } catch {
    return false;
  }
};