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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brenyd.audiread.data.ReaderSettings
import com.brenyd.audiread.data.ReaderTheme
import com.brenyd.audiread.data.TurnStyle
import kotlin.math.roundToInt

private val fonts = listOf(
    Triple("literata", "Literata", FontFamily.Serif),
    Triple("default", "Original", FontFamily.Default),
    Triple("serif", "Serif", FontFamily.Serif),
    Triple("sans-serif", "Sans", FontFamily.SansSerif),
    Triple("sans-serif-condensed", "Condensed", FontFamily.SansSerif),
    Triple("monospace", "Mono", FontFamily.Monospace),
)

@Composable
fun ReaderSettingsPanel(s: ReaderSettings, fixedLayout: Boolean, onChange: (ReaderSettings) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding(),
    ) {
        Text("Display options", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))

        // Theme
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ReaderTheme.entries.forEach { t ->
                val selected = t == s.theme
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(s.copy(theme = t)) }) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color(t.bg))
                            .border(
                                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33888888)),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, null, tint = Color(t.fg))
                        else Text("Aa", color = Color(t.fg), fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif)
                    }
                    Text(t.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (fixedLayout) {
            Text("Dark themes invert page colors.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }

        Divider()
        BrightnessRow(s, onChange)

        if (!fixedLayout) {
            Divider()
            Label("Typeface")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fonts.forEach { (value, label, family) ->
                    FilterChip(
                        selected = s.font == value, onClick = { onChange(s.copy(font = value)) },
                        label = { Text(label, fontFamily = family) },
                    )
                }
            }

            Divider()
            var size by remember(s.fontScale) { mutableFloatStateOf(s.fontScale.toFloat()) }
            Label("Text size")
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 13.sp, fontFamily = FontFamily.Serif)
                Slider(
                    value = size, onValueChange = { size = ((it / 5).roundToInt() * 5).toFloat() },
                    onValueChangeFinished = { onChange(s.copy(fontScale = size.roundToInt())) },
                    valueRange = 60f..250f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("A", fontSize = 24.sp, fontFamily = FontFamily.Serif)
            }

            var line by remember(s.lineHeight) { mutableFloatStateOf(s.lineHeight) }
            Label("Line spacing")
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FormatLineSpacing, null, Modifier.size(18.dp))
                Slider(
                    value = line, onValueChange = { line = (it * 10).roundToInt() / 10f },
                    onValueChangeFinished = { onChange(s.copy(lineHeight = line)) },
                    valueRange = 1.0f..2.4f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Icon(Icons.Filled.FormatLineSpacing, null, Modifier.size(26.dp))
            }

            var margin by remember(s.margin) { mutableFloatStateOf(s.margin.toFloat()) }
            Label("Margins")
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Narrow", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = margin, onValueChange = { margin = it },
                    onValueChangeFinished = { onChange(s.copy(margin = margin.roundToInt())) },
                    valueRange = 4f..64f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("Wide", style = MaterialTheme.typography.labelSmall)
            }

            Divider()
            Label("Alignment")
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !s.justify, onClick = { onChange(s.copy(justify = false)) },
                    leadingIcon = { Icon(Icons.Filled.FormatAlignLeft, null, Modifier.size(18.dp)) }, label = { Text("Left") })
                FilterChip(selected = s.justify, onClick = { onChange(s.copy(justify = true)) },
                    leadingIcon = { Icon(Icons.Filled.FormatAlignJustify, null, Modifier.size(18.dp)) }, label = { Text("Justified") })
            }

            Label("Page turn")
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TurnStyle.entries.forEach { t ->
                    FilterChip(
                        selected = !s.scroll && s.turnStyle == t,
                        onClick = { onChange(s.copy(turnStyle = t, scroll = false)) },
                        label = { Text(t.label) },
                    )
                }
                FilterChip(selected = s.scroll, onClick = { onChange(s.copy(scroll = true)) }, label = { Text("Scroll") })
            }
        }
    }
}

@Composable
private fun BrightnessRow(s: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    val auto = s.brightness < 0f
    var value by remember(s.brightness) { mutableFloatStateOf(if (auto) 0.5f else s.brightness) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.BrightnessLow, "Brightness", Modifier.size(20.dp))
        Slider(
            value = value, enabled = !auto,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(s.copy(brightness = value)) },
            valueRange = 0.02f..1f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Icon(Icons.Filled.BrightnessHigh, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = auto, onCheckedChange = { onChange(s.copy(brightness = if (it) -1f else value)) })
            Text("Auto", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Suppress("unused")
private val pillShape = RoundedCornerShape(50)
