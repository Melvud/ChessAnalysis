package com.example.chessanalysis.local

import com.example.chessanalysis.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Реализация анализа партий и отдельных ходов локально с помощью
 * встроенного движка Stockfish. Функции этого объекта стремятся
 * повторять серверную логику анализа: они возвращают идентичные по
 * структуре объекты [FullReport], [MoveRealtimeResult] и т.д. Код
 * использует утилиты из [LocalStockfish] для получения оценок и
 * [LocalAnalysisUtils] для конвертации этих оценок в вероятности и
 * классификации ходов. В случае ошибки генерации FEN или анализа
 * бросается исключение IllegalStateException.
 */
object LocalAnalyzer {

    /**
     * Анализирует игру целиком и возвращает отчёт, аналогичный
     * серверному. Поддерживает прогресс для UI. В отличие от
     * [analyzeGameByPgn], здесь вызывается колбэк [onProgress] после
     * обработки каждой позиции и хода. Внутри используются
     * [PgnToFens] и [PgnChess] для разборки PGN.
     */
    suspend fun analyzeGameByPgnWithProgress(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 2,
        header: GameHeader? = null,
        onProgress: (EngineClient.ProgressSnapshot) -> Unit = {}
    ): FullReport = withContext(Dispatchers.IO) {
        // Разбираем PGN в список FEN‑ов, uci и заголовок
        val fenRes = PgnToFens.fromPgn(pgn)
        val fens = fenRes.fens
        val uciMoves = fenRes.uciMoves
        val gameHeader = header ?: fenRes.header ?: GameHeader()
        val totalPositions = fens.size
        // Анализируем каждую позицию через Stockfish, собираем подробные линии
        val positions = ArrayList<EngineClient.PositionDTO>(totalPositions)
        for ((idx, fen) in fens.withIndex()) {
            val pos = LocalStockfish.evaluatePositionDetailed(fen, depth, multiPv)
            positions += pos
            val percent = (idx + 1).toDouble() / totalPositions.toDouble()
            val snap = EngineClient.ProgressSnapshot(
                id = "local",
                total = totalPositions,
                done = idx + 1,
                percent = percent,
                etaMs = null,
                stage = "evaluate positions",
                startedAt = null,
                updatedAt = null
            )
            onProgress(snap)
        }
        // Разбираем SAN/uci/FEN для ходов для отчётов
        val moveItems = PgnChess.movesWithFens(pgn)
        val movesReports = ArrayList<MoveReport>(moveItems.size)
        // Списки cp для ACPL
        val bestCpList = mutableListOf<Int?>()
        val afterCpList = mutableListOf<Int?>()
        moveItems.forEachIndexed { idx, item ->
            // позиция до и после хода
            val beforePos = positions[idx]
            val afterPos = positions[idx + 1]
            // оценка лучшего хода: первая линия cp или mate
            val bestLine = beforePos.lines.firstOrNull()
            val bestEval: Float = when {
                bestLine?.mate != null -> if (bestLine.mate!! > 0) 30f else -30f
                bestLine?.cp != null -> bestLine.cp!!.toFloat() / 100f
                else -> 0f
            }
            val bestCp: Int? = when {
                bestLine?.mate != null -> if (bestLine.mate!! > 0) 10000 else -10000
                bestLine?.cp != null -> bestLine.cp
                else -> null
            }
            // оценка после хода
            val afterLine = afterPos.lines.firstOrNull()
            val afterEval: Float = when {
                afterLine?.mate != null -> if (afterLine.mate!! > 0) 30f else -30f
                afterLine?.cp != null -> afterLine.cp!!.toFloat() / 100f
                else -> 0f
            }
            val afterCp: Int? = when {
                afterLine?.mate != null -> if (afterLine.mate!! > 0) 10000 else -10000
                afterLine?.cp != null -> afterLine.cp
                else -> null
            }
            bestCpList += bestCp
            afterCpList += afterCp
            // вероятности выигрыша
            val winBest = LocalAnalysisUtils.evalToWinProb(bestEval).toFloat()
            val winAfter = LocalAnalysisUtils.evalToWinProb(afterEval).toFloat()
            // разница в шансах
            val diff = winBest - winAfter
            // количество легальных ходов до хода
            val legalCount = PgnChess.legalCount(item.beforeFen)
            val cls = LocalAnalysisUtils.classifyMove(diff, legalCount, idx)
            // точность как 100 – штраф
            val accuracy = (100.0 - LocalAnalysisUtils.penaltyForClass(cls)).coerceIn(0.0, 100.0)
            movesReports += MoveReport(
                san = item.san,
                uci = item.uci,
                beforeFen = item.beforeFen,
                afterFen = item.afterFen,
                winBefore = winBest.toDouble(),
                winAfter = winAfter.toDouble(),
                accuracy = accuracy,
                classification = cls,
                tags = emptyList()
            )
            // обновляем прогресс по ходам
            val percent = (idx + 1).toDouble() / moveItems.size.toDouble()
            val snap = EngineClient.ProgressSnapshot(
                id = "local",
                total = moveItems.size,
                done = idx + 1,
                percent = percent,
                etaMs = null,
                stage = "analyse moves",
                startedAt = null,
                updatedAt = null
            )
            onProgress(snap)
        }
        // Сводная точность
        val accuracySummary = LocalAnalysisUtils.computeAccuracySummary(movesReports)
        // ACPL по разнице в cp
        val acpl = LocalAnalysisUtils.computeAcpl(movesReports, bestCpList, afterCpList)
        // Оценённый рейтинг
        val whiteElo = LocalAnalysisUtils.estimateElo(acpl.white.toDouble())
        val blackElo = LocalAnalysisUtils.estimateElo(acpl.black.toDouble())
        val estElo = EstimatedElo(whiteEst = whiteElo, blackEst = blackElo)
        return@withContext FullReport(
            header = gameHeader,
            positions = positions.mapIndexed { i, pos ->
                // Приводим список линий к модели, взяв первые multiPv вариантов
                PositionEval(
                    fen = fens[i],
                    idx = i,
                    lines = pos.lines.map { line ->
                        LineEval(pv = line.pv, cp = line.cp, mate = line.mate, best = if (line.pv.isNotEmpty()) line.pv.first() else null)
                    }
                )
            },
            moves = movesReports,
            accuracy = accuracySummary,
            acpl = acpl,
            estimatedElo = estElo,
            analysisLog = emptyList()
        )
    }

    /**
     * Анализирует игру целиком без вызова колбэка прогресса. Просто
     * перенаправляет в [analyzeGameByPgnWithProgress] с пустым
     * обработчиком прогресса.
     */
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

    /**
     * Анализирует один ход, возвращает расширенный результат для UI,
     * включая оценку позиции после хода, класс хода и линии поиска.
     */
    suspend fun analyzeMoveRealtimeDetailed(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 18,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): EngineClient.MoveRealtimeResult = withContext(Dispatchers.IO) {
        // Получаем анализ позиции до и после хода
        val beforePos = LocalStockfish.evaluatePositionDetailed(beforeFen, depth, multiPv, skillLevel)
        val afterPos = LocalStockfish.evaluatePositionDetailed(afterFen, depth, multiPv, skillLevel)
        // Лучшая линия до хода
        val bestLine = beforePos.lines.firstOrNull()
        val bestEval = when {
            bestLine?.mate != null -> if (bestLine.mate!! > 0) 30f else -30f
            bestLine?.cp != null -> bestLine.cp!!.toFloat() / 100f
            else -> 0f
        }
        val winBest = LocalAnalysisUtils.evalToWinProb(bestEval).toFloat()
        // Оценка после хода
        val afterLine = afterPos.lines.firstOrNull()
        val afterEval = when {
            afterLine?.mate != null -> if (afterLine.mate!! > 0) 30f else -30f
            afterLine?.cp != null -> afterLine.cp!!.toFloat() / 100f
            else -> 0f
        }
        val winAfter = LocalAnalysisUtils.evalToWinProb(afterEval).toFloat()
        // классификация и точность
        val diff = winBest - winAfter
        val legalCount = PgnChess.legalCount(beforeFen)
        val cls = LocalAnalysisUtils.classifyMove(diff, legalCount, 0)
        val bestMove = bestLine?.pv?.firstOrNull()
        return@withContext EngineClient.MoveRealtimeResult(
            evalAfter = afterEval,
            moveClass = cls,
            bestMove = bestMove,
            lines = afterPos.lines.take(multiPv)
        )
    }

    /**
     * Упрощённый анализ одного хода: возвращает тройку (оценка после
     * хода, класс хода, лучший ход), аналогично серверному API.
     */
    suspend fun analyzeMoveRealtime(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): Triple<Float, MoveClass, String?> = withContext(Dispatchers.IO) {
        val res = analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
        return@withContext Triple(res.evalAfter, res.moveClass, res.bestMove)
    }

    /**
     * Анализ двух FEN и UCI‑хода. Идентичен [analyzeMoveRealtime], но
     * совместим по сигнатуре с server API в EngineClient.
     */
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