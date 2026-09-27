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
import java.util.concurrent.atomic.AtomicBoolean
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
    val errors: Int = 0,
    val lastSim: Float? = null,
    val lastErrM: Double? = null,
    val lastInfMs: Double? = null,
    val gpsAccM: Float? = null,
)

/**
 * Владеет камерой, GPS, конвейером и журналом одной сессии M1. Не зависит от LifecycleOwner —
 * его должен пережить пересоздание Activity (см. M1ViewModel). Обязательно вызвать close() при
 * уничтожении владельца (ViewModel.onCleared()), иначе утечёт нативная сессия ORT и поток GPS.
 */
class M1Controller(private val context: Context) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val filesDir = requireNotNull(context.getExternalFilesDir(null))
    private val dataDir = File(filesDir, "refpack")
    private val logDir = File(filesDir, "logs")
    private val executor = Executors.newSingleThreadExecutor()
    private val gps = GpsSource(context)
    private val analyzer = AtomicReference<FrameAnalyzer?>(null)
    private val runningFlag = AtomicBoolean(false)
    private var bundle: LoadedBundle? = null
    private var logger: SessionLogger? = null

    init {
        executor.execute {
            val b = try {
                BundleLoader.load(dataDir)
            } catch (e: Exception) {
                _state.update {
                    it.copy(status = "Нет базы: ${e.message}. Скопируйте файлы: adb push <bundle>/. " +
                        "/sdcard/Android/data/io.visnav.app/files/refpack/")
                }
                return@execute
            }
            bundle = b
            analyzer.set(FrameAnalyzer(intervalMs = 500, inputW = b.meta.inputW, inputH = b.meta.inputH))
            _state.update { it.copy(loaded = true, status = "База: ${b.pack.count} эталонов, модель ${b.meta.model}") }
            // Parity — диагностика, а не условие готовности: провал не должен блокировать запись.
            try {
                val parity = ParityCheck.runIfPresent(dataDir, b.embedder, File(logDir, "parity.json"))
                if (parity != null) {
                    _state.update { it.copy(status = it.status + " · parity cos=%.4f".format(parity)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(status = it.status + " · parity error: ${e.message}") }
            }
        }
    }

    fun setMode(mode: PriorMode) { if (!_state.value.running) _state.update { it.copy(mode = mode) } }

    fun bindCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
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
        // CAS, не значение StateFlow: два быстрых нажатия/повторный вызов с разных потоков не
        // должны создать вторую сессию записи и второй SessionLogger поверх первого.
        if (!runningFlag.compareAndSet(false, true)) return
        val b = bundle
        val frameAnalyzer = analyzer.get()
        if (b == null || frameAnalyzer == null) {
            runningFlag.set(false)
            return
        }
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
            try {
                val fix = gps.fresh()
                val rec = pipeline.process(tMs, rgb, b.meta.inputW, b.meta.inputH, fix, preMs)
                log.frame(rec)
                val err = if (fix != null && rec.fix != null) Geo.haversineM(fix.lat, fix.lon, rec.fix!!.lat, rec.fix!!.lon) else null
                _state.update {
                    it.copy(frames = it.frames + 1, lastSim = rec.fix?.sim, lastErrM = err,
                        lastInfMs = rec.latMs.inf, gpsAccM = fix?.accM)
                }
            } catch (e: Exception) {
                // Один плохой кадр не должен останавливать сессию — считаем и продолжаем.
                _state.update {
                    it.copy(frames = it.frames + 1, errors = it.errors + 1, status = "Ошибка кадра: ${e.message}")
                }
            }
        }
        _state.update { it.copy(running = true, frames = 0, errors = 0, status = "Запись: ${mode.name}") }
    }

    fun stop() {
        if (!runningFlag.compareAndSet(true, false)) return
        analyzer.get()?.onFrame = null
        gps.stop()
        val log = logger
        logger = null
        // Закрываем на потоке анализа — после кадра, который, возможно, ещё обрабатывается.
        executor.execute { log?.close() }
        _state.update { it.copy(running = false, status = "Остановлено, кадров: ${it.frames}") }
    }

    /** Освобождает камеру/GPS/логгер/модель. Вызывать один раз при уничтожении владельца. */
    fun close() {
        if (runningFlag.get()) stop()
        gps.stop()
        val log = logger
        logger = null
        // bundle читаем внутри задачи на том же executor, а не здесь: если close() позвали, пока
        // init ещё грузит бандл на этом же executor, эта задача выполнится после неё и увидит уже
        // присвоенный bundle — иначе только что созданный OrtEmbedder не закрылся бы никогда.
        executor.execute {
            log?.close()
            bundle?.embedder?.close()
        }
        executor.shutdown()
    }
}
