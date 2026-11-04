package com.brenyd.audiread.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Amber = Color(0xFFFFB86B)
private val Ink = Color(0xFF1B1F3B)

@Composable
fun AudiReadTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx).copy(
            background = Color.Black, surface = Color.Black,
        )
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = Amber, onPrimary = Ink, background = Color.Black, surface = Color.Black,
            secondaryContainer = Color(0xFF3A3F5C), primaryContainer = Color(0xFF5A3D14),
        )
        else -> lightColorScheme(primary = Color(0xFF8A4B00), secondaryContainer = Color(0xFFFFE0BF))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
