package com.brenyd.audiread.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.data.ReaderSettings
import com.brenyd.audiread.data.TocEntry
import com.brenyd.audiread.sync.SyncMapper
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

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

/**
 * WebView that turns taps and swipes into page turns:
 * left edge = back, right edge = forward, middle (or top/bottom strip) = show/hide the menu.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ReaderWebView(ctx: Context) : WebView(ctx) {
    /** zone: 0 = left (back), 1 = middle (menu), 2 = right (forward) */
    var onTapZone: (Int) -> Unit = {}
    /** dir: +1 forward, -1 back */
    var onSwipe: (Int) -> Unit = {}
    var paged = true
    /** Called with the view size in CSS pixels (dp) whenever it changes. */
    var onSize: (Int, Int) -> Unit = { _, _ -> }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val type = hitTestResult.type
            if (type == HitTestResult.SRC_ANCHOR_TYPE || type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
            val w = width.toFloat()
            val h = height.toFloat()
            val zone = when {
                e.x < w * 0.3f -> 0
                e.x > w * 0.7f -> 2
                else -> 1
            }
            onTapZone(if (zone != 1 && (e.y < h * 0.08f)) 1 else zone)
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        if (w > 0 && h > 0) onSize((w / d).toInt(), (h / d).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        if (paged) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiped = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!swiped && e.pointerCount == 1 && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                        swiped = true
                        onSwipe(if (dx < 0) 1 else -1)
                    }
                }
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
) : ReaderHandle {
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

    var onPosition: (Int, Double) -> Unit = { _, _ -> }
    var onTapCenter: () -> Unit = {}
    var onUserTurn: () -> Unit = {}
    var cfgJson: String = "{}"
    var scrollMode: Boolean = false

    private var loaded = -1
    private var loading = false
    private var vw = 0
    private var vh = 0
    private var pendingTarget: String = progressTarget(initial.progression)
    private val totalWeight = ebook.sections.sumOf { it.weight.coerceAtLeast(1) }.toDouble()
    private var charsPerPage = 0.0

    // ------------------------------------------------------------------ loading

    fun attach(wv: ReaderWebView) {
        webView = wv
        wv.onTapZone = { onTapZone(it) }
        wv.onSwipe = { dir -> if (turnPage(dir)) onUserTurn() }
        wv.onSize = { w, h -> setSize(w, h) }
        if (wv.width > 0 && wv.height > 0) {
            val d = wv.resources.displayMetrics.density
            vw = (wv.width / d).toInt()
            vh = (wv.height / d).toInt()
        }
        load(section, pendingTarget)
    }

    fun detach() {
        webView = null
        loaded = -1
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

    private fun setSize(w: Int, h: Int) {
        if (w == vw && h == vh) return
        vw = w
        vh = h
        if (!loading) webView?.evaluateJavascript("window.__ar&&__ar.setSize($w,$h)", null)
    }

    fun onPageFinished(wv: WebView) {
        wv.evaluateJavascript("$READER_JS;__ar.vw=$vw;__ar.vh=$vh;__ar.apply($cfgJson);__ar.init($pendingTarget);", null)
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
            0 -> { if (turnPage(-1)) onUserTurn() }
            2 -> { if (turnPage(1)) onUserTurn() }
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

    /** Turns one page instantly. */
    fun turnPage(dir: Int): Boolean {
        if (loading || !canTurn(dir)) return false
        jsTurn(dir)
        return true
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
        turnPage(1)
    }

    override fun prev() {
        turnPage(-1)
    }

    override fun follow(t: Double) {
        if (loading) return
        val p = SyncMapper.textLocal(ebook, t)
        if (p.section != section) {
            if (p.section == section + 1 && page >= pageCount - 1) turnPage(1)
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
            target == page + 1 -> turnPage(1)
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
                    this.settings.useWideViewPort = false
                    this.settings.loadWithOverviewMode = false
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
R.vw = 0; R.vh = 0;
function W(){ return R.vw > 0 ? R.vw : (document.documentElement.clientWidth || window.innerWidth); }
function H(){ return R.vh > 0 ? R.vh : (document.documentElement.clientHeight || window.innerHeight); }
function ensureViewport(){
  var h = document.head || document.getElementsByTagName('head')[0];
  if (!h) return;
  var m = document.querySelector('meta[name=viewport]');
  if (!m) { m = document.createElement('meta'); m.setAttribute('name', 'viewport'); h.insertBefore(m, h.firstChild); }
  m.setAttribute('content', 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no');
}
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
  ensureViewport();
  var Wd = W(), Hh = H(), m = cfg.margin, mt = cfg.marginTop;
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
  c += 'img,svg,video{max-width:100% !important;max-height:' + Math.max(80, Hh - 2*mt - 16) + 'px !important;object-fit:contain;-webkit-column-break-inside:avoid;break-inside:avoid;}';
  c += 'img{height:auto !important;} pre{white-space:pre-wrap !important;} table{max-width:100% !important;}';
  c += '*{max-width:100%;box-sizing:border-box;}';
  if (cfg.scroll) {
    c += 'html{overflow-x:hidden !important;overflow-y:auto !important;height:auto !important;width:auto !important;}';
    c += 'body{margin:0 !important;padding:' + mt + 'px ' + m + 'px ' + (mt*2) + 'px ' + m + 'px !important;height:auto !important;-webkit-columns:auto !important;columns:auto !important;max-width:none !important;width:auto !important;}';
  } else {
    c += 'html{height:' + Hh + 'px !important;width:' + Wd + 'px !important;overflow:hidden !important;margin:0 !important;padding:0 !important;}';
    c += 'body{margin:0 !important;padding:' + mt + 'px ' + m + 'px !important;box-sizing:border-box !important;height:' + Hh + 'px !important;max-width:none !important;width:' + Wd + 'px !important;min-width:0 !important;' +
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
R.setSize = function(w, h){
  R.vw = w; R.vh = h;
  var t = R.lastProg; R.apply(R.cfg);
  setTimeout(function(){ R.goProgression(t); }, 60);
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
var sy0 = 0;
document.addEventListener('touchstart', function(e){ sy0 = e.touches[0].clientY; }, {passive:true});
document.addEventListener('touchmove', function(e){ if (!R.cfg.scroll) e.preventDefault(); }, {passive:false});
document.addEventListener('touchend', function(e){
  if (R.cfg.scroll && Math.abs(e.changedTouches[0].clientY - sy0) > 12) AR.onUserTurn();
}, {passive:true});
document.addEventListener('click', function(e){
  var a = e.target && e.target.closest ? e.target.closest('a') : null;
  if (!a) return;
  var h = a.getAttribute('href');
  if (h && h.charAt(0) === '#') { e.preventDefault(); R.goFragment(decodeURIComponent(h.substring(1))); AR.onUserTurn(); }
}, true);
})()
"""
