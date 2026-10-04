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
    val nextText: String?, val distM: Double?, val routeKm: Double?, val routeMin: Double?,
    val arrived: Boolean, val rerouted: Boolean,
)

/** Ведение по маршруту на телефоне: RouteFollower по выводу Localizer, журнал .nav.jsonl и голос (TextToSpeech). */
class NavSession(
    context: Context, private val roads: RoadPack, private val dest: Destination, navLog: File,
    sessionStartedMs: Long, roadsCreatedAt: String, private val onTtsUnavailable: () -> Unit,
) : Closeable {
    private val log = navLog.bufferedWriter()
    /** null — синтез ещё инициализируется (колбэк приходит асинхронно на главном потоке). */
    @Volatile private var ttsReady: Boolean? = null
    private var ttsReported = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }
    private var enu: Enu? = null
    private var follower: RouteFollower? = null

    init {
        try {
            log.write(NavFormat.header(sessionStartedMs, roadsCreatedAt, dest.lat, dest.lon, emptyList(), emptyList(), emptyList()))
            log.newLine()
        } catch (e: Exception) {
            // Конструктор не вернётся — освобождаем журнал и синтез речи здесь.
            try { close() } catch (_: Exception) { }
            throw e
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
        var rerouted = false
        for (ev in events) {
            log.write(NavFormat.event(ev, e0)); log.newLine()
            when (ev) {
                is NavEvent.Prompt -> speak(ev.text)
                is NavEvent.RouteReady -> if (ev.reroute) { rerouted = true; speak("Маршрут перестроен") }
                else -> Unit
            }
        }
        val r = f.route
        val next = f.nextManeuver
        return NavUi(
            nextText = next?.let { Instructions.prompt(f.maneuvers[it], f.distanceToNextM) },
            distM = f.distanceToNextM, routeKm = r?.lengthM?.div(1000), routeMin = r?.durationS?.div(60),
            arrived = f.arrived, rerouted = rerouted,
        )
    }

    private fun speak(text: String) {
        val ready = ttsReady
        // Ещё не готов: подсказка остаётся в журнале и на экране, но «недоступен» не сообщаем.
        if (ready == null) return
        if (!ready) {
            if (!ttsReported) { ttsReported = true; onTtsUnavailable() }
            return
        }
        val r = tts.setLanguage(Locale("ru", "RU"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (!ttsReported) { ttsReported = true; onTtsUnavailable() }
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "nav-${System.nanoTime()}")
    }

    override fun close() {
        try { log.close() } finally { tts.stop(); tts.shutdown() }
    }
}
