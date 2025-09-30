package com.example.chessanalysis.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Минимальный мост для общения с WASM-Stockfish в WebView.
 * Без COOP/COEP, однопоточная wasm-сборка.
 */
class EngineWebView(
    context: Context,
    private val onLine: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView = WebView(context.applicationContext)

    private var started = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun start() {
        if (started) return
        started = true

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = object : WebChromeClient() {}

        // JS -> Android
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onEngineLine(line: String?) {
                if (line != null) onLine(line)
            }
        }, "Android")

        // Грузим локальную страницу из assets
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    /** Отправить UCI-команду в движок */
    fun send(cmd: String) {
        // Жёстко экранируем кавычки и обратные слэши для JS
        val safe = cmd
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val js = "window.EngineBridge && window.EngineBridge.push(\"$safe\");"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    /** Остановить / зачистить WebView */
    fun stop() {
        mainHandler.post {
            try {
                webView.evaluateJavascript("window.EngineBridge && window.EngineBridge.push('quit');", null)
            } catch (_: Throwable) {}
            try { webView.destroy() } catch (_: Throwable) {}
        }
        started = false
    }
}
