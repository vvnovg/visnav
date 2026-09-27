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
    private val gnssCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            val cb = onGnss ?: return
            var used = 0
            var cn0Sum = 0.0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) { used++; cn0Sum += status.getCn0DbHz(i) }
            }
            cb(io.visnav.core.GnssStatusEvent(System.currentTimeMillis().toDouble(), status.satelliteCount, used,
                if (used > 0) (cn0Sum / used).toFloat() else null))
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
    }

    fun stop() {
        lm.removeUpdates(this)
        lm.unregisterGnssStatusCallback(gnssCallback)
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
