# M4a: производительность — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** замерить на телефоне задержку, расход батареи и нагрев (NFR-4, NFR-6, NFR-7) и получить отчёт на ПК. Добавить адаптивную частоту кадров по нагреву и режиму, ускорить путь кадра, сделать задержку буфера упорядочивания настраиваемой. Позицию на экране продлевать до текущего момента.

**Architecture:**
- **Чистая логика в `:core`** с тестами на JVM: `PerfLog` (формат), статистика опозданий в `EventReorderer`, `Nowcast`, `FrameRateGovernor`, `Resize.areaDownscaleRgba` и `Rotate90`.
- **Приложение:**
  - пишет `session-….perf.jsonl`;
  - раз в 5 с снимает батарею и нагрев;
  - управляет интервалом кадров через регулятор;
  - на вкладке «Отладка» — профиль замера («Полная» или «База без камеры»), задержка буфера, ORT `cpu`/`xnnpack`, длительность.
- **ПК:** `vpr-m4 perf-eval` строит отчёт по NFR-4/6/7 и даёт рекомендацию задержки буфера.

**Tech Stack:** Kotlin 2.1 (`:core` JVM, `:app` Android, Compose, CameraX, ONNX Runtime), Python 3.11+, JUnit, pytest.

**Спецификация:** `docs/superpowers/specs/2026-10-04-m4a-performance-design.md`.

## Global Constraints

- **Решения владельца (2026-10-04):**
  - M4a — производительность;
  - проверка на одном телефоне;
  - база NFR-6 — наш экран без камеры и модели, сравниваются два прогона по 30 мин на батарее;
  - задержку буфера замерить и уменьшить, позицию на экране продлевать прогнозом;
  - в режиме `GNSS` со здоровьем `GOOD` — не чаще 1 кадра в секунду.
- **Форматы, которые не меняются:**
  - строка кадра `.jsonl` (`FrameRecord`, контракт с `fieldlog.py`);
  - строка `.fusion.jsonl` (`TrajectoryFormat.row`);
  - `.nav.jsonl`.

  Заголовок `.fusion.jsonl` получает новое поле `reorder_delay_ms` (Python читает заголовок как dict).
- **`.perf.jsonl`** — одна JSON-строка на запись, UTF-8, числа с точкой. Первая строка — заголовок:
  ```
  {"type":"perf","v":1,"session_started_ms":…,"device":"…","profile":"full|baseline","reorder_delay_ms":1500,"ort":"cpu|xnnpack","battery_capacity_mah":4500|null}
  {"type":"frame","t_ms":…,"pre":…,"inf":…,"search":…,"fuse_ms":…,"nav_ms":…,"e2e_ms":…,"interval_ms":500}
  {"type":"sys","t_ms":…,"batt_pct":…,"charge_uah":…|null,"current_ua":…|null,"batt_temp_c":…|null,"plugged":true|false,"thermal":0..6|null,"headroom":…|null,"interval_ms":…}
  {"type":"late","t_ms":…,"sources":{"frame":{"n":…,"p50":…,"p99":…,"max":…},"gnss_fix":{…},"gnss_status":{…},"imu":{…},"agc":{…}},"late":…,"dropped":…}
  ```
  Миллисекунды записываются с 1 знаком после точки.
- **Ступени интервала кадров:** `[500, 750, 1000, 1500, 2000]` мс.
- **Правила регулятора:**
  - `thermal ≥ 3` → 2000 мс;
  - `thermal == 2`, или `headroom ≥ 0.85`, или p90 полного времени кадра > 250 мс → на ступень медленнее (не чаще раза в 10 с);
  - 60 с без этих условий → на ступень быстрее;
  - режим `GNSS` и здоровье `GOOD` → не быстрее 1000 мс;
  - изменено после ревью Task 2:
    - мягкие правила (`thermal == 2`, `headroom`, p90) замедляют не дальше 1500 мс, до 2000 мс доводит только `thermal ≥ 3`;
    - после замедления по p90 окно стоимостей очищается, первые 5 кадров (прогрев) не учитываются;
    - при выходе из GNSS+GOOD интервал сразу возвращается к ступени без ограничения 1 Гц.
- **Задержка буфера:** варианты 300 / 500 / 800 / 1500 мс, по умолчанию 1500.
- **Прогноз на «сейчас»:**
  - только для маркера и камеры карты;
  - сдвиг по курсу на `v·Δt` при `v ≥ 1 м/с` и конечном курсе;
  - `0 ≤ Δt ≤ 3000 мс`.
- **Отчёт:**
  - NFR-4 — p95 `e2e_ms` ≤ 300;
  - NFR-6 — превышение full над baseline ≤ 15 %/ч, оба прогона не на зарядке;
  - NFR-7 — прогон ≥ 60 мин и `thermal < 3` всё время;
  - рекомендация буфера: `max(p99 по источникам) + 100`, вверх до ступени из 300 / 500 / 800 / 1500.
- **Без новых зависимостей и без сети.** FR-20: кадры не сохраняются. Новые разрешения не нужны: `BatteryManager` и `PowerManager` работают без разрешений.
- **Сборка и тесты:**
  - `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew …`
  - `cd /Users/vvnovg/navigator/research/vpr_bench && uv run pytest -q`
  - Сейчас: `:core` 276, `:replay` 49, `:app` (debug) 26, pytest 282. Lint — 0 ошибок, 35 предупреждений.

---

### Task 1: формат `PerfLog`, статистика опозданий, `Nowcast` (`:core`)

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/PerfLog.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/Nowcast.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/EventReorderer.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/TrajectoryFormat.kt` (`fusionHeader(..., reorderDelayMs: Long? = null)`)
- Test: `PerfLogTest.kt`, `NowcastTest.kt`, `EventReordererTest.kt` (новые случаи), `TrajectoryFormat` (заголовок)

**Interfaces:**
- Produces:

```kotlin
object PerfLog {
    fun header(sessionStartedMs: Long, device: String, profile: String, reorderDelayMs: Long, ort: String, batteryCapacityMah: Int?): String
    fun frame(tMs: Long, pre: Double, inf: Double, search: Double, fuseMs: Double, navMs: Double, e2eMs: Double, intervalMs: Long): String
    fun sys(s: SysSample): String
    fun late(tMs: Long, stats: Map<String, LatenessStats>, late: Int, dropped: Int): String
}
data class SysSample(val tMs: Long, val battPct: Double?, val chargeUah: Long?, val currentUa: Long?, val battTempC: Double?,
    val plugged: Boolean, val thermal: Int?, val headroom: Double?, val intervalMs: Long)
data class LatenessStats(val n: Int, val p50: Double, val p99: Double, val max: Double)

// EventReorderer
fun push(item: ReorderItem, arrivalMs: Long)        // новая перегрузка; старый push(item) = push(item, item.tMs.toLong())
fun latenessSnapshotAndReset(): Map<String, LatenessStats>   // ключи: frame, gnss_fix, gnss_status, imu, agc, other
val delayMs: Long                                   // становится публичным val

object Nowcast {
    /** Позиция «сейчас» по выходу фильтра: сдвиг по курсу на v·Δt при v ≥ 1 м/с и конечном курсе, 0 ≤ Δt ≤ maxAheadMs. */
    fun at(out: LocalizerOutput, nowMs: Long, maxAheadMs: Long = 3000): DoubleArray   // [lat, lon]
}
```

- **Источник события для статистики:** кадр → `frame`. `SensorEvent` → по типу:
  - GNSS-фиксация (`LocationEvent` или как он называется в `SensorLog.kt`) → `gnss_fix`;
  - статус спутников → `gnss_status`;
  - акселерометр, гироскоп, ротация → `imu`;
  - AGC → `agc`;
  - остальное → `other`.

  Соответствие смотреть в `SensorLog.kt`; имена классов в плане не угадывать.

- [ ] **Step 1: Тесты.**
  - **`PerfLogTest`:**
    - каждая строка разбирается `Json.parseToJsonElement`;
    - поля и типы как в Global Constraints;
    - `null` выводится как `null`;
    - числа с точкой при `Locale` с запятой: в тесте `Locale.setDefault(Locale.GERMANY)` и восстановление в `finally`.
  - **`NowcastTest`:**
    - 10 м/с, курс 90° (восток), Δt = 1000 мс → восток +10 м (±0,05) через `Enu`;
    - v = 0,5 → без сдвига;
    - курс NaN → без сдвига;
    - Δt = 10 000 → сдвиг ограничен 30 м;
    - Δt < 0 → без сдвига.
  - **`EventReordererTest`:**
    - `push` с `arrivalMs` = t + 100, 200, 300 для `imu` → `p50 = 200`, `max = 300`;
    - снимок сбрасывает окно;
    - старая перегрузка даёт опоздание 0;
    - выдача событий не изменилась (существующие тесты).
  - **`fusionHeader`:** с `reorderDelayMs = 500` в строке есть `"reorder_delay_ms":500`; без параметра поля нет (старые тесты).
- [ ] **Step 2: RED.** `./gradlew :core:test`
- [ ] **Step 3: Реализация.**
  - Перцентили — по отсортированной копии окна: `p = sorted[ceil(q·n) − 1]`.
  - Окно на источник ограничено 5000 значениями (старые отбрасываются).
  - Формат чисел: `String.format(Locale.ROOT, "%.1f", x)`.
- [ ] **Step 4: GREEN.** `./gradlew :core:test :replay:test`
- [ ] **Step 5: Commit** — `feat(core): perf log format, reorder lateness stats, nowcast for display`

---

### Task 2: регулятор частоты кадров `FrameRateGovernor` (`:core`)

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/FrameRateGovernor.kt`
- Test: `FrameRateGovernorTest.kt`

**Interfaces:**

```kotlin
class FrameRateGovernor(
    val stepsMs: List<Long> = listOf(500, 750, 1000, 1500, 2000),
    private val slowCooldownMs: Long = 10_000, private val recoverAfterMs: Long = 60_000,
    private val p90LimitMs: Double = 250.0, private val headroomLimit: Double = 0.85,
    private val gnssFloorMs: Long = 1000, private val window: Int = 20,
) {
    val intervalMs: Long
    fun onFrameCost(totalMs: Double)                       // pre + inf + search + fuse одного кадра
    /** Вызывается раз в секунду или при новом sys-сэмпле; возвращает новый интервал. */
    fun update(nowMs: Long, thermal: Int?, headroom: Double?, mode: NavMode?, health: GnssHealth?): Long
}
```

- [ ] **Step 1: Тесты.**
  - Старт — 500 мс.
  - **Медленнее:**
    - `thermal = 2` → 750;
    - через 5 с снова `thermal = 2` → остаётся 750 (пауза 10 с);
    - через 10 с → 1000.
  - **Резкий нагрев:** `thermal = 3` → сразу 2000.
  - **Прогноз нагрева:** `headroom = 0.9` → медленнее.
  - **Долгие кадры:** 20 кадров по 300 мс → медленнее; 20 по 100 мс → без изменения.
  - **Восстановление:** после 60 с без условий → на ступень быстрее; ещё 60 с → ещё на ступень; не быстрее 500.
  - **Режим GNSS:** `mode = GNSS`, `health = GOOD` → 1000. Затем `mode = VISUAL` → восстановление идёт по правилу 60 с, без скачка.
  - **Нет данных о нагреве:** `thermal = null`, `headroom = null` → работает по p90 и режиму.
- [ ] **Step 2–4:** RED → реализация → GREEN (`./gradlew :core:test`).
  - После `thermal ≥ 3` возврат идёт тоже по ступеням через 60 с.
  - Здоровье — `GnssHealth.GOOD` (`GnssMonitor.kt`).
- [ ] **Step 5: Commit** — `feat(core): adaptive frame interval governor (thermal, headroom, frame cost, GNSS floor)`

---

### Task 3: быстрый путь кадра — уменьшение из RGBA-буфера и поворот после (`:core` + `FrameAnalyzer`)

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/Resize.kt` (`areaDownscaleRgba`)
- Create: `android/core/src/main/kotlin/io/visnav/core/Rotate90.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/FrameAnalyzer.kt`
- Test: `ResizeTest.kt` (новые случаи), `Rotate90Test.kt`

**Interfaces:**

```kotlin
// Resize: то же area-усреднение, что areaDownscale, но вход — RGBA_8888 байты с rowStride/pixelStride (ImageProxy.planes[0]).
fun areaDownscaleRgba(buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, outW: Int, outH: Int): IntArray  // ARGB
object Rotate90 { fun rotate(px: IntArray, w: Int, h: Int, degrees: Int): IntArray }  // degrees ∈ {0,90,180,270}, по часовой как Matrix.postRotate
```

- [ ] **Step 1: Тесты.**
  - `areaDownscaleRgba` на синтетическом RGBA (`rowStride > w·4`, `pixelStride = 4`) равен `areaDownscale` на эквивалентном ARGB `IntArray` — побайтно для размеров 64×48 → 16×12 и 1280×720 → 224×224 (случайные пиксели, seed).
  - `Rotate90` на матрице 3×2 для 0/90/180/270 — явные ожидаемые массивы; 90 по часовой: `(x, y) → (h−1−y, x)`.
  - **Эквивалентность путей:**
    - «повернуть полный кадр, затем уменьшить до (iw, ih)»;
    - «уменьшить до (ih, iw) для 90/270 или (iw, ih) для 0/180, затем повернуть».

    Для 64×48 и поворотов 0/90/180/270 максимальная разница канала ≤ 1. Если разница больше, остановиться и сообщить: area-усреднение не симметрично, нужен другой порядок.
- [ ] **Step 2–4:** RED → реализация → GREEN. Существующий `ResizeGoldenTest` (совпадение с ПК) должен проходить.
- [ ] **Step 5: `FrameAnalyzer`.**
  - Вместо `toBitmap` + поворот + `getPixels`: `areaDownscaleRgba(planes[0].buffer, …)` в размер с учётом поворота, затем `Rotate90.rotate`, затем `Preprocess.argbToRgb`.
  - Путь для кадра меньше входа модели остаётся прежним, через Bitmap.
  - `lastFrameW` и `lastFrameH` — размеры после поворота, как раньше.
  - `intervalMs` становится `@Volatile var intervalMs: Long`.
  - Сборка: `./gradlew :core:test :app:assembleDebug :app:lintDebug`.
- [ ] **Step 6: Commit** — `perf(app): downscale straight from the RGBA plane and rotate the small image; adjustable frame interval`

---

### Task 4: замеры на телефоне — `.perf.jsonl`, батарея и нагрев, регулятор в работе

**Files:**
- Create: `android/app/src/main/kotlin/io/visnav/app/SysSampler.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/OrtEmbedder.kt` (опция `xnnpack`)
- Modify: `android/app/src/main/kotlin/io/visnav/app/NavScreen.kt` (маркер и камера по `Nowcast`)

**Interfaces:**
- Consumes: всё из Task 1–3.
- Produces:
  - `class SysSampler(context) { fun sample(tMs: Long, intervalMs: Long): SysSample }`:
    - `BatteryManager`: `BATTERY_PROPERTY_CAPACITY` → `batt_pct`, `CHARGE_COUNTER` → `charge_uah`, `CURRENT_NOW` → `current_ua` (≤ 0 или `Long.MIN_VALUE` → `null`);
    - `ACTION_BATTERY_CHANGED` sticky intent → температура (`EXTRA_TEMPERATURE` / 10) и `plugged`;
    - `PowerManager.currentThermalStatus`;
    - `getThermalHeadroom(10)` на API 30+, иначе `null`; NaN → `null`.
  - `batteryCapacityMah`: `charge_uah / (batt_pct / 100) / 1000` при первом сэмпле, если оба есть.
  - `UiState.perf: PerfUi?` с полями `intervalMs`, `thermal`, `e2eP50`, `currentMa` — для вкладки «Отладка».
  - Настройки (`SharedPreferences "visnav"`): `reorder_delay_ms` (300/500/800/1500, по умолчанию 1500), `ort` (`cpu`/`xnnpack`, по умолчанию `cpu`), `profile` (`full`/`baseline`), `duration_min` (0/30/60). Меняются только вне записи, через методы контроллера `setReorderDelay`, `setOrt`, `setProfile`, `setDuration`. ORT меняется перезагрузкой базы, как при смене поездки.

Изменения в `M1Controller.start()`:
- **Журналы:** `PerfLog.header` в `session-….perf.jsonl` (тот же класс-писатель, что `FusionLog`, или общий). В `fusionHeader` передаётся `reorderDelayMs`.
- **Буфер:** `EventReorderer(delayMs = настройка)`; `push(item, System.currentTimeMillis())` для датчиков и кадров.
- **Замеры кадра:**
  - в `sink` для `Frame`: `fuse_ms` (время `localizer.onFrame`), `nav_ms` (время `nav?.onOutput`);
  - `e2e_ms = System.currentTimeMillis() − item.frameTMs` в момент публикации `_state.update`;
  - `pre`, `inf`, `search` — из `FrameRecord.latMs` этого кадра (по `t_ms`; хранить последние в `HashMap<Long, LatencyJson>`, ограниченной 64 записями);
  - `governor.onFrameCost(pre + inf + search + fuse)`;
  - строка `frame` в perf-журнал.
- **Таймер раз в 5 с** (тот же scheduled executor, что drain, задача ставится на `executor`):
  - `SysSampler.sample` → строка `sys`;
  - `reorderer.latenessSnapshotAndReset()` → строка `late`;
  - `governor.update(now, thermal, headroom, mode, health последнего выхода)` → `frameAnalyzer.intervalMs = …`;
  - `UiState.perf`.
- **Профиль `baseline`:** камера не подключается (`bindCamera` ничего не делает и снимает прежние use case). Кадров нет, фильтр работает на GNSS и IMU. Строк `frame` нет.
- **Длительность:** при `duration_min > 0` через это время `stop()` на главном потоке (`Handler(Looper.getMainLooper()).postDelayed`); таймер отменяется при ручном стопе.
- **ORT `xnnpack`:** `options.addXnnpack(mapOf("intra_op_num_threads" to "4"))`. Если сессия не создаётся — откат на CPU со статусом «XNNPACK недоступен — CPU», в perf-заголовке фактическое значение.
- **`NavScreen`:** маркер и камера используют `Nowcast.at(...)` по последнему выходу на момент публикации. Для этого `MapPos` получает `tMs`; пересчёт при каждой отрисовке не нужен. Круг неопределённости — вокруг прогнозной точки.

- [ ] **Step 1: Тесты** — только чистые помощники, если вынесены (например, разбор настроек). Остальное проверяется сборкой.
- [ ] **Step 2: Реализация.**
- [ ] **Step 3: Сборка.** `./gradlew :core:test :replay:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → 0 ошибок lint.
- [ ] **Step 4: Commit** — `feat(app): perf log (frame cost, e2e, battery, thermal, lateness), frame-rate governor, reorder delay and ORT settings, nowcast marker`

---

### Task 5: вкладка «Отладка» — настройки замера и показатели

**Files:**
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Screen.kt`

Добавить блок «Замер» (виден, только когда запись не идёт; во время записи — только показатели):
- «Профиль: Полная / База (без камеры)»;
- «Длительность: без ограничения / 30 мин / 60 мин»;
- «Буфер: 300 / 500 / 800 / 1500 мс»;
- «Модель: CPU / XNNPACK».

Во время записи — строка показателей: «Кадр: каждые N мс · Нагрев: k · E2E p50: X мс · Ток: Y мА».

Подписи уровней нагрева: 0 «нет», 1 «слабый», 2 «умеренный», 3 «сильный», 4 «критический», 5 «аварийный», 6 «отключение».

- [ ] **Step 1: Тест** чистой функции подписи нагрева (`thermalLabel(Int?)`), если вынесена.
- [ ] **Step 2: Реализация и сборка** (как в Task 4) → 0 ошибок lint.
- [ ] **Step 3: Commit** — `feat(app): perf settings and live indicators on the debug tab`

---

### Task 6: `vpr-m4 perf-eval`

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/perfeval.py`
- Create: `research/vpr_bench/src/vpr_bench/m4cli.py`
- Modify: `research/vpr_bench/pyproject.toml` (`vpr-m4 = "vpr_bench.m4cli:main"`)
- Test: `research/vpr_bench/tests/test_perfeval.py`

**Interfaces:**
- CLI:
  ```
  vpr-m4 perf-eval --perf A.perf.jsonl [--baseline B.perf.jsonl] --out report.md
  ```
  Код 2 при ошибке формата или если `--perf` не `profile=full`, а `--baseline` не `baseline`.
- Функции:
  - `read_perf(path) -> PerfLog(header, frames, sys, late)`;
  - `evaluate(full, baseline=None) -> PerfResult`;
  - `render(PerfResult) -> str`.
- **Расход в %/ч:**
  - по `charge_uah`: `(c0 − c1) / capacity_uah / hours · 100`, где `capacity_uah = battery_capacity_mah · 1000` из заголовка;
  - иначе по `batt_pct`: `(p0 − p1) / hours`, с пометкой «по процентам, грубо»;
  - если `plugged` хоть в одном сэмпле — «на зарядке, расход не считается».
- **Рекомендация буфера:** `max(p99 источников по всем окнам, взвешенно по n) + 100` → ближайшая ступень сверху из `[300, 500, 800, 1500]`; если больше 1500 — «> 1500, оставить 1500 и разобраться».

Отчёт (Markdown, по-русски):
1. **Прогон:** устройство, профиль, длительность, буфер, ORT.
2. **NFR-4:** таблица p50/p95/max для `e2e`, `pre`, `inf`, `search`, `fuse`, `nav`; доля буфера = `reorder_delay_ms / p50(e2e)`; вердикт p95 ≤ 300.
3. **NFR-6:** %/ч full, %/ч baseline, превышение, вердикт ≤ 15; средний ток.
4. **NFR-7:**
   - время до первого `thermal ≥ 2`;
   - доли времени по уровням;
   - интервал кадров: доля времени на каждой ступени и минимальная/максимальная;
   - вердикт «≥ 60 мин и `thermal < 3`».
5. **Опоздания:** таблица по источникам (p50, p99, max, n), `late`, `dropped`, рекомендация буфера.

- [ ] **Step 1: Тесты** — синтетические журналы `tmp_path`:
  - 100 кадров с известными `e2e` → p50/p95;
  - два прогона по 30 мин с `charge_uah` → ожидаемые %/ч и превышение;
  - `plugged` → отказ;
  - нет `charge_uah` → по процентам;
  - нагрев: первый `thermal = 2` на 20-й минуте → «20 мин»;
  - прогон 61 мин без `thermal ≥ 3` → вердикт «да»; 30 мин → «нет (короче 60 мин)»;
  - опоздания с p99 = 420 → рекомендация 800;
  - CLI: неверные профили → 2.
- [ ] **Step 2–4:** RED → реализация → GREEN (`uv run pytest -q`).
- [ ] **Step 5: Commit** — `feat(m4): perf-eval — NFR-4/6/7 report and reorder delay recommendation`

---

### Task 7: протокол M4a и SPEC

**Files:**
- Create: `docs/research/m4a-perf.md`
- Modify: `docs/SPEC.md`:
  - строка M4 в плане этапов: «Делится на M4a (производительность), M4b (OBD-II), M4c (UVC/AAOS), M4d (закрытая бета)»;
  - в NFR-4 — примечание про буфер упорядочивания и прогноз на «сейчас».
- Modify: `docs/research/m2c-monitor.md`, если там описано отставание режима на 1,5–2 с — ссылка на настраиваемый буфер.

Содержание `m4a-perf.md`:
1. **Подготовка:**
   - телефон в держателе, камера на окно или улицу;
   - яркость экрана фиксированная (50 %);
   - фоновые приложения закрыты;
   - заряд ≥ 80 %, **не на зарядке**;
   - поездка выбрана (M3c).
2. **NFR-6:**
   - прогон «База» 30 мин, затем «Полная» 30 мин — подряд или в разные дни при той же температуре;
   - команда `perf-eval --perf … --baseline …`.
3. **NFR-7:** прогон «Полная» 60 мин; телефон на зарядке допустим — расход в этом прогоне не считается.
4. **NFR-4 и буфер:**
   - прогон 10 мин с буфером 1500;
   - рекомендация из отчёта;
   - повтор с рекомендованным буфером;
   - сравнить `late` и `dropped`: если опоздавших > 0,1 % событий, вернуть ступень.
5. **ORT:** два прогона по 10 мин, CPU и XNNPACK; выбрать по p95 `inf` и току.
6. **В машине:** «Полная» по маршруту поездки, проверить, что маркер не отстаёт на поворотах (прогноз) и что ступени кадров меняются ожидаемо.
7. **Как устроено:**
   - журнал `.perf.jsonl`;
   - регулятор частоты кадров;
   - прогноз на экране, при котором ведение по маршруту остаётся на выходе фильтра;
   - replay сортирует все события, поэтому при опоздавших событиях телефон и replay расходятся (как и раньше).
8. **Ограничения:**
   - один телефон;
   - API 29 без прогноза нагрева;
   - ток `CURRENT_NOW` на части устройств неточен;
   - перенос маршрутизации с потока кадров — только если p95 `nav_ms` > 50 мс.
9. **Итоги (заполнить):** таблица NFR-4/6/7, выбранный буфер, ORT.

- [ ] **Step 1: Документы.** Команды, флаги и названия настроек сверить с кодом Tasks 1–6.
- [ ] **Step 2: Commit** — `docs: M4a performance protocol; SPEC M4 split and NFR-4 note`
