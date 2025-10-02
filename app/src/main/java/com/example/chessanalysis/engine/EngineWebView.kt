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
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                // Логируем только ошибки
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "[JS ERROR] ${msg.message()}")
                }
                return true
            }
        }

        // JS -> Android bridge
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onEngineLine(line: String?) {
                if (line != null) {
                    Log.d(TAG, "← $line")
                    onLine(line)
                }
            }
        }, "Android")

        Log.d(TAG, "Loading file:///android_asset/www/index.html")
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    fun send(cmd: String) {
        val safe = cmd
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val js = "if(window.EngineBridge){window.EngineBridge.push(\"$safe\");}else{console.error('Bridge not ready');}"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                webView.evaluateJavascript("window.EngineBridge && window.EngineBridge.engine && window.EngineBridge.push('quit');", null)
            } catch (_: Throwable) {}
            try {
                webView.destroy()
            } catch (_: Throwable) {}
        }
        started = false
    }
}