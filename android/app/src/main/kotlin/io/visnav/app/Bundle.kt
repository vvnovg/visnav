package io.visnav.app

import io.visnav.core.RefPack
import io.visnav.core.RefPackMeta
import java.io.File
import java.nio.ByteBuffer

class LoadedBundle(val pack: RefPack, val meta: RefPackMeta, val embedder: OrtEmbedder)

object BundleLoader {
    /** Бросает IllegalStateException с понятным текстом, если файлы не положены через adb push. */
    fun load(dir: File): LoadedBundle {
        val bin = File(dir, "refpack.bin")
        val json = File(dir, "refpack.json")
        val model = File(dir, "model.onnx")
        for (f in listOf(bin, json, model)) check(f.isFile) { "нет файла ${f.absolutePath}" }
        val meta = RefPackMeta.parse(json.readText())
        val pack = RefPack.parse(ByteBuffer.wrap(bin.readBytes()))
        val embedder = OrtEmbedder(model, meta.model)
        check(embedder.inputH == meta.inputH && embedder.inputW == meta.inputW) {
            "модель ${embedder.inputW}x${embedder.inputH} не совпадает с refpack ${meta.inputW}x${meta.inputH}"
        }
        return LoadedBundle(pack, meta, embedder)
    }
}
