@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.brenyd.audiread.ui.reader

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.BookFormat
import com.brenyd.audiread.data.Bookmark
import com.brenyd.audiread.data.EbookInfo
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.data.SettingsStore
import com.brenyd.audiread.playback.ChapterMath
import com.brenyd.audiread.playback.PlayerConnection
import com.brenyd.audiread.playback.PlayerUiState
import com.brenyd.audiread.sync.SyncMapper
import com.brenyd.audiread.ui.components.SpeedDialog
import com.brenyd.audiread.ui.components.TransportControls
import com.brenyd.audiread.ui.detail.SyncPointsDialog
import com.brenyd.audiread.util.Util
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(bookId: String, withAudio: Boolean, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val book = remember(bookId) { LibraryRepository.bookFlow(bookId) }.collectAsState(LibraryRepository.get(bookId)).value
    val ebook = book?.ebook
    if (book == null || ebook == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onBack) { Text("This book has no ebook — go back") }
        }
        return
    }
    ReaderContent(book, ebook, withAudio && book.audio != null, onBack, onOpenPlayer)
}

private fun initialPosition(book: Book, ebook: EbookInfo, audioOn: Boolean): ReadPosition {
    if (!audioOn) return book.readPos
    val ps = PlayerConnection.state.value
    val playingThis = ps.bookId == book.id
    return if (playingThis || book.lastListenAt > book.lastReadAt) {
        val ms = if (playingThis) ps.globalMs else book.listenMs
        SyncMapper.textLocal(ebook, SyncMapper.audioToText(book, ms))
    } else book.readPos
}

private fun Book.bookmarkNear(pos: ReadPosition, pageSpan: Double): Bookmark? =
    bookmarks.firstOrNull { it.section == pos.section && abs(it.progression - pos.progression) < pageSpan * 0.9 }

@Composable
private fun ReaderContent(book: Book, ebook: EbookInfo, audioOn: Boolean, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(book)
    val settings by SettingsStore.reader.collectAsState()
    val player by PlayerConnection.state.collectAsState()
    val scope = rememberCoroutineScope()

    val initial = remember(book.id) { initialPosition(book, ebook, audioOn) }
    var position by remember(book.id) { mutableStateOf(initial) }
    var controls by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSyncPoints by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var returnTo by remember { mutableStateOf<ReadPosition?>(null) }

    LaunchedEffect(book.id) {
        val now = System.currentTimeMillis()
        LibraryRepository.update(book.id) { it.copy(lastOpenedAt = now) }
        if (audioOn && PlayerConnection.state.value.bookId != book.id) {
            val start = if (book.lastListenAt > book.lastReadAt) book.listenMs else SyncMapper.textToAudio(book, initial.global)
            PlayerConnection.playBook(book, start, play = false)
        }
    }

    val onPosition: (Int, Double) -> Unit = remember(book.id) {
        { s, p ->
            val g = SyncMapper.textGlobal(ebook, s, p)
            val pos = ReadPosition(s, p, g)
            position = pos
            LibraryRepository.update(book.id) { it.copy(readPos = pos, lastReadAt = System.currentTimeMillis()) }
        }
    }
    val onUserTurn: () -> Unit = {
        if (audioOn && PlayerConnection.state.value.isPlaying) follow = false
        if (controls) controls = false
    }
    val toggleControls: () -> Unit = { controls = !controls }

    val handle: ReaderHandle
    var webState: WebReaderState? = null
    if (ebook.fixedLayout) {
        val pager = rememberPagerState(
            initialPage = initial.section.coerceIn(0, (ebook.pageCount - 1).coerceAtLeast(0)),
            pageCount = { ebook.pageCount.coerceAtLeast(1) },
        )
        val st = remember(book.id) { PagedReaderState(ebook, pager, scope) }
        st.onPosition = onPosition
        st.onUserTurn = onUserTurn
        handle = st
    } else {
        val st = remember(book.id) { WebReaderState(ebook, initial, scope) }
        st.onPosition = onPosition
        st.onUserTurn = onUserTurn
        st.onTapCenter = toggleControls
        handle = st
        webState = st
    }
    // Jumps that should offer "Back to page …"
    val jumpTo: (() -> Unit) -> Unit = { action ->
        returnTo = position
        action()
        if (audioOn) follow = false
        controls = false
    }

    // Follow the narration.
    val audioSecond = player.globalMs / 1000
    LaunchedEffect(audioSecond, follow, player.isPlaying) {
        if (audioOn && follow && player.isPlaying && player.bookId == book.id) {
            handle.follow(SyncMapper.audioToText(latest, player.globalMs))
        }
    }

    // Immersive reading and brightness.
    val view = LocalView.current
    DisposableEffect(controls) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controls) ctl?.show(WindowInsetsCompat.Type.systemBars())
        else {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        }
        ctl?.isAppearanceLightStatusBars = !settings.theme.dark
        onDispose { }
    }
    DisposableEffect(settings.brightness) {
        val window = (view.context as? Activity)?.window
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = if (settings.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else settings.brightness.coerceIn(0.01f, 1f)
            w.attributes = lp
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as? Activity)?.window
            window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
                val lp = it.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = lp
            }
        }
    }

    val bg = Color(settings.theme.bg)
    val fg = Color(settings.theme.fg)
    val info = handle.pageInfo()
    val chapterTitle = if (ebook.fixedLayout) "" else
        ebook.toc.lastOrNull { it.section <= position.section }?.title ?: ebook.sections.getOrNull(position.section)?.title ?: ""
    val pageLabel = when {
        info.total > 0 -> "${info.page} of ${info.total}"
        else -> "${info.page}%"
    }
    val pageSpan = webState?.let { 1.0 / it.pageCount.coerceAtLeast(1) } ?: 0.5
    val bookmark = book.bookmarkNear(position, pageSpan)

    fun toggleBookmark() {
        val existing = book.bookmarkNear(position, pageSpan)
        LibraryRepository.update(book.id) { b ->
            if (existing != null) b.copy(bookmarks = b.bookmarks - existing)
            else b.copy(bookmarks = (b.bookmarks + Bookmark(position.section, position.progression, position.global,
                chapterTitle.ifBlank { "Page ${info.page}" })).sortedBy { it.global })
        }
    }

    fun jumpChapter(delta: Int) {
        if (ebook.fixedLayout || ebook.toc.isEmpty()) {
            if (ebook.fixedLayout) handle.goToGlobal(((position.section + delta * 10).coerceIn(0, ebook.pageCount - 1)).toDouble() / ebook.pageCount.coerceAtLeast(1))
            else handle.goToGlobal(SyncMapper.sectionStart(ebook, (position.section + delta).coerceIn(0, ebook.sections.size - 1)))
            return
        }
        val toc = ebook.toc.distinctBy { it.section }.sortedBy { it.section }
        val cur = toc.indexOfLast { it.section <= position.section }
        val target = if (delta > 0) toc.getOrNull(cur + 1) else {
            if (cur >= 0 && position.progression > 0.02) toc[cur] else toc.getOrNull(cur - 1)
        }
        target?.let { handle.goToToc(it) }
    }

    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)) {
            when (ebook.format) {
                BookFormat.PDF -> PdfReaderView(handle as PagedReaderState, ebook, settings.theme.dark, pagedTaps(handle, toggleControls, onUserTurn))
                BookFormat.CBZ -> ComicReaderView(handle as PagedReaderState, ebook, pagedTaps(handle, toggleControls, onUserTurn))
                else -> WebReaderView(handle as WebReaderState, settings, Modifier.fillMaxSize())
            }

            // Running head and folio, drawn in the page margins like a printed book.
            if (!ebook.fixedLayout) {
                Text(
                    chapterTitle, color = fg.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 48.dp, end = 48.dp),
                )
            }
            Text(
                pageLabel, color = fg.copy(alpha = 0.5f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
            if (bookmark != null) {
                Icon(
                    Icons.Filled.Bookmark, "Bookmarked", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp).size(30.dp),
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = controls, modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it },
            ) {
                Surface(tonalElevation = 2.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (book.author.isNotBlank()) {
                                Text(book.author, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.FormatSize, "Display options") }
                        if (!ebook.fixedLayout) {
                            IconButton(onClick = { toggleBookmark() }) {
                                Icon(if (bookmark != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    if (bookmark != null) "Remove bookmark" else "Add bookmark")
                            }
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (book.audio != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (audioOn) "Open player" else "Listen to audiobook") },
                                        leadingIcon = { Icon(Icons.Filled.Headphones, null) },
                                        onClick = { showMenu = false; onOpenPlayer() },
                                    )
                                }
                                if (audioOn) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Play audio from this page") },
                                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                                        onClick = {
                                            showMenu = false
                                            PlayerConnection.seekGlobal(SyncMapper.textToAudio(latest, position.global))
                                            PlayerConnection.play()
                                            follow = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Go to where the audio is") },
                                        leadingIcon = { Icon(Icons.Filled.MyLocation, null) },
                                        onClick = {
                                            showMenu = false
                                            handle.goToGlobal(SyncMapper.audioToText(latest, PlayerConnection.state.value.globalMs))
                                            follow = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Pin: narrator is at the top of this page") },
                                        leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                                        onClick = {
                                            showMenu = false
                                            val ms = PlayerConnection.state.value.globalMs
                                            val t = position.global
                                            LibraryRepository.update(book.id) { SyncMapper.withManualAnchor(it, t, ms) }
                                            follow = true
                                            Toast.makeText(ctx, "Sync point saved at ${Util.formatTime(ms)}", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Manage sync points…") },
                                        leadingIcon = { Icon(Icons.Filled.Tune, null) },
                                        onClick = { showMenu = false; showSyncPoints = true },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = controls, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            ) {
                BottomBar(
                    global = position.global,
                    chapterTitle = chapterTitle,
                    info = info,
                    fixedLayout = ebook.fixedLayout,
                    padNav = !audioOn,
                    onContents = { showContents = true },
                    onSeek = { t -> jumpTo { handle.goToGlobal(t) } },
                    onPrev = { jumpChapter(-1); onUserTurn() },
                    onNext = { jumpChapter(1); onUserTurn() },
                )
            }

            val back = returnTo
            if (back != null && !controls && abs(back.global - position.global) > 0.0005) {
                Surface(
                    shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 34.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            returnTo = null
                            handle.goToGlobal(back.global)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                            Spacer(Modifier.width(6.dp))
                            Text("Back to ${(back.global * 100).roundToInt()}%", color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = { returnTo = null }, modifier = Modifier.size(32.dp)) {
                            Text("✕", color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                }
            }

            if (audioOn && !follow) {
                Surface(
                    shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (controls) 150.dp else 34.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            handle.goToGlobal(SyncMapper.audioToText(latest, PlayerConnection.state.value.globalMs))
                            follow = true
                        }) {
                            Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("To audio")
                        }
                        TextButton(onClick = {
                            PlayerConnection.seekGlobal(SyncMapper.textToAudio(latest, position.global))
                            PlayerConnection.play()
                            follow = true
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Play here")
                        }
                    }
                }
            }
        }
        if (audioOn) {
            AudioDock(
                book = book, state = player, follow = follow,
                onFollow = {
                    follow = !follow
                    if (follow) handle.goToGlobal(SyncMapper.audioToText(latest, PlayerConnection.state.value.globalMs))
                },
                onOpenPlayer = onOpenPlayer,
                onSpeed = { showSpeed = true },
            )
        }
    }

    if (showContents) {
        ContentsSheet(
            book = book, ebook = ebook, position = position,
            onDismiss = { showContents = false },
            onToc = { e -> showContents = false; jumpTo { handle.goToToc(e) } },
            onBookmark = { b -> showContents = false; jumpTo { handle.goToGlobal(b.global) } },
        )
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, scrimColor = Color.Transparent) {
            ReaderSettingsPanel(settings, ebook.fixedLayout) { SettingsStore.setReader(it) }
        }
    }
    if (showSyncPoints) SyncPointsDialog(book) { showSyncPoints = false }
    if (showSpeed) SpeedDialog { showSpeed = false }
}

/** Side taps turn pages in fixed-layout readers; the centre toggles the controls. */
private fun pagedTaps(handle: ReaderHandle, toggle: () -> Unit, onUserTurn: () -> Unit): (Int) -> Unit = { z ->
    when (z) {
        0 -> { handle.prev(); onUserTurn() }
        2 -> { handle.next(); onUserTurn() }
        else -> toggle()
    }
}

@Composable
private fun BottomBar(
    global: Double,
    chapterTitle: String,
    info: PageInfo,
    fixedLayout: Boolean,
    padNav: Boolean,
    onContents: () -> Unit,
    onSeek: (Double) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().then(if (padNav) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            val d = drag
            val headline = when {
                d != null && info.total > 0 -> "Page ${(d * info.total).roundToInt().coerceIn(1, info.total)} of ${info.total}"
                d != null -> "${(d * 100).roundToInt()}%"
                else -> chapterTitle.ifBlank { if (info.total > 0) "Page ${info.page} of ${info.total}" else "" }
            }
            Text(
                headline, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onContents) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
                Slider(
                    value = drag ?: global.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { onSeek(it.toDouble()) }; drag = null },
                    colors = SliderDefaults.colors(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val detail = buildString {
                    if (info.total > 0) append("Page ${info.page} of ${info.total}") else append("${info.page}%")
                    if (!fixedLayout && info.total > 0) {
                        append(" · ")
                        append(if (info.leftInChapter == 0) "Last page in chapter" else "${info.leftInChapter} pages left in chapter")
                    }
                }
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onPrev) { Text("‹ Prev") }
                TextButton(onClick = onNext) { Text("Next ›") }
            }
        }
    }
}

@Composable
private fun ContentsSheet(
    book: Book,
    ebook: EbookInfo,
    position: ReadPosition,
    onDismiss: () -> Unit,
    onToc: (com.brenyd.audiread.data.TocEntry) -> Unit,
    onBookmark: (Bookmark) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Bookmarks (${book.bookmarks.size})") })
        }
        if (tab == 0) {
            if (ebook.toc.isEmpty()) {
                Text(
                    if (ebook.fixedLayout) "Use the slider to jump to any page." else "This book has no table of contents.",
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                val cur = ebook.toc.indexOfLast { it.section <= position.section }
                LazyColumn(state = rememberLazyListState((cur - 3).coerceAtLeast(0))) {
                    itemsIndexed(ebook.toc) { i, e ->
                        ListItem(
                            headlineContent = {
                                Text(e.title, fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                Text("${(SyncMapper.sectionStart(ebook, e.section) * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall)
                            },
                            colors = if (i == cur) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            else ListItemDefaults.colors(),
                            modifier = Modifier.clickable { onToc(e) }.padding(start = (e.depth * 16).dp),
                        )
                    }
                }
            }
        } else {
            if (book.bookmarks.isEmpty()) {
                Text("Tap the bookmark icon at the top of a page to save your place.", modifier = Modifier.padding(20.dp))
            } else {
                val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                LazyColumn {
                    items(book.bookmarks) { b ->
                        ListItem(
                            headlineContent = { Text(b.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${(b.global * 100).roundToInt()}% · ${fmt.format(Date(b.createdAt))}") },
                            leadingContent = { Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                IconButton(onClick = {
                                    LibraryRepository.update(book.id) { it.copy(bookmarks = it.bookmarks - b) }
                                }) { Icon(Icons.Filled.Delete, "Delete bookmark") }
                            },
                            modifier = Modifier.clickable { onBookmark(b) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
private fun AudioDock(
    book: Book,
    state: PlayerUiState,
    follow: Boolean,
    onFollow: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSpeed: () -> Unit,
) {
    val audio = book.audio ?: return
    val loaded = state.bookId == book.id
    val g = if (loaded) state.globalMs else book.listenMs
    val ci = if (loaded) state.chapterIndex else ChapterMath.indexAt(audio.chapters, g)
    val start = ChapterMath.start(audio.chapters, ci)
    val end = ChapterMath.end(audio.chapters, ci, audio.totalMs).coerceAtLeast(start + 1)
    Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    audio.chapters.getOrNull(ci)?.title ?: book.title, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text("${Util.formatTime((g - start).coerceAtLeast(0))} / ${Util.formatTime(end - start)}", style = MaterialTheme.typography.labelSmall)
            }
            LinearProgressIndicator(
                progress = { ((g - start).toFloat() / (end - start)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onSpeed, modifier = Modifier.width(56.dp)) {
                    val sp = if (loaded) state.speed else SettingsStore.playback.value.speed
                    Text(String.format(Locale.US, "%.2f", sp).trimEnd('0').trimEnd('.') + "x", style = MaterialTheme.typography.labelLarge)
                }
                TransportControls(
                    state, big = false,
                    onPlayPause = { if (loaded) PlayerConnection.togglePlay() else PlayerConnection.playBook(book, null, play = true) },
                    modifier = Modifier.weight(1f),
                )
                IconToggleButton(checked = follow, onCheckedChange = { onFollow() }) {
                    Icon(
                        Icons.Filled.MyLocation, if (follow) "Following narration" else "Follow narration",
                        tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
