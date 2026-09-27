package io.visnav.app

import android.content.Context
import android.os.Build
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.visnav.core.DescriptorLogWriter
import io.visnav.core.Geo
import io.visnav.core.LocalizationPipeline
import io.visnav.core.PriorMode
import io.visnav.core.PriorPolicy
import io.visnav.core.SensorLogger
import io.visnav.core.SessionHeader
import io.visnav.core.SessionLogger
import java.io.Closeable
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
    val frameSize: String? = null,
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
    private val sensors = SensorRecorder(context)
    private val analyzer = AtomicReference<FrameAnalyzer?>(null)
    private val runningFlag = AtomicBoolean(false)
    @Volatile private var bundle: LoadedBundle? = null
    @Volatile private var logger: SessionLogger? = null
    @Volatile private var sensorLog: SensorLogger? = null
    @Volatile private var descLog: DescriptorLogWriter? = null

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
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        // M0 — эталоны сняты под 16:9 (см. docs/research/drive-protocol.md «Геометрия
                        // камеры»); без этого CameraX может подобрать формат ближе к 4:3 на части устройств.
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build()
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
        try {
            val b = bundle
            val frameAnalyzer = analyzer.get()
            if (b == null || frameAnalyzer == null) {
                runningFlag.set(false)
                return
            }
            val mode = _state.value.mode
            logDir.mkdirs()
            val startedMs = System.currentTimeMillis()
            val base = "session-$startedMs-${mode.name.lowercase()}"
            val log = SessionLogger(File(logDir, "$base.jsonl"))
            logger = log
            val sLog = SensorLogger(File(logDir, "$base.sensors.jsonl")).also { it.header(startedMs) }
            sensorLog = sLog
            val dLog = DescriptorLogWriter(File(logDir, "$base.desc"), b.pack.dim)
            descLog = dLog
            val pipeline = LocalizationPipeline(b.pack, b.embedder, PriorPolicy(mode), onDescriptor = { t, d -> dLog.write(t, d) })
            // Заголовок пишется не здесь, а при первом кадре: только тогда известно фактическое
            // разрешение анализа (device string включает "analysis WxH", см. C2), но первой строкой
            // журнала он всё равно останется — до первого кадра ничего больше не пишется.
            var headerWritten = false
            gps.onLoc = { sLog.event(it) }
            gps.onGnss = { sLog.event(it) }
            gps.start()
            if (!sensors.start { sLog.event(it) }) {
                _state.update { it.copy(status = it.status + " · нет гироскопа/акселерометра — датчики не пишутся") }
            }
            frameAnalyzer.onError = { t ->
                _state.update { it.copy(errors = it.errors + 1, status = "Ошибка кадра (анализ): ${t.message}") }
            }
            frameAnalyzer.onFrame = onFrame@{ tMs, rgb, preMs ->
                if (!headerWritten) {
                    try {
                        log.header(SessionHeader(
                            model = b.meta.model, refpackCreatedAt = b.meta.createdAt,
                            device = "${Build.MANUFACTURER} ${Build.MODEL}; " +
                                "analysis ${frameAnalyzer.lastFrameW}x${frameAnalyzer.lastFrameH}",
                            startedMs = startedMs, mode = mode.name.lowercase(),
                        ))
                        headerWritten = true
                    } catch (e: Exception) {
                        // Заголовок — первая строка журнала; если его не удалось записать, сессию
                        // нельзя продолжать (журнал без заголовка бесполезен для field-eval) —
                        // останавливаем её тем же путём, что и сбой в start().
                        failSession(frameAnalyzer, log, "Ошибка записи заголовка сессии: ${e.message}")
                        return@onFrame
                    }
                }
                try {
                    val fix = gps.fresh()
                    val rec = pipeline.process(tMs, rgb, b.meta.inputW, b.meta.inputH, fix, preMs)
                    log.frame(rec)
                    val err = if (fix != null && rec.fix != null) Geo.haversineM(fix.lat, fix.lon, rec.fix!!.lat, rec.fix!!.lon) else null
                    _state.update {
                        it.copy(frames = it.frames + 1, lastSim = rec.fix?.sim, lastErrM = err,
                            lastInfMs = rec.latMs.inf, gpsAccM = fix?.accM,
                            frameSize = "${frameAnalyzer.lastFrameW}x${frameAnalyzer.lastFrameH}")
                    }
                } catch (e: Exception) {
                    // Один плохой кадр не должен останавливать сессию — считаем и продолжаем.
                    _state.update {
                        it.copy(frames = it.frames + 1, errors = it.errors + 1, status = "Ошибка кадра: ${e.message}")
                    }
                }
            }
            _state.update { it.copy(running = true, frames = 0, errors = 0, status = "Запись: ${mode.name}") }
        } catch (e: Exception) {
            // Любой сбой после CAS (например, база выгружена или диск недоступен) не должен
            // оставить контроллер в состоянии "running=true" без реально работающей записи.
            runningFlag.set(false)
            analyzer.get()?.onFrame = null
            analyzer.get()?.onError = null
            sensors.stop()
            gps.onLoc = null
            gps.onGnss = null
            gps.stop()
            val log = logger
            logger = null
            val sLog = sensorLog
            sensorLog = null
            val dLog = descLog
            descLog = null
            closeQuietly("журнала датчиков", sLog)
            closeQuietly("журнала дескрипторов", dLog)
            try {
                log?.close()
            } catch (closeError: Exception) {
                _state.update { it.copy(status = "Ошибка запуска: ${e.message}; ошибка закрытия журнала: ${closeError.message}") }
                return
            }
            _state.update { it.copy(running = false, status = "Ошибка запуска: ${e.message}") }
        }
    }

    fun stop() {
        if (!runningFlag.compareAndSet(true, false)) return
        analyzer.get()?.onFrame = null
        analyzer.get()?.onError = null
        sensors.stop()
        gps.onLoc = null
        gps.onGnss = null
        gps.stop()
        val log = logger
        logger = null
        val sLog = sensorLog
        sensorLog = null
        val dLog = descLog
        descLog = null
        val skipped = sLog?.skipped ?: 0
        // Закрываем на потоке анализа — после кадра, который, возможно, ещё обрабатывается.
        executor.execute {
            try {
                log?.close()
            } catch (e: Exception) {
                _state.update { it.copy(status = it.status + " · ошибка закрытия журнала: ${e.message}") }
            }
            closeQuietly("журнала датчиков", sLog)
            closeQuietly("журнала дескрипторов", dLog)
        }
        _state.update {
            it.copy(running = false, status = "Остановлено, кадров: ${it.frames}" +
                if (skipped > 0) " · пропущено датчиков: $skipped" else "")
        }
    }

    /**
     * Тот же путь остановки, что и сбой в start() после CAS, но вызываемый уже из-под onFrame —
     * когда сессия technically "running", но продолжать её нельзя (например, не удалось записать
     * заголовок журнала). Мы уже на потоке анализа (executor), поэтому log.close() можно вызвать
     * напрямую, без дополнительного execute{}.
     */
    private fun failSession(frameAnalyzer: FrameAnalyzer, log: SessionLogger, message: String) {
        if (!runningFlag.compareAndSet(true, false)) return
        frameAnalyzer.onFrame = null
        frameAnalyzer.onError = null
        sensors.stop()
        gps.onLoc = null
        gps.onGnss = null
        gps.stop()
        if (logger === log) logger = null
        val sLog = sensorLog
        sensorLog = null
        val dLog = descLog
        descLog = null
        closeQuietly("журнала датчиков", sLog)
        closeQuietly("журнала дескрипторов", dLog)
        try {
            log.close()
        } catch (closeError: Exception) {
            _state.update { it.copy(running = false, status = "$message; ошибка закрытия журнала: ${closeError.message}") }
            return
        }
        _state.update { it.copy(running = false, status = message) }
    }

    /** Освобождает камеру/GPS/логгер/модель. Вызывать один раз при уничтожении владельца. */
    fun close() {
        if (runningFlag.get()) stop()
        sensors.stop()
        gps.onLoc = null
        gps.onGnss = null
        gps.stop()
        val log = logger
        logger = null
        val sLog = sensorLog
        sensorLog = null
        val dLog = descLog
        descLog = null
        // bundle читаем внутри задачи на том же executor, а не здесь: если close() позвали, пока
        // init ещё грузит бандл на этом же executor, эта задача выполнится после неё и увидит уже
        // присвоенный bundle — иначе только что созданный OrtEmbedder не закрылся бы никогда.
        executor.execute {
            try {
                log?.close()
            } catch (e: Exception) {
                _state.update { it.copy(status = it.status + " · ошибка закрытия журнала: ${e.message}") }
            }
            closeQuietly("журнала датчиков", sLog)
            closeQuietly("журнала дескрипторов", dLog)
            bundle?.embedder?.close()
        }
        executor.shutdown()
    }

    private fun closeQuietly(label: String, closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: Exception) {
            _state.update { it.copy(status = it.status + " · ошибка закрытия $label: ${e.message}") }
        }
    }
}
