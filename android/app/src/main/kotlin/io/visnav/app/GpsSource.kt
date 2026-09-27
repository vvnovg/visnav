package io.visnav.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import io.visnav.core.GpsFix

class GpsSource(context: Context) : LocationListener {
    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile var latest: GpsFix? = null
        private set

    @SuppressLint("MissingPermission") // разрешение проверяет MainActivity до start()
    fun start() = lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())

    fun stop() = lm.removeUpdates(this)

    /** Последний GPS не старше maxAgeMs, иначе null. */
    fun fresh(nowMs: Long, maxAgeMs: Long = 3000): GpsFix? = latest?.takeIf { nowMs - it.tMs <= maxAgeMs }

    override fun onLocationChanged(l: Location) {
        latest = GpsFix(l.latitude, l.longitude, l.accuracy, l.time)
    }

    // На API 29 эти методы ещё абстрактные — реализуем явно.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
