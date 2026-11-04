@file:OptIn(ExperimentalMaterial3Api::class)

package com.brenyd.audiread.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.data.SettingsStore
import com.brenyd.audiread.playback.NowPlaying
import com.brenyd.audiread.playback.PlayerConnection
import com.brenyd.audiread.playback.PlayerUiState
import com.brenyd.audiread.util.Util
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

fun placeholderColor(seed: String): Color {
    val hue = (abs(seed.hashCode()) % 360).toFloat()
    return Color.hsv(hue, 0.42f, 0.42f)
}

@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(placeholderColor(book.title))) {
        val path = book.coverPath
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    book.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, maxLines = 5, overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(book.author, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
        }
    }
}

/** Circular-arrow icon with the number of seconds in the middle. */
@Composable
fun SeekIcon(seconds: Int, forward: Boolean, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Replay, contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
            modifier = Modifier.size(size).graphicsLayer { scaleX = if (forward) -1f else 1f },
        )
        Text(
            "$seconds", fontSize = (size.value * 0.26f).sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = (size.value * 0.14f).dp),
        )
    }
}

@Composable
fun TransportControls(
    state: PlayerUiState,
    big: Boolean,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pb by SettingsStore.playback.collectAsState()
    val icon = if (big) 34.dp else 26.dp
    val btn = if (big) 60.dp else 44.dp
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { PlayerConnection.prevChapter() }, modifier = Modifier.size(btn)) {
            Icon(Icons.Filled.SkipPrevious, "Previous chapter", Modifier.size(icon))
        }
        IconButton(onClick = { PlayerConnection.rewind() }, modifier = Modifier.size(btn)) {
            SeekIcon(pb.backSec, forward = false, size = icon)
        }
        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(if (big) 80.dp else 52.dp)) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (state.isPlaying) "Pause" else "Play",
                Modifier.size(if (big) 46.dp else 30.dp),
            )
        }
        IconButton(onClick = { PlayerConnection.forward() }, modifier = Modifier.size(btn)) {
            SeekIcon(pb.fwdSec, forward = true, size = icon)
        }
        IconButton(onClick = { PlayerConnection.nextChapter() }, modifier = Modifier.size(btn)) {
            Icon(Icons.Filled.SkipNext, "Next chapter", Modifier.size(icon))
        }
    }
}

@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val pb by SettingsStore.playback.collectAsState()
    var v by remember { mutableFloatStateOf(pb.speed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Playback speed") },
        text = {
            Column {
                Text(String.format(Locale.US, "%.2fx", v), style = MaterialTheme.typography.headlineMedium)
                Slider(
                    value = v,
                    onValueChange = { v = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { PlayerConnection.setSpeed(v) },
                    valueRange = 0.5f..3.5f,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f, 2.5f).forEach { s ->
                        FilterChip(
                            selected = abs(v - s) < 0.01f,
                            onClick = { v = s; PlayerConnection.setSpeed(s) },
                            label = { Text(if (s == 1f) "1x" else "${s}x") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Skip silence")
                        Text("Trims pauses in narration", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = pb.skipSilence, onCheckedChange = { PlayerConnection.setSkipSilence(it) })
                }
                Spacer(Modifier.height(12.dp))
                Text("Skip back / forward", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 30).forEach { s ->
                        FilterChip(selected = pb.backSec == s, onClick = { SettingsStore.setPlayback(pb.copy(backSec = s)) }, label = { Text("-${s}s") })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 15, 30, 60).forEach { s ->
                        FilterChip(selected = pb.fwdSec == s, onClick = { SettingsStore.setPlayback(pb.copy(fwdSec = s)) }, label = { Text("+${s}s") })
                    }
                }
            }
        },
    )
}

@Composable
fun SleepDialog(onDismiss: () -> Unit) {
    val sleep by NowPlaying.sleep.collectAsState()
    val options = listOf(5, 10, 15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Sleep timer") },
        text = {
            Column {
                val s = sleep
                if (s != null) {
                    val label = if (s.endOfChapter) "Stops at the end of this chapter"
                    else "Stops in ${Util.formatTime((s.endsAt - System.currentTimeMillis()).coerceAtLeast(0))}"
                    Text(label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                    TextButton(onClick = { PlayerConnection.sleep(0); onDismiss() }) { Text("Turn off timer") }
                }
                TextButton(onClick = { PlayerConnection.sleep(-1); onDismiss() }) { Text("End of chapter") }
                options.forEach { m ->
                    TextButton(onClick = { PlayerConnection.sleep(m); onDismiss() }) {
                        Text(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60}h ${m % 60}m")
                    }
                }
            }
        },
    )
}

@Composable
fun ChapterSheet(book: Book, currentIndex: Int, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val chapters = book.audio?.chapters ?: emptyList()
    val total = book.audio?.totalMs ?: 0L
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(chapters) { i, ch ->
                val len = (if (i + 1 < chapters.size) chapters[i + 1].startMs else total) - ch.startMs
                val current = i == currentIndex
                ListItem(
                    headlineContent = {
                        Text(ch.title, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = { Text("${Util.formatTime(ch.startMs)} · ${Util.formatDuration(len)}") },
                    leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp)) },
                    colors = if (current) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                    modifier = Modifier.clickable { onPick(ch.startMs) },
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

fun chapterTitle(book: Book?, index: Int): String = book?.audio?.chapters?.getOrNull(index)?.title ?: ""

@Composable
fun MiniPlayer(onOpen: (String) -> Unit) {
    val st by PlayerConnection.state.collectAsState()
    val books by LibraryRepository.books.collectAsState()
    val id = st.bookId ?: return
    val book = books.firstOrNull { it.id == id } ?: return
    val pb by SettingsStore.playback.collectAsState()
    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (st.totalMs > 0) (st.globalMs.toFloat() / st.totalMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(book, Modifier.size(width = 40.dp, height = 58.dp), corner = 4.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        chapterTitle(book, st.chapterIndex), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { PlayerConnection.rewind() }) { SeekIcon(pb.backSec, forward = false, size = 26.dp) }
                IconButton(onClick = { PlayerConnection.togglePlay() }) {
                    Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(30.dp))
                }
            }
        }
    }
}
