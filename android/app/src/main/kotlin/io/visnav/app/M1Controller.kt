package io.visnav.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import io.visnav.core.FrameRateGovernor
import io.visnav.core.LatencyJson
import io.visnav.core.LocalizerOutput
import io.visnav.core.PerfLog
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
import io.visnav.core.RoadInfo
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
    /** Идёт loadBundle (загрузка базы поездки). loaded == false && !loading — загрузка не удалась. */
    val loading: Boolean = true,
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
    val roadsLoaded: Boolean = false,
    val road: RoadInfo? = null,
    val nav: NavUi? = null,
    /** Позиция фильтра на последнем кадре (для карты); сбрасывается вместе с nav. */
    val pos: MapPos? = null,
    /** Выбранная поездка (trips/<имя>); null — старая раскладка без trips/. */
    val trip: String? = null,
    /** Поездки на телефоне по алфавиту; пусто — старая раскладка. */
    val trips: List<String> = emptyList(),
    /**
     * Абсолютный путь каталога данных выбранной поездки (`refpack/trips/<имя>/` или `refpack/` в старой
     * раскладке); ставится вместе с trip сразу после resolve, null — поездку определить не удалось.
     */
    val tripDir: String? = null,
    /** Настройки замеров (меняются только вне записи). */
    val settings: PerfSettings = PerfSettings(),
    /** Замеры для вкладки «Отладка»; обновляются раз в 5 с во время записи. */
    val perf: PerfUi? = null,
)

/**
 * Замеры на вкладке «Отладка»: интервал кадров, thermal status, медиана e2e за 5 с, ток батареи.
 * e2e — от t_ms (приход кадра в анализатор), а не от снимка камеры; в baseline e2e не считается.
 */
data class PerfUi(val intervalMs: Long, val thermal: Int?, val e2eP50: Double?, val currentMa: Double?)

/**
 * Позиция для карты из LocalizerOutput: σ, курс (рад, от севера по часовой) и скорость (м/с) как в выводе;
 * [tMs] — время выхода фильтра (для прогноза на «сейчас», см. nowcastPos).
 */
data class MapPos(
    val lat: Double, val lon: Double, val sigmaM: Double, val psiRad: Double, val speedMps: Double, val mode: NavMode,
    val tMs: Long,
)

/** Построчный журнал (траектория фильтра, замеры): заголовок и по строке на запись. */
private class LineLog(file: File) : Closeable {
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
    private val prefs = context.getSharedPreferences("visnav", Context.MODE_PRIVATE)
    private val logDir = File(filesDir, "logs")
    private val executor = Executors.newSingleThreadExecutor()
    private val gps = GpsSource(context)
    private val sensors = SensorRecorder(context)
    private val analyzer = AtomicReference<FrameAnalyzer?>(null)
    private val runningFlag = AtomicBoolean(false)
    @Volatile private var bundle: LoadedBundle? = null
    /** Защищает пару bundle/analyzer: замена в loadBundle() и чтение в start(). */
    private val swapLock = Any()
    /** Последняя выбранная поездка, ещё не загруженная executor'ом (схлопывает частые selectTrip). */
    private val pendingTrip = AtomicReference<String?>(null)
    /**
     * Перезагрузки базы, поставленные на executor и ещё не начатые (selectTrip, setOrt, после stop()).
     * loading = true ставится синхронно при постановке, чтобы «Старт» был недоступен до конца загрузки.
     */
    private val queuedLoads = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var logger: SessionLogger? = null
    @Volatile private var sensorLog: SensorLogger? = null
    @Volatile private var descLog: DescriptorLogWriter? = null
    @Volatile private var fusionLog: LineLog? = null
    @Volatile private var perfLog: LineLog? = null
    private val sysSampler = SysSampler(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * Остановка по длительности прогона (duration_min); снимается при любой остановке. Handler.postDelayed
     * считает по uptimeMillis, который стоит во сне устройства, — это верно, потому что экран прогона держит
     * FLAG_KEEP_SCREEN_ON и устройство не засыпает.
     */
    @Volatile private var durationStop: Runnable? = null
    @Volatile private var navSession: NavSession? = null
    /** Выпускает остаток очереди переупорядочителя (на executor) и возвращает число опоздавших событий. */
    @Volatile private var flush: (() -> Pair<Int, Int>)? = null
    /** Периодический drain на executor, независимо от кадров (иначе очередь растёт, если кадры встали). */
    @Volatile private var drainNow: (() -> Unit)? = null
    @Volatile private var drainTimer: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var drainTask: java.util.concurrent.ScheduledFuture<*>? = null
    /** Drain уже стоит в очереди executor'а или выполняется — тик таймера пропускается, задачи не копятся. */
    private val drainQueued = AtomicBoolean(false)
    /** Замеры раз в 5 с на executor (sys, late, регулятор кадров). */
    @Volatile private var perfTickNow: (() -> Unit)? = null
    @Volatile private var perfTask: java.util.concurrent.ScheduledFuture<*>? = null
    /** Пустые кадры профиля baseline (на том же таймере). */
    @Volatile private var baselineTask: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        _state.update { it.copy(settings = readSettings()) }
        executor.execute { loadBundle(prefs.getString("trip", null)) }
    }

    private fun readSettings() = PerfSettings(
        reorderDelayMs = PerfSettings.reorderDelay(prefs.getLong(PREF_REORDER_DELAY, PerfSettings.DEFAULT_REORDER_DELAY_MS)),
        ort = PerfSettings.ort(prefs.getString(PREF_ORT, null)),
        profile = PerfSettings.profile(prefs.getString(PREF_PROFILE, null)),
        durationMin = PerfSettings.duration(prefs.getInt(PREF_DURATION, 0)),
    )

    /** Меняет и запоминает настройки; во время записи не действует (false). */
    private fun updateSettings(change: (PerfSettings) -> PerfSettings): Boolean {
        if (runningFlag.get() || _state.value.running) return false
        val n = change(_state.value.settings)
        prefs.edit()
            .putLong(PREF_REORDER_DELAY, n.reorderDelayMs)
            .putString(PREF_ORT, n.ort)
            .putString(PREF_PROFILE, n.profile)
            .putInt(PREF_DURATION, n.durationMin)
            .apply()
        _state.update { it.copy(settings = n) }
        return true
    }

    /** Задержка буфера переупорядочения: 300/500/800/1500 мс. */
    fun setReorderDelay(ms: Long) { updateSettings { it.copy(reorderDelayMs = PerfSettings.reorderDelay(ms)) } }

    /** Профиль: full — камера и модель, baseline — без камеры (база NFR-6). Экраны перепривязывают камеру. */
    fun setProfile(profile: String) { updateSettings { it.copy(profile = PerfSettings.profile(profile)) } }

    /** Длительность прогона, мин: 0 — без ограничения, 30 или 60 — затем stop(). */
    fun setDuration(min: Int) { updateSettings { it.copy(durationMin = PerfSettings.duration(min)) } }

    /** Исполнитель ORT (cpu/xnnpack): база перезагружается на executor, как при смене поездки. */
    fun setOrt(ort: String) {
        val old = _state.value.settings.ort
        if (!updateSettings { it.copy(ort = PerfSettings.ort(ort)) } || _state.value.settings.ort == old) return
        postLoad { loadBundle(_state.value.trip ?: prefs.getString("trip", null)) }
    }

    /**
     * Ставит перезагрузку базы на executor и сразу (синхронно) поднимает loading. Флаг снимается, когда
     * не осталось поставленных перезагрузок: в конце loadBundle() или задачи, если та ничего не загрузила.
     */
    private fun postLoad(task: () -> Unit) {
        if (executor.isShutdown) return
        queuedLoads.incrementAndGet()
        _state.update { it.copy(loading = true) }
        try {
            executor.execute {
                queuedLoads.decrementAndGet()
                try { task() } finally { _state.update { it.copy(loading = queuedLoads.get() > 0) } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // close() уже закрыл executor.
            queuedLoads.decrementAndGet()
            _state.update { it.copy(loading = queuedLoads.get() > 0) }
        }
    }

    /**
     * Выбор поездки (trips/<имя>): перезагружает базу и модель на executor. Во время записи не действует.
     * Выбор запоминается и восстанавливается при следующем запуске. Частые вызовы схлопываются:
     * executor грузит только последний выбор.
     */
    fun selectTrip(name: String) {
        if (executor.isShutdown) return
        val s = _state.value
        if (s.running) return
        // Повторный выбор уже загруженной поездки отменяет ещё не загруженный выбор (A → B → A: остаётся A).
        if (name == s.trip && s.loaded) { pendingTrip.set(null); return }
        pendingTrip.set(name)
        postLoad { pendingTrip.getAndSet(null)?.let { loadBundle(it) } }
    }

    /**
     * Перечитывает список поездок (`refpack/trips/`) на executor: экран навигации вызывает при входе на вкладку
     * и при открытии меню поездок, чтобы появилась поездка, положенная через adb push после запуска.
     * Во время записи не действует. Если поездка не выбрана (trips/ был пуст или старая раскладка), а поездки
     * появились, первая из них загружается сразу.
     */
    fun refreshTrips() {
        if (executor.isShutdown || _state.value.running) return
        try {
            executor.execute {
                val trips = TripLayout.list(dataDir).orEmpty()
                _state.update { it.copy(trips = trips) }
                val s = _state.value
                if (s.trip == null && trips.isNotEmpty() && !s.running && !s.loading) loadBundle(null)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // close() уже закрыл executor.
        }
    }

    /** Только на executor: закрывает прежнюю базу и грузит выбранную поездку (или старую раскладку). */
    private fun loadBundle(wanted: String?) {
        // Под swapLock — вместе с чтением bundle/analyzer в start(): либо start() берёт прежнюю базу и
        // runningFlag уже поднят (тогда не трогаем её), либо видит null и откатывает запуск.
        val old = synchronized(swapLock) {
            if (runningFlag.get()) return
            bundle.also { bundle = null; analyzer.set(null) }
        }
        runCatching { old?.embedder?.close() }
        _state.update { it.copy(loaded = false, loading = true, roadsLoaded = false, status = "Загрузка базы…") }
        try {
            loadTrip(wanted)
        } finally {
            // Пока поставлены следующие перезагрузки, «Старт» остаётся недоступен.
            _state.update { it.copy(loading = queuedLoads.get() > 0) }
        }
    }

    /** Тело loadBundle() после закрытия прежней базы. */
    private fun loadTrip(wanted: String?) {
        val trips = TripLayout.list(dataDir).orEmpty()
        val dirs = TripLayout.resolve(dataDir, wanted).getOrElse { e ->
            _state.update { it.copy(loaded = false, trip = null, trips = trips, tripDir = null, status = e.message ?: "Нет поездок") }
            return
        }
        // Карта на экране зависит только от tripDir: ставим его сразу, вместе с trip, до загрузки базы.
        _state.update { it.copy(trip = dirs.name, trips = trips, tripDir = dirs.dataDir.absolutePath) }
        val prefix = dirs.name?.let { "Поездка: $it · " }.orEmpty()
        // Общая модель лежит в корне refpack/, а не в каталоге поездки: подсказка adb push должна вести туда.
        if (dirs.name != null && !dirs.model.isFile) {
            _state.update {
                it.copy(loaded = false, status = prefix + "Нет модели: ${dirs.model.absolutePath}. Скопируйте: " +
                    "adb push <пакет>/model.onnx /sdcard/Android/data/io.visnav.app/files/refpack/")
            }
            return
        }
        val wantXnnpack = _state.value.settings.ort == PerfSettings.ORT_XNNPACK
        val b = try {
            BundleLoader.load(dirs.dataDir, dirs.model, xnnpack = wantXnnpack)
        } catch (e: Exception) {
            val target = dirs.name?.let { "refpack/trips/$it/" } ?: "refpack/"
            val source = if (dirs.name != null) "<пакет>" else "<bundle>"
            _state.update {
                it.copy(loaded = false, trip = dirs.name, trips = trips,
                    status = prefix + "Нет базы: ${e.message}. Скопируйте файлы: adb push $source/. " +
                        "/sdcard/Android/data/io.visnav.app/files/$target")
            }
            return
        }
        synchronized(swapLock) {
            bundle = b
            analyzer.set(FrameAnalyzer(intervalMs = 500, inputW = b.meta.inputW, inputH = b.meta.inputH))
        }
        if (dirs.name != null) prefs.edit().putString("trip", dirs.name).apply()
        _state.update { it.copy(loaded = true, roadsLoaded = b.roads != null, trip = dirs.name, trips = trips,
            status = prefix + "База: ${b.pack.count} эталонов, модель ${b.meta.model}") }
        if (File(dirs.dataDir, "route.json").isFile && b.roads == null) {
            _state.update { it.copy(status = it.status + " · route.json без графа дорог — маршрут не строится") }
        }
        b.routeWarning?.let { w -> _state.update { it.copy(status = it.status + " · " + w) } }
        if (wantXnnpack && b.embedder.ort != PerfSettings.ORT_XNNPACK) {
            val reason = b.xnnpackError?.let { " ($it)" }.orEmpty()
            _state.update { it.copy(status = it.status + " · XNNPACK недоступен$reason — CPU") }
        }
        // Parity — диагностика, а не условие готовности: провал не должен блокировать запись.
        // Файлы parity лежат в корне refpack/ и относятся к общей модели refpack/model.onnx.
        if (dirs.model != File(dataDir, "model.onnx")) {
            _state.update { it.copy(status = it.status + " · parity: пропущено (модель поездки)") }
            return
        }
        try {
            val parity = ParityCheck.runIfPresent(dataDir, b.embedder, File(logDir, "parity.json"))
            if (parity != null) {
                _state.update { it.copy(status = it.status + " · parity cos=%.4f".format(parity)) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = it.status + " · parity error: ${e.message}") }
        }
    }

    fun setMode(mode: PriorMode) { if (!_state.value.running) _state.update { it.copy(mode = mode) } }

    /**
     * Привязывает камеру к [lifecycleOwner]: анализ кадров и, если задан [previewView], превью.
     * Без превью (навигационный экран) привязывается только анализ; каждая привязка заменяет прежнюю
     * (`unbindAll`). Перепривязка при смене вкладки во время записи даёт паузу в кадрах. Разрешение анализа
     * без Preview (только ImageAnalysis) ещё нужно проверить на устройстве — CameraX может выбрать другое.
     * В профиле baseline камера не подключается: прежние use case снимаются и ничего не привязывается
     * (экраны вызывают bindCamera заново при смене профиля).
     */
    fun bindCamera(previewView: PreviewView?, lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            if (_state.value.settings.baseline) return@addListener
            val preview = previewView?.let { v -> Preview.Builder().build().also { it.setSurfaceProvider(v.surfaceProvider) } }
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
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *listOfNotNull(preview, analysis).toTypedArray())
        }, ContextCompat.getMainExecutor(context))
    }

    fun start() {
        // CAS, не значение StateFlow: два быстрых нажатия/повторный вызов с разных потоков не
        // должны создать вторую сессию записи и второй SessionLogger поверх первого.
        // running ещё true, а runningFlag уже false — задача остановки прежней сессии не завершилась: не стартуем,
        // иначе её итоговое running = false затрёт новую сессию.
        if (_state.value.running) return
        if (!runningFlag.compareAndSet(false, true)) return
        try {
            val (b, frameAnalyzer) = synchronized(swapLock) { bundle to analyzer.get() }
            // Поставлена перезагрузка (смена ORT или поездки) — не стартуем на прежней базе.
            if (b == null || frameAnalyzer == null || _state.value.loading) {
                runningFlag.set(false)
                if (_state.value.loading) _state.update { it.copy(status = it.status + " · Загрузка базы — подождите") }
                return
            }
            val mode = _state.value.mode
            val settings = _state.value.settings
            val baseline = settings.baseline
            // Новый регулятор на каждую сессию: прогрев (первые кадры) считается заново.
            val governor = FrameRateGovernor()
            frameAnalyzer.intervalMs = governor.intervalMs
            // Ставим running/status здесь, а не в конце: последующие предупреждения этого блока
            // (обрыв заголовка датчиков через onFirstFailure, отсутствие гироскопа/акселерометра)
            // дописываются к этому статусу через "it.status + ...", а не затираются им — раньше
            // финальный _state.update шёл последним и стирал их целиком.
            val profileNote = if (baseline) " · baseline, без камеры" else ""
            _state.update { it.copy(running = true, frames = 0, errors = 0, status = "Запись: ${mode.name}$profileNote",
                navMode = null, sigmaM = null, gnssReasons = emptySet(), road = null, nav = null, pos = null, perf = null) }
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
            val fLog = LineLog(File(logDir, "$base.fusion.jsonl"))
            fusionLog = fLog
            fLog.line(TrajectoryFormat.fusionHeader(startedMs, b.meta.createdAt, b.roadsMeta?.createdAt,
                reorderDelayMs = settings.reorderDelayMs))
            val pLog = LineLog(File(logDir, "$base.perf.jsonl"))
            perfLog = pLog
            // Ёмкость батареи — по первому сэмплу (в заголовке); сам сэмпл — первая строка sys.
            val firstSys = sysSampler.sample(startedMs, frameAnalyzer.intervalMs)
            pLog.line(PerfLog.header(startedMs, "${Build.MANUFACTURER} ${Build.MODEL}", settings.profile,
                settings.reorderDelayMs, b.embedder.ort, batteryCapacityMah(firstSys.chargeUah, firstSys.battPct)))
            pLog.line(PerfLog.sys(firstSys))
            // start() вызывается из UI (кнопка «Старт»), т. е. на главном потоке — TextToSpeech создаётся здесь.
            val nav = if (b.roads != null && b.destination != null) {
                NavSession(context, b.roads, b.destination, File(logDir, "$base.nav.jsonl"), startedMs,
                    b.roadsMeta!!.createdAt) {
                    _state.update { it.copy(status = it.status + " · Голосовые подсказки недоступны") }
                }
            } else null
            navSession = nav
            val localizer = Localizer(b.pack, LocalizerConfig(), b.roads)
            val reorderer = EventReorderer(delayMs = settings.reorderDelayMs)
            // Замеры кадра — только на executor (onFrame и sink): pre/inf/search по t_ms кадра, e2e за окно 5 с.
            val latencies = boundedMap<Long, LatencyJson>(64)
            val e2eWindow = ArrayList<Double>()
            var lastOut: LocalizerOutput? = null
            var perfFailureReported = false
            var sensorFailureReported = false
            var frameFailureReported = false
            var navFailureReported = false
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
                        val fuseT0 = System.nanoTime()
                        val out = localizer.onFrame(item.frameTMs, item.desc)
                        val fuseMs = (System.nanoTime() - fuseT0) / 1e6
                        var navMs = 0.0
                        var e2eMs = Double.NaN
                        if (out != null) {
                            lastOut = out
                            fLog.line(TrajectoryFormat.row(out, false, null))
                            val navT0 = System.nanoTime()
                            val navUi = try {
                                nav?.onOutput(out)
                            } catch (ex: Exception) {
                                // Сбой ведения не останавливает запись; учитываем один раз, без обновления UI на каждый кадр.
                                if (!navFailureReported) {
                                    navFailureReported = true
                                    _state.update { it.copy(errors = it.errors + 1, status = "Ошибка маршрута: ${ex.message}") }
                                }
                                null
                            }
                            navMs = (System.nanoTime() - navT0) / 1e6
                            val pos = MapPos(out.lat, out.lon, out.sigmaM, out.psiRad, out.speedMps, out.mode, out.tMs)
                            _state.update { it.copy(navMode = out.mode, sigmaM = out.sigmaM, gnssReasons = out.reasons,
                                road = out.road, nav = mergeNav(it.nav, navUi), pos = pos) }
                            // Тот же (настенный) час, что t_ms кадра.
                            // e2e считается от t_ms — прихода кадра в анализатор, а не от снимка камеры.
                            e2eMs = (System.currentTimeMillis() - item.frameTMs).toDouble()
                            if (!baseline) e2eWindow.add(e2eMs)
                        }
                        // baseline: пустые кадры по таймеру — без строк frame и без стоимости кадра в регуляторе.
                        if (!baseline) {
                            val lat = latencies.remove(item.frameTMs)
                            val pre = lat?.pre ?: Double.NaN
                            val inf = lat?.inf ?: Double.NaN
                            val search = lat?.search ?: Double.NaN
                            governor.onFrameCost(pre + inf + search + fuseMs)
                            pLog.line(PerfLog.frame(item.frameTMs, pre, inf, search, fuseMs, navMs, e2eMs,
                                frameAnalyzer.intervalMs))
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
            flush = {
                reorderer.drainAll(sink)
                // Финальные строки late и sys перед закрытием perf-журнала (хвост после последнего тика).
                try {
                    val now = System.currentTimeMillis()
                    pLog.line(PerfLog.late(now, reorderer.latenessSnapshotAndReset(), reorderer.late, reorderer.dropped,
                        reorderer.lateBySourceSnapshot()))
                    pLog.line(PerfLog.sys(sysSampler.sample(now, frameAnalyzer.intervalMs)))
                } catch (_: Exception) {
                    // Журнал замеров вторичен: сбой здесь не мешает закрыть остальные журналы.
                }
                Pair(reorderer.late, reorderer.dropped)
            }
            // Раз в 5 с на executor: батарея и нагрев, опоздания событий, регулятор частоты кадров.
            val perfTick: () -> Unit = {
                val e2eP50 = p50(e2eWindow)
                e2eWindow.clear()
                try {
                    val now = System.currentTimeMillis()
                    val sys = sysSampler.sample(now, frameAnalyzer.intervalMs)
                    pLog.line(PerfLog.sys(sys))
                    pLog.line(PerfLog.late(now, reorderer.latenessSnapshotAndReset(), reorderer.late, reorderer.dropped,
                        reorderer.lateBySourceSnapshot()))
                    // Регулятор — по монотонным часам.
                    val interval = governor.update(SystemClock.elapsedRealtime(), sys.thermal, sys.headroom,
                        lastOut?.mode, lastOut?.health)
                    frameAnalyzer.intervalMs = interval
                    _state.update {
                        it.copy(perf = PerfUi(interval, sys.thermal, e2eP50, sys.currentUa?.let { c -> c / 1000.0 }))
                    }
                } catch (e: Exception) {
                    if (!perfFailureReported) {
                        perfFailureReported = true
                        _state.update { it.copy(errors = it.errors + 1, status = it.status + " · ошибка замеров: ${e.message}") }
                    }
                }
            }
            // baseline: пустой кадр (без дескриптора) с текущим интервалом регулятора — фильтр выдаёт позицию
            // по GNSS/IMU, экран, ведение и .fusion.jsonl работают как в full, но без камеры и модели.
            // Ошибка push не должна останавливать цепочку пустых кадров: сообщаем один раз и повторяем через 1 с.
            var baselineErrorReported = false
            val baselineFrame: (() -> Long)? = if (baseline) {
                {
                    try {
                        val now = System.currentTimeMillis()
                        reorderer.push(ReorderItem.Frame(now, null), now)
                        governor.intervalMs
                    } catch (e: Exception) {
                        if (!baselineErrorReported) {
                            baselineErrorReported = true
                            _state.update { it.copy(errors = it.errors + 1, status = it.status + " · ошибка пустого кадра: ${e.message}") }
                        }
                        BASELINE_RETRY_MS
                    }
                }
            } else null
            startDrainTimer({ reorderer.drain(System.currentTimeMillis(), sink) }, perfTick, baselineFrame)
            // Колбэки датчиков/GNSS: журнал, затем только постановка в очередь (коротко, без фильтра).
            val feed: (SensorEvent) -> Unit = { e ->
                sLog.event(e)
                // В фильтр идёт только то, что пишется в журнал, — как видит Replayer.
                if (SensorLogFormat.isWritable(e)) reorderer.push(ReorderItem.Sensor(e), System.currentTimeMillis())
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
            if (baseline) {
                // Кадров камеры не будет (сессия ORT загружена, но простаивает): заголовок журнала кадров
                // пишется сразу, чтобы .jsonl не остался пустым.
                log.header(SessionHeader(
                    model = b.meta.model, refpackCreatedAt = b.meta.createdAt,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}; analysis none (baseline)",
                    startedMs = startedMs, mode = mode.name.lowercase(), trip = _state.value.trip,
                ))
                startDurationStop(settings.durationMin)
                return
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
                            startedMs = startedMs, mode = mode.name.lowercase(), trip = _state.value.trip,
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
                    latencies[tMs] = rec.latMs
                    // После process() и записи кадра: вне интервала замера поиска (латентность M1 не меняется).
                    reorderer.push(ReorderItem.Frame(tMs, frameDesc), System.currentTimeMillis())
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
            startDurationStop(settings.durationMin)
        } catch (e: Exception) {
            // Любой сбой после CAS (например, база выгружена или диск недоступен) не должен
            // оставить контроллер в состоянии "running=true" без реально работающей записи.
            runningFlag.set(false)
            flush = null
            stopDrainTimer()
            cancelDurationStop()
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
            val pl = perfLog
            perfLog = null
            val ns = navSession
            navSession = null
            val failedCount = sLog?.failed ?: 0
            val closeErrors = mutableListOf<String>()
            closeQuietly("журнала датчиков", sLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала дескрипторов", dLog)?.let { closeErrors.add(it) }
            closeQuietly("журнала фильтра", fl)?.let { closeErrors.add(it) }
            closeQuietly("журнала замеров", pl)?.let { closeErrors.add(it) }
            closeQuietly("журнала маршрута", ns)?.let { closeErrors.add(it) }
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
            _state.update { it.copy(running = false, status = "Ошибка запуска: ${e.message}$suffix",
                navMode = null, sigmaM = null,
                gnssReasons = emptySet(), road = null, nav = null, pos = null) }
        }
    }

    fun stop() {
        if (!runningFlag.compareAndSet(true, false)) return
        cancelDurationStop()
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
        val pl = perfLog
        perfLog = null
        val ns = navSession
        navSession = null
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
            closeQuietly("журнала замеров", pl)?.let { closeErrors.add(it) }
            closeQuietly("журнала маршрута", ns)?.let { closeErrors.add(it) }
            val suffix = buildString {
                if (late > 0) append(" · опоздавших событий фильтра: $late")
                if (dropped > 0) append(" · отброшено событий фильтра: $dropped")
                if (skipped > 0) append(" · пропущено датчиков: $skipped")
                if (failedCount > 0) append(" · журнал датчиков прерван после ошибки записи")
                for (err in closeErrors) append(" · $err")
            }
            // Настройку ORT могли сменить в гонке со «Стартом» — тогда база загружена с прежней. Перезагрузку
            // ставим до финального update: postLoad поднимает loading, а update ниже его сохраняет, так что
            // «Старт» не проскочит между running = false и loading = true.
            val loaded = bundle
            if (loaded != null && loaded.requestedOrt != _state.value.settings.ort) {
                postLoad { loadBundle(_state.value.trip ?: prefs.getString("trip", null)) }
            }
            _state.update { it.copy(running = false, status = "Остановлено, кадров: ${it.frames}$suffix",
                navMode = null, sigmaM = null,
                gnssReasons = emptySet(), road = null, nav = null, pos = null) }
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
        cancelDurationStop()
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
        val pl = perfLog
        perfLog = null
        val ns = navSession
        navSession = null
        stopDrainTimer()
        val flushFn = flush
        flush = null
        val failedCount = sLog?.failed ?: 0
        val closeErrors = mutableListOf<String>()
        try { flushFn?.invoke() } catch (e: Exception) { closeErrors.add("ошибка завершения фильтра: ${e.message}") }
        closeQuietly("журнала датчиков", sLog)?.let { closeErrors.add(it) }
        closeQuietly("журнала дескрипторов", dLog)?.let { closeErrors.add(it) }
        closeQuietly("журнала фильтра", fl)?.let { closeErrors.add(it) }
        closeQuietly("журнала замеров", pl)?.let { closeErrors.add(it) }
        closeQuietly("журнала маршрута", ns)?.let { closeErrors.add(it) }
        try {
            log.close()
        } catch (closeError: Exception) {
            closeErrors.add("ошибка закрытия журнала: ${closeError.message}")
        }
        val suffix = buildString {
            if (failedCount > 0) append(" · ошибок записи датчиков: $failedCount")
            for (err in closeErrors) append("; $err")
        }
        _state.update { it.copy(running = false, status = "$message$suffix",
                navMode = null, sigmaM = null,
                gnssReasons = emptySet(), road = null, nav = null, pos = null) }
    }

    /** Освобождает камеру/GPS/логгер/модель. Вызывать один раз при уничтожении владельца. */
    fun close() {
        if (runningFlag.get()) stop()
        cancelDurationStop()
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
        val pl = perfLog
        perfLog = null
        val ns = navSession
        navSession = null
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
            closeQuietly("журнала замеров", pl)?.let { closeErrors.add(it) }
            closeQuietly("журнала маршрута", ns)?.let { closeErrors.add(it) }
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

    private fun startDrainTimer(drain: () -> Unit, perfTick: () -> Unit, baselineFrame: (() -> Long)?) {
        stopDrainTimer()
        // drainNow и perfTickNow ставим после stopDrainTimer(): тот обнуляет их.
        drainNow = drain
        perfTickNow = perfTick
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        drainTimer = timer
        // Таймер только ставит задачу на executor: сам drain остаётся однопоточным. Тик частый (DRAIN_TICK_MS),
        // чтобы e2e не росло на ожидание тика сверх задержки переупорядочителя; в очереди executor'а не больше
        // одной задачи drain, поэтому кадр ждёт за ней не дольше одного (обычно пустого) drain.
        drainTask = timer.scheduleWithFixedDelay({
            if (drainQueued.compareAndSet(false, true)) {
                try {
                    executor.execute {
                        try {
                            drainNow?.invoke()
                        } finally {
                            drainQueued.set(false)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // executor уже закрыт — таймер остановит close().
                    drainQueued.set(false)
                }
            }
        }, DRAIN_TICK_MS, DRAIN_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        perfTask = timer.scheduleWithFixedDelay({
            try {
                executor.execute { perfTickNow?.invoke() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // executor уже закрыт — таймер остановит close().
            }
        }, PERF_TICK_MS, PERF_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        baselineFrame?.let { scheduleBaselineFrame(timer, 0, it) }
    }

    /** Пустой кадр baseline: только push в очередь; следующий — через возвращённый интервал регулятора. */
    private fun scheduleBaselineFrame(
        timer: java.util.concurrent.ScheduledExecutorService, delayMs: Long, push: () -> Long,
    ) {
        try {
            baselineTask = timer.schedule({
                scheduleBaselineFrame(timer, push(), push)
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Таймер остановлен (stop/fail/close).
        }
    }

    private fun stopDrainTimer() {
        drainTask?.cancel(false)
        drainTask = null
        perfTask?.cancel(false)
        perfTask = null
        baselineTask?.cancel(false)
        baselineTask = null
        drainTimer?.shutdownNow()
        drainTimer = null
        drainNow = null
        perfTickNow = null
    }

    /** При duration_min > 0 — stop() на главном потоке через это время; снимается cancelDurationStop(). */
    private fun startDurationStop(durationMin: Int) {
        cancelDurationStop()
        if (durationMin <= 0) return
        val r = Runnable { stop() }
        durationStop = r
        mainHandler.postDelayed(r, durationMin * 60_000L)
    }

    private fun cancelDurationStop() {
        durationStop?.let { mainHandler.removeCallbacks(it) }
        durationStop = null
    }

    private fun closeQuietly(label: String, closeable: Closeable?): String? = try {
        closeable?.close()
        null
    } catch (e: Exception) {
        "ошибка закрытия $label: ${e.message}"
    }

    private companion object {
        const val PREF_REORDER_DELAY = "reorder_delay_ms"
        const val PREF_ORT = "ort"
        const val PREF_PROFILE = "profile"
        const val PREF_DURATION = "duration_min"
        const val PERF_TICK_MS = 5_000L
        const val DRAIN_TICK_MS = 50L
        /** Повтор пустого кадра baseline после ошибки push. */
        const val BASELINE_RETRY_MS = 1_000L
    }
}
