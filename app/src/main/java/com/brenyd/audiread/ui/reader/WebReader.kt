package com.brenyd.audiread.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.data.ReaderSettings
import com.brenyd.audiread.data.TocEntry
import com.brenyd.audiread.sync.SyncMapper
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

interface ReaderHandle {
    fun goToGlobal(t: Double)
    fun goToToc(e: TocEntry)
    fun next()
    fun prev()
    /** Moves to [t] only if it isn't already on screen (used to follow the narration). */
    fun follow(t: Double)
    fun pageLabel(): String
}

private fun progressTarget(p: Double): String = "{\"type\":\"progression\",\"value\":${if (p.isNaN()) 0.0 else p}}"
private fun fragmentTarget(id: String): String = JSONObject().put("type", "fragment").put("id", id).toString()
private const val END_TARGET = "{\"type\":\"end\"}"

fun cssColor(argb: Long): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

fun readerConfig(s: ReaderSettings): String = JSONObject().apply {
    put("bg", cssColor(s.theme.bg))
    put("fg", cssColor(s.theme.fg))
    put("link", if (s.theme.dark) "#8AB4F8" else "#1A56B0")
    put("font", s.font)
    put("lineHeight", s.lineHeight.toDouble())
    put("justify", s.justify)
    put("margin", s.margin)
    put("marginTop", 34)
    put("scroll", s.scroll)
    put("override", s.theme.dark)
}.toString()

/** Reflowable reader (EPUB and converted formats) rendered in a WebView with CSS-column pagination. */
class WebReaderState(private val ebook: EbookInfo, initial: ReadPosition) : ReaderHandle {
    var webView: WebView? = null
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
    var onTap: (Int) -> Unit = {}
    var onUserTurn: () -> Unit = {}
    var cfgJson: String = "{}"
    var scrollMode: Boolean = false

    private var loaded = -1
    private var loading = false
    private var pendingTarget: String = progressTarget(initial.progression)

    fun attach(wv: WebView) {
        webView = wv
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

    fun onPageFinished(wv: WebView) {
        wv.evaluateJavascript("$READER_JS;__ar.apply($cfgJson);__ar.init($pendingTarget);", null)
    }

    fun reportPage(p: Int, n: Int, prog: Double) {
        page = p
        pageCount = n.coerceAtLeast(1)
        progression = prog
        loading = false
        onPosition(section, prog)
    }

    fun edge(dir: Int) {
        if (dir > 0 && section < ebook.sections.size - 1) load(section + 1, progressTarget(0.0))
        else if (dir < 0 && section > 0) load(section - 1, END_TARGET)
    }

    fun applySettings(cfg: String, scroll: Boolean) {
        val changed = cfg != cfgJson
        cfgJson = cfg
        scrollMode = scroll
        if (changed && !loading) webView?.evaluateJavascript("window.__ar&&__ar.relayout($cfg)", null)
    }

    /** Called on resize-free text zoom changes. */
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

    override fun goToGlobal(t: Double) {
        val p = SyncMapper.textLocal(ebook, t)
        load(p.section, progressTarget(p.progression))
    }

    override fun goToToc(e: TocEntry) {
        load(e.section, e.fragment?.let { fragmentTarget(it) } ?: progressTarget(0.0))
    }

    override fun next() {
        webView?.evaluateJavascript("window.__ar&&__ar.next()", null)
    }

    override fun prev() {
        webView?.evaluateJavascript("window.__ar&&__ar.prev()", null)
    }

    override fun follow(t: Double) {
        if (loading) return
        val p = SyncMapper.textLocal(ebook, t)
        if (p.section != section) {
            load(p.section, progressTarget(p.progression))
            return
        }
        if (scrollMode) {
            if (abs(p.progression - progression) > 0.04) load(p.section, progressTarget(p.progression))
            return
        }
        val target = floor(p.progression * pageCount).toInt().coerceIn(0, pageCount - 1)
        if (target != page) webView?.evaluateJavascript("window.__ar&&__ar.go($target)", null)
    }

    override fun pageLabel(): String =
        if (scrollMode) "${(progression * 100).toInt()}%" else "${page + 1} / $pageCount"
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
    fun onTap(zone: Int) {
        h.post { s.onTap(zone) }
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
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                this.settings.javaScriptEnabled = true
                this.settings.allowFileAccess = true
                this.settings.textZoom = rs.fontScale
                this.settings.setSupportZoom(false)
                this.settings.builtInZoomControls = false
                setBackgroundColor(bg)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
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
    LaunchedEffect(rs) {
        val wv = state.webView ?: return@LaunchedEffect
        wv.setBackgroundColor(bg)
        if (wv.settings.textZoom != rs.fontScale) {
            wv.settings.textZoom = rs.fontScale
            state.cfgJson = cfg
            state.scrollMode = rs.scroll
            state.relayout()
        } else {
            state.applySettings(cfg, rs.scroll)
        }
    }
}

/** Pagination and gesture script injected into every chapter. */
private val READER_JS = """
(function(){
if (window.__ar) return;
var R = {}; window.__ar = R;
R.cfg = {scroll:false, margin:20, marginTop:30};
R.page = 0; R.lastProg = 0;
function W(){ return window.innerWidth; }
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
  c += 'html{background:' + cfg.bg + ' !important;-webkit-text-size-adjust:none !important;}';
  c += 'body{color:' + cfg.fg + ' !important;background:transparent !important;word-wrap:break-word;}';
  if (cfg.override) c += 'body *{color:inherit !important;background-color:transparent !important;}';
  if (cfg.font && cfg.font !== 'default') c += 'body,body *{font-family:' + cfg.font + ' !important;}';
  c += 'p,li,blockquote,dd{line-height:' + cfg.lineHeight + ' !important;}';
  if (cfg.justify) c += 'p{text-align:justify !important;-webkit-hyphens:auto;hyphens:auto;}';
  c += 'a{color:' + cfg.link + ' !important;}';
  c += 'img,svg,video{max-width:100% !important;max-height:' + Math.max(80, H - 2*mt - 16) + 'px !important;object-fit:contain;-webkit-column-break-inside:avoid;break-inside:avoid;}';
  c += 'img{height:auto !important;}';
  c += 'pre{white-space:pre-wrap !important;} table{max-width:100% !important;}';
  if (cfg.scroll) {
    c += 'html{overflow-x:hidden !important;overflow-y:auto !important;height:auto !important;width:auto !important;}';
    c += 'body{margin:0 !important;padding:' + mt + 'px ' + m + 'px ' + (mt*2) + 'px ' + m + 'px !important;height:auto !important;-webkit-columns:auto !important;columns:auto !important;max-width:none !important;}';
  } else {
    c += 'html{height:' + H + 'px !important;width:' + Wd + 'px !important;overflow:hidden !important;margin:0 !important;padding:0 !important;}';
    c += 'body{margin:0 !important;padding:' + mt + 'px ' + m + 'px !important;box-sizing:border-box !important;height:' + H + 'px !important;max-width:none !important;width:auto !important;' +
         '-webkit-column-width:' + (Wd - 2*m) + 'px !important;column-width:' + (Wd - 2*m) + 'px !important;' +
         '-webkit-column-gap:' + (2*m) + 'px !important;column-gap:' + (2*m) + 'px !important;column-fill:auto !important;overflow:visible !important;}';
  }
  st.textContent = c;
};
R.count = function(){
  if (R.cfg.scroll) return 1;
  var sw = Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0);
  return Math.max(1, Math.ceil(sw / W() - 0.02));
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
  window.scrollTo(p * W(), 0); R.report();
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
  R.go(Math.floor((r.left + window.scrollX + 1) / W()));
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
R.init = function(t){ setTimeout(function(){ R.jump(t); }, 60); };
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
window.addEventListener('scroll', function(){ if (!R.cfg.scroll) return; clearTimeout(sc); sc = setTimeout(R.report, 200); });
var sx = 0, sy = 0, stt = 0;
document.addEventListener('touchstart', function(e){ var t = e.touches[0]; sx = t.clientX; sy = t.clientY; stt = Date.now(); }, {passive:true});
document.addEventListener('touchmove', function(e){ if (!R.cfg.scroll) e.preventDefault(); }, {passive:false});
document.addEventListener('touchend', function(e){
  var t = e.changedTouches[0]; var dx = t.clientX - sx, dy = t.clientY - sy;
  if (!R.cfg.scroll && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) {
    if (dx < 0) R.next(); else R.prev(); AR.onUserTurn(); return;
  }
  if (Math.abs(dx) < 12 && Math.abs(dy) < 12 && Date.now() - stt < 450) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a) return;
    var sel = window.getSelection(); if (sel && sel.toString().length > 0) return;
    var w = W(), x = t.clientX;
    if (x < w * 0.25) { R.prev(); AR.onUserTurn(); }
    else if (x > w * 0.75) { R.next(); AR.onUserTurn(); }
    else AR.onTap(1);
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
