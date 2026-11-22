package top.aaa000111.maid.search

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import top.aaa000111.maid.runtime.TimedWait

internal class RenderedWebPageLoader(private val context: Context) {
    data class Page(val url: String, val html: String)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(url: String, timeoutMillis: Long): Page = withTimeoutOrNull(timeoutMillis) {
        renderLock.withLock {
            require(withContext(Dispatchers.IO) { isPublicWebUrl(url) }) {
                "仅支持公开的 HTTPS 网页"
            }
            withContext(Dispatchers.Main) {
                val view = WebView(context)
                val loaded = CompletableDeferred<Unit>()
                var pageError: String? = null
                fun fail(message: String) {
                    pageError = message
                    loaded.complete(Unit)
                }
                try {
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            if (isPublicWebUrl(request.url.toString())) null
                            else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val blocked = !isHttpsWebUrl(request.url.toString())
                            if (blocked && request.isForMainFrame) fail("网页跳转到了不支持的地址")
                            return blocked
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loaded.complete(Unit)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) fail("网页加载失败（WebView ${error.errorCode}）")
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            if (request.isForMainFrame) fail("网页加载失败（HTTP ${errorResponse.statusCode}）")
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            fail("WebView 渲染进程已退出")
                            return true
                        }
                    }
                    view.loadUrl(url)
                    loaded.await()
                    // 给页面脚本一小段时间填充 DOM，再采样。整个过程受外层超时约束。
                    TimedWait.await(750.milliseconds)
                    while (true) {
                        pageError?.let { error(it) }
                        val finalUrl = view.url.orEmpty()
                        require(withContext(Dispatchers.IO) { isPublicWebUrl(finalUrl) }) {
                            "网页最终地址不是公开的 HTTPS 地址"
                        }
                        pageError?.let { error(it) }
                        val html = readHtml(view)
                        pageError?.let { error(it) }
                        if (html.isNotEmpty() && view.url == finalUrl) {
                            return@withContext Page(finalUrl, html)
                        }
                        TimedWait.await(250.milliseconds)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("网页渲染未完成")
                } finally {
                    view.destroy()
                }
            }
        }
    } ?: error("网页渲染超时")

    private suspend fun readHtml(view: WebView): String {
        val result = suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(
                """
                (() => {
                    const root = document.querySelector('article, main, [role="main"]') || document.body;
                    if (!root || !root.innerText.trim()) return "";
                    const html = document.documentElement.outerHTML;
                    return html.length <= 1000000 ? html : null;
                })()
                """.trimIndent(),
            ) { value -> continuation.resume(value) }
        }
        check(result != null && result != "null") { "网页 DOM 过大或无法读取" }
        return Json.parseToJsonElement(result).jsonPrimitive.content
    }

    companion object {
        private val renderLock = Mutex()

        fun isHttpsWebUrl(url: String): Boolean =
            SearchEngine.isValidHttpUrl(url) &&
                url.startsWith("https://", ignoreCase = true) &&
                !WebPageFetcher.isDisallowedLocalTarget(url)

        fun isPublicWebUrl(url: String): Boolean {
            if (!isHttpsWebUrl(url)) return false
            return runCatching {
                InetAddress.getAllByName(URI(url).host).all { address ->
                    !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                        !address.isMulticastAddress && (address.address.size != 16 ||
                            (address.address[0].toInt() and 0xfe) != 0xfc)
                }
            }.getOrDefault(false)
        }
    }
}
