package io.visnav.app

import android.content.Context
import android.os.Build
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.visnav.core.Geo
import io.visnav.core.LocalizationPipeline
import io.visnav.core.PriorMode
import io.visnav.core.PriorPolicy
import io.visnav.core.SessionHeader
import io.visnav.core.SessionLogger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val status: String = "Загрузка базы…",
    val loaded: Boolean = false,
    val running: Boolean = false,
    val mode: PriorMode = PriorMode.GPS,
    val frames: Int = 0,
    val lastSim: Float? = null,
    val lastErrM: Double? = null,
    val lastInfMs: Double? = null,
    val gpsAccM: Float? = null,
)

class M1Controller(private val context: Context, private val lifecycleOwner: LifecycleOwner) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val filesDir = requireNotNull(context.getExternalFilesDir(null))
    private val dataDir = File(filesDir, "refpack")
    private val logDir = File(filesDir, "logs")
    private val executor = Executors.newSingleThreadExecutor()
    private val gps = GpsSource(context)
    private val analyzer = AtomicReference<FrameAnalyzer?>(null)
    private var bundle: LoadedBundle? = null
    private var logger: SessionLogger? = null

    init {
        executor.execute {
            try {
                val b = BundleLoader.load(dataDir)
                bundle = b
                analyzer.set(FrameAnalyzer(intervalMs = 500, inputW = b.meta.inputW, inputH = b.meta.inputH))
                val parity = ParityCheck.runIfPresent(dataDir, b.embedder, File(logDir, "parity.json"))
                val parityText = parity?.let { " · parity cos=%.4f".format(it) } ?: ""
                _state.update {
                    it.copy(loaded = true, status = "База: ${b.pack.count} эталонов, модель ${b.meta.model}$parityText")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(status = "Нет базы: ${e.message}. Скопируйте файлы: adb push <bundle>/. " +
                        "/sdcard/Android/data/io.visnav.app/files/refpack/")
                }
            }
        }
    }

    fun setMode(mode: PriorMode) { if (!_state.value.running) _state.update { it.copy(mode = mode) } }

    fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
                )
                .build()
            analysis.setAnalyzer(executor) { image -> analyzer.get()?.analyze(image) ?: image.close() }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    fun start() {
        val b = bundle ?: return
        val frameAnalyzer = analyzer.get() ?: return
        val mode = _state.value.mode
        val pipeline = LocalizationPipeline(b.pack, b.embedder, PriorPolicy(mode))
        logDir.mkdirs()
        val startedMs = System.currentTimeMillis()
        val log = SessionLogger(File(logDir, "session-$startedMs-${mode.name.lowercase()}.jsonl"))
        log.header(SessionHeader(
            model = b.meta.model, refpackCreatedAt = b.meta.createdAt,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", startedMs = startedMs, mode = mode.name.lowercase(),
        ))
        logger = log
        gps.start()
        frameAnalyzer.onFrame = { tMs, rgb, preMs ->
            val fix = gps.fresh(tMs)
            val rec = pipeline.process(tMs, rgb, b.meta.inputW, b.meta.inputH, fix, preMs)
            log.frame(rec)
            val err = if (fix != null && rec.fix != null) Geo.haversineM(fix.lat, fix.lon, rec.fix!!.lat, rec.fix!!.lon) else null
            _state.update {
                it.copy(frames = it.frames + 1, lastSim = rec.fix?.sim, lastErrM = err,
                    lastInfMs = rec.latMs.inf, gpsAccM = fix?.accM)
            }
        }
        _state.update { it.copy(running = true, frames = 0, status = "Запись: ${mode.name}") }
    }

    fun stop() {
        analyzer.get()?.onFrame = null
        gps.stop()
        val log = logger
        logger = null
        // Закрываем на потоке анализа — после кадра, который, возможно, ещё обрабатывается.
        executor.execute { log?.close() }
        _state.update { it.copy(running = false, status = "Остановлено, кадров: ${it.frames}") }
    }
}
