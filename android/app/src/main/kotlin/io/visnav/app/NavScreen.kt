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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import io.visnav.core.GnssHealth
import io.visnav.core.Instructions
import io.visnav.core.MapGeometry
import io.visnav.core.LocalizerOutput
import io.visnav.core.MapStyle
import io.visnav.core.NavMode
import io.visnav.core.Nowcast
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
 * Позиция «сейчас» для маркера, круга σ и камеры: Nowcast по последнему выходу фильтра ([MapPos.tMs]) на момент
 * [nowMs] (настенные часы, как t_ms). σ, курс, скорость и режим не меняются.
 */
internal fun nowcastPos(p: MapPos, nowMs: Long): MapPos {
    val out = LocalizerOutput(
        tMs = p.tMs, lat = p.lat, lon = p.lon, sigmaM = p.sigmaM, visSim = null, visAccepted = null, visState = "",
        stationary = false, mode = p.mode, health = GnssHealth.GOOD, reasons = emptySet(),
        psiRad = p.psiRad, speedMps = p.speedMps,
    )
    val ll = Nowcast.at(out, nowMs)
    return if (ll[0] == p.lat && ll[1] == p.lon) p else p.copy(lat = ll[0], lon = ll[1])
}

/** Строка поездки на панели: ничего (старая раскладка), текст или выбор из нескольких поездок. */
internal sealed interface TripLabel {
    data object None : TripLabel
    data class Text(val name: String) : TripLabel
    data class Picker(val name: String, val trips: List<String>) : TripLabel
}

/**
 * Выбор доступен, только если поездок больше одной, запись не идёт и база не грузится (после неудачной загрузки
 * выбор остаётся, чтобы можно было переключиться на другую поездку).
 */
internal fun tripLabel(trip: String?, trips: List<String>, running: Boolean, loading: Boolean): TripLabel = when {
    trip == null -> TripLabel.None
    trips.size > 1 && !running && !loading -> TripLabel.Picker(trip, trips)
    else -> TripLabel.Text(trip)
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
    // Смена профиля (full/baseline) перепривязывает камеру: в baseline bindCamera только снимает use case.
    val profile = s.settings.profile
    LaunchedEffect(permissionsGranted, lifecycleOwner, profile) {
        if (permissionsGranted) controller.bindCamera(null, lifecycleOwner)
    }
    var mapData by remember(controller) { mutableStateOf<Result<MapData>?>(null) }
    val policy = remember { CameraPolicy() }
    // Страховка: между сессиями nav и так null, но маршрут прежней поездки не переживает смену поездки.
    val routeStore = remember(s.trip) { RouteStore() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoads by remember { mutableIntStateOf(0) }
    var free by remember { mutableStateOf(false) }
    // Карта зависит только от каталога поездки: tripDir меняется один раз на смену поездки (сразу после resolve).
    val tripDir = s.tripDir
    LaunchedEffect(tripDir) {
        val result = tripDir?.let { dir -> withContext(Dispatchers.IO) { MapDataLoader.load(File(dir)) } }
        // key(data) пересоздаёт карту: старая MapLibreMap уничтожается, ждём onStyleLoaded новой.
        map = null
        free = false
        mapData = result
    }
    // Вход на вкладку: перечитать список поездок (поездка могла появиться через adb push).
    LaunchedEffect(Unit) { controller.refreshTrips() }

    // Каждая загрузка стиля (в том числе смена день/ночь) перезапускает эффект: маршрут, манёвры и позиция
    // отправляются заново в источники нового стиля. Источники берутся из map.style на каждом обновлении.
    LaunchedEffect(map, styleLoads, routeStore) {
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
                // Прогноз на момент публикации; между выходами фильтра не пересчитывается.
                val now = st.pos?.let { nowcastPos(it, System.currentTimeMillis()) }
                style.showPosition(now)
                now?.let { p ->
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
            TripRow(tripLabel(s.trip, s.trips, s.running, s.loading), controller::refreshTrips, controller::selectTrip)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (s.running) controller.stop() else controller.start() },
                    enabled = s.loaded && permissionsGranted && (s.running || !s.loading),
                ) { Text(if (s.running) "Стоп" else "Старт") }
                val m = map
                if (free && m != null) {
                    OutlinedButton(onClick = {
                        policy.recenter()
                        free = false
                        controller.state.value.pos?.let { p ->
                            follow(m, policy, nowcastPos(p, System.currentTimeMillis()), jump = false)?.let { free = !it }
                        }
                    }) { Text("В центр") }
                }
            }
            if (!permissionsGranted) Text("Нужны разрешения на камеру и геопозицию")
            Text(s.status, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.weight(0.64f).fillMaxHeight()) {
            when (val r = mapData) {
                null -> if (tripDir == null && !s.loading) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Нет карты: поездка не выбрана")
                        Text(s.status, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Загрузка карты…", Modifier.padding(16.dp))
                }
                else -> r.fold(
                    onSuccess = { data ->
                        val night = isSystemInDarkTheme()
                        val styleJson = remember(data, night) { data.styleJson(night) }
                        // Новая MapData (другая поездка) — новая карта: onMapReady ставит камеру в центр её bounds.
                        key(data) {
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
                        }
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
                        val mapDir = File(tripDir ?: "<каталог поездки>", "map").path
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

/**
 * «Поездка: <имя>»: текстом или кнопкой с меню поездок (текущая отмечена «✓»); в старой раскладке ничего.
 * Открытие меню вызывает [onOpen] — перечитать список поездок.
 */
@Composable
private fun TripRow(label: TripLabel, onOpen: () -> Unit, onSelect: (String) -> Unit) {
    when (label) {
        TripLabel.None -> Unit
        is TripLabel.Text -> Text("Поездка: ${label.name}")
        is TripLabel.Picker -> Box {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { onOpen(); expanded = true }) { Text("Поездка: ${label.name} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (t in label.trips) {
                    DropdownMenuItem(text = { Text(if (t == label.name) "✓ $t" else t) }, onClick = { expanded = false; onSelect(t) })
                }
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
