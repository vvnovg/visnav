package io.visnav.app

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Настройки замеров (SharedPreferences "visnav"): задержка буфера, ORT, профиль и длительность прогона.
 * Меняются только вне записи (методы M1Controller.set…).
 */
data class PerfSettings(
    val reorderDelayMs: Long = DEFAULT_REORDER_DELAY_MS,
    val ort: String = ORT_CPU,
    val profile: String = PROFILE_FULL,
    val durationMin: Int = 0,
) {
    val baseline: Boolean get() = profile == PROFILE_BASELINE

    companion object {
        const val DEFAULT_REORDER_DELAY_MS = 1500L
        const val ORT_CPU = "cpu"
        const val ORT_XNNPACK = "xnnpack"
        const val PROFILE_FULL = "full"
        const val PROFILE_BASELINE = "baseline"
        val REORDER_DELAYS_MS = listOf(300L, 500L, 800L, 1500L)
        val ORTS = listOf(ORT_CPU, ORT_XNNPACK)
        val PROFILES = listOf(PROFILE_FULL, PROFILE_BASELINE)
        val DURATIONS_MIN = listOf(0, 30, 60)

        fun reorderDelay(v: Long): Long = if (v in REORDER_DELAYS_MS) v else DEFAULT_REORDER_DELAY_MS
        fun ort(v: String?): String = if (v in ORTS) v!! else ORT_CPU
        fun profile(v: String?): String = if (v in PROFILES) v!! else PROFILE_FULL
        fun duration(v: Int): Int = if (v in DURATIONS_MIN) v else 0

        /** Следующий вариант по кругу; неизвестное значение — первый. */
        fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]
    }
}

/** Ёмкость батареи, мА·ч, по счётчику заряда и проценту: charge_uah / (batt_pct / 100) / 1000. */
internal fun batteryCapacityMah(chargeUah: Long?, battPct: Double?): Int? {
    if (chargeUah == null || battPct == null || !(battPct > 0.0)) return null
    return (chargeUah / (battPct / 100.0) / 1000.0).roundToInt()
}

/** Свойство BatteryManager: ≤ 0 или «нет данных» (Long/Int.MIN_VALUE) → null. */
internal fun batteryProp(v: Long): Long? = if (v <= 0L) null else v

/**
 * CURRENT_NOW: «нет данных» (Long/Int.MIN_VALUE) и 0 → null. Знак тока зависит от устройства (на части
 * телефонов разряд отрицательный), поэтому отрицательные значения сохраняются.
 */
internal fun batteryCurrent(v: Long): Long? = if (v == 0L || v == Long.MIN_VALUE || v == Int.MIN_VALUE.toLong()) null else v

/** Карта, которая хранит не больше [max] последних вставленных записей. */
internal fun <K, V> boundedMap(max: Int): MutableMap<K, V> = object : LinkedHashMap<K, V>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
}

/** Медиана (nearest-rank), null для пустого списка. */
internal fun p50(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[ceil(0.5 * sorted.size).toInt() - 1]
}
