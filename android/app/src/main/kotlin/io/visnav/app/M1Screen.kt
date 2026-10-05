package io.visnav.app

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.visnav.core.GnssReason
import io.visnav.core.NavMode
import io.visnav.core.PriorMode
import kotlin.math.roundToInt

@Composable
fun M1Screen(controller: M1Controller, permissionsGranted: Boolean) {
    val s by controller.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // Привязка при появлении превью и при смене профиля (в baseline bindCamera только снимает use case).
    val profile = s.settings.profile
    LaunchedEffect(previewView, lifecycleOwner, profile) {
        previewView?.let { controller.bindCamera(it, lifecycleOwner) }
    }
    Row(Modifier.fillMaxSize()) {
        if (permissionsGranted) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } else {
            Text("Нужны разрешения на камеру и геопозицию", Modifier.weight(1f).padding(16.dp))
        }
        Column(
            Modifier.width(280.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(s.status)
            Text("Кадр: ${s.frameSize ?: "—"}")
            Text("Кадров: ${s.frames}")
            Text("Ошибок: ${s.errors}")
            Text("Сходство: ${s.lastSim?.let { "%.3f".format(it) } ?: "—"}")
            Text("Ошибка к GPS: ${s.lastErrM?.let { "%.0f м".format(it) } ?: "—"}")
            Text("Инференс: ${s.lastInfMs?.let { "%.0f мс".format(it) } ?: "—"}")
            Text("Точность GPS: ${s.gpsAccM?.let { "%.0f м".format(it) } ?: "нет сигнала"}")
            s.nav?.let { nav ->
                Text(when {
                    nav.arrived -> "Вы прибыли"
                    nav.routeFailed -> "Маршрут не найден"
                    else -> nav.nextText ?: "Маршрут строится…"
                },
                    style = MaterialTheme.typography.titleMedium)
                if (nav.routeKm != null && nav.routeMin != null && !nav.arrived) {
                    Text("Маршрут: ${"%.1f".format(nav.routeKm)} км, ~${nav.routeMin.roundToInt()} мин")
                }
                if (nav.rerouted) Text("Маршрут перестроен")
            }
            s.navMode?.let { m ->
                Text("${modeLabel(m)}${s.sigmaM?.let { " ±${Math.round(it)} м" } ?: ""}", color = modeColor(m))
                val reasons = s.gnssReasons.map { reasonLabel(it) }
                if (reasons.isNotEmpty()) Text(reasons.joinToString(", "))
                if (s.roadsLoaded) {
                    Text(s.road?.let { r ->
                        "Дорога: привязана, ${Math.round(r.confidence * 100)} %" + if (r.used) ", уточняет позицию" else ""
                    } ?: "Дорога: не найдена")
                }
            }
            if (s.roadsLoaded) {
                Text("Дороги © участники OpenStreetMap", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { controller.setMode(if (s.mode == PriorMode.GPS) PriorMode.VISUAL else PriorMode.GPS) },
                enabled = !s.running,
            ) { Text("Режим: ${if (s.mode == PriorMode.GPS) "окно по GPS" else "визуальное слежение"}") }
            Button(
                onClick = { if (s.running) controller.stop() else controller.start() },
                enabled = s.loaded && permissionsGranted && (s.running || !s.loading),
            ) { Text(if (s.running) "Стоп" else "Старт") }
            if (s.running) {
                Text(perfLine(s.perf))
            } else {
                PerfSettingsControls(controller, s.settings, loading = s.loading)
            }
        }
    }
}

/** Блок «Замер»: по нажатию — следующий вариант; виден только вне записи (модель — ещё и не во время загрузки базы). */
@Composable
private fun PerfSettingsControls(controller: M1Controller, st: PerfSettings, loading: Boolean) {
    Text("Замер", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(
        onClick = { controller.setProfile(PerfSettings.next(PerfSettings.PROFILES, st.profile)) },
    ) { Text("Профиль: ${profileLabel(st.profile)}") }
    OutlinedButton(
        onClick = { controller.setDuration(PerfSettings.next(PerfSettings.DURATIONS_MIN, st.durationMin)) },
    ) { Text("Длительность: ${durationLabel(st.durationMin)}") }
    OutlinedButton(
        onClick = { controller.setReorderDelay(PerfSettings.next(PerfSettings.REORDER_DELAYS_MS, st.reorderDelayMs)) },
    ) { Text("Буфер: ${st.reorderDelayMs} мс") }
    OutlinedButton(
        onClick = { controller.setOrt(PerfSettings.next(PerfSettings.ORTS, st.ort)) },
        enabled = !loading,
    ) { Text("Модель: ${ortLabel(st.ort)}") }
}

internal fun profileLabel(profile: String) =
    if (profile == PerfSettings.PROFILE_BASELINE) "База (без камеры)" else "Полная"

internal fun ortLabel(ort: String) = if (ort == PerfSettings.ORT_XNNPACK) "XNNPACK" else "CPU"

internal fun durationLabel(min: Int) = if (min == 0) "без ограничения" else "$min мин"

private val THERMAL_LABELS = listOf("нет", "слабый", "умеренный", "сильный", "критический", "аварийный", "отключение")

/** Подпись уровня нагрева PowerManager (THERMAL_STATUS_NONE..SHUTDOWN); неизвестный или нет данных — «—». */
internal fun thermalLabel(status: Int?): String = status?.let { THERMAL_LABELS.getOrNull(it) } ?: "—"

/** Строка показателей во время записи; до первого замера — прочерки. */
internal fun perfLine(p: PerfUi?): String =
    "Кадр: каждые ${p?.let { "${it.intervalMs} мс" } ?: "—"}" +
        " · Нагрев: ${thermalLabel(p?.thermal)}" +
        " · E2E p50: ${p?.e2eP50?.let { "%.0f мс".format(it) } ?: "—"}" +
        " · Ток: ${p?.currentMa?.let { "%.0f мА".format(it) } ?: "—"}"

internal fun modeLabel(m: NavMode) = when (m) {
    NavMode.GNSS -> "GNSS"
    NavMode.FUSED -> "GNSS + камера"
    NavMode.VISUAL -> "Визуальная навигация"
    NavMode.DEAD_RECKONING -> "Счисление пути — точность снижена"
}

internal fun modeColor(m: NavMode) = when (m) {
    NavMode.GNSS, NavMode.FUSED -> Color(0xFF2E7D32)
    NavMode.VISUAL -> Color(0xFF1565C0)
    NavMode.DEAD_RECKONING -> Color(0xFFEF6C00)
}

internal fun reasonLabel(r: GnssReason): String = when (r) {
    GnssReason.NO_FIX -> "нет фикса"
    GnssReason.FEW_SATS -> "мало спутников"
    GnssReason.LOW_CN0 -> "слабый сигнал"
    GnssReason.AGC_DROP -> "глушение (AGC)"
    GnssReason.POOR_ACCURACY -> "низкая точность"
    GnssReason.JUMP -> "скачок"
    GnssReason.INNOVATION -> "расхождение с фильтром"
    GnssReason.UNIFORM_CN0 -> "признак подмены"
    GnssReason.FIX_GAP -> "перерыв фиксов"
}
