package com.neardi.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** 简单线图按统一的 24 格绘制，不引入整套图标运行库。 */
@Composable
fun RecorderGlyph(name: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
        when (name) {
            "server" -> {
                drawRoundRect(color, p(3f,3f), Size(18*u,7*u), CornerRadius(2*u), style=stroke)
                drawRoundRect(color, p(3f,14f), Size(18*u,7*u), CornerRadius(2*u), style=stroke)
                drawCircle(color,u,p(7f,6.5f)); drawCircle(color,u,p(7f,17.5f))
            }
            "file" -> {
                val page=Path().apply { moveTo(5*u,2*u); lineTo(14*u,2*u); lineTo(20*u,8*u); lineTo(20*u,22*u); lineTo(5*u,22*u); close() }
                drawPath(page,color,style=stroke); line(14f,2f,14f,8f); line(14f,8f,20f,8f); line(9f,13f,16f,13f); line(9f,17f,14f,17f)
            }
            "moon" -> {
                val moon=Path().apply { moveTo(16*u,3*u); cubicTo(1*u,-1*u,0*u,22*u,15*u,21*u); cubicTo(19*u,21*u,22*u,18*u,22*u,16*u); cubicTo(11*u,19*u,10*u,8*u,16*u,3*u) }
                drawPath(moon,color,style=stroke)
            }
            "back" -> { line(15f, 4f, 7f, 12f); line(7f, 12f, 15f, 20f) }
            "chevron" -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            "expand" -> {
                line(3f, 9f, 3f, 3f); line(3f, 3f, 9f, 3f)
                line(15f, 3f, 21f, 3f); line(21f, 3f, 21f, 9f)
                line(21f, 15f, 21f, 21f); line(21f, 21f, 15f, 21f)
                line(9f, 21f, 3f, 21f); line(3f, 21f, 3f, 15f)
            }
            "camera", "camera-off" -> {
                drawRoundRect(color, p(2f, 5f), Size(14*u, 14*u), CornerRadius(3*u), style=stroke)
                val lens = Path().apply { moveTo(17*u,9*u); lineTo(22*u,6*u); lineTo(22*u,18*u); lineTo(17*u,15*u) }
                drawPath(lens, color, style=stroke)
                if (name == "camera-off") line(2f, 2f, 22f, 22f)
            }
            "playback" -> {
                drawCircle(color,9*u,p(12f,12f),style=stroke)
                val play=Path().apply { moveTo(10*u,8*u); lineTo(16*u,12*u); lineTo(10*u,16*u); close() }
                drawPath(play,color,style=stroke)
            }
            "event" -> {
                val hex=Path().apply { moveTo(8*u,3*u);lineTo(16*u,3*u);lineTo(21*u,12*u);lineTo(16*u,21*u);lineTo(8*u,21*u);lineTo(3*u,12*u);close() }
                drawPath(hex,color,style=stroke);line(12f,7f,12f,13f);drawCircle(color,.8f*u,p(12f,17f))
            }
            "settings" -> {
                val gear = Path().apply {
                    repeat(32) { i ->
                        val a = i * Math.PI / 16 - Math.PI / 2
                        val r = if (i % 4 == 1 || i % 4 == 2) 9.4f else 7.2f
                        val x = (12f + cos(a).toFloat() * r) * u
                        val y = (12f + sin(a).toFloat() * r) * u
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(gear,color,style=stroke)
                drawCircle(color,2.8f*u,p(12f,12f),style=stroke)
            }
            "download" -> {
                line(12f, 3f, 12f, 15f)
                line(7f, 10f, 12f, 15f); line(12f, 15f, 17f, 10f)
                line(4f, 17f, 4f, 21f); line(4f, 21f, 20f, 21f); line(20f, 21f, 20f, 17f)
            }
            "rewind", "forward" -> {
                scale(if (name == "forward") -1f else 1f, 1f) {
                    drawArc(color, -140f, 290f, false, p(3f,3f), Size(18*u,18*u), style=stroke)
                    val arrow=Path().apply { moveTo(5*u,1.5f*u); lineTo(5*u,6.5f*u); lineTo(10*u,6.5f*u) }
                    drawPath(arrow,color,style=stroke)
                }
            }
            "info" -> {
                drawCircle(color,9*u,p(12f,12f),style=stroke)
                drawCircle(color,u,p(12f,7f)); line(12f,11f,12f,17f)
            }
        }
    }
}
