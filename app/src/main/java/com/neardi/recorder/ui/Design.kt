package com.neardi.recorder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 列表可接受额外底部避让；当前根布局已经为悬浮导航缩小内容视口。
val LocalRecorderContentBottomInset = compositionLocalOf { 0.dp }

// 两端共享视觉层次和事件颜色，控件仍使用 Android 原生 Material 3。
val RecorderBlue: Color @Composable get() = MaterialTheme.colorScheme.primary
val RecorderInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val RecorderBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val RecorderMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val RecorderLine: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val RecorderGreen: Color @Composable get() = MaterialTheme.colorScheme.tertiary
val EventNames = linkedMapOf("dwell" to "长时间停留", "person" to "人", "vehicle" to "车", "animal" to "动物")
fun eventColor(type: String) = when (type) {
    "dwell" -> Color(0xFFD9575C)
    "vehicle" -> Color(0xFF9660D4)
    "animal" -> Color(0xFF2D9E72)
    else -> Color(0xFF347EE8)
}
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject?.metric(key: String, suffix: String = ""): String {
    if (this == null || isNull(key)) return "—"
    val number = optDouble(key, Double.NaN)
    return if (number.isFinite()) String.format(Locale.getDefault(), "%.1f", number) + suffix else "—"
}
fun bytesText(bytes: Double): String = when {
    !bytes.isFinite() || bytes < 0 -> "—"
    bytes >= 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024 * 1024 * 1024))
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024 * 1024))
}
fun dateText(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}.getOrDefault(value)

@Composable fun RecorderTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme(
            primary = Color(0xFF73AEFF), onPrimary = Color(0xFF08264A),
            primaryContainer = Color(0xFF183758), onPrimaryContainer = Color(0xFFAED1FF),
            secondary = Color(0xFF73AEFF), onSecondary = Color(0xFF08264A),
            secondaryContainer = Color(0xFF292C32), onSecondaryContainer = Color(0xFFF2F2F7),
            tertiary = Color(0xFF61C880), tertiaryContainer = Color(0xFF183E26), onTertiaryContainer = Color(0xFF9DE4B1),
            background = Color(0xFF000000), onBackground = Color(0xFFF1F3F5),
            surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF1F3F5),
            surfaceVariant = Color(0xFF111318), onSurfaceVariant = Color(0xFFA6ACB5),
            surfaceContainer = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF111318),
            surfaceContainerHigh = Color(0xFF292C32), surfaceTint = Color.Transparent,
            outline = Color(0xFF676B74), outlineVariant = Color(0xFF34363A),
        ) else lightColorScheme(
            primary = Color(0xFF0A59F7), onPrimary = Color.White,
            primaryContainer = Color(0xFFE8F2FF), onPrimaryContainer = Color(0xFF005BBE),
            secondary = Color(0xFF0A59F7), onSecondary = Color.White,
            secondaryContainer = Color(0xFFF0F0F2), onSecondaryContainer = Color(0xFF1D1D1F),
            tertiary = Color(0xFF249B45), tertiaryContainer = Color(0xFFE3F5E8), onTertiaryContainer = Color(0xFF1A6B30),
            background = Color(0xFFF1F3F5), onBackground = Color(0xFF182431),
            surface = Color.White, onSurface = Color(0xFF182431),
            surfaceVariant = Color(0xFFE7EBF0), onSurfaceVariant = Color(0xFF667281),
            surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF8F8F9),
            surfaceContainerHigh = Color(0xFFF0F0F2), surfaceTint = Color.Transparent,
            outline = Color(0xFFD8D8DE), outlineVariant = Color(0xFFE5E8EC),
        ),
        typography = Typography(
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = {
            // 根页面不是 Surface，需显式给默认 Text 提供随主题变化的前景色。
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
        },
    )
}

@Composable fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle.isNotBlank()) Text(subtitle, color = RecorderMuted, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun MessageCard(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun ChoiceMenu(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(10.dp),
            border = BorderStroke(0.75.dp, RecorderLine), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RecorderBlue)) {
            Text("$label：${options.firstOrNull { it.first == selected }?.second ?: "全部"} ▾", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}
