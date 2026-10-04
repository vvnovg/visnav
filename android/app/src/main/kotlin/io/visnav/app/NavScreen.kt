package io.visnav.app

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.visnav.core.CameraPolicy
import io.visnav.core.Instructions
import io.visnav.core.MapGeometry
import io.visnav.core.MapStyle
import io.visnav.core.NavMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Расстояние до манёвра на панели: «Сейчас» ближе 30 м, иначе как в подсказках. */
internal fun maneuverDistanceText(m: Double): String = if (m < 30.0) "Сейчас" else Instructions.distanceText(m)

/** «Осталось N км · ~M мин»: минуты — доля оценки маршрута по оставшейся длине (не меньше 1). */
internal fun remainingText(remainingM: Double, routeKm: Double?, routeMin: Double?): String {
    val km = "%.1f".format(Locale.ROOT, remainingM / 1000).replace('.', ',')
    val min = if (routeKm != null && routeMin != null && routeKm > 0) {
        maxOf(1, (routeMin * remainingM / (routeKm * 1000)).roundToInt())
    } else null
    return "Осталось $km км" + (min?.let { " · ~$it мин" } ?: "")
}

/** Цвет режима (SPEC §7) для слоёв карты. */
internal fun modeHex(m: NavMode): String = when (m) {
    NavMode.GNSS, NavMode.FUSED -> "#2E7D32"
    NavMode.VISUAL -> "#1565C0"
    NavMode.DEAD_RECKONING -> "#EF6C00"
}

/**
 * Последний маршрут, полученный экраном (NavUi передаёт геометрию только при смене версии): новая версия заменяет
 * маршрут, та же версия с пустыми списками его сохраняет, nav == null или версия 0 — очищает.
 */
internal class RouteStore {
    var version = 0; private set
    var routeJson: String = MapGeometry.empty(); private set
    var maneuversJson: String = MapGeometry.empty(); private set

    fun update(nav: NavUi?) {
        if (nav == null) {
            if (version != 0) { version = 0; routeJson = MapGeometry.empty(); maneuversJson = MapGeometry.empty() }
        } else if (nav.routeVersion != version) {
            version = nav.routeVersion
            routeJson = if (nav.routeLatLon.isEmpty()) MapGeometry.empty() else MapGeometry.lineString(nav.routeLatLon)
            maneuversJson = MapGeometry.points(nav.maneuverLatLon)
        }
    }
}

private fun Style.setGeoJson(sourceId: String, json: String) {
    getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(json)
}

/** Слои позиции: маркер и круг σ в цвете режима, стрелка курса при скорости ≥ 3 м/с. */
private fun Style.showPosition(p: MapPos?) {
    if (p == null) {
        for (id in listOf(MapStyle.MARKER_SOURCE, MapStyle.ACCURACY_SOURCE, MapStyle.HEADING_SOURCE)) setGeoJson(id, MapGeometry.empty())
        return
    }
    val hex = modeHex(p.mode)
    setGeoJson(MapStyle.MARKER_SOURCE, MapGeometry.point(p.lat, p.lon, hex))
    setGeoJson(MapStyle.ACCURACY_SOURCE, MapGeometry.circlePolygon(p.lat, p.lon, p.sigmaM, hex))
    val moving = p.speedMps.isFinite() && p.speedMps >= 3.0
    val len = if (p.sigmaM.isFinite()) max(12.0, 2 * p.sigmaM) else 12.0
    setGeoJson(
        MapStyle.HEADING_SOURCE,
        if (moving) MapGeometry.headingArrow(p.lat, p.lon, p.psiRad, len, hex) else MapGeometry.empty(),
    )
}

/**
 * Следящая камера: цель по CameraPolicy и плавный переход за 400 мс, а при [jump] — мгновенный (первое ведение
 * после загрузки стиля, чтобы камера не прилетала из (0,0)). Возвращает true — камера ведёт, false — режим
 * «свободно», null — позиция нечисловая, состояние не меняется.
 */
private fun follow(map: MapLibreMap, policy: CameraPolicy, p: MapPos, jump: Boolean): Boolean? {
    if (!p.lat.isFinite() || !p.lon.isFinite()) return null
    val speed = if (p.speedMps.isFinite()) p.speedMps else 0.0
    val t = policy.update(SystemClock.elapsedRealtime(), p.lat, p.lon, speed, p.psiRad) ?: return false
    val camera = CameraPosition.Builder().target(LatLng(t.lat, t.lon)).zoom(t.zoom).bearing(t.bearingDeg).tilt(0.0).build()
    val update = CameraUpdateFactory.newCameraPosition(camera)
    if (jump) map.moveCamera(update) else map.easeCamera(update, 400)
    return true
}

/**
 * Навигационный экран (горизонтально): слева панель манёвра и состояния (36 %), справа офлайн-карта MapLibre
 * с маршрутом, манёврами, позицией и кругом неопределённости. Все обращения к карте — на главном потоке.
 */
@Composable
fun NavScreen(controller: M1Controller, permissionsGranted: Boolean) {
    val s by controller.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Кадры анализируются и без вкладки «Отладка»: привязываем камеру без превью. Каждая смена вкладки
    // перепривязывает камеру — во время записи это пауза в кадрах (см. M1Controller.bindCamera).
    LaunchedEffect(permissionsGranted, lifecycleOwner) {
        if (permissionsGranted) controller.bindCamera(null, lifecycleOwner)
    }
    var mapData by remember(controller) { mutableStateOf<Result<MapData>?>(null) }
    LaunchedEffect(controller) {
        mapData = withContext(Dispatchers.IO) { MapDataLoader.load(controller.refpackDir) }
    }
    val policy = remember { CameraPolicy() }
    val routeStore = remember { RouteStore() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoads by remember { mutableIntStateOf(0) }
    var free by remember { mutableStateOf(false) }

    // Каждая загрузка стиля (в том числе смена день/ночь) перезапускает эффект: маршрут, манёвры и позиция
    // отправляются заново в источники нового стиля. Источники берутся из map.style на каждом обновлении.
    LaunchedEffect(map, styleLoads) {
        val m = map ?: return@LaunchedEffect
        var sentVersion = -1
        var sentPos: MapPos? = null
        var first = true
        // Первое ведение после onMapReady/onStyleLoaded (эффект перезапускается на каждой загрузке стиля) — moveCamera.
        var jumped = false
        controller.state.collect { st ->
            val style = m.style?.takeIf { it.isFullyLoaded } ?: return@collect
            routeStore.update(st.nav)
            if (routeStore.version != sentVersion) {
                sentVersion = routeStore.version
                style.setGeoJson(MapStyle.ROUTE_SOURCE, routeStore.routeJson)
                style.setGeoJson(MapStyle.MANEUVER_SOURCE, routeStore.maneuversJson)
            }
            if (first || st.pos !== sentPos) {
                first = false
                sentPos = st.pos
                style.showPosition(st.pos)
                st.pos?.let { p ->
                    follow(m, policy, p, jump = !jumped)?.let { following -> free = !following; if (following) jumped = true }
                }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(0.36f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManeuverPanel(s.nav)
            s.navMode?.let { m ->
                Text("${modeLabel(m)}${s.sigmaM?.let { " ±${Math.round(it)} м" } ?: ""}", color = modeColor(m))
                val reasons = s.gnssReasons.map { reasonLabel(it) }
                if (reasons.isNotEmpty()) Text(reasons.joinToString(", "))
            }
            s.nav?.let { nav ->
                if (nav.remainingM != null && !nav.arrived) Text(remainingText(nav.remainingM, nav.routeKm, nav.routeMin))
            }
            if (s.navMode != null && s.roadsLoaded) {
                Text(s.road?.let { r ->
                    "Дорога: привязана, ${Math.round(r.confidence * 100)} %" + if (r.used) ", уточняет позицию" else ""
                } ?: "Дорога: не найдена")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (s.running) controller.stop() else controller.start() },
                    enabled = s.loaded && permissionsGranted,
                ) { Text(if (s.running) "Стоп" else "Старт") }
                val m = map
                if (free && m != null) {
                    OutlinedButton(onClick = {
                        policy.recenter()
                        free = false
                        controller.state.value.pos?.let { p -> follow(m, policy, p, jump = false)?.let { free = !it } }
                    }) { Text("В центр") }
                }
            }
            if (!permissionsGranted) Text("Нужны разрешения на камеру и геопозицию")
            Text(s.status, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.weight(0.64f).fillMaxHeight()) {
            when (val r = mapData) {
                null -> Text("Загрузка карты…", Modifier.padding(16.dp))
                else -> r.fold(
                    onSuccess = { data ->
                        val night = isSystemInDarkTheme()
                        val styleJson = remember(data, night) { data.styleJson(night) }
                        MapLibreMap(
                            modifier = Modifier.fillMaxSize(),
                            styleJson = styleJson,
                            onMapReady = { m ->
                                // Старт без позиции: центр bounds карты, масштаб 13.
                                if (controller.state.value.pos == null) {
                                    val b = data.bounds
                                    val camera = CameraPosition.Builder()
                                        .target(LatLng((b[1] + b[3]) / 2, (b[0] + b[2]) / 2)).zoom(13.0).tilt(0.0).build()
                                    m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
                                }
                            },
                            onStyleLoaded = { m, _ -> map = m; styleLoads++ },
                            onUserGesture = { policy.onUserGesture(SystemClock.elapsedRealtime()); free = true },
                        )
                        Text(
                            data.attribution,
                            Modifier.align(Alignment.BottomEnd)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    onFailure = { e ->
                        val mapDir = File(controller.refpackDir, "map").absolutePath
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Нет карты: ${e.message?.removePrefix("нет карты: ") ?: e}")
                            Text("Скопируйте карту: adb push <map>/. $mapDir/", style = MaterialTheme.typography.bodySmall)
                            Text("После adb push переключите вкладку", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
        }
    }
}

/** Иконка и расстояние до манёвра, улица; вместо них — состояние маршрута по NavUi. */
@Composable
private fun ManeuverPanel(nav: NavUi?) {
    if (nav == null) return
    val type = nav.nextType
    when {
        nav.arrived -> Text("Вы прибыли", style = MaterialTheme.typography.headlineMedium)
        nav.routeFailed -> Text("Маршрут не найден", style = MaterialTheme.typography.headlineSmall)
        type == null -> Text("Маршрут строится…", style = MaterialTheme.typography.headlineSmall)
        else -> {
            ManeuverIcon(type, nav.nextExit, Modifier.size(96.dp), MaterialTheme.colorScheme.primary)
            nav.nextDistM?.let { Text(maneuverDistanceText(it), style = MaterialTheme.typography.displaySmall) }
            nav.nextStreet?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (nav.rerouted) Text("Маршрут перестроен", style = MaterialTheme.typography.titleSmall)
}
