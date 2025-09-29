package com.example.chessanalysis.local

object SfLauncher {
    init {
        System.loadLibrary("sflauncher")
    }
    external fun run(pathToLibStockfish: String): Int
}
