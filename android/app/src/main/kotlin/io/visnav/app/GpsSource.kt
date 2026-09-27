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

    @SuppressLint("MissingPermission") // разрешение проверяет MainActivity до start()
    fun start() = lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())

    fun stop() = lm.removeUpdates(this)

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
    }

    // На API 29 эти методы ещё абстрактные — реализуем явно.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
