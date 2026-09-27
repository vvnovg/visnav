package io.visnav.core

import java.util.PriorityQueue

data class Hit(val index: Int, val sim: Float)

/**
 * Полный перебор эталонов внутри окна неопределённости. Дескрипторы L2-нормированы,
 * поэтому скалярное произведение — это косинусное сходство. Для базы одного района
 * (тысячи эталонов) перебора достаточно; ANN-индекс нужен при переходе на коридоры (M3).
 */
class GeoIndex(private val pack: RefPack) {
    fun search(
        query: FloatArray,
        k: Int,
        centerLat: Double? = null,
        centerLon: Double? = null,
        radiusM: Double? = null,
    ): List<Hit> {
        require(query.size == pack.dim) { "query dim ${query.size} != ${pack.dim}" }
        require(k > 0) { "k must be > 0" }
        val useWindow = centerLat != null && centerLon != null && radiusM != null
        val lut = Half.LUT
        val desc = pack.descriptors
        val d = pack.dim
        val heap = PriorityQueue<Hit>(k, compareBy { it.sim })
        for (i in 0 until pack.count) {
            if (useWindow && Geo.haversineM(centerLat!!, centerLon!!, pack.lats[i], pack.lons[i]) > radiusM!!) continue
            var s = 0f
            val base = i * d
            for (j in 0 until d) s += lut[desc[base + j].toInt() and 0xFFFF] * query[j]
            if (heap.size < k) {
                heap.add(Hit(i, s))
            } else if (s > heap.peek().sim) {
                heap.poll()
                heap.add(Hit(i, s))
            }
        }
        return heap.sortedByDescending { it.sim }
    }
}
