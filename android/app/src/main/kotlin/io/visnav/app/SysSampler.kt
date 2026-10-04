package io.visnav.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import io.visnav.core.SysSample

/** Батарея и нагрев без разрешений: BatteryManager, sticky ACTION_BATTERY_CHANGED и PowerManager. */
class SysSampler(context: Context) {
    private val appContext = context.applicationContext
    private val battery = appContext.getSystemService(BatteryManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)

    fun sample(tMs: Long, intervalMs: Long): SysSample {
        val pct = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toLong()?.let(::batteryProp)
        val charge = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let(::batteryProp)
        val current = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let(::batteryCurrent)
        // Sticky-интент: приёмник не регистрируется, возвращается последнее состояние батареи.
        val sticky: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val thermal = power?.currentThermalStatus
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power?.getThermalHeadroom(10)?.toDouble()?.takeIf { it.isFinite() }
        } else null
        return SysSample(
            tMs = tMs, battPct = pct?.toDouble(), chargeUah = charge, currentUa = current, battTempC = temp,
            plugged = plugged, thermal = thermal, headroom = headroom, intervalMs = intervalMs,
        )
    }
}
