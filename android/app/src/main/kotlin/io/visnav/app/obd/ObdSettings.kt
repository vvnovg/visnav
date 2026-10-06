package io.visnav.app.obd

import io.visnav.core.ElmState

/**
 * Настройки OBD (SharedPreferences "visnav"): obd_kind — off/spp/ble, obd_addr и obd_name — выбранный адаптер
 * (null — не выбран), obd_addr_kind — вид (spp/ble), для которого он выбран. Меняются только вне записи.
 */
data class ObdSettings(
    val kind: String = OFF,
    val addr: String? = null,
    val name: String? = null,
    val addrKind: String? = null,
) {
    val enabled: Boolean get() = kind != OFF

    /** Смена вида: при выключении адаптер сохраняется; при включении — только если он выбран для этого же вида. */
    fun withKind(k: String): ObdSettings {
        val nk = kind(k)
        return if (nk == OFF || nk == addrKind) copy(kind = nk) else ObdSettings(nk)
    }

    companion object {
        const val OFF = "off"
        const val SPP = "spp"
        const val BLE = "ble"
        val KINDS = listOf(OFF, SPP, BLE)

        fun kind(v: String?): String = if (v in KINDS) v!! else OFF

        /** [addrKind] нет (настройки до obd_addr_kind) — адаптер относится к текущему виду. */
        fun parse(kind: String?, addr: String?, name: String?, addrKind: String? = null): ObdSettings {
            val k = kind(kind)
            val a = addr?.takeIf { it.isNotEmpty() } ?: return ObdSettings(k)
            val ak = kind(addrKind).takeIf { it != OFF } ?: k.takeIf { it != OFF }
            return ObdSettings(k, a, name, ak)
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

/**
 * Пара (k, kSlow) для сохранения или null. Сохраняется только после включения правила (b) SPEED_MISMATCH
 * (Localizer.obdDistanceArmed: kSlow сошлась или восстановленное значение прошло проверку); до того сохранённые
 * значения не меняются.
 */
internal fun factorsToSave(distanceArmed: Boolean, k: Double?, kSlow: Double?): Pair<Double, Double>? {
    if (!distanceArmed || k == null || kSlow == null || !k.isFinite() || !kSlow.isFinite()) return null
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
