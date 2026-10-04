package io.visnav.app

import android.content.Context
import android.speech.tts.TextToSpeech
import io.visnav.core.Enu
import io.visnav.core.Instructions
import io.visnav.core.LocalizerOutput
import io.visnav.core.NavEvent
import io.visnav.core.NavFormat
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.RouteFollower
import java.io.Closeable
import java.io.File
import java.util.Locale

data class NavUi(
    val nextText: String?, val routeKm: Double?, val routeMin: Double?,
    val arrived: Boolean, val rerouted: Boolean,
    /** Маршрут не построен (RouteFailed) и у ведения нет маршрута. */
    val routeFailed: Boolean,
)

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
        val next = f.nextManeuver
        return NavUi(
            nextText = next?.let { Instructions.prompt(f.maneuvers[it], f.distanceToNextM) },
            routeKm = r?.lengthM?.div(1000), routeMin = r?.durationS?.div(60),
            arrived = f.arrived, rerouted = rerouteAtMs?.let { o.tMs - it < REROUTE_SHOW_MS } ?: false,
            routeFailed = routeFailed,
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
    }
}
