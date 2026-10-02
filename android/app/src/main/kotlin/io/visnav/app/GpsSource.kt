package io.visnav.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import io.visnav.core.GpsFix

class GpsSource(context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)

    private data class TimedFix(val fix: GpsFix, val elapsedNanos: Long)

    @Volatile private var latest: TimedFix? = null

    @Volatile var onLoc: ((io.visnav.core.LocEvent) -> Unit)? = null
    @Volatile var onGnss: ((io.visnav.core.GnssStatusEvent) -> Unit)? = null
    private val gnssExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile var onAgc: ((io.visnav.core.AgcEvent) -> Unit)? = null
    private val gnssCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            val cb = onGnss ?: return
            var used = 0
            var sum = 0.0
            var sumSq = 0.0
            var max = Float.NEGATIVE_INFINITY
            var gps = 0; var glo = 0; var gal = 0; var bds = 0
            for (i in 0 until status.satelliteCount) {
                if (!status.usedInFix(i)) continue
                val c = status.getCn0DbHz(i)
                used++; sum += c; sumSq += c.toDouble() * c
                if (c > max) max = c
                when (status.getConstellationType(i)) {
                    android.location.GnssStatus.CONSTELLATION_GPS -> gps++
                    android.location.GnssStatus.CONSTELLATION_GLONASS -> glo++
                    android.location.GnssStatus.CONSTELLATION_GALILEO -> gal++
                    android.location.GnssStatus.CONSTELLATION_BEIDOU -> bds++
                }
            }
            val mean = if (used > 0) sum / used else 0.0
            // Стандартное отклонение по генеральной совокупности.
            val std = if (used > 0) Math.sqrt(maxOf(0.0, sumSq / used - mean * mean)) else 0.0
            cb(io.visnav.core.GnssStatusEvent(
                System.currentTimeMillis().toDouble(), status.satelliteCount, used,
                if (used > 0) mean.toFloat() else null,
                if (used > 0) std.toFloat() else null,
                if (used > 0) max else null,
                gps, glo, gal, bds,
            ))
        }
    }

    private val agcCallback = object : android.location.GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: android.location.GnssMeasurementsEvent) {
            val cb = onAgc ?: return
            val levels = ArrayList<Double>()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                for (a in event.gnssAutomaticGainControls) levels.add(a.levelDb)
            }
            if (levels.isEmpty()) {
                @Suppress("DEPRECATION")
                for (m in event.measurements) {
                    if (m.hasAutomaticGainControlLevelDb()) levels.add(m.automaticGainControlLevelDb)
                }
            }
            if (levels.isEmpty()) return
            cb(io.visnav.core.AgcEvent(System.currentTimeMillis().toDouble(), levels.average().toFloat(), levels.size))
        }
    }

    @SuppressLint("MissingPermission") // разрешение проверяет MainActivity до start()
    fun start() {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lm.registerGnssStatusCallback(gnssExecutor, gnssCallback)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
        }
        // Измерения требуют того же разрешения; если платформа их не отдаёт, AGC просто не пишется.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lm.registerGnssMeasurementsCallback(gnssExecutor, agcCallback)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssMeasurementsCallback(agcCallback, android.os.Handler(Looper.getMainLooper()))
        }
    }

    fun stop() {
        lm.removeUpdates(this)
        lm.unregisterGnssStatusCallback(gnssCallback)
        lm.unregisterGnssMeasurementsCallback(agcCallback)
    }

    /** Останавливает GPS/GNSS и освобождает поток gnssExecutor. Вызывать один раз при уничтожении владельца. */
    fun close() {
        stop()
        gnssExecutor.shutdown()
    }

    /**
     * Последняя GPS-фиксация не старше maxAgeMs, иначе null. Свежесть считается по монотонным
     * часам устройства (elapsedRealtimeNanos), а не по Location.time (может прыгать/рассинхронизироваться).
     */
    fun fresh(maxAgeMs: Long = 3000): GpsFix? {
        val f = latest ?: return null
        val deltaNanos = SystemClock.elapsedRealtimeNanos() - f.elapsedNanos
        return f.fix.takeIf { deltaNanos in 0..maxAgeMs * 1_000_000 }
    }

    override fun onLocationChanged(l: Location) {
        // l.time (wall clock, set by the GPS provider) can drift from the phone's own wall clock
        // or jump around; reconstruct the fix's wall-clock time from the phone's own clocks
        // instead, so frame.t_ms and gps.t_ms in the log are on the same clock (see C1).
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val tMs = System.currentTimeMillis() - (nowElapsedNanos - l.elapsedRealtimeNanos) / 1_000_000
        latest = TimedFix(GpsFix(l.latitude, l.longitude, l.accuracy, tMs), l.elapsedRealtimeNanos)
        onLoc?.invoke(io.visnav.core.LocEvent(
            tMs.toDouble(), l.latitude, l.longitude, l.accuracy,
            if (l.hasSpeed()) l.speed else null,
            if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
            if (l.hasBearing()) l.bearing else null,
            if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null,
        ))
    }

    // На API 29 эти методы ещё абстрактные — реализуем явно.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
