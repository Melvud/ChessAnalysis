package com.example.chessanalysis.local

import com.example.chessanalysis.*
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.abs

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
        onProgress: (EngineClient.ProgressSnapshot) -> Unit
    ): FullReport = withContext(Dispatchers.IO) {
        val parsed = PgnChess.movesWithFens(pgn)
        val total = parsed.size

        val reportPositions = ArrayList<PositionEval>(total + 1)
        val reportMoves = ArrayList<MoveReport>(total)

        val progressId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        // Стартовая позиция
        val startFen = parsed.firstOrNull()?.beforeFen
            ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        notify(progressId, 0, total, "preparing", startedAt, onProgress)

        val pos0 = EngineClient.evaluateFenDetailed(startFen, depth, multiPv, null)
        reportPositions += toPositionEval(fen = startFen, idx = 0, src = pos0)

        notify(progressId, 0, total, "evaluating", startedAt, onProgress)

        // Анализ ходов
        for (i in 0 until total) {
            val beforeFen = parsed[i].beforeFen
            val afterFen = parsed[i].afterFen
            val uciPlayed = parsed[i].uci
            val sanPlayed = parsed[i].san

            val posBefore = EngineClient.evaluateFenDetailed(beforeFen, depth, multiPv, null)
            val posAfter = EngineClient.evaluateFenDetailed(afterFen, depth, multiPv, null)

            val bestMove = posBefore.bestMove ?: posBefore.lines.firstOrNull()?.pv?.firstOrNull()
            val cls = classifyMove(posBefore, posAfter, bestMove, uciPlayed)

            val winBefore = WinPercentage.getPositionWinPercentage(
                posBefore.lines.firstOrNull()?.cp,
                posBefore.lines.firstOrNull()?.mate
            ).toDouble()

            val winAfter = WinPercentage.getPositionWinPercentage(
                posAfter.lines.firstOrNull()?.cp,
                posAfter.lines.firstOrNull()?.mate
            ).toDouble()

            val accuracy = calculateMoveAccuracy(winBefore, winAfter, i)

            reportMoves += MoveReport(
                san = sanPlayed,
                uci = uciPlayed,
                beforeFen = beforeFen,
                afterFen = afterFen,
                winBefore = winBefore,
                winAfter = winAfter,
                accuracy = accuracy,
                classification = cls,
                tags = emptyList()
            )

            reportPositions += toPositionEval(fen = afterFen, idx = i + 1, src = posAfter)

            notify(progressId, i + 1, total, "evaluating", startedAt, onProgress)
        }

        notify(progressId, total, total, "postprocess", startedAt, onProgress)

        val tagsHeader = PgnChess.headerFromPgn(pgn)
        val hdr = header ?: tagsHeader

        // Win% → per-move accuracy
        val winPercents: List<Double> = reportPositions.map { p ->
            val top = p.lines.firstOrNull()
            if (top == null) 50.0 else WinPercentage.getLineWinPercentage(top.cp, top.mate).toDouble()
        }

        val movesAccuracy = Accuracy.perMoveAccFromWin(winPercents)
        val whiteAccList = movesAccuracy.filterIndexed { idx, _ -> idx % 2 == 0 }
        val blackAccList = movesAccuracy.filterIndexed { idx, _ -> idx % 2 == 1 }

        val whiteAcc = if (whiteAccList.isNotEmpty()) whiteAccList.average() else 0.0
        val blackAcc = if (blackAccList.isNotEmpty()) blackAccList.average() else 0.0

        // ACPL
        val positions1 = reportPositions.map { pos ->
            EngineClient.PositionDTO(
                lines = pos.lines.map { line ->
                    EngineClient.LineDTO(
                        pv = line.pv,
                        cp = line.cp,
                        mate = line.mate,
                        depth = line.depth,
                        multiPv = line.multiPv
                    )
                },
                bestMove = pos.bestMove
            )
        }

        val acplByColor = Accuracy.calculateACPL(positions1)

        // Estimated Elo
        val est = EstimateElo.computeEstimatedElo(positions1, hdr.whiteElo, hdr.blackElo)

        notify(progressId, total, total, "done", startedAt, onProgress)

        FullReport(
            header = hdr,
            positions1 = positions1,
            positions = reportPositions,
            moves = reportMoves,
            accuracy = AccuracySummary(
                whiteMovesAcc = AccByColor(whiteAcc, whiteAcc, whiteAcc),
                blackMovesAcc = AccByColor(blackAcc, blackAcc, blackAcc)
            ),
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
    ): EngineClient.MoveRealtimeResult = withContext(Dispatchers.IO) {
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

        EngineClient.MoveRealtimeResult(
            evalAfter = evalAfter,
            moveClass = cls,
            bestMove = bestMove,
            lines = posAfter.lines
        )
    }

    // ===== helpers =====

    private fun classifyMove(
        posBefore: EngineClient.PositionDTO,
        posAfter: EngineClient.PositionDTO,
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

    private fun cpOrMateAsCp(line: EngineClient.LineDTO?): Int {
        line ?: return 0
        return when {
            line.mate != null -> if (line.mate > 0) 3000 else -3000
            line.cp != null -> line.cp
            else -> 0
        }
    }

    private fun calculateMoveAccuracy(winBefore: Double, winAfter: Double, moveIndex: Int): Double {
        val isWhiteMove = moveIndex % 2 == 0
        val winDiff = if (isWhiteMove)
            kotlin.math.max(0.0, winBefore - winAfter)
        else
            kotlin.math.max(0.0, winAfter - winBefore)

        val raw = 103.1668100711649 * kotlin.math.exp(-0.04354415386753951 * winDiff) - 3.166924740191411
        return kotlin.math.min(100.0, kotlin.math.max(0.0, raw + 1.0))
    }

    private suspend fun notify(
        id: String,
        done: Int,
        total: Int,
        stage: String,
        startedAt: Long,
        onProgress: (EngineClient.ProgressSnapshot) -> Unit
    ) {
        val percent = if (total > 0) done.toDouble() * 100.0 / total else null
        val snap = EngineClient.ProgressSnapshot(
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
        src: EngineClient.PositionDTO
    ): PositionEval {
        val linesEval: List<LineEval> = src.lines.map { l ->
            LineEval(
                pv = l.pv,
                cp = l.cp,
                mate = l.mate,
                best = l.pv.firstOrNull()
            )
        }

        return PositionEval(
            fen = fen,
            idx = idx,
            lines = linesEval
        )
    }
}