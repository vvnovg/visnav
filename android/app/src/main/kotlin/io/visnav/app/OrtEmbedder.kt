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

/**
 * ONNX-модель с контрактом M1: "image" uint8 [1,H,W,3] RGB → "descriptor" float32 [1,D].
 * При [xnnpack] сессия создаётся с XNNPACK; если не создаётся — на CPU, фактическое значение в [ort].
 */
class OrtEmbedder(modelFile: File, override val name: String, xnnpack: Boolean = false) : Embedder, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options: OrtSession.SessionOptions
    private val session: OrtSession
    /** Фактический исполнитель: "cpu" или "xnnpack". */
    val ort: String
    val inputH: Int
    val inputW: Int

    init {
        var created: Pair<OrtSession.SessionOptions, OrtSession>? = null
        if (xnnpack) {
            val opts = cpuOptions()
            created = try {
                opts.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                opts to env.createSession(modelFile.absolutePath, opts)
            } catch (_: Exception) {
                opts.close()
                null
            }
        }
        ort = if (created != null) PerfSettings.ORT_XNNPACK else PerfSettings.ORT_CPU
        val (opts, s) = created ?: cpuOptions().let { opts ->
            try {
                opts to env.createSession(modelFile.absolutePath, opts)
            } catch (e: Exception) {
                opts.close()
                throw e
            }
        }
        options = opts
        session = s
        val shape = (session.inputInfo.getValue("image").info as TensorInfo).shape
        inputH = shape[1].toInt()
        inputW = shape[2].toInt()
    }

    private fun cpuOptions() = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }

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
