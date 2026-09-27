package io.visnav.app

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.visnav.core.Embedder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ONNX-модель с контрактом M1: "image" uint8 [1,H,W,3] RGB → "descriptor" float32 [1,D]. */
class OrtEmbedder(modelFile: File, override val name: String) : Embedder, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    private val session: OrtSession = env.createSession(modelFile.absolutePath, options)
    val inputH: Int
    val inputW: Int

    init {
        val shape = (session.inputInfo.getValue("image").info as TensorInfo).shape
        inputH = shape[1].toInt()
        inputW = shape[2].toInt()
    }

    override fun embed(rgb: ByteArray, width: Int, height: Int): FloatArray {
        require(width == inputW && height == inputH) { "expected ${inputW}x$inputH, got ${width}x$height" }
        val buf = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder())
        buf.put(rgb).rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, height.toLong(), width.toLong(), 3), OnnxJavaType.UINT8)
            .use { tensor ->
                session.run(mapOf("image" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    return (result[0].value as Array<FloatArray>)[0]
                }
            }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
