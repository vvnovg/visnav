package io.visnav.app

import android.content.Context
import android.speech.tts.TextToSpeech
import io.visnav.core.Enu
import io.visnav.core.Instructions
import io.visnav.core.LocalizerOutput
import io.visnav.core.Maneuver
import io.visnav.core.ManeuverType
import io.visnav.core.NavEvent
import io.visnav.core.NavFormat
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.Route
import io.visnav.core.RouteFollower
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class NavUi(
    val nextText: String?, val routeKm: Double?, val routeMin: Double?,
    val arrived: Boolean, val rerouted: Boolean,
    /** Маршрут не построен (RouteFailed) и у ведения нет маршрута. */
    val routeFailed: Boolean,
    /** Следующий манёвр (без DEPART): тип, номер съезда на кольце (0 — не кольцо), улица после манёвра. */
    val nextType: ManeuverType? = null, val nextExit: Int = 0, val nextStreet: String? = null,
    /** Расстояние по маршруту до следующего манёвра, м. */
    val nextDistM: Double? = null,
    /** Осталось по маршруту, м: длина маршрута минус прогресс (не меньше 0). */
    val remainingM: Double? = null,
    /** Версия маршрута: растёт при каждом новом маршруте; 0 — маршрута ещё нет. */
    val routeVersion: Int = 0,
    /**
     * Точки маршрута [lat, lon] — от NavSession только в обновлении, где сменилась версия, иначе пусто.
     * В UiState.nav списки текущей версии сохраняются (см. [mergeNav]).
     */
    val routeLatLon: List<DoubleArray> = emptyList(),
    /**
     * Точки манёвров без DEPART [lat, lon] — от NavSession только в обновлении, где сменилась версия, иначе пусто.
     * В UiState.nav списки текущей версии сохраняются (см. [mergeNav]).
     */
    val maneuverLatLon: List<DoubleArray> = emptyList(),
)

/**
 * NavUi по состоянию ведения. Геометрия маршрута ([NavUi.routeLatLon], [NavUi.maneuverLatLon]) переводится
 * в широту/долготу только при [sendRoute] — в обновлении, где сменилась версия маршрута.
 */
internal fun navUiOf(
    route: Route?, maneuvers: List<Maneuver>, next: Int?, distanceToNextM: Double?, progressM: Double,
    arrived: Boolean, rerouted: Boolean, routeFailed: Boolean, routeVersion: Int, sendRoute: Boolean, enu: Enu,
): NavUi {
    val m = next?.let { maneuvers[it] }
    return NavUi(
        nextText = m?.let { Instructions.prompt(it, distanceToNextM) },
        routeKm = route?.lengthM?.div(1000), routeMin = route?.durationS?.div(60),
        arrived = arrived, rerouted = rerouted, routeFailed = routeFailed,
        nextType = m?.type, nextExit = m?.exit ?: 0, nextStreet = m?.street, nextDistM = distanceToNextM,
        remainingM = route?.let { (it.lengthM - progressM).coerceAtLeast(0.0) },
        routeVersion = routeVersion,
        routeLatLon = if (sendRoute && route != null) route.points.map { enu.toLatLon(it[0], it[1]) } else emptyList(),
        maneuverLatLon = if (sendRoute) {
            maneuvers.filter { it.type != ManeuverType.DEPART }.map { enu.toLatLon(it.e, it.n) }
        } else emptyList(),
    )
}

/**
 * Новое значение NavUi для UiState: null (нет вывода ведения на кадре) оставляет прежнее. При той же версии маршрута
 * пустые списки геометрии заменяются прежними — StateFlow сливает быстрые обновления, и обновление со сменой версии
 * могло бы не дойти до экрана.
 */
internal fun mergeNav(prev: NavUi?, next: NavUi?): NavUi? = when {
    next == null -> prev
    prev != null && next.routeVersion == prev.routeVersion && next.routeLatLon.isEmpty() && next.maneuverLatLon.isEmpty() ->
        next.copy(routeLatLon = prev.routeLatLon, maneuverLatLon = prev.maneuverLatLon)
    else -> next
}

/** Ведение по маршруту на телефоне: RouteFollower по выводу Localizer, журнал .nav.jsonl и голос (TextToSpeech). */
class NavSession(
    context: Context, private val roads: RoadPack, private val dest: Destination, navLog: File,
    sessionStartedMs: Long, roadsCreatedAt: String, private val onTtsUnavailable: () -> Unit,
) : Closeable {
    /** Состояние синтеза: колбэк инициализации приходит на главном потоке, подсказки — с потока кадров. */
    private val ttsLock = Any()
    /** null — синтез ещё инициализируется; true — готов и русский язык поддерживается. */
    private var ttsReady: Boolean? = null
    private var ttsReported = false
    private var closed = false
    /** Подсказки, пришедшие до конца инициализации (не больше PENDING_MAX, старые вытесняются). */
    private val pending = ArrayDeque<String>()
    private lateinit var tts: TextToSpeech
    private val log: java.io.BufferedWriter
    private var enu: Enu? = null
    private var follower: RouteFollower? = null
    private var routeFailedSeen = false
    private var routeFailedSpoken = false
    private var rerouteAtMs: Long? = null
    /** Маршрут, геометрия которого уже передана в NavUi, и его версия. */
    private var sentRoute: Route? = null
    private var routeVersion = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onTtsInit(status) }
        log = try {
            navLog.bufferedWriter()
        } catch (e: Exception) {
            tts.shutdown()
            throw e
        }
        try {
            log.write(NavFormat.header(sessionStartedMs, roadsCreatedAt, dest.lat, dest.lon, emptyList(), emptyList(), emptyList()))
            log.newLine()
        } catch (e: Exception) {
            // Конструктор не вернётся — освобождаем журнал и синтез речи здесь.
            try { close() } catch (_: Exception) { }
            throw e
        }
    }

    /** Колбэк инициализации: язык выбирается один раз; очередь озвучивается или сбрасывается. */
    private fun onTtsInit(status: Int) {
        synchronized(ttsLock) {
            if (closed) return
            // При ошибке привязки к движку колбэк может прийти прямо из конструктора, до присваивания tts;
            // к tts обращаемся только при SUCCESS (он приходит асинхронно, после конструктора).
            val ok = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale("ru", "RU")).let {
                it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
            }
            ttsReady = ok
            if (ok) {
                while (pending.isNotEmpty()) say(pending.removeFirst())
            } else {
                pending.clear()
                reportUnavailable()
            }
        }
    }

    /** Вызывается на потоке кадров по порядку времени. */
    fun onOutput(o: LocalizerOutput): NavUi? {
        if (!o.lat.isFinite() || !o.lon.isFinite()) return null
        val e0 = enu ?: Enu(o.lat, o.lon).also { enu = it }
        val f = follower ?: run {
            val d = e0.toEn(dest.lat, dest.lon)
            RouteFollower(RoadIndex(roads, e0), d[0], d[1]).also { follower = it }
        }
        val en = e0.toEn(o.lat, o.lon)
        val events = f.update(o.tMs, en[0], en[1], o.sigmaM, o.psiRad, o.speedMps)
        var reroute = false
        val texts = ArrayList<String>()
        for (ev in events) {
            log.write(NavFormat.event(ev, e0)); log.newLine()
            when (ev) {
                is NavEvent.Prompt -> texts += ev.text
                is NavEvent.RouteReady -> if (ev.reroute) { reroute = true; rerouteAtMs = ev.tMs }
                is NavEvent.RouteFailed -> routeFailedSeen = true
                is NavEvent.Arrived -> Unit
            }
        }
        // Перестроение и подсказка одного обновления звучат одной фразой; в журнале текст подсказки не меняется.
        if (reroute) {
            speak(if (texts.isEmpty()) "Маршрут перестроен" else "Маршрут перестроен. " + texts.first())
            texts.drop(1).forEach { speak(it) }
        } else {
            texts.forEach { speak(it) }
        }
        val r = f.route
        if (r != null) routeFailedSpoken = false     // «не найден» прозвучит снова, только если маршрут был и пропал
        val routeFailed = routeFailedSeen && r == null
        if (routeFailed && !routeFailedSpoken) { routeFailedSpoken = true; speak("Маршрут не найден") }
        val newRoute = r != null && r !== sentRoute
        if (newRoute) { sentRoute = r; routeVersion = ROUTE_VERSIONS.incrementAndGet() }
        return navUiOf(
            route = r, maneuvers = f.maneuvers, next = f.nextManeuver, distanceToNextM = f.distanceToNextM,
            progressM = f.progressM, arrived = f.arrived,
            rerouted = rerouteAtMs?.let { o.tMs - it < REROUTE_SHOW_MS } ?: false,
            routeFailed = routeFailed, routeVersion = routeVersion, sendRoute = newRoute, enu = e0,
        )
    }

    private fun speak(text: String) {
        synchronized(ttsLock) {
            if (closed) return
            when (ttsReady) {
                null -> {
                    if (pending.size >= PENDING_MAX) pending.removeFirst()
                    pending.addLast(text)
                }
                true -> say(text)
                // Подсказка остаётся в журнале и на экране.
                false -> reportUnavailable()
            }
        }
    }

    private fun say(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "nav-${System.nanoTime()}")
    }

    private fun reportUnavailable() {
        if (!ttsReported) { ttsReported = true; onTtsUnavailable() }
    }

    override fun close() {
        synchronized(ttsLock) { closed = true; pending.clear() }
        try { log.close() } finally { tts.stop(); tts.shutdown() }
    }

    private companion object {
        const val PENDING_MAX = 3
        /** Сколько по времени вывода держится отметка «Маршрут перестроен» на экране. */
        const val REROUTE_SHOW_MS = 5000L
        /** Общий счётчик версий маршрута: версии не повторяются и между сессиями записи. */
        val ROUTE_VERSIONS = AtomicInteger(0)
    }
}
