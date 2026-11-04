@file:OptIn(ExperimentalMaterial3Api::class)

package com.brenyd.audiread.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.data.ReadPosition
import com.brenyd.audiread.importer.ImportManager
import com.brenyd.audiread.playback.PlayerConnection
import com.brenyd.audiread.sync.SyncMapper
import com.brenyd.audiread.ui.components.BookCover
import com.brenyd.audiread.ui.components.MiniPlayer
import com.brenyd.audiread.ui.components.placeholderColor
import com.brenyd.audiread.ui.library.persistRead
import com.brenyd.audiread.util.Util
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush

@Composable
fun BookDetailScreen(bookId: String, onBack: () -> Unit, onRead: (Boolean) -> Unit, onListen: () -> Unit) {
    val ctx = LocalContext.current
    val book = remember(bookId) { LibraryRepository.bookFlow(bookId) }.collectAsState(LibraryRepository.get(bookId)).value
    if (book == null) {
        LaunchedOnce(onBack)
        return
    }
    val player by PlayerConnection.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var syncPoints by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    val linkAudioFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { uris.forEach { persistRead(ctx, it) }; ImportManager.importAudioFiles(ctx, uris, book.id) }
    }
    val linkAudioFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persistRead(ctx, uri); ImportManager.importAudioFolder(ctx, uri, book.id) }
    }
    val linkEbook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persistRead(ctx, uri); ImportManager.attachEbook(ctx, uri, book.id) }
    }
    val audioTypes = arrayOf("audio/*", "video/mp4", "application/octet-stream")

    fun listen() {
        PlayerConnection.ensure(book, play = true)
        onListen()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit title & author") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; editing = true })
                        DropdownMenuItem(text = { Text(if (book.audio == null) "Add audiobook files" else "Replace audiobook (files)") },
                            leadingIcon = { Icon(Icons.Filled.Headphones, null) },
                            onClick = { menu = false; linkAudioFiles.launch(audioTypes) })
                        DropdownMenuItem(text = { Text(if (book.audio == null) "Add audiobook folder" else "Replace audiobook (folder)") },
                            leadingIcon = { Icon(Icons.Filled.Headphones, null) },
                            onClick = { menu = false; linkAudioFolder.launch(null) })
                        DropdownMenuItem(text = { Text(if (book.ebook == null) "Add ebook" else "Replace ebook") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                            onClick = { menu = false; linkEbook.launch(arrayOf("*/*")) })
                        if (book.ebook != null && book.audio != null) {
                            DropdownMenuItem(text = { Text("Sync points") }, leadingIcon = { Icon(Icons.Filled.Sync, null) },
                                onClick = { menu = false; syncPoints = true })
                        }
                        DropdownMenuItem(text = { Text("Remove from library") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onOpen = { listen() }) },
    ) { pad ->
        val chapters = book.audio?.chapters ?: emptyList()
        val toc = book.ebook?.toc ?: emptyList()
        val showTabs = chapters.isNotEmpty() && toc.isNotEmpty()
        val listTab = if (showTabs) tab else if (chapters.isNotEmpty()) 0 else 1

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(listOf(placeholderColor(book.title).copy(alpha = 0.55f), Color.Transparent))
                    ).padding(top = pad.calculateTopPadding()),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BookCover(book, Modifier.width(170.dp).aspectRatio(2f / 3f).shadow(12.dp, MaterialTheme.shapes.medium), corner = 10.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        if (book.author.isNotBlank()) {
                            Text(book.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(6.dp))
                        val facts = buildList {
                            book.ebook?.let { e ->
                                add(e.format.label + if (e.fixedLayout) " · ${e.pageCount} pages" else if (e.toc.isNotEmpty()) " · ${e.toc.size} sections" else "")
                            }
                            book.audio?.let { a -> add("${Util.formatDuration(a.totalMs)} audio · ${a.chapters.size} chapters") }
                        }
                        Text(facts.joinToString("   •   "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard("Listen", Icons.Filled.Headphones, book.audio != null, Modifier.weight(1f)) { listen() }
                    ModeCard("Read", Icons.AutoMirrored.Filled.MenuBook, book.ebook != null, Modifier.weight(1f)) { onRead(false) }
                    ModeCard("Read + Listen", Icons.Filled.AutoStories, book.ebook != null && book.audio != null, Modifier.weight(1f)) { onRead(true) }
                }
            }

            if (book.audio == null || book.ebook == null) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Link, null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (book.audio == null) "Have the audiobook? Link it to read and listen together."
                                    else "Have the ebook? Link it to read along while you listen.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row {
                                if (book.audio == null) {
                                    TextButton(onClick = { linkAudioFiles.launch(audioTypes) }) { Text("Pick files") }
                                    TextButton(onClick = { linkAudioFolder.launch(null) }) { Text("Pick folder") }
                                } else {
                                    TextButton(onClick = { linkEbook.launch(arrayOf("*/*")) }) { Text("Pick ebook") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    if (book.ebook != null) {
                        ProgressLine("Reading", book.readProgress, "${(book.readProgress * 100).toInt()}%")
                    }
                    if (book.audio != null) {
                        val total = book.audio.totalMs
                        ProgressLine(
                            "Listening", book.listenProgress,
                            "${Util.formatTime(book.listenMs)} of ${Util.formatTime(total)}",
                        )
                    }
                }
            }

            if (showTabs) {
                item {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Audio chapters") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Contents") })
                    }
                }
            }
            if (listTab == 0 && chapters.isNotEmpty()) {
                if (!showTabs) item { SectionHeader("Audio chapters") }
                val currentCh = if (player.bookId == book.id) player.chapterIndex else -1
                itemsIndexed(chapters) { i, ch ->
                    val total = book.audio?.totalMs ?: 0L
                    val len = (if (i + 1 < chapters.size) chapters[i + 1].startMs else total) - ch.startMs
                    ListItem(
                        headlineContent = {
                            Text(ch.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (i == currentCh) FontWeight.Bold else FontWeight.Normal)
                        },
                        supportingContent = { Text(Util.formatDuration(len)) },
                        leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp)) },
                        modifier = Modifier.clickable {
                            PlayerConnection.playBook(book, ch.startMs, play = true)
                            onListen()
                        },
                    )
                }
            } else if (toc.isNotEmpty()) {
                if (!showTabs) item { SectionHeader("Contents") }
                itemsIndexed(toc) { _, entry ->
                    ListItem(
                        headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier
                            .clickable {
                                val e = book.ebook
                                if (e != null) {
                                    val g = SyncMapper.textGlobal(e, entry.section, 0.0)
                                    LibraryRepository.update(book.id) {
                                        it.copy(readPos = ReadPosition(entry.section, 0.0, g), lastReadAt = System.currentTimeMillis())
                                    }
                                }
                                onRead(false)
                            }
                            .padding(start = (entry.depth * 16).dp),
                    )
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    if (editing) EditDialog(book) { editing = false }
    if (syncPoints) SyncPointsDialog(book) { syncPoints = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove \"${book.title}\"?") },
            text = { Text("Your original files are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (PlayerConnection.state.value.bookId == book.id) PlayerConnection.stop()
                    LibraryRepository.remove(book.id)
                    onBack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LaunchedOnce(action: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { action() }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun ProgressLine(label: String, value: Float, detail: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun ModeCard(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(96.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(30.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun EditDialog(book: Book, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                LibraryRepository.update(book.id) { it.copy(title = title.trim().ifEmpty { it.title }, author = author.trim()) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SyncPointsDialog(book: Book, onDismiss: () -> Unit) {
    val auto = remember(book.id) { SyncMapper.autoAnchors(book).size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync points") },
        text = {
            Column {
                Text(
                    "Text and audio are lined up using the start and end of the book" +
                        (if (auto > 0) ", $auto matched chapters" else "") +
                        " and any points you pin while reading (Read + Listen → sync menu → Pin).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                if (book.syncAnchors.isEmpty()) {
                    Text("No pinned sync points yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    book.syncAnchors.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Text ${String.format(java.util.Locale.US, "%.1f", a.text * 100)}%  ↔  ${Util.formatTime(a.audioMs)}",
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                LibraryRepository.update(book.id) { b -> b.copy(syncAnchors = b.syncAnchors.filterNot { it == a }) }
                            }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (book.syncAnchors.isNotEmpty()) {
                TextButton(onClick = { LibraryRepository.update(book.id) { it.copy(syncAnchors = emptyList()) } }) { Text("Clear all") }
            }
        },
    )
}
