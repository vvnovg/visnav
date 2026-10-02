package io.visnav.app

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.visnav.core.GnssReason
import io.visnav.core.NavMode
import io.visnav.core.PriorMode

@Composable
fun M1Screen(controller: M1Controller, permissionsGranted: Boolean) {
    val s by controller.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    Row(Modifier.fillMaxSize()) {
        if (permissionsGranted) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { controller.bindCamera(it, lifecycleOwner) } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } else {
            Text("Нужны разрешения на камеру и геопозицию", Modifier.weight(1f).padding(16.dp))
        }
        Column(Modifier.width(280.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.status)
            Text("Кадр: ${s.frameSize ?: "—"}")
            Text("Кадров: ${s.frames}")
            Text("Ошибок: ${s.errors}")
            Text("Сходство: ${s.lastSim?.let { "%.3f".format(it) } ?: "—"}")
            Text("Ошибка к GPS: ${s.lastErrM?.let { "%.0f м".format(it) } ?: "—"}")
            Text("Инференс: ${s.lastInfMs?.let { "%.0f мс".format(it) } ?: "—"}")
            Text("Точность GPS: ${s.gpsAccM?.let { "%.0f м".format(it) } ?: "нет сигнала"}")
            s.navMode?.let { m ->
                Text("${modeLabel(m)}${s.sigmaM?.let { " ±${Math.round(it)} м" } ?: ""}", color = modeColor(m))
                if (s.gnssReasons.isNotEmpty()) {
                    Text(s.gnssReasons.mapNotNull { reasonLabel(it) }.joinToString(", "))
                }
            }
            OutlinedButton(
                onClick = { controller.setMode(if (s.mode == PriorMode.GPS) PriorMode.VISUAL else PriorMode.GPS) },
                enabled = !s.running,
            ) { Text("Режим: ${if (s.mode == PriorMode.GPS) "окно по GPS" else "визуальное слежение"}") }
            Button(
                onClick = { if (s.running) controller.stop() else controller.start() },
                enabled = s.loaded && permissionsGranted,
            ) { Text(if (s.running) "Стоп" else "Старт") }
        }
    }
}

private fun modeLabel(m: NavMode) = when (m) {
    NavMode.GNSS -> "GNSS"
    NavMode.FUSED -> "GNSS + камера"
    NavMode.VISUAL -> "Визуальная навигация"
    NavMode.DEAD_RECKONING -> "Счисление пути — точность снижена"
}

private fun modeColor(m: NavMode) = when (m) {
    NavMode.GNSS, NavMode.FUSED -> Color(0xFF2E7D32)
    NavMode.VISUAL -> Color(0xFF1565C0)
    NavMode.DEAD_RECKONING -> Color(0xFFEF6C00)
}

/** FIX_GAP в списке брифа отсутствует: служебная причина, на экране не показывается. */
private fun reasonLabel(r: GnssReason): String? = when (r) {
    GnssReason.NO_FIX -> "нет фикса"
    GnssReason.FEW_SATS -> "мало спутников"
    GnssReason.LOW_CN0 -> "слабый сигнал"
    GnssReason.AGC_DROP -> "глушение (AGC)"
    GnssReason.POOR_ACCURACY -> "низкая точность"
    GnssReason.JUMP -> "скачок"
    GnssReason.INNOVATION -> "расхождение с фильтром"
    GnssReason.UNIFORM_CN0 -> "признак подмены"
    GnssReason.FIX_GAP -> null
}
