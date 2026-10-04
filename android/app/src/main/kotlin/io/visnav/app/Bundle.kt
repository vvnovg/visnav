package io.visnav.app

import io.visnav.core.RefPack
import io.visnav.core.RefPackMeta
import io.visnav.core.RoadPack
import io.visnav.core.RoadPackMeta
import java.io.File
import java.nio.ByteBuffer

class LoadedBundle(
    val pack: RefPack, val meta: RefPackMeta, val embedder: OrtEmbedder,
    val roads: RoadPack? = null, val roadsMeta: RoadPackMeta? = null,
    val destination: Destination? = null,
    /** Неисправный route.json: запись идёт без маршрута, текст показывается в статусе. */
    val routeWarning: String? = null,
)

data class Destination(val lat: Double, val lon: Double, val name: String?)

object BundleLoader {
    /** Бросает IllegalStateException с понятным текстом, если файлы не положены через adb push. */
    fun load(dir: File, model: File = File(dir, "model.onnx")): LoadedBundle {
        val bin = File(dir, "refpack.bin")
        val json = File(dir, "refpack.json")
        for (f in listOf(bin, json, model)) check(f.isFile) { "нет файла ${f.absolutePath}" }
        val meta = RefPackMeta.parse(json.readText())
        val pack = RefPack.parse(ByteBuffer.wrap(bin.readBytes()))
        val roadsBin = File(dir, "roadpack.bin")
        val roadsJson = File(dir, "roadpack.json")
        check(roadsBin.isFile == roadsJson.isFile) { "граф дорог: нужны оба файла, roadpack.bin и roadpack.json" }
        val roadsMeta = try {
            if (roadsJson.isFile) RoadPackMeta.parse(roadsJson.readText()) else null
        } catch (e: Exception) {
            throw IllegalStateException("граф дорог: ${e.message}", e)
        }
        val roads = try {
            if (roadsBin.isFile) RoadPack.parse(ByteBuffer.wrap(roadsBin.readBytes())) else null
        } catch (e: Exception) {
            throw IllegalStateException("граф дорог: ${e.message}", e)
        }
        if (roads != null && roadsMeta != null) {
            check(roads.nodeCount == roadsMeta.nodeCount && roads.edgeCount == roadsMeta.edgeCount) {
                "roadpack.bin не совпадает с roadpack.json"
            }
        }
        val routeJson = File(dir, "route.json")
        var routeWarning: String? = null
        // Ошибка в route.json не мешает записи: маршрут просто не строится.
        val destination = if (roadsBin.isFile && routeJson.isFile) {
            try {
                val o = org.json.JSONObject(routeJson.readText())
                val lat = o.getDouble("dest_lat"); val lon = o.getDouble("dest_lon")
                check(lat in -90.0..90.0 && lon in -180.0..180.0) { "координаты вне диапазона" }
                Destination(lat, lon, o.optString("dest_name").ifEmpty { null })
            } catch (e: Exception) {
                routeWarning = "route.json: ${e.message} — маршрут не строится"
                null
            }
        } else null
        val embedder = OrtEmbedder(model, meta.model)
        try {
            check(embedder.inputH == meta.inputH && embedder.inputW == meta.inputW) {
                "модель ${embedder.inputW}x${embedder.inputH} не совпадает с refpack ${meta.inputW}x${meta.inputH}"
            }
            val probe = embedder.embed(
                ByteArray(embedder.inputW * embedder.inputH * 3), embedder.inputW, embedder.inputH,
            )
            check(probe.size == pack.dim) {
                "модель выдаёт дескриптор размерности ${probe.size}, а refpack ожидает ${pack.dim}"
            }
        } catch (e: Exception) {
            embedder.close()
            throw e
        }
        return LoadedBundle(pack, meta, embedder, roads, roadsMeta, destination, routeWarning)
    }
}
