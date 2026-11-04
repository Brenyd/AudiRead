package com.brenyd.audiread.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.brenyd.audiread.ui.detail.BookDetailScreen
import com.brenyd.audiread.ui.library.LibraryScreen
import com.brenyd.audiread.ui.player.PlayerScreen
import com.brenyd.audiread.ui.reader.ReaderScreen

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenBook = { nav.navigate("book/$it") },
                onOpenPlayer = { nav.navigate("player/$it") },
            )
        }
        composable("book/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            BookDetailScreen(
                bookId = id,
                onBack = { nav.popBackStack() },
                onRead = { both -> nav.navigate("reader/$id/${if (both) "both" else "read"}") },
                onListen = { nav.navigate("player/$id") },
            )
        }
        composable("reader/{id}/{mode}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val mode = entry.arguments?.getString("mode") ?: "read"
            ReaderScreen(
                bookId = id,
                withAudio = mode == "both",
                onBack = { nav.popBackStack() },
                onOpenPlayer = { nav.navigate("player/$id") },
            )
        }
        composable("player/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            PlayerScreen(
                bookId = id,
                onBack = { nav.popBackStack() },
                onReadAlong = { bid -> nav.navigate("reader/$bid/both") },
            )
        }
    }
}
