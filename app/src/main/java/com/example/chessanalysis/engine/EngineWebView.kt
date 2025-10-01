package com.example.chessanalysis.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Минимальный мост для общения с WASM‑Stockfish в WebView.
 */
class EngineWebView(
    context: Context,
    private val onLine: (String) -> Unit
) {
    companion object {
        private const val TAG = "EngineWebView"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView = WebView(context.applicationContext)
    private var started = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun start() {
        if (started) return
        started = true

        Log.d(TAG, "Starting EngineWebView...")

        with(webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // КРИТИЧНО для WASM
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Log.e(TAG, "WebView error: $errorCode - $description at $failingUrl")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "[JS] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                return true
            }
        }

        // JS -> Android: слушаем строки от движка
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onEngineLine(line: String?) {
                Log.d(TAG, "← Engine: $line")
                line?.let { onLine(it) }
            }
        }, "Android")

        // Загружаем локальную страницу из assets/www
        Log.d(TAG, "Loading file:///android_asset/www/index.html")
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    /** Отправить UCI‑команду в движок */
    fun send(cmd: String) {
        Log.d(TAG, "→ Sending: $cmd")
        // Экранируем кавычки и обратные слэши для JS
        val safe = cmd
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val js = "window.EngineBridge && window.EngineBridge.push(\"$safe\");"
        mainHandler.post {
            webView.evaluateJavascript(js) { result ->
                Log.d(TAG, "JS eval result: $result")
            }
        }
    }

    /** Остановить / зачистить WebView */
    fun stop() {
        mainHandler.post {
            try {
                webView.evaluateJavascript("window.EngineBridge && window.EngineBridge.push('quit');", null)
            } catch (_: Throwable) {}
            try {
                webView.destroy()
            } catch (_: Throwable) {}
        }
        started = false
    }
}