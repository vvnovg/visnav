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
import io.visnav.core.FrameCaptureEvent
import io.visnav.core.EventReorderer
import io.visnav.core.Geo
import io.visnav.core.GnssReason
import io.visnav.core.Localizer
import io.visnav.core.LocalizerConfig
import io.visnav.core.NavMode
import io.visnav.core.ReorderItem
import io.visnav.core.SensorEvent
import io.visnav.core.TrajectoryFormat
import io.visnav.core.LocalizationPipeline
import io.visnav.core.PriorMode
import io.visnav.core.PriorPolicy
import io.visnav.core.SensorLogFormat
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
    val navMode: NavMode? = null,
    val sigmaM: Double? = null,
    val gnssReasons: Set<GnssReason> = emptySet(),
)

/** Журнал траектории фильтра: заголовок и по строке на кадр. */
private class FusionLog(file: File) : Closeable {
    private val w = file.bufferedWriter()
    @Synchronized fun line(s: String) { w.write(s); w.newLine() }
    @Synchronized override fun close() = w.close()
}

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
    @Volatile private var fusionLog: FusionLog? = null
    /** Выпускает остаток очереди переупорядочителя (на executor) и возвращает число опоздавших событий. */
    @Volatile private var flush: (() -> Pair<Int, Int>)? = null
    /** Периодический drain на executor, независимо от кадров (иначе очередь растёт, если кадры встали). */
    @Volatile private var drainNow: (() -> Unit)? = null
    @Volatile private var drainTimer: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var drainTask: java.util.concurrent.ScheduledFuture<*>? = null

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
            // Ставим running/status здесь, а не в конце: последующие предупреждения этого блока
            // (обрыв заголовка датчиков через onFirstFailure, отсутствие гироскопа/акселерометра)
            // дописываются к этому статусу через "it.status + ...", а не затираются им — раньше
            // финальный _state.update шёл последним и стирал их целиком.
            _state.update { it.copy(running = true, frames = 0, errors = 0, status = "Запись: ${mode.name}",
                navMode = null, sigmaM = null, gnssReasons = emptySet()) }
            logDir.mkdirs()
            val startedMs = System.currentTimeMillis()
            val base = "session-$startedMs-${mode.name.lowercase()}"
            val log = SessionLogger(File(logDir, "$base.jsonl"))
            logger = log
            // Поле присваивается до header(): если header() всё же бросит исключение (обычные
            // IOException уже перехватываются самим SensorLogger), файл всё равно должен закрыться
            // через путь ошибки start() ниже, а не остаться висеть незакрытым.
            val sLog = SensorLogger(File(logDir, "$base.sensors.jsonl"))
            sensorLog = sLog
            // Ставим обработчик до header(): если сам заголовок не запишется, это тоже первая
            // (и единственная) поломка потока датчиков, и о ней тоже нужно сообщить.
            sLog.onFirstFailure = { e ->
                _state.update {
                    it.copy(errors = it.errors + 1, status = "Ошибка записи датчиков: ${e.message} — остановите запись")
                }
            }
            sLog.header(startedMs)
            val dLog = DescriptorLogWriter(File(logDir, "$base.desc"), b.pack.dim)
            descLog = dLog
            val fLog = FusionLog(File(logDir, "$base.fusion.jsonl"))
            fusionLog = fLog
            fLog.line(TrajectoryFormat.fusionHeader(startedMs, b.meta.createdAt))
            val localizer = Localizer(b.pack, LocalizerConfig())
            val reorderer = EventReorderer()
            var sensorFailureReported = false
            var frameFailureReported = false
            // Весь доступ к Localizer — только на потоке кадров (executor), через sink переупорядочителя.
            val sink: (ReorderItem) -> Unit = { item ->
                when (item) {
                    is ReorderItem.Sensor -> try {
                        localizer.onSensor(item.event)
                    } catch (ex: Exception) {
                        // Один раз и без обновления UI на каждое событие.
                        if (!sensorFailureReported) {
                            sensorFailureReported = true
                            _state.update { it.copy(errors = it.errors + 1, status = "Ошибка фильтра (датчики): ${ex.message}") }
                        }
                    }
                    is ReorderItem.Frame -> try {
                        val out = localizer.onFrame(item.frameTMs, item.desc)
                        if (out != null) {
                            fLog.line(TrajectoryFormat.row(out, false, null))
                            _state.update { it.copy(navMode = out.mode, sigmaM = out.sigmaM, gnssReasons = out.reasons) }
                        }
                    } catch (e: Exception) {
                        // Сбой фильтра на кадре не останавливает запись.
                        val first = !frameFailureReported
                        frameFailureReported = true
                        _state.update {
                            it.copy(errors = it.errors + 1, status = if (first) "Ошибка фильтра: ${e.message}" else it.status)
                        }
                    }
                }
            }
            flush = { reorderer.drainAll(sink); Pair(reorderer.late, reorderer.dropped) }
            drainNow = { reorderer.drain(System.currentTimeMillis(), sink) }
            startDrainTimer()
            // Колбэки датчиков/GNSS: журнал, затем только постановка в очередь (коротко, без фильтра).
            val feed: (SensorEvent) -> Unit = { e ->
                sLog.event(e)
                // В фильтр идёт только то, что пишется в журнал, — как видит Replayer.
                if (SensorLogFormat.isWritable(e)) reorderer.push(ReorderItem.Sensor(e))
            }
            var frameDesc: FloatArray? = null
            var descriptorFailureReported = false
            val pipeline = LocalizationPipeline(b.pack, b.embedder, PriorPolicy(mode), onDescriptor = { t, d ->
                try {
                    dLog.write(t, d)
                } catch (e: Exception) {
                    // Один плохой дескриптор не должен ронять кадр или сессию — считаем и продолжаем,
                    // как и с остальными ошибками кадра. Статус выставляем только при первом сбое,
                    // чтобы поток однотипных ошибок не забивал статус построчно; счётчик растёт всегда.
                    if (!descriptorFailureReported) {
                        descriptorFailureReported = true
                        _state.update { it.copy(errors = it.errors + 1, status = "ошибка записи дескриптора: ${e.message}") }
                    } else {
                        _state.update { it.copy(errors = it.errors + 1) }
                    }
                }
                frameDesc = d
            })
            // Заголовок пишется не здесь, а при первом кадре: только тогда известно фактическое
            // разрешение анализа (device string включает "analysis WxH", см. C2), но первой строкой
            // журнала он всё равно останется — до первого кадра ничего больше не пишется.
            var headerWritten = false
            gps.onLoc = feed
            gps.onGnss = feed
            gps.onAgc = feed
            gps.start()
            sensors.onWarning = { message -> _state.update { it.copy(status = it.status + " · $message") } }
            val sensorsStarted = sensors.start(feed)
            if (!sensorsStarted) {
                _state.update { it.copy(status = it.status + " · нет гироскопа/акселерометра — датчики не пишутся") }
            }
            frameAnalyzer.onError = { t ->
                _state.update { it.copy(errors = it.errors + 1, status = "Ошибка кадра (анализ): ${t.message}") }
            }
            frameAnalyzer.onFrame = onFrame@{ tMs, rgb, preMs, captureTsNs ->
                // t = tMs (the same wall-clock value as this frame's .jsonl t_ms) — NOT a conversion
                // of captureTsNs: that raw camera timestamp isn't reliably on elapsedRealtimeNanos on
                // every device, so offsetMs (calibrated against elapsedRealtimeNanos in SensorRecorder)
                // would silently produce a wrong wall time. captureTsNs is kept as-is in cap_ns for
                // later, source-aware correlation (see FrameCaptureEvent). Skipped entirely when the
                // sensor thread never started — there is no SensorRecorder clock domain to relate it to.
                if (captureTsNs > 0 && sensorsStarted) {
                    sLog.event(FrameCaptureEvent(tMs.toDouble(), tMs, captureTsNs))
                }
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
                    frameDesc = null
                    val rec = pipeline.process(tMs, rgb, b.meta.inputW, b.meta.inputH, fix, preMs)
                    log.frame(rec)
                    // После process() и записи кадра: вне интервала замера поиска (латентность M1 не меняется).
                    reorderer.push(ReorderItem.Frame(tMs, frameDesc))
                    reorderer.drain(System.currentTimeMillis(), sink)
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
        } catch (e: Exception) {
            // Любой сбой после CAS (например, база выгружена или диск недоступен) не должен
            // оставить контроллер в состоянии "running=true" без реально работающей записи.
            runningFlag.set(false)
            flush = null
            stopDrainTimer()
            analyzer.get()?.onFrame = null
            analyzer.get()?.onError = null
            sensors.stop()
            gps.onLoc = null
            gps.onGnss = null
            gps.onAgc = null
            gps.stop()
            val log = logger
            logger = null
            val sLog = sensorLog
            sensorLog = null
            val dLog = descLog
            descLog = null
            val fl = fusionLog
            fusionLog = null
            val failedCount = sLog?.failed ?: 0
            val closeErrors = mutableListOf<String>()
            closeQuietly("журнала датчиков", sLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала дескрипторов", dLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала фильтра", fl)?.let { closeErrors.add(it) }
            try {
                log?.close()
            } catch (closeError: Exception) {
                closeErrors.add("ошибка закрытия журнала: ${closeError.message}")
            }
            // Один финальный _state.update: собираем все ошибки закрытия в статус разом, чтобы более
            // ранняя (например, датчиков) не была затёрта более поздним присваиванием статуса.
            val suffix = buildString {
                if (failedCount > 0) append(" · ошибок записи датчиков: $failedCount")
                for (err in closeErrors) append("; $err")
            }
            _state.update { it.copy(running = false, status = "Ошибка запуска: ${e.message}$suffix") }
        }
    }

    fun stop() {
        if (!runningFlag.compareAndSet(true, false)) return
        analyzer.get()?.onFrame = null
        analyzer.get()?.onError = null
        sensors.stop()
        gps.onLoc = null
        gps.onGnss = null
        gps.onAgc = null
        gps.stop()
        val log = logger
        logger = null
        val sLog = sensorLog
        sensorLog = null
        val dLog = descLog
        descLog = null
        val fl = fusionLog
        fusionLog = null
        stopDrainTimer()
        val flushFn = flush
        flush = null
        val skipped = sLog?.skipped ?: 0
        val failedCount = sLog?.failed ?: 0
        // Закрываем и формируем финальный статус на потоке анализа — после кадра, который, возможно,
        // ещё обрабатывается, и одним _state.update, чтобы ошибка закрытия (обнаруженная здесь, на
        // executor) не была затёрта более ранним присваиванием статуса с потока вызывающего stop().
        executor.execute {
            val closeErrors = mutableListOf<String>()
            // Выпускаем остаток очереди до закрытия журнала фильтра.
            val (late, dropped) = try { flushFn?.invoke() ?: Pair(0, 0) } catch (e: Exception) {
                closeErrors.add("ошибка завершения фильтра: ${e.message}"); Pair(0, 0)
            }
            try {
                log?.close()
            } catch (e: Exception) {
                closeErrors.add("ошибка закрытия журнала: ${e.message}")
            }
            closeQuietly("журнала датчиков", sLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала дескрипторов", dLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала фильтра", fl)?.let { closeErrors.add(it) }
            val suffix = buildString {
                if (late > 0) append(" · опоздавших событий фильтра: $late")
                if (dropped > 0) append(" · отброшено событий фильтра: $dropped")
                if (skipped > 0) append(" · пропущено датчиков: $skipped")
                if (failedCount > 0) append(" · журнал датчиков прерван после ошибки записи")
                for (err in closeErrors) append(" · $err")
            }
            _state.update { it.copy(running = false, status = "Остановлено, кадров: ${it.frames}$suffix") }
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
        gps.onAgc = null
        gps.stop()
        if (logger === log) logger = null
        val sLog = sensorLog
        sensorLog = null
        val dLog = descLog
        descLog = null
        val fl = fusionLog
        fusionLog = null
        stopDrainTimer()
        val flushFn = flush
        flush = null
        val failedCount = sLog?.failed ?: 0
        val closeErrors = mutableListOf<String>()
        try { flushFn?.invoke() } catch (e: Exception) { closeErrors.add("ошибка завершения фильтра: ${e.message}") }
        closeQuietly("журнала датчиков", sLog)?.let { closeErrors.add(it) }
        closeQuietly("журнала дескрипторов", dLog)?.let { closeErrors.add(it) }
        closeQuietly("журнала фильтра", fl)?.let { closeErrors.add(it) }
        try {
            log.close()
        } catch (closeError: Exception) {
            closeErrors.add("ошибка закрытия журнала: ${closeError.message}")
        }
        val suffix = buildString {
            if (failedCount > 0) append(" · ошибок записи датчиков: $failedCount")
            for (err in closeErrors) append("; $err")
        }
        _state.update { it.copy(running = false, status = "$message$suffix") }
    }

    /** Освобождает камеру/GPS/логгер/модель. Вызывать один раз при уничтожении владельца. */
    fun close() {
        if (runningFlag.get()) stop()
        stopDrainTimer()
        sensors.stop()
        gps.close()
        val log = logger
        logger = null
        val sLog = sensorLog
        sensorLog = null
        val dLog = descLog
        descLog = null
        val fl = fusionLog
        fusionLog = null
        // bundle читаем внутри задачи на том же executor, а не здесь: если close() позвали, пока
        // init ещё грузит бандл на этом же executor, эта задача выполнится после неё и увидит уже
        // присвоенный bundle — иначе только что созданный OrtEmbedder не закрылся бы никогда.
        val failedCount = sLog?.failed ?: 0
        executor.execute {
            val closeErrors = mutableListOf<String>()
            try {
                log?.close()
            } catch (e: Exception) {
                closeErrors.add("ошибка закрытия журнала: ${e.message}")
            }
            closeQuietly("журнала датчиков", sLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала дескрипторов", dLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала фильтра", fl)?.let { closeErrors.add(it) }
            if (failedCount > 0 || closeErrors.isNotEmpty()) {
                val suffix = buildString {
                    if (failedCount > 0) append(" · ошибок записи датчиков: $failedCount")
                    for (err in closeErrors) append(" · $err")
                }
                _state.update { it.copy(status = it.status + suffix) }
            }
            bundle?.embedder?.close()
        }
        executor.shutdown()
    }

    private fun startDrainTimer() {
        stopDrainTimer()
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        drainTimer = timer
        // Таймер только ставит задачу на executor: сам drain остаётся однопоточным.
        drainTask = timer.scheduleWithFixedDelay({
            try {
                executor.execute { drainNow?.invoke() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // executor уже закрыт — таймер остановит close().
            }
        }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun stopDrainTimer() {
        drainTask?.cancel(false)
        drainTask = null
        drainTimer?.shutdownNow()
        drainTimer = null
        drainNow = null
    }

    private fun closeQuietly(label: String, closeable: Closeable?): String? = try {
        closeable?.close()
        null
    } catch (e: Exception) {
        "ошибка закрытия $label: ${e.message}"
    }
}
