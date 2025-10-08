package com.github.movesense.engine

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
import java.util.concurrent.atomic.AtomicBoolean

class EngineWebView private constructor(
    context: Context,
    private val onLine: (String) -> Unit
) {
    companion object {
        private const val TAG = "EngineWebView"

        @Volatile
        private var INSTANCE: EngineWebView? = null

        fun getInstance(context: Context, onLine: (String) -> Unit): EngineWebView {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EngineWebView(context.applicationContext, onLine).also {
                    INSTANCE = it
                    Log.d(TAG, "Created new EngineWebView singleton")
                }
            }
        }

        fun updateListener(onLine: (String) -> Unit) {
            INSTANCE?.onLineCallback = onLine
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView = WebView(context.applicationContext)
    private val started = AtomicBoolean(false)
    private val engineInitialized = AtomicBoolean(false)
    private var onLineCallback = onLine

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun start() {
        if (started.getAndSet(true)) {
            Log.d(TAG, "WebView already started")
            return
        }

        Log.d(TAG, "Starting EngineWebView...")

        mainHandler.post {
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
                    Log.d(TAG, "✓ Page loaded: $url")
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
                    if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e(TAG, "[JS ERROR] ${msg.message()}")
                    }
                    return true
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onEngineLine(line: String?) {
                    if (line != null) {
                        onLineCallback(line)
                    }
                }
            }, "Android")

            Log.d(TAG, "Loading file:///android_asset/www/index.html")
            webView.loadUrl("file:///android_asset/www/index.html")
        }
    }

    fun send(cmd: String) {
        if (!started.get()) {
            Log.w(TAG, "Attempted to send before start: $cmd")
            return
        }

        val safe = cmd
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val js = "if(window.EngineBridge){window.EngineBridge.push(\"$safe\");}else{console.error('Bridge not ready');}"

        mainHandler.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending: $cmd", e)
            }
        }
    }

    fun isInitialized(): Boolean = engineInitialized.get()

    fun markInitialized() {
        engineInitialized.set(true)
        Log.d(TAG, "✓ Engine marked as initialized")
    }

    fun stop() {
        Log.d(TAG, "stop() called (no-op)")
    }
}