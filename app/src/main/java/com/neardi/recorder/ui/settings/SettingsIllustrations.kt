package com.neardi.recorder.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** 简单线图直接绘制，不打包图标库，也不把示意设备误当成真实产品照片。 */
@Composable
internal fun RecorderIllustration(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val topColor = lerp(surface, ink, 0.10f)
    val frontColor = lerp(surface, ink, 0.18f)
    val sideColor = lerp(surface, ink, 0.24f)
    Canvas(modifier) {
        scale(size.width / 100f, size.height / 78f, pivot = Offset.Zero) {
            val top = Path().apply {
                moveTo(4f, 26f); lineTo(62f, 12f); lineTo(96f, 28f)
                lineTo(35f, 44f); close()
            }
            val front = Path().apply {
                moveTo(35f, 44f); lineTo(96f, 28f); lineTo(96f, 45f)
                lineTo(35f, 62f); close()
            }
            val side = Path().apply {
                moveTo(4f, 26f); lineTo(35f, 44f); lineTo(35f, 62f)
                lineTo(4f, 44f); close()
            }
            drawPath(top, topColor)
            drawPath(front, frontColor)
            drawPath(side, sideColor)
            listOf(top, front, side).forEach { drawPath(it, outline, style = Stroke(1f, join = StrokeJoin.Round)) }
            drawLine(outline, Offset(42f, 51f), Offset(73f, 43f), 1.3f, StrokeCap.Round)
            drawCircle(outline, 1.3f, Offset(87f, 40f))
            drawLine(outline, Offset(12f, 37f), Offset(27f, 46f), 1.2f, StrokeCap.Round)
            drawLine(outline, Offset(12f, 41f), Offset(27f, 50f), 1.2f, StrokeCap.Round)
        }
    }
}

@Composable
internal fun SettingsCategoryIcon(category: String, modifier: Modifier = Modifier) {
    // 主题色在组合阶段读取，Canvas 内只使用已捕获的颜色。
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        scale(size.width / 32f, size.height / 32f, pivot = Offset.Zero) {
            val stroke = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.9f, StrokeCap.Round)
            when (category) {
                "connection" -> {
                    drawPath(Path().apply {
                        moveTo(13f, 20f); lineTo(10f, 23f)
                        cubicTo(7f, 26f, 2f, 22f, 5f, 18f); lineTo(11f, 12f)
                        cubicTo(14f, 9f, 17f, 10f, 19f, 12f)
                    }, color, style = stroke)
                    drawPath(Path().apply {
                        moveTo(19f, 12f); lineTo(22f, 9f)
                        cubicTo(25f, 6f, 30f, 10f, 27f, 14f); lineTo(21f, 20f)
                        cubicTo(18f, 23f, 15f, 22f, 13f, 20f)
                    }, color, style = stroke)
                    line(12f, 20f, 20f, 12f)
                }
                "storage" -> {
                    drawPath(Path().apply {
                        moveTo(7f, 4f); lineTo(25f, 4f); lineTo(28f, 21f)
                        lineTo(28f, 28f); lineTo(4f, 28f); lineTo(4f, 21f); close()
                    }, color, style = stroke)
                    line(5f, 21f, 27f, 21f)
                    drawCircle(color, 1.25f, Offset(23f, 24.5f))
                    drawCircle(color, 1.25f, Offset(18.5f, 24.5f))
                }
                "model" -> {
                    drawRoundRect(color, Offset(6f, 6f), Size(20f, 20f), CornerRadius(3f), style = stroke)
                    for (position in listOf(11f, 21f)) {
                        line(position, 3f, position, 6f); line(position, 26f, position, 29f)
                        line(3f, position, 6f, position); line(26f, position, 29f, position)
                    }
                    drawPath(Path().apply {
                        moveTo(10f, 21f); lineTo(13f, 12f); lineTo(16f, 21f)
                    }, color, style = stroke)
                    line(11.3f, 17.5f, 14.7f, 17.5f)
                    line(21f, 12f, 21f, 21f)
                }
                "appearance" -> {
                    drawCircle(color, 11f, Offset(16f, 16f), style = stroke)
                    drawArc(color.copy(alpha = 0.7f), -90f, 180f, useCenter = true,
                        topLeft = Offset(5f, 5f), size = Size(22f, 22f))
                    line(16f, 5f, 16f, 27f)
                }
                else -> {
                    drawPath(Path().apply {
                        moveTo(7f, 3f); lineTo(20f, 3f); lineTo(27f, 10f)
                        lineTo(27f, 29f); lineTo(7f, 29f); close()
                        moveTo(19f, 3f); lineTo(19f, 11f); lineTo(27f, 11f)
                    }, color, style = stroke)
                    line(12f, 17f, 22f, 17f); line(12f, 21f, 22f, 21f)
                    line(12f, 25f, 18f, 25f)
                }
            }
        }
    }
}
