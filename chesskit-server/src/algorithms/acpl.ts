export type Acpl = { white: number; black: number };

/**
 * Рассчитывает средний centipawn loss (ACPL) отдельно для белых и чёрных
 * по логике Chesskit: учитываем ТОЛЬКО потери (ухудшение оценки для стороны, делающей ход).
 * Улучшение позиции даёт нулевой вклад в CPL.
 * Матовые оценки пропускаем. Значения cp от движка даны относительно стороны, которая ХОДИТ в позиции.
 * Поэтому после хода (в позиции `after`) оценка относится уже к сопернику и её нужно инвертировать,
 * чтобы сравнивать из точки зрения сделавшего ход.
 */
export function computeAcpl(positions: any[], cplCap = 500): Acpl {
  if (!Array.isArray(positions) || positions.length < 2) {
    return { white: 0, black: 0 };
  }

  let whiteSum = 0;
  let whiteCnt = 0;
  let blackSum = 0;
  let blackCnt = 0;

  for (let i = 0; i < positions.length - 1; i++) {
    const before = positions[i];
    const after = positions[i + 1];
    if (!before || !after) continue;

    const fen: string | undefined = before.fen;
    const l0Before = before.lines?.[0];
    const l0After = after.lines?.[0];
    if (!fen || !l0Before || !l0After) continue;

    // Пропускаем, если есть матовые оценки
    if (l0Before.mate != null || l0After.mate != null) continue;

    // Должны быть численные cp (относительно стороны, которая ходит в соответствующей позиции)
    if (typeof l0Before.cp !== "number" || typeof l0After.cp !== "number") continue;

    // Приводим обе оценки к системе отсчёта сделавшего ход:
    // - до хода: уже в нужной системе отсчёта (ходит тот, кто делает ход)
    // - после хода: очередь соперника, значит инвертируем знак
    const afterFromMoverPOV = -l0After.cp;

    // Потеря только при ухудшении оценки для сделавшего ход
    const loss = Math.max(0, l0Before.cp - afterFromMoverPOV);
    const clipped = Math.min(loss, cplCap);

    const side = sideFromFen(fen);
    if (side === "w") {
      whiteSum += clipped;
      whiteCnt++;
    } else {
      blackSum += clipped;
      blackCnt++;
    }
  }

  const white = whiteCnt ? Math.round(whiteSum / whiteCnt) : 0;
  const black = blackCnt ? Math.round(blackSum / blackCnt) : 0;
  return { white, black };
}

/** Кто должен ходить в позиции по FEN */
function sideFromFen(fen: string): "w" | "b" {
  try {
    const parts = fen.trim().split(/\s+/);
    const side = parts[1];
    return side === "b" ? "b" : "w";
  } catch {
    return "w";
  }
}
