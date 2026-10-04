package io.visnav.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import io.visnav.core.ManeuverType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Стрелка манёвра на Canvas: прямо, «держитесь» (30°), поворот (90°), резкий поворот (135°), разворот дугой,
 * кольцо (окружность со стрелкой выхода и номером съезда [exit]), финиш (флажок). Левые манёвры — зеркально.
 */
@Composable
fun ManeuverIcon(type: ManeuverType, exit: Int, modifier: Modifier, color: Color) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val mirror = type == ManeuverType.SLIGHT_LEFT || type == ManeuverType.LEFT || type == ManeuverType.SHARP_LEFT
            scale(if (mirror) -1f else 1f, 1f) {
                when (type) {
                    ManeuverType.DEPART, ManeuverType.CONTINUE -> turn(0.0, color)
                    ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT -> turn(30.0, color)
                    ManeuverType.LEFT, ManeuverType.RIGHT -> turn(90.0, color)
                    ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT -> turn(135.0, color)
                    ManeuverType.UTURN -> uturn(color)
                    ManeuverType.ROUNDABOUT -> roundabout(color)
                    ManeuverType.ARRIVE -> flag(color)
                }
            }
        }
        if (type == ManeuverType.ROUNDABOUT && exit > 0) {
            // Центр кольца — на 0,45 высоты (см. roundabout), а не 0,5.
            Text(
                "$exit", color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(BiasAlignment(0f, -0.1f)),
            )
        }
    }
}

private fun DrawScope.p(x: Float, y: Float) = Offset(x * size.width, y * size.height)

private fun DrawScope.stroke() = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Наконечник в точке [tip], направленный по [angleDeg] (0 — вверх, по часовой). */
private fun DrawScope.head(tip: Offset, angleDeg: Double, color: Color) {
    val a = Math.toRadians(angleDeg)
    val h = size.minDimension * 0.22f
    val fx = sin(a).toFloat(); val fy = -cos(a).toFloat()      // вперёд
    val nx = -fy; val ny = fx                                    // поперёк
    val base = Offset(tip.x - fx * h, tip.y - fy * h)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + nx * h * 0.6f, base.y + ny * h * 0.6f)
        lineTo(base.x - nx * h * 0.6f, base.y - ny * h * 0.6f)
        close()
    }
    drawPath(path, color)
}

/** Вверх от низа, затем поворот вправо на [angleDeg]; 0 — прямо. */
private fun DrawScope.turn(angleDeg: Double, color: Color) {
    val start = p(0.5f, 0.92f)
    if (angleDeg == 0.0) {
        val tip = p(0.5f, 0.1f)
        drawLine(color, start, Offset(tip.x, tip.y + size.minDimension * 0.15f), size.minDimension * 0.1f, StrokeCap.Round)
        head(tip, 0.0, color)
        return
    }
    val bend = p(0.5f, 0.52f)
    val a = Math.toRadians(angleDeg)
    val len = size.minDimension * 0.34f
    val fx = sin(a).toFloat(); val fy = -cos(a).toFloat()
    val tip = Offset(bend.x + fx * len, bend.y + fy * len)
    val shaftEnd = Offset(tip.x - fx * size.minDimension * 0.15f, tip.y - fy * size.minDimension * 0.15f)
    val path = Path().apply { moveTo(start.x, start.y); lineTo(bend.x, bend.y); lineTo(shaftEnd.x, shaftEnd.y) }
    drawPath(path, color, style = stroke())
    head(tip, angleDeg, color)
}

/** Разворот дугой влево: вверх, полуокружность, вниз. */
private fun DrawScope.uturn(color: Color) {
    val r = size.width * 0.17f
    val right = 0.64f; val top = 0.42f
    val cx = size.width * right - r
    val path = Path().apply {
        moveTo(size.width * right, size.height * 0.92f)
        lineTo(size.width * right, size.height * top)
        arcTo(
            rect = Rect(Offset(cx, size.height * top), r),
            startAngleDegrees = 0f, sweepAngleDegrees = -180f, forceMoveTo = false,
        )
        lineTo(cx - r, size.height * 0.56f)
    }
    drawPath(path, color, style = stroke())
    head(Offset(cx - r, size.height * 0.74f), 180.0, color)
}

/** Кольцо: въезд снизу, окружность, стрелка выхода вправо-вверх. */
private fun DrawScope.roundabout(color: Color) {
    val c = p(0.5f, 0.45f)
    val r = size.minDimension * 0.22f
    val w = size.minDimension * 0.1f
    drawCircle(color, r, c, style = stroke())
    drawLine(color, p(0.5f, 0.95f), Offset(c.x, c.y + r), w, StrokeCap.Round)
    val a = Math.toRadians(45.0)
    val fx = sin(a).toFloat(); val fy = -cos(a).toFloat()
    val from = Offset(c.x + fx * r, c.y + fy * r)
    val tip = Offset(c.x + fx * (r + size.minDimension * 0.3f), c.y + fy * (r + size.minDimension * 0.3f))
    drawLine(color, from, Offset(tip.x - fx * size.minDimension * 0.15f, tip.y - fy * size.minDimension * 0.15f), w, StrokeCap.Round)
    head(tip, 45.0, color)
}

/** Финиш: древко и треугольный флажок. */
private fun DrawScope.flag(color: Color) {
    drawLine(color, p(0.32f, 0.92f), p(0.32f, 0.1f), size.minDimension * 0.08f, StrokeCap.Round)
    val path = Path().apply {
        val a = p(0.32f, 0.1f); val b = p(0.8f, 0.26f); val c = p(0.32f, 0.42f)
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
    }
    drawPath(path, color)
}
