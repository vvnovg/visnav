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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
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
            Text("Кадров: ${s.frames}")
            Text("Ошибок: ${s.errors}")
            Text("Сходство: ${s.lastSim?.let { "%.3f".format(it) } ?: "—"}")
            Text("Ошибка к GPS: ${s.lastErrM?.let { "%.0f м".format(it) } ?: "—"}")
            Text("Инференс: ${s.lastInfMs?.let { "%.0f мс".format(it) } ?: "—"}")
            Text("Точность GPS: ${s.gpsAccM?.let { "%.0f м".format(it) } ?: "нет сигнала"}")
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
