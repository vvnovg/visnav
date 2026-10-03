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
)

object BundleLoader {
    /** Бросает IllegalStateException с понятным текстом, если файлы не положены через adb push. */
    fun load(dir: File): LoadedBundle {
        val bin = File(dir, "refpack.bin")
        val json = File(dir, "refpack.json")
        val model = File(dir, "model.onnx")
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
        return LoadedBundle(pack, meta, embedder, roads, roadsMeta)
    }
}
