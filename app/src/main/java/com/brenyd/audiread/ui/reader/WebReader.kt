package com.brenyd.audiread.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.data.ReaderSettings
import com.brenyd.audiread.data.TocEntry
import com.brenyd.audiread.data.TurnStyle
import com.brenyd.audiread.sync.SyncMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

data class PageInfo(val page: Int, val total: Int, val leftInChapter: Int)

interface ReaderHandle {
    fun goToGlobal(t: Double)
    fun goToToc(e: TocEntry)
    fun next()
    fun prev()
    /** Moves to [t] only if it isn't already on screen (used to follow the narration). */
    fun follow(t: Double)
    fun pageInfo(): PageInfo
}

private fun progressTarget(p: Double): String = "{\"type\":\"progression\",\"value\":${if (p.isNaN()) 0.0 else p}}"
private fun fragmentTarget(id: String): String = JSONObject().put("type", "fragment").put("id", id).toString()
private const val END_TARGET = "{\"type\":\"end\"}"

fun cssColor(argb: Long): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

private val fontStacks = mapOf(
    "literata" to "'Literata', 'Noto Serif', serif",
    "serif" to "'Noto Serif', serif",
    "sans-serif" to "'Roboto', sans-serif",
    "sans-serif-condensed" to "sans-serif-condensed",
    "monospace" to "monospace",
)

fun readerConfig(s: ReaderSettings): String = JSONObject().apply {
    put("bg", cssColor(s.theme.bg))
    put("fg", cssColor(s.theme.fg))
    put("link", if (s.theme.dark) "#8AB4F8" else "#1A73E8")
    put("font", fontStacks[s.font] ?: "default")
    put("lineHeight", s.lineHeight.toDouble())
    put("justify", s.justify)
    put("margin", s.margin)
    put("marginTop", 46)
    put("scroll", s.scroll)
    put("override", s.theme.dark)
}.toString()

/** WebView that hands horizontal drags to the page-turn engine and everything else (taps, selection) to the page. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ReaderWebView(ctx: Context) : WebView(ctx) {
    interface Turner {
        fun dragStart(dir: Int, x: Float, y: Float): Boolean
        fun drag(x: Float, y: Float)
        fun dragEnd(x: Float, y: Float, vx: Float)
    }

    var turner: Turner? = null
    var paged = true
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var rejected = false
    private var tracker: VelocityTracker? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = turner
        if (!paged || t == null) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false; rejected = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging && !rejected && e.pointerCount == 1) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        if (t.dragStart(if (dx < 0) 1 else -1, downX, downY)) {
                            dragging = true
                            val cancel = MotionEvent.obtain(e)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.onTouchEvent(cancel)
                            cancel.recycle()
                        } else rejected = true
                    }
                }
                if (dragging) {
                    t.drag(e.x, e.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(e)
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    t.dragEnd(e.x, e.y, tracker?.xVelocity ?: 0f)
                    dragging = false
                    tracker?.recycle(); tracker = null
                    return true
                }
                tracker?.recycle(); tracker = null
            }
        }
        return super.onTouchEvent(e)
    }
}

/** Reflowable reader (EPUB and converted formats): a WebView laid out in CSS columns, one column per page. */
class WebReaderState(
    private val ebook: EbookInfo,
    initial: ReadPosition,
    private val scope: CoroutineScope,
) : ReaderHandle, ReaderWebView.Turner {
    var webView: ReaderWebView? = null
        private set
    var section by mutableIntStateOf(initial.section.coerceIn(0, (ebook.sections.size - 1).coerceAtLeast(0)))
        private set
    var page by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(1)
        private set
    var progression by mutableDoubleStateOf(initial.progression)
        private set
    var turn by mutableStateOf<PageTurn?>(null)
        private set

    var onPosition: (Int, Double) -> Unit = { _, _ -> }
    var onTapCenter: () -> Unit = {}
    var onUserTurn: () -> Unit = {}
    var cfgJson: String = "{}"
    var scrollMode: Boolean = false
    var turnStyle: TurnStyle = TurnStyle.CURL

    private var loaded = -1
    private var loading = false
    private var pendingTarget: String = progressTarget(initial.progression)
    private val pageSignal = Channel<Unit>(Channel.CONFLATED)
    private val pool = arrayOfNulls<Bitmap>(2)
    private val totalWeight = ebook.sections.sumOf { it.weight.coerceAtLeast(1) }.toDouble()
    private var charsPerPage = 0.0

    // ------------------------------------------------------------------ loading

    fun attach(wv: ReaderWebView) {
        webView = wv
        wv.turner = this
        load(section, pendingTarget)
    }

    fun detach() {
        webView?.turner = null
        webView = null
        loaded = -1
        turn = null
    }

    fun load(s: Int, target: String) {
        if (ebook.sections.isEmpty()) return
        val idx = s.coerceIn(0, ebook.sections.size - 1)
        val wv = webView
        if (wv == null) {
            section = idx
            pendingTarget = target
            return
        }
        if (idx == loaded) {
            if (loading) pendingTarget = target
            else wv.evaluateJavascript("window.__ar&&__ar.jump($target)", null)
            return
        }
        section = idx
        loaded = idx
        loading = true
        pendingTarget = target
        wv.loadUrl(Uri.fromFile(File(ebook.contentDir, ebook.sections[idx].path)).toString())
    }

    fun onPageFinished(wv: WebView) {
        wv.evaluateJavascript("$READER_JS;__ar.apply($cfgJson);__ar.init($pendingTarget);", null)
    }

    fun reportPage(p: Int, n: Int, prog: Double) {
        page = p
        pageCount = n.coerceAtLeast(1)
        progression = prog
        loading = false
        if (!scrollMode && n > 0) {
            val cpp = ebook.sections[section].weight.coerceAtLeast(1) / n.toDouble()
            charsPerPage = if (charsPerPage == 0.0) cpp else charsPerPage * 0.7 + cpp * 0.3
        }
        pageSignal.trySend(Unit)
        onPosition(section, prog)
    }

    fun edge(dir: Int) {
        if (dir > 0 && section < ebook.sections.size - 1) load(section + 1, progressTarget(0.0))
        else if (dir < 0 && section > 0) load(section - 1, END_TARGET)
    }

    fun applySettings(cfg: String, rs: ReaderSettings) {
        val changed = cfg != cfgJson
        cfgJson = cfg
        scrollMode = rs.scroll
        turnStyle = rs.turnStyle
        webView?.paged = !rs.scroll
        if (changed && !loading) webView?.evaluateJavascript("window.__ar&&__ar.relayout($cfg)", null)
    }

    fun relayout() {
        if (!loading) webView?.evaluateJavascript("window.__ar&&__ar.relayout()", null)
    }

    fun resolveLink(url: Uri): Boolean {
        if (url.scheme != "file") return false
        val path = url.path ?: return true
        val target = File(path).canonicalPath
        val idx = ebook.sections.indexOfFirst { File(ebook.contentDir, it.path).canonicalPath == target }
        if (idx >= 0) {
            onUserTurn()
            load(idx, url.fragment?.let { fragmentTarget(it) } ?: progressTarget(0.0))
        }
        return true
    }

    fun onTapZone(zone: Int) {
        when (zone) {
            0 -> { if (turnPage(-1, animated = true)) onUserTurn() }
            2 -> { if (turnPage(1, animated = true)) onUserTurn() }
            else -> onTapCenter()
        }
    }

    // ------------------------------------------------------------------ page turning

    private fun canTurn(dir: Int): Boolean =
        if (dir > 0) !(section >= ebook.sections.size - 1 && page >= pageCount - 1)
        else !(section <= 0 && page <= 0)

    private fun jsTurn(dir: Int) {
        webView?.evaluateJavascript(if (dir > 0) "window.__ar&&__ar.next()" else "window.__ar&&__ar.prev()", null)
    }

    private fun snapshot(slot: Int): Bitmap? {
        val wv = webView ?: return null
        if (wv.width <= 0 || wv.height <= 0) return null
        var b = pool[slot]
        if (b == null || b.width != wv.width || b.height != wv.height) {
            b = runCatching { Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
            pool[slot] = b
        }
        val c = Canvas(b)
        c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
        wv.draw(c)
        return b
    }

    private suspend fun awaitRendered() {
        withTimeoutOrNull(2500) { pageSignal.receive() }
        val wv = webView ?: return
        withTimeoutOrNull(500) {
            suspendCancellableCoroutine { cont ->
                wv.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
        }
        delay(16)
    }

    /** Starts a page turn. Returns false if there is nothing to turn to. */
    private fun startTurn(dir: Int, cornerY: Float?): PageTurn? {
        if (turn != null || loading || !canTurn(dir)) return null
        val wv = webView ?: return null
        val w = wv.width.toFloat()
        val h = wv.height.toFloat()
        val current = snapshot(0) ?: return null
        val t = PageTurn(dir, w, h, cornerY ?: h)
        if (dir > 0) t.front = current else t.back = current
        while (pageSignal.tryReceive().isSuccess) Unit
        turn = t
        jsTurn(dir)
        scope.launch {
            awaitRendered()
            if (turn !== t) return@launch
            val other = snapshot(1)
            if (dir > 0) t.back = other else t.front = other
            t.ready = true
            if (t.released) settle(t, decide(t))
        }
        return t
    }

    /** Turns one page; animated unless the style is None. */
    fun turnPage(dir: Int, animated: Boolean): Boolean {
        if (turn != null) return false
        if (!canTurn(dir)) return false
        if (!animated || turnStyle == TurnStyle.NONE || scrollMode) {
            jsTurn(dir)
            return true
        }
        val t = startTurn(dir, null) ?: run { jsTurn(dir); return true }
        t.released = true
        t.releaseVelocity = if (dir > 0) -3000f else 3000f
        return true
    }

    override fun dragStart(dir: Int, x: Float, y: Float): Boolean {
        if (turnStyle == TurnStyle.NONE) {
            if (turn == null && canTurn(dir)) {
                jsTurn(dir)
                onUserTurn()
            }
            return false
        }
        val h = webView?.height?.toFloat() ?: return false
        val t = startTurn(dir, if (y < h / 2f) 0f else h) ?: return false
        t.downX = x
        t.downY = y
        onUserTurn()
        return true
    }

    override fun drag(x: Float, y: Float) {
        val t = turn ?: return
        if (t.released) return
        val dy = (y - t.downY) * 0.6f
        if (t.dir > 0) t.setTip(t.width + (x - t.downX) * 1.4f, t.cornerY + dy)
        else t.setTip(-t.width + (x - t.downX) * 2f, t.cornerY + dy)
    }

    override fun dragEnd(x: Float, y: Float, vx: Float) {
        val t = turn ?: return
        t.released = true
        t.releaseVelocity = vx
        if (t.ready) settle(t, decide(t))
    }

    private fun decide(t: PageTurn): Boolean {
        val v = t.releaseVelocity
        return if (t.dir > 0) v < -600f || (t.x < t.width * 0.6f && v < 600f)
        else v > 600f || (t.x > -t.width * 0.4f && v > -600f)
    }

    private fun settle(t: PageTurn, complete: Boolean) {
        scope.launch {
            val targetX = if (t.dir > 0) (if (complete) -t.width else t.width) else (if (complete) t.width else -t.width)
            val startX = t.x
            val startY = t.y
            val span = abs(targetX - startX).coerceAtLeast(1f)
            val tapStart = abs(startX) >= t.width - 1f && abs(startY - t.cornerY) < 1f
            val lift = if (t.cornerY > 0f) -t.height * 0.12f else t.height * 0.12f
            val dur = (180 + 260 * span / (2 * t.width)).toInt()
            animate(0f, 1f, animationSpec = tween(dur, easing = FastOutSlowInEasing)) { f, _ ->
                val nx = startX + (targetX - startX) * f
                val ny = if (tapStart) t.cornerY + lift * sin(PI * f).toFloat()
                else startY + (t.cornerY - startY) * f
                t.x = nx
                t.y = ny
            }
            if (!complete) {
                // Put the page back the way it was, hidden behind the overlay.
                while (pageSignal.tryReceive().isSuccess) Unit
                jsTurn(-t.dir)
                awaitRendered()
            }
            if (turn === t) turn = null
        }
    }

    // ------------------------------------------------------------------ ReaderHandle

    override fun goToGlobal(t: Double) {
        val p = SyncMapper.textLocal(ebook, t)
        load(p.section, progressTarget(p.progression))
    }

    override fun goToToc(e: TocEntry) {
        load(e.section, e.fragment?.let { fragmentTarget(it) } ?: progressTarget(0.0))
    }

    override fun next() {
        turnPage(1, animated = true)
    }

    override fun prev() {
        turnPage(-1, animated = true)
    }

    override fun follow(t: Double) {
        if (loading || turn != null) return
        val p = SyncMapper.textLocal(ebook, t)
        if (p.section != section) {
            if (p.section == section + 1 && page >= pageCount - 1) turnPage(1, animated = true)
            else load(p.section, progressTarget(p.progression))
            return
        }
        if (scrollMode) {
            if (abs(p.progression - progression) > 0.04) load(p.section, progressTarget(p.progression))
            return
        }
        val target = floor(p.progression * pageCount).toInt().coerceIn(0, pageCount - 1)
        when {
            target == page -> Unit
            target == page + 1 -> turnPage(1, animated = true)
            else -> webView?.evaluateJavascript("window.__ar&&__ar.go($target)", null)
        }
    }

    override fun pageInfo(): PageInfo {
        if (scrollMode || charsPerPage <= 0.0) {
            return PageInfo((progression * 100).toInt(), 0, 0)
        }
        val starts = SyncMapper.starts(ebook)
        val before = starts.getOrElse(section) { 0.0 } * totalWeight
        val total = ceil(totalWeight / charsPerPage).toInt().coerceAtLeast(1)
        val current = ((before / charsPerPage).toInt() + page + 1).coerceIn(1, total)
        return PageInfo(current, total, (pageCount - 1 - page).coerceAtLeast(0))
    }
}

private class Bridge(private val s: WebReaderState) {
    private val h = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPage(p: Int, n: Int, prog: Double) {
        h.post { s.reportPage(p, n, prog) }
    }

    @JavascriptInterface
    fun onEdge(dir: Int) {
        h.post { s.edge(dir) }
    }

    @JavascriptInterface
    fun onTapZone(zone: Int) {
        h.post { s.onTapZone(zone) }
    }

    @JavascriptInterface
    fun onUserTurn() {
        h.post { s.onUserTurn() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebReaderView(state: WebReaderState, rs: ReaderSettings, modifier: Modifier = Modifier) {
    val cfg = readerConfig(rs)
    val bg = (rs.theme.bg and 0xFFFFFFFFL).toInt()
    if (state.webView == null) {
        state.cfgJson = cfg
        state.scrollMode = rs.scroll
        state.turnStyle = rs.turnStyle
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ReaderWebView(ctx).apply {
                    this.settings.javaScriptEnabled = true
                    this.settings.allowFileAccess = true
                    this.settings.textZoom = rs.fontScale
                    this.settings.setSupportZoom(false)
                    this.settings.builtInZoomControls = false
                    paged = !rs.scroll
                    setBackgroundColor(bg)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isHapticFeedbackEnabled = false
                    addJavascriptInterface(Bridge(state), "AR")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            state.onPageFinished(view)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (state.resolveLink(u)) return true
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                            return true
                        }
                    }
                    state.attach(this)
                }
            },
            onRelease = { wv ->
                state.detach()
                wv.destroy()
            },
        )
        val t = state.turn
        if (t != null) {
            Canvas(Modifier.fillMaxSize()) {
                // reading t.x / t.y here makes the canvas redraw on every animation frame
                if (t.x.isNaN() || t.y.isNaN()) return@Canvas
                drawIntoCanvas { c -> PageCurl.draw(c.nativeCanvas, t, rs.turnStyle, bg, rs.theme.dark) }
            }
        }
    }
    LaunchedEffect(rs) {
        val wv = state.webView ?: return@LaunchedEffect
        wv.setBackgroundColor(bg)
        if (wv.settings.textZoom != rs.fontScale) {
            wv.settings.textZoom = rs.fontScale
            state.applySettings(cfg, rs)
            state.relayout()
        } else {
            state.applySettings(cfg, rs)
        }
    }
}

/** Pagination and tap script injected into every chapter. Pages are columns shifted with a transform. */
private val READER_JS = """
(function(){
if (window.__ar) return;
var R = {}; window.__ar = R;
R.cfg = {scroll:false, margin:20, marginTop:40};
R.page = 0; R.lastProg = 0;
function W(){ return window.innerWidth; }
function shift(x){
  var b = document.body; if (!b) return;
  b.style.setProperty('transform', x ? 'translate3d(' + (-x) + 'px,0,0)' : 'none', 'important');
}
R.apply = function(cfg){
  R.cfg = cfg;
  var st = document.getElementById('__ar_style');
  if (!st) {
    st = document.createElement('style'); st.id = '__ar_style';
    var h = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
    h.appendChild(st);
  }
  var Wd = W(), H = window.innerHeight, m = cfg.margin, mt = cfg.marginTop;
  var c = '';
  c += "@font-face{font-family:'Literata';src:url('file:///android_asset/fonts/Literata.ttf');font-weight:200 900;font-style:normal;}";
  c += "@font-face{font-family:'Literata';src:url('file:///android_asset/fonts/Literata-Italic.ttf');font-weight:200 900;font-style:italic;}";
  c += 'html{background:' + cfg.bg + ' !important;-webkit-text-size-adjust:none !important;}';
  c += 'body{color:' + cfg.fg + ' !important;background:transparent !important;word-wrap:break-word;overflow-wrap:break-word;}';
  if (cfg.override) c += 'body *{color:inherit !important;background-color:transparent !important;}';
  if (cfg.font && cfg.font !== 'default') c += 'body,body p,body div,body span,body li,body blockquote,body a,body em,body i,body b,body strong,body h1,body h2,body h3,body h4,body h5,body h6{font-family:' + cfg.font + ' !important;}';
  c += 'p,li,blockquote,dd{line-height:' + cfg.lineHeight + ' !important;}';
  if (cfg.justify) c += 'p{text-align:justify !important;-webkit-hyphens:auto;hyphens:auto;}';
  c += 'a{color:' + cfg.link + ' !important;}';
  c += 'img,svg,video{max-width:100% !important;max-height:' + Math.max(80, H - 2*mt - 16) + 'px !important;object-fit:contain;-webkit-column-break-inside:avoid;break-inside:avoid;}';
  c += 'img{height:auto !important;} pre{white-space:pre-wrap !important;} table{max-width:100% !important;}';
  c += '*{max-width:100%;box-sizing:border-box;}';
  if (cfg.scroll) {
    c += 'html{overflow-x:hidden !important;overflow-y:auto !important;height:auto !important;width:auto !important;}';
    c += 'body{margin:0 !important;padding:' + mt + 'px ' + m + 'px ' + (mt*2) + 'px ' + m + 'px !important;height:auto !important;-webkit-columns:auto !important;columns:auto !important;max-width:none !important;width:auto !important;}';
  } else {
    c += 'html{height:' + H + 'px !important;width:' + Wd + 'px !important;overflow:hidden !important;margin:0 !important;padding:0 !important;}';
    c += 'body{margin:0 !important;padding:' + mt + 'px ' + m + 'px !important;box-sizing:border-box !important;height:' + H + 'px !important;max-width:none !important;width:' + Wd + 'px !important;min-width:0 !important;' +
         '-webkit-column-width:' + (Wd - 2*m) + 'px !important;column-width:' + (Wd - 2*m) + 'px !important;' +
         '-webkit-column-gap:' + (2*m) + 'px !important;column-gap:' + (2*m) + 'px !important;column-fill:auto !important;overflow:visible !important;will-change:transform;}';
  }
  st.textContent = c;
  if (cfg.scroll) shift(0);
};
R.count = function(){
  if (R.cfg.scroll) return 1;
  var b = document.body; if (!b) return 1;
  var sw = Math.max(b.scrollWidth, document.documentElement.scrollWidth);
  return Math.max(1, Math.round((sw + R.cfg.margin) / W()));
};
R.progression = function(){
  if (R.cfg.scroll) {
    var h = document.documentElement.scrollHeight - window.innerHeight;
    return h > 0 ? Math.min(1, window.scrollY / h) : 0;
  }
  var n = R.count();
  return n > 0 ? R.page / n : 0;
};
R.report = function(){ var p = R.progression(); R.lastProg = p; AR.onPage(R.page, R.count(), p); };
R.go = function(p){
  if (R.cfg.scroll) { R.report(); return; }
  var n = R.count(); p = Math.max(0, Math.min(n - 1, p)); R.page = p;
  window.scrollTo(0, 0); shift(p * W()); R.report();
};
R.goProgression = function(t){
  if (R.cfg.scroll) {
    var h = document.documentElement.scrollHeight - window.innerHeight;
    window.scrollTo(0, Math.max(0, t * h)); R.report(); return;
  }
  var n = R.count(); R.go(Math.floor(t * n + 0.0001));
};
R.goFragment = function(id){
  var el = document.getElementById(id);
  if (!el) { var els = document.getElementsByName(id); el = (els && els.length) ? els[0] : null; }
  if (!el) { R.go(0); return; }
  if (R.cfg.scroll) { el.scrollIntoView(); R.report(); return; }
  var r = el.getBoundingClientRect();
  R.go(Math.floor((r.left + R.page * W() + 1) / W()));
};
R.next = function(){
  if (R.cfg.scroll) {
    var y = window.scrollY; window.scrollBy(0, window.innerHeight * 0.9);
    if (Math.abs(window.scrollY - y) < 2) AR.onEdge(1); else R.report(); return;
  }
  if (R.page < R.count() - 1) R.go(R.page + 1); else AR.onEdge(1);
};
R.prev = function(){
  if (R.cfg.scroll) {
    var y = window.scrollY; window.scrollBy(0, -window.innerHeight * 0.9);
    if (Math.abs(window.scrollY - y) < 2) AR.onEdge(-1); else R.report(); return;
  }
  if (R.page > 0) R.go(R.page - 1); else AR.onEdge(-1);
};
R.jump = function(t){
  if (!t) return;
  if (t.type === 'end') { if (R.cfg.scroll) R.goProgression(1); else R.go(R.count() - 1); }
  else if (t.type === 'fragment') R.goFragment(t.id);
  else R.goProgression(t.value || 0);
};
R.init = function(t){
  var done = false;
  function run(){ if (done) return; done = true; R.jump(t); }
  if (document.fonts && document.fonts.ready) { document.fonts.ready.then(function(){ setTimeout(run, 30); }); setTimeout(run, 400); }
  else setTimeout(run, 60);
};
R.relayout = function(cfg){
  var t = R.progression(); R.apply(cfg || R.cfg);
  setTimeout(function(){ R.goProgression(t); }, 80);
};
var rt = null;
window.addEventListener('resize', function(){
  clearTimeout(rt);
  rt = setTimeout(function(){ var t = R.lastProg; R.apply(R.cfg); setTimeout(function(){ R.goProgression(t); }, 60); }, 120);
});
var sc = null;
window.addEventListener('scroll', function(){
  if (!R.cfg.scroll) { if (window.scrollX || window.scrollY) window.scrollTo(0, 0); return; }
  clearTimeout(sc); sc = setTimeout(R.report, 200);
});
var sx = 0, sy = 0, stt = 0;
document.addEventListener('touchstart', function(e){ var t = e.touches[0]; sx = t.clientX; sy = t.clientY; stt = Date.now(); }, {passive:true});
document.addEventListener('touchmove', function(e){ if (!R.cfg.scroll) e.preventDefault(); }, {passive:false});
document.addEventListener('touchend', function(e){
  var t = e.changedTouches[0]; var dx = t.clientX - sx, dy = t.clientY - sy;
  if (Math.abs(dx) < 12 && Math.abs(dy) < 12 && Date.now() - stt < 450) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a) return;
    var sel = window.getSelection(); if (sel && sel.toString().length > 0) return;
    var w = W(), x = t.clientX;
    AR.onTapZone(x < w * 0.28 ? 0 : (x > w * 0.72 ? 2 : 1));
  } else if (R.cfg.scroll && Math.abs(dy) > 12) {
    AR.onUserTurn();
  }
}, {passive:true});
document.addEventListener('click', function(e){
  var a = e.target && e.target.closest ? e.target.closest('a') : null;
  if (!a) return;
  var h = a.getAttribute('href');
  if (h && h.charAt(0) === '#') { e.preventDefault(); R.goFragment(decodeURIComponent(h.substring(1))); AR.onUserTurn(); }
}, true);
})()
"""
