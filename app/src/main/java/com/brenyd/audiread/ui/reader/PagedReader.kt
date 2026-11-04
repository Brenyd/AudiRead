@file:OptIn(ExperimentalFoundationApi::class)

package com.brenyd.audiread.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.TocEntry
import com.brenyd.audiread.sync.SyncMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Fixed-layout reader state (PDF and comics): one pager page per book page. */
class PagedReaderState(
    private val ebook: EbookInfo,
    val pager: PagerState,
    private val scope: CoroutineScope,
) : ReaderHandle {
    var onPosition: (Int, Double) -> Unit = { _, _ -> }
    var onUserTurn: () -> Unit = {}
    var zoomed by mutableStateOf(false)

    val count: Int get() = ebook.pageCount.coerceAtLeast(1)

    override fun goToGlobal(t: Double) {
        val p = SyncMapper.textLocal(ebook, t).section
        scope.launch { pager.scrollToPage(p) }
    }

    override fun goToToc(e: TocEntry) {
        scope.launch { pager.scrollToPage(e.section.coerceIn(0, count - 1)) }
    }

    override fun next() {
        if (pager.currentPage < count - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
    }

    override fun prev() {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    override fun follow(t: Double) {
        val p = SyncMapper.textLocal(ebook, t).section
        if (p != pager.currentPage && !pager.isScrollInProgress) scope.launch { pager.animateScrollToPage(p) }
    }

    override fun pageInfo(): PageInfo = PageInfo(pager.currentPage + 1, count, 0)
}

private val invert = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

@Composable
private fun TrackPager(state: PagedReaderState) {
    LaunchedEffect(state) {
        snapshotFlow { state.pager.currentPage }.collect { state.onPosition(it, 0.0) }
    }
    LaunchedEffect(state) {
        state.pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) state.onUserTurn() }
    }
}

@Composable
private fun ZoomablePage(state: PagedReaderState, onTapZone: (Int) -> Unit, content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomed = scale > 1.01f
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        state.zoomed = scale > 1.01f
                    },
                    onTap = { pos ->
                        val w = size.width
                        onTapZone(if (pos.x < w * 0.25f) 0 else if (pos.x > w * 0.75f) 2 else 1)
                    },
                )
            }
            .then(
                if (zoomed) Modifier.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                        state.zoomed = scale > 1.01f
                    }
                } else Modifier
            ),
    ) {
        content(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@Composable
fun PdfReaderView(state: PagedReaderState, ebook: EbookInfo, night: Boolean, onTapZone: (Int) -> Unit) {
    val ctx = LocalContext.current
    val renderer = remember(ebook.sourceUri) {
        runCatching {
            ctx.contentResolver.openFileDescriptor(Uri.parse(ebook.sourceUri), "r")?.let { PdfRenderer(it) }
        }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't open this PDF. If the file was moved, re-add it from the book's menu.")
        }
        return
    }
    TrackPager(state)
    val mutex = remember { Mutex() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        HorizontalPager(state = state.pager, userScrollEnabled = !state.zoomed, key = { it }) { page ->
            val bmp by produceState<Bitmap?>(null, page, widthPx) {
                value = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        runCatching {
                            val p = renderer.openPage(page)
                            try {
                                val w = (widthPx * 1.6f).toInt().coerceIn(400, 2600)
                                val h = (w * p.height.toFloat() / p.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                b.eraseColor(android.graphics.Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            } finally {
                                p.close()
                            }
                        }.getOrNull()
                    }
                }
            }
            ZoomablePage(state, onTapZone) { m ->
                val b = bmp
                if (b == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else Image(
                    bitmap = b.asImageBitmap(), contentDescription = "Page ${page + 1}",
                    contentScale = ContentScale.Fit, modifier = m,
                    colorFilter = if (night) invert else null,
                )
            }
        }
    }
}

@Composable
fun ComicReaderView(state: PagedReaderState, ebook: EbookInfo, onTapZone: (Int) -> Unit) {
    TrackPager(state)
    HorizontalPager(state = state.pager, userScrollEnabled = !state.zoomed, key = { it }) { page ->
        ZoomablePage(state, onTapZone) { m ->
            val section = ebook.sections.getOrNull(page)
            if (section != null) {
                AsyncImage(
                    model = File(ebook.contentDir, section.path),
                    contentDescription = "Page ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = m,
                )
            }
        }
    }
}
