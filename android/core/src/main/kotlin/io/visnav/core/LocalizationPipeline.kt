package io.visnav.core

interface Embedder {
    val name: String
    /** rgb — плотный RGB uint8 размера width × height (уже под вход модели). Возвращает L2-нормированный дескриптор. */
    fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray
}

/** Один кадр → дескриптор → окно поиска → top-k эталонов → запись журнала. Без фильтра (M1). */
class LocalizationPipeline(
    private val pack: RefPack,
    private val embedder: Embedder,
    private val priorPolicy: PriorPolicy,
    private val k: Int = 5,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onDescriptor: ((tMs: Long, desc: FloatArray) -> Unit)? = null,
) {
    private val index = GeoIndex(pack)

    fun process(tMs: Long, rgb: ByteArray, width: Int, height: Int, gps: GpsFix?, preMs: Double): FrameRecord {
        if (gps != null) priorPolicy.onGps(gps)
        val t0 = nanoTime()
        val desc = embedder.embed(rgb, width, height)
        val t1 = nanoTime()
        onDescriptor?.invoke(tMs, desc)
        val prior = priorPolicy.prior(tMs)
        val hits = index.search(desc, k, prior?.lat, prior?.lon, prior?.radiusM)
        val t2 = nanoTime()
        val best = hits.firstOrNull()
        val fix = best?.let { Fix(pack.lats[it.index], pack.lons[it.index], it.sim, tMs) }
        if (fix != null) priorPolicy.onVisualFix(fix)
        return FrameRecord(
            tMs = tMs,
            mode = priorPolicy.mode.name.lowercase(),
            gps = gps?.let { GpsJson(it.lat, it.lon, it.accM, it.tMs) },
            prior = prior?.let { PriorJson(it.lat, it.lon, it.radiusM) },
            top = hits.map { HitJson(it.index, it.sim, pack.lats[it.index], pack.lons[it.index]) },
            fix = fix?.let { FixJson(it.lat, it.lon, it.sim) },
            latMs = LatencyJson(preMs, (t1 - t0) / 1e6, (t2 - t1) / 1e6),
        )
    }
}
