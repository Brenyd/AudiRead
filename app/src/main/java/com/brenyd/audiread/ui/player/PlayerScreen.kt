@file:OptIn(ExperimentalMaterial3Api::class)

package com.brenyd.audiread.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.playback.ChapterMath
import com.brenyd.audiread.playback.NowPlaying
import com.brenyd.audiread.playback.PlayerConnection
import com.brenyd.audiread.ui.components.BookCover
import com.brenyd.audiread.ui.components.ChapterSheet
import com.brenyd.audiread.ui.components.SleepDialog
import com.brenyd.audiread.ui.components.SpeedDialog
import com.brenyd.audiread.ui.components.TransportControls
import com.brenyd.audiread.ui.components.placeholderColor
import com.brenyd.audiread.util.Util
import java.util.Locale

@Composable
fun PlayerScreen(bookId: String, onBack: () -> Unit, onReadAlong: (String) -> Unit) {
    val st by PlayerConnection.state.collectAsState()
    val books by LibraryRepository.books.collectAsState()
    val sleep by NowPlaying.sleep.collectAsState()
    val activeId = st.bookId ?: bookId
    val book = books.firstOrNull { it.id == activeId }
    val audio = book?.audio
    if (book == null || audio == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onBack) { Text("Nothing playing — go back") }
        }
        return
    }
    val loaded = st.bookId == book.id
    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    val chapters = audio.chapters
    val total = audio.totalMs
    val g = if (loaded) st.globalMs else book.listenMs
    val ci = if (loaded) st.chapterIndex else ChapterMath.indexAt(chapters, g)
    val chStart = ChapterMath.start(chapters, ci)
    val chEnd = ChapterMath.end(chapters, ci, total).coerceAtLeast(chStart + 1)
    val chLen = chEnd - chStart
    val inCh = (g - chStart).coerceIn(0, chLen)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now listening", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Close") } },
                actions = {
                    if (book.ebook != null) {
                        IconButton(onClick = { onReadAlong(book.id) }) { Icon(Icons.Filled.AutoStories, "Read along") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(placeholderColor(book.title).copy(alpha = 0.5f), MaterialTheme.colorScheme.background))
            )
        ) {
            Column(
                Modifier.fillMaxSize().padding(pad).navigationBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    BookCover(
                        book,
                        Modifier.fillMaxHeight().aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                            .shadow(16.dp, MaterialTheme.shapes.medium),
                        corner = 12.dp,
                    )
                }
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    chapters.getOrNull(ci)?.title ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showChapters = true }.padding(4.dp),
                )

                Slider(
                    value = dragging ?: (inCh.toFloat() / chLen),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { f ->
                            val target = chStart + (f * chLen).toLong()
                            if (loaded) PlayerConnection.seekGlobal(target) else PlayerConnection.playBook(book, target, play = false)
                        }
                        dragging = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    val shown = dragging?.let { (it * chLen).toLong() } ?: inCh
                    Text(Util.formatTime(shown), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("-" + Util.formatTime(chLen - shown), style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(8.dp))
                TransportControls(
                    st, big = true,
                    onPlayPause = { if (loaded) PlayerConnection.togglePlay() else PlayerConnection.playBook(book, null, play = true) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSpeed = true }) {
                        val sp = if (loaded) st.speed else 1f
                        Text(String.format(Locale.US, "%.2f", sp).trimEnd('0').trimEnd('.') + "x",
                            style = MaterialTheme.typography.titleSmall)
                    }
                    IconButton(onClick = { showSleep = true }) {
                        Icon(Icons.Filled.Bedtime, "Sleep timer",
                            tint = if (sleep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showChapters = true }) { Icon(Icons.AutoMirrored.Filled.List, "Chapters") }
                }
                val pct = if (total > 0) (g * 100 / total).toInt() else 0
                Text(
                    "Chapter ${ci + 1} of ${chapters.size}  ·  $pct%  ·  ${Util.formatDuration((total - g).coerceAtLeast(0))} left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }

    if (showChapters) {
        ChapterSheet(book, ci, onDismiss = { showChapters = false }) { ms ->
            if (loaded) {
                PlayerConnection.seekGlobal(ms)
                PlayerConnection.play()
            } else PlayerConnection.playBook(book, ms, play = true)
            showChapters = false
        }
    }
    if (showSpeed) SpeedDialog { showSpeed = false }
    if (showSleep) SleepDialog { showSleep = false }
}
