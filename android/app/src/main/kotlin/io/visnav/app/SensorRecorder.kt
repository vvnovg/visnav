package io.visnav.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent as AndroidSensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.visnav.core.AccelEvent
import io.visnav.core.GyroEvent
import io.visnav.core.SensorEvent

/**
 * Гироскоп и акселерометр с периодом 10 мс на отдельном потоке. Метка SensorEvent.timestamp —
 * монотонные часы (отсчёт elapsedRealtimeNanos); переводим в настенное время телефона через
 * смещение, зафиксированное при start(), чтобы датчики, кадры и GPS были на одних часах.
 */
class SensorRecorder(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    @Volatile private var sink: ((SensorEvent) -> Unit)? = null
    private var offsetMs = 0.0

    fun start(sink: (SensorEvent) -> Unit): Boolean {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        offsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1e6
        this.sink = sink
        val t = HandlerThread("sensors").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        // maxReportLatencyUs = 100 мс — разрешаем системе батчить события (экономия энергии);
        // на метки времени (SensorEvent.timestamp) это не влияет, только на задержку доставки.
        sm.registerListener(this, gyro, 10_000, 100_000, handler)
        sm.registerListener(this, accel, 10_000, 100_000, handler)
        return true
    }

    fun stop() {
        sm.unregisterListener(this)
        sink = null
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: AndroidSensorEvent) {
        val s = sink ?: return
        val tMs = offsetMs + e.timestamp / 1e6
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> s(GyroEvent(tMs, e.values[0], e.values[1], e.values[2]))
            Sensor.TYPE_ACCELEROMETER -> s(AccelEvent(tMs, e.values[0], e.values[1], e.values[2]))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
