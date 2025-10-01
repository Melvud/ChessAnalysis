package com.example.chessanalysis.local

import com.example.chessanalysis.*
import com.example.chessanalysis.EngineClient.LineDTO
import com.example.chessanalysis.EngineClient.PositionDTO
import com.example.chessanalysis.EngineClient.ProgressSnapshot
import com.example.chessanalysis.EngineClient.MoveRealtimeResult
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.abs

// ВАЖНО: используем ТОП-УРОВНЕВЫЕ модели проекта.
import com.example.chessanalysis.LineEval
import com.example.chessanalysis.MoveEval
import com.example.chessanalysis.PositionEval

class LocalGameAnalyzer(
    private val progressHook: (id: String, percent: Double?, stage: String?) -> Unit = { _, _, _ -> }
) {

    suspend fun evaluateGameByPgn(
        pgn: String,
        depth: Int,
        multiPv: Int,
        workersNb: Int,
        header: GameHeader?
    ): FullReport = evaluateGameByPgnWithProgress(pgn, depth, multiPv, workersNb, header) { _ -> }

    suspend fun evaluateGameByPgnWithProgress(
        pgn: String,
        depth: Int,
        multiPv: Int,
        workersNb: Int,
        header: GameHeader?,
        onProgress: (ProgressSnapshot) -> Unit
    ): FullReport = withContext(Dispatchers.IO) {
        val parsed = PgnChess.movesWithFens(pgn)
        val total = parsed.size

        val reportPositions = ArrayList<PositionEval>(total + 1)
        val reportMoves = ArrayList<MoveEval>(total)

        val progressId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        // Стартовая позиция
        val startFen = parsed.firstOrNull()?.beforeFen
            ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val pos0 = EngineClient.evaluateFenDetailed(startFen, depth, multiPv, null)
        reportPositions += toPositionEval(
            fen = startFen,
            idx = 0,
            src = pos0
        )
        notify(progressId, 0, total, "init", startedAt, onProgress)

        // Анализ ходов
        for (i in 0 until total) {
            val beforeFen = parsed[i].beforeFen
            val afterFen = parsed[i].afterFen
            val uciPlayed = parsed[i].uci

            val posBefore = EngineClient.evaluateFenDetailed(beforeFen, depth, multiPv, null)
            val posAfter = EngineClient.evaluateFenDetailed(afterFen, depth, multiPv, null)

            val bestMove = posBefore.bestMove ?: posBefore.lines.firstOrNull()?.pv?.firstOrNull()
            val cls = classifyMove(posBefore, posAfter, bestMove, uciPlayed)

            reportMoves += MoveEval(
                played = uciPlayed,
                bestMove = bestMove,
                classification = cls
            )

            reportPositions += toPositionEval(
                fen = afterFen,
                idx = i + 1,
                src = posAfter
            )

            notify(progressId, i + 1, total, "analyzing", startedAt, onProgress)
        }

        val tagsHeader = PgnChess.headerFromPgn(pgn)
        val hdr = header ?: tagsHeader

        // Win% → per-move accuracy
        val winPercents: List<Double> = reportPositions.map { p ->
            val top = p.lines.firstOrNull()
            if (top == null) 50.0 else WinPercentage.getLineWinPercentage(top.cp, top.mate).toDouble()
        }
        val movesAccuracy = Accuracy.perMoveAccFromWin(winPercents)
        val whiteAcc = movesAccuracy.filterIndexed { idx, _ -> idx % 2 == 0 }.average()
        val blackAcc = movesAccuracy.filterIndexed { idx, _ -> idx % 2 == 1 }.average()

        // ACPL: требуется white/black acc и weighted
        val acplByColor = Accuracy.calculateACPL(
            positions = reportPositions,
            whiteMovesAcc = whiteAcc,
            blackMovesAcc = blackAcc,
            weighted = true
        )

        // Estimated Elo
        val est = EstimateElo.computeEstimatedElo(
            positions = reportPositions,
            whiteElo = hdr.whiteElo,
            blackElo = hdr.blackElo
        )

        FullReport(
            header = hdr,
            moves = reportMoves,
            positions = reportPositions,
            accuracy = AccuracySummary(AccByColor(whiteAcc, blackAcc)),
            acpl = Acpl(acplByColor.white, acplByColor.black),
            estimatedElo = EstimatedElo(est.white, est.black)
        )
    }

    suspend fun analyzeMoveRealtimeDetailed(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int?
    ): MoveRealtimeResult = withContext(Dispatchers.IO) {
        val posBefore = EngineClient.evaluateFenDetailed(beforeFen, depth, multiPv, skillLevel)
        val posAfter = EngineClient.evaluateFenDetailed(afterFen, depth, multiPv, skillLevel)

        val evalAfter = posAfter.lines.firstOrNull()?.let { line ->
            when {
                line.mate != null -> if (line.mate > 0) 30f else -30f
                line.cp != null -> line.cp.toFloat() / 100f
                else -> 0f
            }
        } ?: 0f

        val bestMove = posBefore.bestMove ?: posBefore.lines.firstOrNull()?.pv?.firstOrNull()
        val cls = classifyMove(posBefore, posAfter, bestMove, uciMove)

        MoveRealtimeResult(
            evalAfter = evalAfter,
            moveClass = cls,
            bestMove = bestMove,
            lines = posAfter.lines
        )
    }

    // ===== helpers =====

    private fun classifyMove(
        posBefore: PositionDTO,
        posAfter: PositionDTO,
        bestMove: String?,
        played: String
    ): MoveClass {
        if (bestMove != null && bestMove == played) return MoveClass.BEST

        val beforeTop = posBefore.lines.firstOrNull()
        val afterTop = posAfter.lines.firstOrNull()

        if (afterTop?.mate != null) {
            return if (afterTop.mate > 0) MoveClass.FORCED else MoveClass.BLUNDER
        }
        if (beforeTop?.mate != null) {
            return MoveClass.BLUNDER
        }

        val beforeEval = cpOrMateAsCp(beforeTop)
        val afterEval = cpOrMateAsCp(afterTop)
        val deltaCpPawns = abs(beforeEval - afterEval).toFloat() / 100f

        return when {
            deltaCpPawns == 0f -> MoveClass.BEST
            deltaCpPawns <= 0.20f -> MoveClass.EXCELLENT
            deltaCpPawns <= 0.50f -> MoveClass.OKAY
            deltaCpPawns <= 1.00f -> MoveClass.INACCURACY
            deltaCpPawns <= 2.00f -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
    }

    private fun cpOrMateAsCp(line: LineDTO?): Int {
        line ?: return 0
        return when {
            line.mate != null -> if (line.mate > 0) 3000 else -3000
            line.cp != null -> line.cp
            else -> 0
        }
    }

    private suspend fun notify(
        id: String,
        done: Int,
        total: Int,
        stage: String,
        startedAt: Long,
        onProgress: (ProgressSnapshot) -> Unit
    ) {
        val percent = if (total > 0) done.toDouble() * 100.0 / total else null
        val snap = ProgressSnapshot(
            id = id,
            total = total,
            done = done,
            percent = percent,
            etaMs = null,
            stage = stage,
            startedAt = startedAt,
            updatedAt = System.currentTimeMillis()
        )
        onProgress(snap)
        progressHook(id, percent, stage)
        delay(1)
    }

    private fun toPositionEval(
        fen: String,
        idx: Int,
        src: PositionDTO
    ): PositionEval {
        val top = src.lines.firstOrNull()
        val evaluation = when {
            top?.mate != null -> if (top.mate > 0) 30f else -30f
            top?.cp != null -> top.cp.toFloat() / 100f
            else -> null
        }
        val bestMove = src.bestMove ?: src.lines.firstOrNull()?.pv?.firstOrNull()

        // преобразование LineDTO -> LineEval
        val linesEval: List<LineEval> = src.lines.map { l ->
            LineEval(
                pv = l.pv,
                cp = l.cp,
                mate = l.mate,
                depth = l.depth,
                multiPv = l.multiPv
            )
        }

        return PositionEval(
            fen = fen,
            idx = idx,
            lines = linesEval,
            bestMove = bestMove,
            evaluation = evaluation
        )
    }
}
