package io.visnav.core

import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive

/** Показания батареи и нагрева; `null` — значение недоступно на устройстве. */
data class SysSample(
    val tMs: Long, val battPct: Double?, val chargeUah: Long?, val currentUa: Long?, val battTempC: Double?,
    val plugged: Boolean, val thermal: Int?, val headroom: Double?, val intervalMs: Long,
)

/** Опоздание событий одного источника за окно, мс. */
data class LatenessStats(val n: Int, val p50: Double, val p99: Double, val max: Double)

/** Строки `.perf.jsonl` v1: заголовок, кадр, система, опоздания. Миллисекунды — с 1 знаком после точки. */
object PerfLog {
    fun header(
        sessionStartedMs: Long, device: String, profile: String, reorderDelayMs: Long, ort: String, batteryCapacityMah: Int?,
    ): String =
        "{\"type\":\"perf\",\"v\":1,\"session_started_ms\":$sessionStartedMs,\"device\":${JsonPrimitive(device)}," +
            "\"profile\":${JsonPrimitive(profile)},\"reorder_delay_ms\":$reorderDelayMs,\"ort\":${JsonPrimitive(ort)}," +
            "\"battery_capacity_mah\":${batteryCapacityMah ?: "null"}}"

    fun frame(
        tMs: Long, pre: Double, inf: Double, search: Double, fuseMs: Double, navMs: Double, e2eMs: Double, intervalMs: Long,
    ): String =
        "{\"type\":\"frame\",\"t_ms\":$tMs,\"pre\":${ms(pre)},\"inf\":${ms(inf)},\"search\":${ms(search)}," +
            "\"fuse_ms\":${ms(fuseMs)},\"nav_ms\":${ms(navMs)},\"e2e_ms\":${ms(e2eMs)},\"interval_ms\":$intervalMs}"

    fun sys(s: SysSample): String =
        "{\"type\":\"sys\",\"t_ms\":${s.tMs},\"batt_pct\":${num(s.battPct)},\"charge_uah\":${s.chargeUah ?: "null"}," +
            "\"current_ua\":${s.currentUa ?: "null"},\"batt_temp_c\":${num(s.battTempC)},\"plugged\":${s.plugged}," +
            "\"thermal\":${s.thermal ?: "null"},\"headroom\":${num(s.headroom)},\"interval_ms\":${s.intervalMs}}"

    /** [lateBySource] — накопительные счётчики опоздавших по источникам; в `late_src` идут только ненулевые, в порядке карты. */
    fun late(tMs: Long, stats: Map<String, LatenessStats>, late: Int, dropped: Int, lateBySource: Map<String, Int>): String {
        val sources = stats.entries.joinToString(",") { (src, st) ->
            "${JsonPrimitive(src)}:{\"n\":${st.n},\"p50\":${ms(st.p50)},\"p99\":${ms(st.p99)},\"max\":${ms(st.max)}}"
        }
        val lateSrc = lateBySource.entries.filter { it.value != 0 }.joinToString(",") { (src, n) -> "${JsonPrimitive(src)}:$n" }
        return "{\"type\":\"late\",\"t_ms\":$tMs,\"sources\":{$sources},\"late\":$late,\"dropped\":$dropped," +
            "\"late_src\":{$lateSrc}}"
    }

    /** JSON не знает NaN/Infinity — не-конечное значение пишется как `null`. */
    private fun ms(x: Double): String = if (x.isFinite()) String.format(Locale.ROOT, "%.1f", x) else "null"
    private fun num(x: Double?): String = if (x != null && x.isFinite()) x.toString() else "null"
}
