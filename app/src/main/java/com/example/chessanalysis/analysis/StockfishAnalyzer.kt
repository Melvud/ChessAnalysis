package com.example.chessanalysis.engine

import android.util.Log
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.net.StockfishApiV2
import com.example.chessanalysis.util.AnalyticsMath
import com.example.chessanalysis.util.FenTools
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Анализ партии поверх Stockfish Online v2.
 * Главная идея: на КАЖДОМ ходе:
 *  1) запрашиваем оценку текущей позиции (получаем bestmove, cp/mate)
 *  2) считаем оценку ПОСЛЕ лучшего ответа движка (делаем ход bestMoveUci и снова дергаем API)
 *  3) считаем оценку ПОСЛЕ фактически сыгранного хода (двигаем фактический uci и дергаем API)
 *  4) считаем:
 *     - потерю в cp для ACPL: max(0, bestAfterForMover - actualAfterForMover), clip 1000
 *     - падение win% (логистическая формула) для классификации/accuracy
 *     - категории как в Chesskit по порогам win%-drop (+ Opening/Best/Splendid)
 *
 * Никаких вызовов chess-api здесь нет.
 */
class StockfishAnalyzer(
    private val depth: Int = 14
) : GameAnalyzer {

    private val TAG = "Analyzer"

    override fun analyze(game: ParsedGame): AnalysisResult {
        Log.d(TAG, "=== NEW GAME ANALYSIS === depth=$depth")

        val movesOut = mutableListOf<MoveAnalysis>()
        var fen = game.startFen
        var moverIsWhite = FenTools.sideToMove(fen) == 'w'
        var plyNum = 0

        var acplWhiteSum = 0.0
        var acplBlackSum = 0.0
        var movesW = 0
        var movesB = 0

        val accWinDropsW = mutableListOf<Double>()
        val accWinDropsB = mutableListOf<Double>()

        val counts = mutableMapOf<MoveClass, Int>()

        // Открытие (по книге) — определим один раз по префиксу SAN/PGN
        val opening = game.opening

        for (ply in game.moves) {
            plyNum++

            // 1) оценка ТЕКУЩЕЙ позиции
            val evalNow = StockfishApiV2.evaluateFen(fen, depth)
            if (!evalNow.ok) {
                // Пытаемся продолжать; но чтобы не плодить нули, просто ставим 0 cp как fallback.
                Log.w(TAG, "Engine failed at ply=$plyNum; continue with 0 eval")
            }

            val cpWhite = AnalyticsMath.cpFrom(evalNow)
            // для удобства считаем 'cp для ходящего'
            val cpMoverNow = if (moverIsWhite) cpWhite else -cpWhite
            val winBefore = AnalyticsMath.winPercent(cpMoverNow)

            // 2) оценка ПОСЛЕ лучшего хода движка (для расчёта CPL/ACPL)
            val bestUci = evalNow.bestMoveUci
            val fenBestAfter = if (bestUci != null) FenTools.applyUciSafe(fen, bestUci) else null
            val evalBestAfter = fenBestAfter?.let { StockfishApiV2.evaluateFen(it, depth) }
            val cpBestAfterWhite = evalBestAfter?.let { AnalyticsMath.cpFrom(it) } ?: cpWhite
            val cpBestAfterForMover = if (moverIsWhite) cpBestAfterWhite else -cpBestAfterWhite

            // 3) оценка ПОСЛЕ фактического хода
            val fenActualAfter = FenTools.applyUciSafe(fen, ply.uci)
            val evalActual = fenActualAfter?.let { StockfishApiV2.evaluateFen(it, depth) }
            val cpActualAfterWhite = evalActual?.let { AnalyticsMath.cpFrom(it) } ?: cpWhite
            val cpActualAfterForMover = if (moverIsWhite) cpActualAfterWhite else -cpActualAfterWhite
            val winAfter = AnalyticsMath.winPercent(cpActualAfterForMover)

            // Потери для ACPL (только если хуже лучшего)
            val lossCp = max(0.0, (cpBestAfterForMover - cpActualAfterForMover).toDouble()).coerceIn(0.0, 1000.0)

            if (moverIsWhite) {
                acplWhiteSum += lossCp
                movesW++
            } else {
                acplBlackSum += lossCp
                movesB++
            }

            // Классификация (Chesskit thresholds по win%-drop)
            val drop = max(0.0, winBefore - winAfter) // только ухудшение для ходившего
            val klass = classifyMove(
                drop = drop,
                isOpening = opening?.plyRange?.contains(plyNum) == true, // теоретический — если ход попадает в диапазон книги
                isBest = (bestUci != null && bestUci == ply.uci),
                isSplendidCandidate = isSacrifice(fen, ply.uci) && drop <= 2.0
            )
            counts[klass] = (counts[klass] ?: 0) + 1

            // Лог — чтобы можно было проверить шаги
            Log.d(
                TAG,
                "Move #$plyNum ${if (moverIsWhite) "WHITE" else "BLACK"} ${ply.san} | " +
                        "cp(mover): $cpMoverNow -> $cpActualAfterForMover | " +
                        "win%: ${"%.2f".format(winBefore)} -> ${"%.2f".format(winAfter)} | " +
                        "drop=${"%.2f".format(drop)} class=$klass best=$bestUci"
            )

            // Для точности запомним drop (он же penalization)
            if (moverIsWhite) accWinDropsW += drop else accWinDropsB += drop

            // Запишем деталь хода
            movesOut += MoveAnalysis(
                moveNumber = (plyNum + 1) / 2,
                uci = ply.uci,
                san = ply.san,
                bestMove = bestUci,
                scoreCpBefore = cpMoverNow,
                scoreCpAfter = cpActualAfterForMover,
                winBefore = winBefore,
                winAfter = winAfter,
                classification = klass
            )

            // обновим для следующего ply
            fen = fenActualAfter ?: fen // если по какой-то причине applyUci не удался — не ломаемся
            moverIsWhite = !moverIsWhite
        }

        // === ИТОГИ ===
        val acplW = if (movesW > 0) acplWhiteSum / movesW else 0.0
        val acplB = if (movesB > 0) acplBlackSum / movesB else 0.0

        // Accuracy% как у Chesskit (экспонента по drop + веса мы опускаем, если хотите — можно включить позже)
        val accW = AnalyticsMath.accuracyPercent(accWinDropsW)
        val accB = AnalyticsMath.accuracyPercent(accWinDropsB)
        val accTotal = (accW + accB) / 2.0

        // Performance по ACPL (Chesskit / формула Lichess)
        val perfW = AnalyticsMath.estimateEloFromAcpl(acplW, game.whiteElo)
        val perfB = AnalyticsMath.estimateEloFromAcpl(acplB, game.blackElo)

        Log.d(TAG, "--- SUMMARY ---")
        Log.d(TAG, "ACPL white=${"%.1f".format(acplW)} black=${"%.1f".format(acplB)}")
        Log.d(TAG, "ACC white=${"%.1f".format(accW)}% black=${"%.1f".format(accB)}% total=${"%.1f".format(accTotal)}%")
        Log.d(TAG, "PERF (ACPL) white=${perfW ?: "-"} black=${perfB ?: "-"}; tags W:${game.whiteElo ?: "-"} B:${game.blackElo ?: "-"}")

        return AnalysisResult(
            moves = movesOut,
            summary = SummaryBlock(
                acplWhite = acplW,
                acplBlack = acplB,
                accuracyWhite = accW,
                accuracyBlack = accB,
                perfWhite = perfW,
                perfBlack = perfB,
                opening = opening,
                counts = counts
            )
        )
    }

    private fun classifyMove(
        drop: Double,
        isOpening: Boolean,
        isBest: Boolean,
        isSplendidCandidate: Boolean
    ): MoveClass {
        if (isOpening) return MoveClass.OPENING
        if (isBest && drop <= 0.5) return MoveClass.BEST
        if (isSplendidCandidate) return MoveClass.SPLENDID

        // Chesskit thresholds by win%-drop:
        // >20% BLUNDER, 10–20 MISTAKE, 5–10 INACCURACY, 2–5 OKAY, <=2 EXCELLENT
        return when {
            drop > 20.0 -> MoveClass.BLUNDER
            drop > 10.0 -> MoveClass.MISTAKE
            drop > 5.0  -> MoveClass.INACCURACY
            drop > 2.0  -> MoveClass.OKAY
            else        -> MoveClass.EXCELLENT
        }
    }

    /** Примитивная проверка "жертвы" (материал минус сразу после хода). */
    private fun isSacrifice(fenBefore: String, uci: String): Boolean {
        val matBefore = FenTools.materialCount(fenBefore)
        val fenAfter = FenTools.applyUciSafe(fenBefore, uci) ?: return false
        val matAfter = FenTools.materialCount(fenAfter)
        // жертва хотя бы фигуры (>= 3 пешек эквивалентно)
        return (matAfter - matBefore) <= -300
    }
}
