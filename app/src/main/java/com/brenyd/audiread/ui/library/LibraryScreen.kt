@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.brenyd.audiread.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brenyd.audiread.data.Book
import com.brenyd.audiread.data.LibraryRepository
import com.brenyd.audiread.importer.ImportManager
import com.brenyd.audiread.playback.PlayerConnection
import com.brenyd.audiread.ui.components.BookCover
import com.brenyd.audiread.ui.components.MiniPlayer

enum class LibFilter(val label: String) { ALL("All"), EBOOKS("Ebooks"), AUDIO("Audiobooks"), BOTH("Ebook + audio") }
enum class LibSort(val label: String) { RECENT("Recently opened"), TITLE("Title"), AUTHOR("Author"), ADDED("Date added") }

fun persistRead(ctx: Context, uri: Uri) {
    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit, onOpenPlayer: (String) -> Unit) {
    val ctx = LocalContext.current
    val books by LibraryRepository.books.collectAsState()
    val status by ImportManager.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { ImportManager.messages.collect { snackbar.showSnackbar(it) } }

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(LibFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibSort.RECENT) }
    var showSort by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Book?>(null) }

    val pickEbooks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistRead(ctx, it) }
            ImportManager.importEbooks(ctx, uris)
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistRead(ctx, it) }
            ImportManager.importAudioFiles(ctx, uris, null)
        }
    }
    val pickAudioFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistRead(ctx, uri)
            ImportManager.importAudioFolder(ctx, uri, null)
        }
    }
    val pickScan = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistRead(ctx, uri)
            ImportManager.scanFolder(ctx, uri)
        }
    }

    val visible = remember(books, query, filter, sort) {
        books.filter { b ->
            when (filter) {
                LibFilter.ALL -> true
                LibFilter.EBOOKS -> b.ebook != null
                LibFilter.AUDIO -> b.audio != null
                LibFilter.BOTH -> b.ebook != null && b.audio != null
            }
        }.filter { b ->
            query.isBlank() || b.title.contains(query, true) || b.author.contains(query, true)
        }.let { list ->
            when (sort) {
                LibSort.RECENT -> list.sortedWith(compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt })
                LibSort.TITLE -> list.sortedBy { it.title.lowercase() }
                LibSort.AUTHOR -> list.sortedWith(compareBy<Book>({ it.author.lowercase() }, { it.title.lowercase() }))
                LibSort.ADDED -> list.sortedByDescending { it.addedAt }
            }
        }
    }
    val recent = remember(books) {
        books.filter { it.lastOpenedAt > 0 && it.progress < 0.995f }.sortedByDescending { it.lastOpenedAt }.take(10)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search title or author") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("Library", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                    Box {
                        IconButton(onClick = { showSort = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                            LibSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { sort = s; showSort = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add") },
            )
        },
        bottomBar = { MiniPlayer(onOpen = onOpenPlayer) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            status?.let { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val p = s.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (books.isEmpty()) {
                EmptyLibrary(onAdd = { showAdd = true })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (recent.isNotEmpty() && query.isBlank() && filter == LibFilter.ALL) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Continue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(recent, key = { "r" + it.id }) { b -> ContinueCard(b, onOpenBook) }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibFilter.entries.forEach { f ->
                                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                            }
                        }
                    }
                    items(visible, key = { it.id }) { b ->
                        BookCard(b, onClick = { onOpenBook(b.id) }, onLongClick = { toDelete = b })
                    }
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Text("Add to library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            AddOption(Icons.AutoMirrored.Filled.MenuBook, "Ebook files", "EPUB, PDF, MOBI, AZW3, FB2, TXT, HTML, CBZ, Markdown") {
                showAdd = false; pickEbooks.launch(arrayOf("*/*"))
            }
            AddOption(Icons.Filled.AudioFile, "Audiobook files", "One big file (M4B, MP3…) or several parts") {
                showAdd = false; pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/octet-stream"))
            }
            AddOption(Icons.Filled.FolderOpen, "Audiobook folder", "A folder of chapter files, including CD1/CD2 subfolders") {
                showAdd = false; pickAudioFolder.launch(null)
            }
            AddOption(Icons.Filled.LibraryAdd, "Scan a library folder", "Finds every ebook and audiobook and pairs matching titles") {
                showAdd = false; pickScan.launch(null)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove \"${b.title}\"?") },
            text = { Text("This removes the book from your library. Your original files are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (PlayerConnection.state.value.bookId == b.id) PlayerConnection.stop()
                    LibraryRepository.remove(b.id)
                    toDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Headphones, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add ebooks, audiobooks, or scan a folder — matching ebooks and audiobooks are paired automatically so you can read and listen together.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onAdd) { Text("Add books") }
    }
}

@Composable
private fun FormatBadges(book: Book, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.6f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (book.ebook != null) Icon(Icons.AutoMirrored.Filled.MenuBook, "Ebook", Modifier.size(14.dp), tint = Color.White)
            if (book.audio != null) Icon(Icons.Filled.Headphones, "Audiobook", Modifier.size(14.dp), tint = Color.White)
        }
    }
}

@Composable
private fun BookCard(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            FormatBadges(book, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        if (book.progress > 0f) {
            LinearProgressIndicator(
                progress = { book.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) {
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContinueCard(book: Book, onOpen: (String) -> Unit) {
    Column(Modifier.width(118.dp).clickable { onOpen(book.id) }) {
        Box {
            BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            FormatBadges(book, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp))
        Text(
            "${(book.progress * 100).toInt()}% · ${book.title}", style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
