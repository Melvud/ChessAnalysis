package com.example.chessanalysis.local

import com.example.chessanalysis.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalAnalyzer {

    suspend fun analyzeGameByPgnWithProgress(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 2,
        header: GameHeader? = null,
        onProgress: (EngineClient.ProgressSnapshot) -> Unit = {}
    ): FullReport = withContext(Dispatchers.IO) {
        val fenRes = PgnToFens.fromPgn(pgn)
        val fens = fenRes.fens
        val gameHeader = header ?: fenRes.header ?: GameHeader()

        val positionsDto = ArrayList<EngineClient.PositionDTO>(fens.size)
        for ((idx, fen) in fens.withIndex()) {
            val pos = LocalStockfish.evaluatePositionDetailed(
                fen = fen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = null, // Fixed: removed TODO()
                context = null     // Fixed: removed TODO()
            )
            positionsDto.add(pos)

            onProgress(
                EngineClient.ProgressSnapshot(
                    id = "local",
                    total = fens.size,
                    done = idx + 1,
                    percent = (idx + 1).toDouble() / fens.size.toDouble() * 100.0,
                    etaMs = null,
                    stage = "Анализ позиций...",
                    startedAt = null,
                    updatedAt = null
                )
            )
        }

        val moveItems = PgnChess.movesWithFens(pgn)
        val movesReports = ArrayList<MoveReport>(moveItems.size)
        val bestCpList = mutableListOf<Int?>()
        val afterCpList = mutableListOf<Int?>()

        moveItems.forEachIndexed { idx, item ->
            val beforePos = positionsDto[idx]
            val afterPos = positionsDto[idx + 1]

            val bestLine = beforePos.lines.firstOrNull()
            val afterLine = afterPos.lines.firstOrNull()

            val bestEval: Float = when {
                bestLine?.mate != null -> if (bestLine.mate!! > 0) 30f else -30f
                bestLine?.cp != null -> bestLine.cp!! / 100f
                else -> 0f
            }
            val afterEval: Float = when {
                afterLine?.mate != null -> if (afterLine.mate!! > 0) 30f else -30f
                afterLine?.cp != null -> afterLine.cp!! / 100f
                else -> 0f
            }

            val bestCp: Int? = bestLine?.cp ?: (bestLine?.mate?.let { if (it > 0) 10000 else -10000 })
            val afterCp: Int? = afterLine?.cp ?: (afterLine?.mate?.let { if (it > 0) 10000 else -10000 })

            bestCpList.add(bestCp)
            afterCpList.add(afterCp)

            val winBest = LocalAnalysisUtils.evalToWinProb(bestEval).toFloat()
            val winAfter = LocalAnalysisUtils.evalToWinProb(afterEval).toFloat()
            val diff = winBest - winAfter

            val legalCount = PgnChess.legalCount(item.beforeFen)
            val cls = LocalAnalysisUtils.classifyMove(diff, legalCount, idx)
            val accuracy = (100.0 - LocalAnalysisUtils.penaltyForClass(cls)).coerceIn(0.0, 100.0)

            movesReports.add(MoveReport(
                san = item.san,
                uci = item.uci,
                beforeFen = item.beforeFen,
                afterFen = item.afterFen,
                winBefore = winBest.toDouble(),
                winAfter = winAfter.toDouble(),
                accuracy = accuracy,
                classification = cls,
                tags = emptyList()
            ))
        }

        val accuracySummary = LocalAnalysisUtils.computeAccuracySummary(movesReports)
        val acpl = LocalAnalysisUtils.computeAcpl(movesReports, bestCpList, afterCpList)
        val whiteElo = LocalAnalysisUtils.estimateElo(acpl.white.toDouble())
        val blackElo = LocalAnalysisUtils.estimateElo(acpl.black.toDouble())
        val estElo = EstimatedElo(whiteEst = whiteElo, blackEst = blackElo)

        val positionsEval = positionsDto.mapIndexed { i, pos ->
            PositionEval(
                fen = fens[i],
                idx = i,
                lines = pos.lines.take(multiPv).map { line ->
                    LineEval(
                        pv = line.pv,
                        cp = line.cp,
                        mate = line.mate,
                        best = line.pv.firstOrNull()
                    )
                }
            )
        }

        FullReport(
            header = gameHeader,
            positions = positionsEval,
            moves = movesReports,
            accuracy = accuracySummary,
            acpl = acpl,
            estimatedElo = estElo,
            analysisLog = emptyList()
        )
    }

    suspend fun analyzeGameByPgn(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 2,
        header: GameHeader? = null
    ): FullReport = analyzeGameByPgnWithProgress(
        pgn = pgn,
        depth = depth,
        multiPv = multiPv,
        header = header,
        onProgress = {}
    )

    suspend fun analyzeMoveRealtimeDetailed(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 18,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): EngineClient.MoveRealtimeResult = withContext(Dispatchers.IO) {
        val beforePos = LocalStockfish.evaluatePositionDetailed(
            fen = beforeFen,
            depth = depth,
            multiPv = multiPv,
            skillLevel = skillLevel,
            context = null // Fixed: removed TODO()
        )
        val afterPos = LocalStockfish.evaluatePositionDetailed(
            fen = afterFen,
            depth = depth,
            multiPv = multiPv,
            skillLevel = skillLevel,
            context = null // Fixed: removed TODO()
        )

        val bestLine = beforePos.lines.firstOrNull()
        val afterLine = afterPos.lines.firstOrNull()

        val bestEval = when {
            bestLine?.mate != null -> if (bestLine.mate!! > 0) 30f else -30f
            bestLine?.cp != null -> bestLine.cp!! / 100f
            else -> 0f
        }
        val afterEval = when {
            afterLine?.mate != null -> if (afterLine.mate!! > 0) 30f else -30f
            afterLine?.cp != null -> afterLine.cp!! / 100f
            else -> 0f
        }

        val winBest = LocalAnalysisUtils.evalToWinProb(bestEval).toFloat()
        val winAfter = LocalAnalysisUtils.evalToWinProb(afterEval).toFloat()
        val diff = winBest - winAfter

        val legalCount = PgnChess.legalCount(beforeFen)
        val cls = LocalAnalysisUtils.classifyMove(diff, legalCount, 0)

        EngineClient.MoveRealtimeResult(
            evalAfter = afterEval,
            moveClass = cls,
            bestMove = bestLine?.pv?.firstOrNull(),
            lines = afterPos.lines.take(multiPv)
        )
    }

    suspend fun analyzeMoveRealtime(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): Triple<Float, MoveClass, String?> = withContext(Dispatchers.IO) {
        val res = analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
        Triple(res.evalAfter, res.moveClass, res.bestMove)
    }

    suspend fun analyzeMoveByFens(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3
    ): Triple<Float, MoveClass, String?> = analyzeMoveRealtime(
        beforeFen = beforeFen,
        afterFen = afterFen,
        uciMove = uciMove,
        depth = depth,
        multiPv = multiPv,
        skillLevel = null
    )
}