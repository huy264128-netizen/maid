package top.aaa000111.maid.browser

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.aaa000111.maid.display.VirtualDisplayManager
import top.aaa000111.maid.runtime.TimedWait
import top.aaa000111.maid.search.RenderedWebPageLoader
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/** 一个应用内离屏浏览器。所有 View 和标签页状态由主线程持有。 */
internal class HeadlessBrowser private constructor(private val context: Context) {
    data class Viewport(val widthPx: Int, val heightPx: Int, val densityDpi: Int)
    data class TabState(
        val id: Int,
        val url: String?,
        val title: String?,
        val active: Boolean,
        val loading: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val revision: Long,
        val error: String?,
    )
    data class Event(val type: String, val tab: TabState)
    data class Frame(val id: Long, val viewport: Viewport, val bitmap: Bitmap)

    private class Tab(val id: Int, val view: WebView) {
        var loading = false
        var error: String? = null
        var url: String? = null
        var revision = 0L
    }

    private data class FrameTarget(
        val id: Long,
        val tabId: Int,
        val revision: Long,
        val left: Int,
        val top: Int,
    )

    private val tabs = linkedMapOf<Int, Tab>()
    private val updates = MutableSharedFlow<Event>(replay = 16, extraBufferCapacity = 16)
    val events = updates.asSharedFlow()
    private val captureLock = Mutex()
    private var nextTabId = 0
    private var activeId: Int? = null
    private var nextFrameId = 0L
    private var frameTarget: FrameTarget? = null
    private var frameRequest: CompletableDeferred<Bitmap>? = null
    private var closed = false

    private val displays = context.getSystemService(DisplayManager::class.java)
        ?: error("DisplayManager unavailable")
    private lateinit var viewport: Viewport
    private var reader: ImageReader? = null
    private val virtualDisplayManager = VirtualDisplayManager(context)
    private var window: Presentation? = null
    private lateinit var container: FrameLayout

    @Suppress("DEPRECATION")
    private fun initialize() {
        val source = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: error("Default display unavailable")
        val metrics = DisplayMetrics().also(source::getRealMetrics)
        viewport = Viewport(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

        val images = ImageReader.newInstance(viewport.widthPx, viewport.heightPx, PixelFormat.RGBA_8888, 2)
        reader = images
        images.setOnImageAvailableListener({ sourceReader ->
            sourceReader.acquireLatestImage()?.use { image ->
                val request = frameRequest
                if (request != null && request.isActive) {
                    runCatching { image.toBitmap() }.fold(
                        { bitmap -> if (!request.complete(bitmap)) bitmap.recycle() },
                        { error -> request.completeExceptionally(error) },
                    )
                }
            }
        }, Handler(Looper.getMainLooper()))

        val display = virtualDisplayManager.create(
            "Maid browser",
            viewport.widthPx,
            viewport.heightPx,
            viewport.densityDpi,
            images.surface,
        )
        val presentation = Presentation(context, display)
        window = presentation
        container = FrameLayout(presentation.context)
        presentation.setContentView(container)
        presentation.show()
        presentation.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            insetsController?.hide(WindowInsets.Type.systemBars())
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun openTab(url: String? = null): TabState {
        val target = url?.let { validateUrl(it) }
        return onMain {
            check(tabs.size < 4) { "At most 4 browser tabs are allowed" }
            val view = WebView(container.context)
            val tab = Tab(++nextTabId, view)
            tabs[tab.id] = tab
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            view.webViewClient = client(tab)
            activate(tab)
            navigate(tab, target ?: "about:blank")
            state(tab)
        }
    }

    suspend fun selectTab(id: Int): TabState = onMain {
        val tab = tab(id)
        activate(tab)
        state(tab)
    }

    suspend fun closeTab(id: Int): List<TabState> = onMain {
        val tab = tab(id)
        disposeTab(tab)
        if (activeId == null) tabs.values.lastOrNull()?.let(::activate)
        tabs.values.map(::state)
    }

    suspend fun tabStates(): List<TabState> = onMain { tabs.values.map(::state) }

    suspend fun goTo(id: Int, url: String): TabState {
        val target = validateUrl(url)
        return onMain { tab(id).also { navigate(it, target) }.let(::state) }
    }

    suspend fun history(id: Int, action: String): TabState = onMain {
        val tab = tab(id)
        when (action) {
            "back" -> { check(tab.view.canGoBack()) { "No back history" }; begin(tab); tab.view.goBack() }
            "forward" -> { check(tab.view.canGoForward()) { "No forward history" }; begin(tab); tab.view.goForward() }
            "reload" -> { check(tab.url != null) { "No page to reload" }; begin(tab); tab.view.reload() }
            else -> error("Expected back, forward or reload")
        }
        state(tab)
    }

    suspend fun capture(id: Int): Frame = captureLock.withLock {
        withTimeoutOrNull(5_000) {
            onMain {
                val tab = activeTab(id)
                val revision = tab.revision
                val visual = CompletableDeferred<Unit>()
                tab.view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { visual.complete(Unit) }
                })
                visual.await()
                check(activeId == id && tab.revision == revision) { "Page changed during capture" }
                reader?.acquireLatestImage()?.close()
                val request = CompletableDeferred<Bitmap>()
                frameRequest = request
                try {
                    tab.view.invalidate()
                    container.invalidate()
                    val bitmap = request.await()
                    if (activeId != id || tab.revision != revision) {
                        bitmap.recycle()
                        error("Page changed during capture")
                    }
                    val frameId = ++nextFrameId
                    val location = IntArray(2).also(tab.view::getLocationOnScreen)
                    frameTarget = FrameTarget(frameId, id, revision, location[0], location[1])
                    Frame(frameId, viewport, bitmap)
                } finally {
                    frameRequest = null
                    if (request.isActive) request.cancel()
                }
            }
        } ?: error("Timed out waiting for a browser frame")
    }

    suspend fun gesture(id: Int, frameId: Long, points: List<Pair<Float, Float>>, millis: Long): TabState = onMain {
        val tab = activeTab(id)
        val frame = frameTarget
        val location = IntArray(2).also(tab.view::getLocationOnScreen)
        check(frame != null && frame.id == frameId && frame.tabId == id &&
            frame.revision == tab.revision && frame.left == location[0] && frame.top == location[1]
        ) { "Capture a new frame before using coordinates" }
        require(points.size in 2..24)
        val localPoints = points.map { (x, y) -> (x - frame.left) to (y - frame.top) }
        require(localPoints.all { (x, y) -> x >= 0 && y >= 0 && x < tab.view.width && y < tab.view.height }) {
            "Coordinates must be inside the rendered page"
        }
        frameTarget = null
        val start = SystemClock.uptimeMillis()
        var last = localPoints.first()
        sendTouch(tab.view, start, MotionEvent.ACTION_DOWN, last)
        try {
            localPoints.drop(1).forEachIndexed { index, point ->
                if (millis > 0) TimedWait.await((millis / (points.size - 1)).milliseconds)
                check(activeId == id && tabs[id] === tab) { "Tab changed during gesture" }
                last = point
                sendTouch(tab.view, start, if (index == points.size - 2) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE, point)
            }
        } catch (error: Throwable) {
            if (tabs[id] === tab) sendTouch(tab.view, start, MotionEvent.ACTION_CANCEL, last)
            throw error
        }
        state(tab)
    }

    suspend fun key(id: Int, keyCode: Int): TabState = onMain {
        val tab = activeTab(id)
        frameTarget = null
        tab.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        tab.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        state(tab)
    }

    suspend fun typeText(id: Int, text: String): TabState = onMain {
        val tab = activeTab(id)
        require(text.length <= 4_000)
        val connection = tab.view.onCreateInputConnection(EditorInfo()) ?: error("No focused editable field")
        check(connection.commitText(text, 1)) { "WebView refused text input" }
        frameTarget = null
        state(tab)
    }

    suspend fun pageText(id: Int): String = withTimeoutOrNull(5_000) {
        onMain {
            val tab = tab(id)
            val result = suspendCancellableCoroutine { continuation ->
                tab.view.evaluateJavascript("document.body?.innerText.slice(0, 12000) ?? ''") {
                    continuation.resume(it)
                }
            }
            if (result == null || result == "null") "" else Json.parseToJsonElement(result).jsonPrimitive.content
        }
    } ?: error("Timed out reading the page")

    data class DomSnapshot(val url: String, val title: String, val html: String, val truncated: Boolean)

    suspend fun pageDom(id: Int, maxPayloadBytes: Int): DomSnapshot = withTimeoutOrNull(5_000L) {
        onMain {
            val tab = tab(id)
            val revision = tab.revision
            val result = suspendCancellableCoroutine { continuation ->
                tab.view.evaluateJavascript(
                    """
                    (() => {
                        const root = document.body?.cloneNode(true);
                        if (!root) return null;
                        root.querySelectorAll('script,style,template,noscript,svg,iframe,input[type="hidden"]').forEach(node => node.remove());
                        root.querySelectorAll('textarea,[contenteditable]').forEach(node => node.textContent = '');
                        const attributes = new Set(['href', 'download', 'type', 'role', 'aria-label', 'title']);
                        for (const node of [root, ...root.querySelectorAll('*')]) {
                            for (const attribute of Array.from(node.attributes)) {
                                if (!attributes.has(attribute.name)) node.removeAttribute(attribute.name);
                            }
                        }
                        const html = root.outerHTML;
                        const url = location.href;
                        const title = document.title;
                        const encoder = new TextEncoder();
                        const limit = Math.max(0, $maxPayloadBytes - encoder.encode(url).length - encoder.encode(title).length);
                        const {read} = encoder.encodeInto(html, new Uint8Array(limit));
                        return {url, title, html: html.slice(0, read), truncated: read < html.length};
                    })()
                    """.trimIndent(),
                ) { continuation.resume(it) }
            }
            check(tabs[id] === tab && tab.revision == revision) { "Page changed during HTML capture. Retry after navigation." }
            check(result != null && result != "null") { "No DOM available in this tab" }
            val data = Json.parseToJsonElement(result).jsonObject
            val url = data.getValue("url").jsonPrimitive.content
            check(url == tab.view.url) { "Page URL changed during HTML capture" }
            DomSnapshot(
                url = url,
                title = data.getValue("title").jsonPrimitive.content,
                html = data.getValue("html").jsonPrimitive.content,
                truncated = data.getValue("truncated").jsonPrimitive.boolean,
            )
        }
    } ?: error("Timed out reading page HTML")

    suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) { release() }

    private fun release() {
        if (closed) return
        closed = true
        frameRequest?.cancel()
        tabs.values.toList().forEach(::disposeTab)
        window?.dismiss()
        try {
            virtualDisplayManager.close()
        } finally {
            reader?.close()
        }
    }

    private fun activate(tab: Tab) {
        if (activeId == tab.id) return
        container.removeAllViews()
        activeId = tab.id
        frameTarget = null
        container.addView(tab.view, FrameLayout.LayoutParams(-1, -1))
        tab.view.requestFocus()
        emit(tab, "TabSelected")
    }

    private fun disposeTab(tab: Tab) {
        tabs.remove(tab.id)
        if (activeId == tab.id) {
            activeId = null
            frameTarget = null
            container.removeView(tab.view)
            frameRequest?.cancel()
        }
        tab.view.destroy()
    }

    private fun navigate(tab: Tab, url: String) {
        tab.url = url
        begin(tab)
        tab.view.loadUrl(url)
    }

    private fun begin(tab: Tab) {
        tab.loading = true
        tab.error = null
        emit(tab, "NavigationStarted")
    }

    private fun fail(tab: Tab, message: String) {
        tab.loading = false
        tab.error = message
        emit(tab, "NavigationFailed")
    }

    private fun client(tab: Tab) = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (RenderedWebPageLoader.isPublicWebUrl(request.url.toString())) null
            else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val blocked = !RenderedWebPageLoader.isHttpsWebUrl(request.url.toString())
            if (blocked && request.isForMainFrame) fail(tab, "Navigation blocked: public HTTPS pages only")
            return blocked
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val previousUrl = tab.url
            tab.url = url
            if (!tab.loading) begin(tab)
            if (previousUrl != url) emit(tab, "UrlChanged")
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (tab.url != url) {
                tab.url = url
                emit(tab, "UrlChanged")
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view.url != url) return
            tab.url = url
            tab.loading = false
            if (tab.error == null) emit(tab, "NavigationFinished")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) fail(tab, "WebView error ${error.errorCode}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) fail(tab, "HTTP ${response.statusCode}")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            fail(tab, "WebView renderer stopped")
            disposeTab(tab)
            if (activeId == null) tabs.values.lastOrNull()?.let(::activate)
            return true
        }
    }

    private fun tab(id: Int): Tab = tabs[id] ?: error("Unknown browser tab: $id")

    private fun activeTab(id: Int): Tab {
        check(activeId == id) { "Select the tab before rendering or sending input" }
        return tab(id)
    }

    private fun state(tab: Tab) = TabState(
        tab.id, tab.url, tab.view.title, tab.id == activeId, tab.loading,
        tab.view.canGoBack(), tab.view.canGoForward(), tab.revision, tab.error,
    )

    private fun emit(tab: Tab, type: String) {
        tab.revision++
        updates.tryEmit(Event(type, state(tab)))
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Browser closed" }
        block()
    }

    private suspend fun validateUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        rawUrl.trim().also { require(RenderedWebPageLoader.isPublicWebUrl(it)) { "Use a public HTTPS URL" } }
    }

    private fun sendTouch(view: View, start: Long, action: Int, point: Pair<Float, Float>) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val rowBytes = width * 4
        check(plane.pixelStride == 4)
        val bytes = ByteBuffer.allocate(rowBytes * height)
        val source = plane.buffer
        val row = ByteArray(rowBytes)
        val origin = source.position()
        repeat(height) { y ->
            source.position(origin + y * plane.rowStride)
            source.get(row)
            bytes.put(row)
        }
        bytes.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(bytes) }
    }

    companion object {
        suspend fun open(context: Context): HeadlessBrowser = withContext(Dispatchers.Main.immediate) {
            val browser = HeadlessBrowser(context.applicationContext)
            try {
                browser.initialize()
                browser
            } catch (error: Throwable) {
                browser.release()
                throw error
            }
        }
    }
}
