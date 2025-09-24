export type Acpl = { white: number; black: number };

export function computeAcpl(positions: any[], cplCap = 500): Acpl {
  if (!Array.isArray(positions) || positions.length < 2) {
    return { white: 0, black: 0 };
  }

  let whiteSum = 0;
  let whiteCnt = 0;
  let blackSum = 0;
  let blackCnt = 0;

  // Идём по полуходам: i -> ход сыгран, сравниваем оценку до и после
  for (let i = 0; i < positions.length - 1; i++) {
    const before = positions[i];
    const after = positions[i + 1];
    if (!before || !after) continue;

    const fen: string | undefined = before.fen;
    const l0Before = before.lines?.[0];
    const l0After = after.lines?.[0];
    if (!fen || !l0Before || !l0After) continue;

    const sideToMove = sideFromFen(fen); // 'w' | 'b'

    // Пропускаем, если матовые оценки (mate) — cp в таких случаях некорректно усреднять
    if (l0Before.mate != null || l0After.mate != null) continue;

    // Требуются численные cp
    if (typeof l0Before.cp !== "number" || typeof l0After.cp !== "number") continue;

    // Приводим знак cp к стороне, которая ХОДИТ в "before":
    //  - Белые ходят:  signed(cp) = cp
    //  - Чёрные ходят: signed(cp) = -cp
    const bestBeforeSigned = sideToMove === "w" ? l0Before.cp : -l0Before.cp;
    const afterSigned = sideToMove === "w" ? l0After.cp : -l0After.cp;

    // Потеря (clipped)
    const loss = Math.abs(bestBeforeSigned - afterSigned);
    const clipped = Math.min(loss, cplCap);

    if (sideToMove === "w") {
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

/** Вытаскиваем, кто ходит, из FEN (второе поле) */
function sideFromFen(fen: string): "w" | "b" {
  // FEN: "<pieces> <side> <castling> <enpassant> <halfmove> <fullmove>"
  // Пример: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  try {
    const parts = fen.trim().split(/\s+/);
    const side = parts[1];
    return side === "b" ? "b" : "w";
  } catch {
    return "w";
  }
}
