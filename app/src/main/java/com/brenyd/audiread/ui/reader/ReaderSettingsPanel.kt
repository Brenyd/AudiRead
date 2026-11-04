package com.brenyd.audiread.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brenyd.audiread.data.ReaderSettings
import com.brenyd.audiread.data.ReaderTheme
import java.util.Locale
import kotlin.math.roundToInt

private val fonts = listOf(
    "default" to "Original",
    "serif" to "Serif",
    "sans-serif" to "Sans",
    "sans-serif-condensed" to "Condensed",
    "monospace" to "Mono",
)

@Composable
fun ReaderSettingsPanel(s: ReaderSettings, fixedLayout: Boolean, onChange: (ReaderSettings) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ReaderTheme.entries.forEach { t ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Color(t.bg))
                            .border(
                                BorderStroke(if (t == s.theme) 3.dp else 1.dp, if (t == s.theme) MaterialTheme.colorScheme.primary else Color.Gray),
                                CircleShape,
                            )
                            .clickable { onChange(s.copy(theme = t)) },
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = Color(t.fg), fontWeight = FontWeight.SemiBold) }
                    Text(t.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (fixedLayout) {
            Spacer(Modifier.height(12.dp))
            Text("Dark themes invert page colors.", style = MaterialTheme.typography.bodySmall)
        } else {
            TextOptions(s, onChange)
        }
    }
}

@Composable
private fun TextOptions(s: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Column {

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OutlinedIconButton(onClick = { onChange(s.copy(fontScale = (s.fontScale - 10).coerceAtLeast(60))) }) {
                Icon(Icons.Filled.Remove, "Smaller")
            }
            Text("${s.fontScale}%", modifier = Modifier.padding(horizontal = 12.dp))
            OutlinedIconButton(onClick = { onChange(s.copy(fontScale = (s.fontScale + 10).coerceAtMost(260))) }) {
                Icon(Icons.Filled.Add, "Larger")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Font", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fonts.forEach { (value, label) ->
                FilterChip(selected = s.font == value, onClick = { onChange(s.copy(font = value)) }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(16.dp))
        var line by remember(s.lineHeight) { mutableFloatStateOf(s.lineHeight) }
        Text("Line spacing  ${String.format(Locale.US, "%.1f", line)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = line, onValueChange = { line = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { onChange(s.copy(lineHeight = line)) },
            valueRange = 1.0f..2.4f,
        )

        var margin by remember(s.margin) { mutableFloatStateOf(s.margin.toFloat()) }
        Text("Margins  ${margin.roundToInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = margin, onValueChange = { margin = it },
            onValueChangeFinished = { onChange(s.copy(margin = margin.roundToInt())) },
            valueRange = 0f..64f,
        )

        SwitchRow("Justify text", s.justify) { onChange(s.copy(justify = it)) }
        SwitchRow("Continuous scrolling", s.scroll) { onChange(s.copy(scroll = it)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
