package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.chessanalysis.*

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun GameReportScreenPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            GameReportScreen(
                report = createSampleReport(),
                onBack = {},
                onNextKeyMoment = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GameReportScreenDarkPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            GameReportScreen(
                report = createSampleReport(),
                onBack = {},
                onNextKeyMoment = {}
            )
        }
    }
}

private fun createSampleReport(): FullReport {
    return FullReport(
        header = GameHeader(
            white = "Игрок 1",
            black = "Игрок 2",
            result = "1-0",
            date = "2024-01-01",
            eco = "C20",
            opening = "King's Pawn Game"
        ),
        positions = listOf(
            PositionEval(
                fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                idx = 0,
                lines = listOf(LineEval(cp = 0))
            ),
            PositionEval(
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                idx = 1,
                lines = listOf(LineEval(cp = 21))
            ),
            PositionEval(
                fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                idx = 2,
                lines = listOf(LineEval(cp = 0))
            ),
            PositionEval(
                fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
                idx = 3,
                lines = listOf(LineEval(cp = 15))
            )
        ),
        moves = listOf(
            MoveReport(
                san = "e4",
                uci = "e2e4",
                beforeFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                afterFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                winBefore = 0.5,
                winAfter = 0.52,
                accuracy = 0.95,
                classification = MoveClass.OPENING,
                tags = emptyList()
            ),
            MoveReport(
                san = "e5",
                uci = "e7e5",
                beforeFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                afterFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                winBefore = 0.52,
                winAfter = 0.5,
                accuracy = 0.98,
                classification = MoveClass.OPENING,
                tags = emptyList()
            ),
            MoveReport(
                san = "Nf3",
                uci = "g1f3",
                beforeFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                afterFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
                winBefore = 0.5,
                winAfter = 0.52,
                accuracy = 0.92,
                classification = MoveClass.BEST,
                tags = emptyList()
            )
        ),
        accuracy = AccuracySummary(
            whiteMovesAcc = AccByColor(0.93, 0.91, 0.92),
            blackMovesAcc = AccByColor(0.89, 0.87, 0.88)
        ),
        acpl = Acpl(white = 15, black = 23),
        estimatedElo = EstimatedElo(whiteEst = 1800, blackEst = 1750),
        analysisLog = emptyList()
    )
}
