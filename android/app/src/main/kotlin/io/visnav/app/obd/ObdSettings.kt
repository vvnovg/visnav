package io.visnav.app.obd

import io.visnav.core.ElmState

/**
 * Настройки OBD (SharedPreferences "visnav"): obd_kind — off/spp/ble, obd_addr и obd_name — выбранный адаптер
 * (null — не выбран). Меняются только вне записи.
 */
data class ObdSettings(val kind: String = OFF, val addr: String? = null, val name: String? = null) {
    val enabled: Boolean get() = kind != OFF

    companion object {
        const val OFF = "off"
        const val SPP = "spp"
        const val BLE = "ble"
        val KINDS = listOf(OFF, SPP, BLE)

        fun kind(v: String?): String = if (v in KINDS) v!! else OFF

        fun parse(kind: String?, addr: String?, name: String?): ObdSettings {
            val a = addr?.takeIf { it.isNotEmpty() }
            return ObdSettings(kind(kind), a, if (a != null) name else null)
        }

        /** Следующий вид по кругу: выкл → Bluetooth → BLE → выкл. */
        fun next(kind: String): String = KINDS[(KINDS.indexOf(kind(kind)) + 1) % KINDS.size]
    }
}

/** Состояние OBD на «Отладке» во время записи. */
data class ObdUi(val state: String, val lastKmh: Int? = null, val rttMs: Double? = null, val deviceName: String? = null)

/** Адаптер в списке выбора: сопряжённый (Bluetooth) или найденный поиском (BLE). */
data class ObdDevice(val addr: String, val name: String)

internal fun obdKindLabel(kind: String) = when (kind) {
    ObdSettings.SPP -> "Bluetooth"
    ObdSettings.BLE -> "BLE"
    else -> "выкл"
}

/** Имена адаптеров ELM327 для поиска BLE (без учёта регистра, вхождение). */
private val OBD_NAMES = listOf("OBD", "OBDII", "V-LINK", "IOS-Vlink", "Vgate", "OBDLink")

internal fun isObdName(name: String?): Boolean =
    !name.isNullOrEmpty() && OBD_NAMES.any { name.contains(it, ignoreCase = true) }

/**
 * Разрешения времени выполнения для вида OBD: на Android 12+ BLUETOOTH_CONNECT (+ BLUETOOTH_SCAN для BLE);
 * раньше BLUETOOTH/BLUETOOTH_ADMIN выдаются при установке, а поиск BLE требует геопозиции.
 */
internal fun obdPermissions(kind: String, sdk: Int): List<String> = when {
    kind == ObdSettings.OFF -> emptyList()
    sdk >= 31 -> listOfNotNull(BLUETOOTH_CONNECT, if (kind == ObdSettings.BLE) BLUETOOTH_SCAN else null)
    kind == ObdSettings.BLE -> listOf(ACCESS_FINE_LOCATION)
    else -> emptyList()
}

// Строками, а не Manifest.permission.*: константы Android 12 при minSdk 29 (lint InlinedApi).
private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
private const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

/** Ключи сохранённых поправок спидометра по адаптеру. */
internal fun obdKKey(addr: String) = "obd_k_$addr"
internal fun obdKSlowKey(addr: String) = "obd_kslow_$addr"

/** Минимальное время обучения в сессии, после которого поправки сохраняются, мс. */
internal const val OBD_SAVE_LEARNED_MS = 600_000.0

/** Пара (k, kSlow) для сохранения или null: обучения меньше 10 мин либо значений нет. */
internal fun factorsToSave(learnedMs: Double, k: Double?, kSlow: Double?): Pair<Double, Double>? {
    if (learnedMs < OBD_SAVE_LEARNED_MS || k == null || kSlow == null || !k.isFinite() || !kSlow.isFinite()) return null
    return k to kSlow
}

/** Сохранённая поправка: нет, NaN или ≤ 0 — null (Localizer ограничит остальное до [kMin, kMax]). */
internal fun restoredFactor(v: Float?): Double? = v?.takeIf { it.isFinite() && it > 0f }?.toDouble()

internal fun obdStateLabel(s: ElmState, msg: String?): String {
    val label = when (s) {
        ElmState.CONNECTING -> "подключение"
        ElmState.INIT -> "инициализация"
        ElmState.SEARCHING -> "поиск протокола"
        ElmState.POLLING -> "опрос"
        ElmState.NO_DATA -> "нет данных"
        ElmState.ERROR -> "ошибка"
        ElmState.RECONNECT_WAIT -> return "переподключение" + (msg?.let { " через $it" } ?: "")
        ElmState.STOPPED -> "остановлено"
    }
    return label + (msg?.let { ": $it" } ?: "")
}

/** «OBD: <состояние> · <км/ч> · RTT <мс>»; скорость и RTT — когда были. */
internal fun obdLine(o: ObdUi?): String {
    if (o == null) return "OBD: —"
    return "OBD: ${o.state}" + (o.lastKmh?.let { " · $it км/ч" } ?: "") + (o.rttMs?.let { " · RTT %.0f мс".format(it) } ?: "")
}
