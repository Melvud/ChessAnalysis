package com.example.chessanalysis.local

import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.AccByColor
import com.example.chessanalysis.Acpl
import com.example.chessanalysis.AccuracySummary
import kotlin.math.abs
import kotlin.math.exp

/**
 * Набор утилит для локального анализа шахматных партий. Эти функции
 * реализуют преобразования оценок движка в вероятности победы,
 * классификацию ходов по качеству, а также расчёт точности, ACPL
 * и примерную оценку рейтинга по результатам партии. Алгоритмы
 * основаны на серверной реализации (analysis.ts) и подобраны так,
 * чтобы возвращать максимально похожие на неё результаты, хотя
 * точные пороги могут отличаться. При необходимости можно
 * скорректировать коэффициенты и пороги.
 */
object LocalAnalysisUtils {

    /**
     * Переводит числовую оценку позиции (в пешках) в вероятность
     * выигрыша для стороны, делающей ход. Используем логистическую
     * функцию, подобранную эмпирически. Большие оценки (10+ пешек)
     * приводят к вероятности, близкой к 1.0, отрицательные – к 0.0.
     */
    fun evalToWinProb(eval: Float): Double {
        // коэффициент кривизны логистической функции: чем больше, тем
        // быстрее вероятность стремится к 0/1. На сервере используется
        // более сложная модель, но для локального анализа этого
        // приближения достаточно.
        val k = 1.1f
        val exponent = (-k * eval).toDouble()
        return 1.0 / (1.0 + exp(exponent))
    }

    /**
     * Классифицирует ход исходя из разницы между вероятностью
     * выигрыша при лучшем ходе и после сделанного хода. Чем больше
     * дельта, тем хуже ход. Также учитываем количество легальных ходов:
     * если ход единственный, считаем его принудительным (FORCED).
     * Для первых ходов партии, если потеря ничтожна, классифицируем
     * как OPENING.
     *
     * @param delta Разница в вероятности выигрыша (winBest - winAfter)
     * @param legalCount Число легальных ходов в позиции до хода
     * @param moveIndex Порядковый номер хода (0 для первого хода белых)
     */
    fun classifyMove(delta: Float, legalCount: Int, moveIndex: Int): MoveClass {
        // если только один ход, классифицируем как принудительный
        if (legalCount <= 1) return MoveClass.FORCED
        val absDelta = abs(delta)
        // первые 8 полуходов считаем открытием, если дельта мала
        if (moveIndex < 8 && absDelta < 0.05f) return MoveClass.OPENING
        return when {
            absDelta < 0.02f -> MoveClass.PERFECT
            absDelta < 0.05f -> MoveClass.SPLENDID
            absDelta < 0.10f -> MoveClass.EXCELLENT
            absDelta < 0.20f -> MoveClass.OKAY
            absDelta < 0.40f -> MoveClass.INACCURACY
            absDelta < 0.80f -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
    }

    /**
     * Штрафные очки за определённый класс хода. Используется для
     * расчёта точности (accuracy). Чем хуже ход, тем больше штраф.
     *
     * @return Значение, которое нужно вычесть из 100, чтобы получить
     *         итоговую точность хода (от 0 до 100).
     */
    fun penaltyForClass(cls: MoveClass): Double {
        return when (cls) {
            MoveClass.OPENING, MoveClass.FORCED, MoveClass.PERFECT, MoveClass.BEST -> 0.0
            MoveClass.SPLENDID -> 0.5
            MoveClass.EXCELLENT -> 1.0
            MoveClass.OKAY -> 3.0
            MoveClass.INACCURACY -> 6.0
            MoveClass.MISTAKE -> 12.0
            MoveClass.BLUNDER -> 25.0
        }
    }

    /**
     * Оценка рейтинга по среднему ACPL. Это грубая эвристика,
     * использующая типичные диапазоны: чем меньше ACPL, тем выше
     * ожидаемый рейтинг. Функция симметрична для белых и чёрных.
     */
    fun estimateElo(acpl: Double): Int {
        return when {
            acpl < 10 -> 2800
            acpl < 20 -> 2600
            acpl < 35 -> 2400
            acpl < 50 -> 2200
            acpl < 75 -> 2000
            acpl < 100 -> 1800
            acpl < 150 -> 1600
            acpl < 200 -> 1400
            acpl < 300 -> 1200
            else -> 1000
        }
    }

    /**
     * Расчитывает сводку точности для белых и чёрных. Для каждого
     * игрока считаются три вида среднего: итеративное (просто среднее
     * арифметическое), гармоническое и взвешенное (квадратичное). Эти
     * показатели используются в отчёте для детального анализа.
     */
    fun computeAccuracySummary(reports: List<com.example.chessanalysis.MoveReport>): AccuracySummary {
        var whiteItera = 0.0
        var whiteHarm = 0.0
        var whiteWeight = 0.0
        var blackItera = 0.0
        var blackHarm = 0.0
        var blackWeight = 0.0
        var whiteCount = 0
        var blackCount = 0

        reports.forEachIndexed { idx, report ->
            val acc = report.accuracy
            if (idx % 2 == 0) {
                whiteItera += acc
                whiteHarm += 1.0 / (acc + 1e-6)
                whiteWeight += acc * acc
                whiteCount++
            } else {
                blackItera += acc
                blackHarm += 1.0 / (acc + 1e-6)
                blackWeight += acc * acc
                blackCount++
            }
        }

        fun toAcc(itera: Double, harm: Double, weight: Double, count: Int): AccByColor {
            val it = if (count > 0) itera / count else 0.0
            val ha = if (count > 0) count / harm else 0.0
            val we = if (count > 0) weight / count else 0.0
            return AccByColor(itera = it, harmonic = ha, weighted = we)
        }

        return AccuracySummary(
            whiteMovesAcc = toAcc(whiteItera, whiteHarm, whiteWeight, whiteCount),
            blackMovesAcc = toAcc(blackItera, blackHarm, blackWeight, blackCount)
        )
    }

    /**
     * Вычисляет ACPL (Average CentiPawn Loss) для белых и чёрных. Мы
     * рассматриваем потери в pawns (разница в оценке, умноженная на 100),
     * затем берём среднее по количеству ходов каждого цвета. Матовые
     * значения трактуем как большой проигрыш (1000cp).
     */
    fun computeAcpl(reports: List<com.example.chessanalysis.MoveReport>, bestCpList: List<Int?>, afterCpList: List<Int?>): Acpl {
        var whiteLoss = 0.0
        var blackLoss = 0.0
        var whiteCount = 0
        var blackCount = 0
        reports.forEachIndexed { idx, _ ->
            val best = bestCpList.getOrNull(idx)
            val after = afterCpList.getOrNull(idx)
            val diffCp = if (best == null || after == null) {
                1000.0 // если оценка недоступна, считаем большой потерей
            } else {
                abs(best - after).toDouble()
            }
            if (idx % 2 == 0) {
                whiteLoss += diffCp
                whiteCount++
            } else {
                blackLoss += diffCp
                blackCount++
            }
        }
        return Acpl(
            white = if (whiteCount > 0) (whiteLoss / whiteCount).toInt() else 0,
            black = if (blackCount > 0) (blackLoss / blackCount).toInt() else 0
        )
    }
}