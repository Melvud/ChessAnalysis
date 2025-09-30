package com.example.chessanalysis.local

import com.example.chessanalysis.EngineClient
import com.example.chessanalysis.EngineClient.LineDTO
import com.example.chessanalysis.EngineClient.PositionDTO
import com.example.chessanalysis.EngineClient.ProgressSnapshot
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.MoveEval
import com.example.chessanalysis.PositionEval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID

/**
 * Локальная сборка отчёта, совместимая со структурой FullReport, как на сервере.
 * Никаких extension-свойств без accessors — всё обычные функции/классы.
 */
class LocalGameAnalyzer(
    private val sendHook: (String) -> Unit = {},                 // на будущее (если надо проксировать команды в движок)
    private val progressHook: (id: String, percent: Double?, stage: String?) -> Unit = { _, _, _ -> }
) {

    // Небольшой контейнер для «прогресса»
    data class LocalProgress(
        val id: String,
        val total: Int,
        val done: Int,
        val percent: Double?,
        val etaMs: Long?,
        val stage: String?,
        val startedAt: Long?,
        val updatedAt: Long?
    )

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
        onProgress: (LocalProgress) -> Unit
    ): FullReport = withContext(Dispatchers.IO) {
        val parsed = parsePgnToUciAndFens(pgn)

        val total = parsed.movesUci.size
        val reportPositions = ArrayList<PositionEval>(total + 1)
        val reportMoves = ArrayList<MoveEval>(total)

        val progressId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        // Начальная позиция — до первого хода
        run {
            val startFen = parsed.fensBefore.firstOrNull() ?: "startpos"
            val pos = EngineClient.evaluateFenDetailed(
                fen = startFen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = null
            )
            reportPositions += pos.toPositionEval()
            notify(progressId, 0, total, "init", startedAt, onProgress)
        }

        // Для каждого хода: считаем позицию «после хода», классифицируем, записываем PV/лучший ход
        for (i in 0 until total) {
            val afterFen = parsed.fensAfter[i]
            val uciPlayed = parsed.movesUci[i]

            val posAfter: PositionDTO = EngineClient.evaluateFenDetailed(
                fen = afterFen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = null
            )

            val bestLine: LineDTO? = posAfter.lines.firstOrNull()
            val bestEval: Float? = when {
                bestLine?.mate != null -> if (bestLine.mate!! > 0) 30f else -30f
                bestLine?.cp != null -> bestLine.cp!!.toFloat() / 100f
                else -> null
            }
            val playedEval: Float? = bestEval // т.к. posAfter — это уже позиция ПОСЛЕ сыгранного хода
            val bestIsMate = bestLine?.mate
            val playedIsMate = bestLine?.mate // тот же mate (позиция после хода)

            val cls = MoveClassifier.classify(
                MoveClassifier.EvalPair(
                    best = bestEval,
                    played = playedEval,
                    bestIsMate = bestIsMate,
                    playedIsMate = playedIsMate
                )
            )

            // MoveEval: как на сервере — classification + bestMove (из первой линии PV)
            val bestMoveFromPv = bestLine?.pv?.firstOrNull()
            reportMoves += MoveEval(
                played = uciPlayed,
                best = bestMoveFromPv,
                classification = cls
            )

            reportPositions += posAfter.toPositionEval()

            notify(progressId, i + 1, total, "analyzing", startedAt, onProgress)
        }

        // Соберём хедеры (если переданы пользователем — используем их, иначе из PGN)
        val hdr = header ?: GameHeader(
            event = parsed.header["Event"],
            site = parsed.header["Site"],
            date = parsed.header["Date"],
            round = parsed.header["Round"],
            white = parsed.header["White"],
            black = parsed.header["Black"],
            result = parsed.header["Result"],
            eco = parsed.header["ECO"]
        )

        // Итоги/«accuracy» (ACPL и т.п.) — простая заглушка 1-в-1 по полям;
        // Если у вас уже есть классы AccuracySummary/Acpl/EstimatedElo — оставляем их формирование нулевым/пустым.
        val accuracy = com.example.chessanalysis.AccuracySummary(emptyMap())
        val acpl = com.example.chessanalysis.Acpl(emptyMap())
        val estimatedElo = com.example.chessanalysis.EstimatedElo(emptyMap())

        // Финальный отчёт
        FullReport(
            header = hdr,
            moves = reportMoves,
            positions = reportPositions,
            accuracy = accuracy,
            acpl = acpl,
            estimatedElo = estimatedElo
        )
    }

    private suspend fun notify(
        id: String,
        done: Int,
        total: Int,
        stage: String,
        startedAt: Long,
        onProgress: (LocalProgress) -> Unit
    ) {
        val percent = if (total > 0) done.toDouble() * 100.0 / total else null
        val snap = LocalProgress(
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
        // Чуть разгрузим UI
        delay(1)
    }

    // --- маппинги в ваши DTO отчёта ---

    private fun PositionDTO.toPositionEval(): PositionEval {
        // Берём top-line
        val top = lines.firstOrNull()
        val evalAfter: Float? = when {
            top?.mate != null -> if (top.mate!! > 0) 30f else -30f
            top?.cp != null -> top.cp!!.toFloat() / 100f
            else -> null
        }
        return PositionEval(
            best = top?.pv?.firstOrNull(),
            lines = lines,
            eval = evalAfter
        )
    }
}
