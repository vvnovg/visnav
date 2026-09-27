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
import io.visnav.core.ClockEvent
import io.visnav.core.GyroEvent
import io.visnav.core.GyroUncalEvent
import io.visnav.core.SensorEvent

private const val CLOCK_EVENT_PERIOD_MS = 1000.0
private const val CLOCK_SYNC_WARN_NS = 5_000_000_000L // 5 s

/**
 * Гироскоп и акселерометр с периодом 10 мс на отдельном потоке. Метка SensorEvent.timestamp —
 * монотонные часы (отсчёт elapsedRealtimeNanos); переводим в настенное время телефона через
 * смещение, зафиксированное при start(), чтобы датчики, кадры и GPS были на одних часах.
 */
class SensorRecorder(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    @Volatile private var sink: ((SensorEvent) -> Unit)? = null

    /** Смещение «монотонные часы -> настенное время», зафиксированное в start(); 0.0, пока запись не идёт. */
    @Volatile var offsetMs: Double = 0.0
        private set

    /**
     * Вызывается ровно один раз — на первом событии датчика, если его метка (SystemEvent.timestamp,
     * монотонные часы) разошлась с elapsedRealtimeNanos() больше чем на 5 с: признак того, что метки
     * датчиков не на часах elapsedRealtime и синхронизация с кадрами/GPS может быть неверной.
     */
    @Volatile var onWarning: ((String) -> Unit)? = null

    private var firstEventChecked = false
    private var lastClockEventMs = Double.NEGATIVE_INFINITY

    fun start(sink: (SensorEvent) -> Unit): Boolean {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        offsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1e6
        firstEventChecked = false
        lastClockEventMs = Double.NEGATIVE_INFINITY
        this.sink = sink
        val t = HandlerThread("sensors").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        // maxReportLatencyUs = 100 мс — разрешаем системе батчить события (экономия энергии);
        // на метки времени (SensorEvent.timestamp) это не влияет, только на задержку доставки.
        sm.registerListener(this, gyro, 10_000, 100_000, handler)
        sm.registerListener(this, accel, 10_000, 100_000, handler)
        // Некалиброванный гироскоп — не на всех устройствах; его отсутствие не должно останавливать запись.
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)?.let {
            sm.registerListener(this, it, 10_000, 100_000, handler)
        }
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
        if (!firstEventChecked) {
            firstEventChecked = true
            val diffNs = e.timestamp - SystemClock.elapsedRealtimeNanos()
            if (Math.abs(diffNs) > CLOCK_SYNC_WARN_NS) {
                onWarning?.invoke("метки датчиков не на часах elapsedRealtime — синхронизация может быть неверной")
            }
        }
        val tMs = offsetMs + e.timestamp / 1e6
        if (tMs - lastClockEventMs >= CLOCK_EVENT_PERIOD_MS) {
            lastClockEventMs = tMs
            s(ClockEvent(tMs, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()))
        }
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> s(GyroEvent(tMs, e.values[0], e.values[1], e.values[2]))
            Sensor.TYPE_ACCELEROMETER -> s(AccelEvent(tMs, e.values[0], e.values[1], e.values[2]))
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> s(
                GyroUncalEvent(tMs, e.values[0], e.values[1], e.values[2], e.values[3], e.values[4], e.values[5])
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
