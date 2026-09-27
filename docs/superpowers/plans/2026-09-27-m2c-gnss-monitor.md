# M2c: монитор GNSS, режимы навигации и фильтр на телефоне — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Детектор деградации, глушения и подмены GNSS (FR-14) и автоматическое переключение режимов `GNSS → FUSED → VISUAL → DEAD_RECKONING` с гистерезисом (FR-15).
- Всё это работает и на телефоне в реальном времени, и в replay.
- Replay умеет подмешивать искусственную подмену и глушение и измеряет задержку обнаружения, ложные срабатывания и точность во время атаки.

**Architecture:** Логика фильтра из `Replayer` (M2a) выносится в общий класс `:core` `Localizer`. Его получают события датчиков и кадры, он отдаёт оценку позиции, режим и состояние GNSS. Внутри:
- **`GnssMonitor`** оценивает каждый GNSS-фикс и статус спутников и выдаёт `GOOD`, `DEGRADED` или `UNTRUSTED` с причинами. Признаки:
  - нет фикса;
  - мало спутников;
  - низкий C/N0;
  - провал усиления приёмника (AGC);
  - плохая точность;
  - скачок;
  - расхождение с фильтром (χ²);
  - неестественно ровный C/N0 по спутникам.
- **`ModeManager`** переводит состояние в режим с задержками: ухудшение — через 2 с, улучшение — через 10 с.
- **Использование GNSS в фильтре по режимам:** в `GNSS` — как есть, в `FUSED` — с σ×3, в `VISUAL` и `DEAD_RECKONING` — не используется.

`Replayer` и приложение используют один и тот же `Localizer`. Приложение пишет вывод в `.fusion.jsonl` в том же формате, что и replay-траектория, и показывает режим на экране (FR-19). Запись дополняется статистикой по спутникам (разброс и максимум C/N0, спутники по системам) и событиями AGC.

**Tech Stack:** Kotlin 2.1 (JVM 17), Android (GnssStatus, GnssMeasurementsEvent), Python 3.11+ (numpy), JUnit 4, pytest.

## Global Constraints

- **Целевые показатели M2c.** Спецификация (FR-14/15) чисел не задаёт; это предложения плана, владелец может их изменить:
  - задержка обнаружения подмены (состояние `UNTRUSTED`) ≤ 5 с для смещений ≥ 100 м;
  - задержка выхода из режима `GNSS` при глушении ≤ 5 с;
  - ложное `UNTRUSTED` ≤ 1 % времени движения на чистых данных (вне подмешанных окон, после первых 60 с);
  - во время подменённого окна с визуальными фиксациями P95 ошибки ≤ 15 м (как NFR-1);
  - на реальной поездке через зону подмены полнота обнаружения ≥ 90 % секунд, которые `clean_track` пометил как подмену.
- **Режимы и цвета на экране** (SPEC §7):
  - `GNSS` — 🟢 «GNSS»;
  - `FUSED` — 🟢 «GNSS + камера»;
  - `VISUAL` — 🔵 «Визуальная навигация»;
  - `DEAD_RECKONING` — 🟠 «Счисление пути — точность снижена».
  - Рядом радиус доверия «±N м» и причины, если состояние не `GOOD`.
- **Формат `.sensors.jsonl` v1 расширяется только аддитивно** (решение владельца по M2a): новые поля необязательные, читатели игнорируют неизвестные виды событий и поля.
- **Формат строки траектории / fusion** — существующие поля M2a без изменений, в конец добавляются `"mode"`, `"health"`, `"reasons"`, `"injected"`.
- **Лицензии и зависимости** — новых нет.
- **Android:** без Google Play Services, `minSdk 29`.
- **FR-20:** изображения не сохраняются.
- **Команды Gradle** — из `/Users/vvnovg/navigator/android` с `JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"`.
- **Команды Python** — из `/Users/vvnovg/navigator/research/vpr_bench` через `uv run`.
- **Коммиты** заканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Сейчас `:core` — 78 тестов, `:replay` — 17, Python — 160 passed / 4 deselected.

---

## Структура файлов

```
android/core/src/main/kotlin/io/visnav/core/
├── SensorLog.kt        Task 1  GnssStatusEvent + cn0Std/cn0Max/used по системам; AgcEvent ("agc")
├── Ekf2d.kt            Task 1  + positionD2()
├── GnssMonitor.kt      Task 2  GnssHealth, GnssReason, Assessment, MonitorConfig, GnssMonitor
├── ModeManager.kt      Task 3  NavMode, ModeConfig, ModeManager
├── Localizer.kt        Task 4  LocalizerConfig, LocalizerOutput, Localizer
└── TrajectoryFormat.kt Task 4  строка траектории/fusion (общая для replay и приложения)
android/replay/src/main/kotlin/io/visnav/replay/
├── Replayer.kt         Task 4 (тонкая обёртка над Localizer), Task 5 (глушение/подмена)
├── TrajectoryWriter.kt Task 4/5
└── Main.kt             Task 5  --jam, --spoof, --no-monitor
android/app/src/main/kotlin/io/visnav/app/
├── GpsSource.kt        Task 7  статистика спутников, AGC
├── M1Controller.kt     Task 7  Localizer на телефоне, .fusion.jsonl, режим в UiState
└── M1Screen.kt         Task 7  режим, цвет, ±σ, причины
research/vpr_bench/src/vpr_bench/
├── monitoreval.py  m2cli.py (+ monitor-eval)   Task 6
docs/research/m2c-monitor.md                    Task 8
```

---

### Task 1: Расширение записи GNSS и χ²-расстояние фикса

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/SensorLog.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/Ekf2d.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/SensorLogTest.kt` (дополнить), `android/core/src/test/kotlin/io/visnav/core/Ekf2dTest.kt` (дополнить)

**Interfaces:**
- Produces:
  - `GnssStatusEvent(tMs, sats, used, cn0Mean: Float?, cn0Std: Float? = null, cn0Max: Float? = null, usedGps: Int? = null, usedGlo: Int? = null, usedGal: Int? = null, usedBds: Int? = null)`. Строка: прежние поля, затем `,"cn0_std":…,"cn0_max":…,"used_gps":…,"used_glo":…,"used_gal":…,"used_bds":…`; `null`, если значения нет или оно нечисловое. Парсер читает новые поля как необязательные: строка без них, в формате M2a, тоже читается.
  - `AgcEvent(tMs: Double, agcDb: Float, n: Int)`, строка `{"t":…,"k":"agc","agc_db":…,"n":…}`. `isWritable` требует конечный `agcDb`.
  - `Ekf2d.positionD2(e: Double, n: Double, sigma: Double): Double` — χ²-расстояние Махаланобиса фикса до текущего прогноза. Состояние не меняется; `require(sigma > 0 && sigma.isFinite())`.

- [ ] **Step 1: Падающие тесты**

Добавить в `SensorLogTest.kt`:
```kotlin
    @Test fun gnssStatusExtendedFieldsRoundTrip() {
        val e = GnssStatusEvent(5.0, 30, 14, 33.5f, 4.25f, 41f, 6, 4, 3, 1)
        assertEquals("{\"t\":5.0,\"k\":\"gnss\",\"sats\":30,\"used\":14,\"cn0\":33.5,\"cn0_std\":4.25,\"cn0_max\":41.0," +
            "\"used_gps\":6,\"used_glo\":4,\"used_gal\":3,\"used_bds\":1}", SensorLogFormat.line(e))
        assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
    }

    @Test fun gnssStatusM2aLineStillParses() {
        val e = SensorLogFormat.parse("{\"t\":3000.0,\"k\":\"gnss\",\"sats\":20,\"used\":12,\"cn0\":null}")
        assertEquals(GnssStatusEvent(3000.0, 20, 12, null), e)
    }

    @Test fun agcEventRoundTripAndWritability() {
        val e = AgcEvent(7.0, -2.5f, 12)
        assertEquals("{\"t\":7.0,\"k\":\"agc\",\"agc_db\":-2.5,\"n\":12}", SensorLogFormat.line(e))
        assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
        assertFalse(SensorLogFormat.isWritable(AgcEvent(7.0, Float.NaN, 1)))
    }
```
(Добавить `import kotlin.test.assertFalse`, если его нет.) Существующий тест точного формата строки `gnss` из M2a обновить под новый формат: к нему добавляются шесть полей со значением `null`.

Добавить в `Ekf2dTest.kt`:
```kotlin
    @Test fun positionD2MatchesUpdateGateAndDoesNotChangeState() {
        val f = Ekf2d().also { it.init(0.0, 0.0, 0.0, 0.0, posSigma = 3.0, psiSigma = 0.1, vSigma = 1.0) }
        val before = f.x.copyOf() to f.p.copyOf()
        // S = (9 + 16)·I = 25·I, смещение 10 м по востоку → d² = 100/25 = 4
        assertEquals(4.0, f.positionD2(10.0, 0.0, 4.0), 1e-9)
        assertContentEquals(before.first, f.x); assertContentEquals(before.second, f.p)
        assertFailsWith<IllegalArgumentException> { f.positionD2(0.0, 0.0, 0.0) }
    }
```
(Нужные импорты `assertContentEquals`, `assertFailsWith`.)

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (нет `AgcEvent`, `positionD2`, новых параметров `GnssStatusEvent`).

- [ ] **Step 2: Реализовать**

В `SensorLog.kt`:
```kotlin
data class GnssStatusEvent(
    override val tMs: Double, val sats: Int, val used: Int, val cn0Mean: Float?,
    val cn0Std: Float? = null, val cn0Max: Float? = null,
    val usedGps: Int? = null, val usedGlo: Int? = null, val usedGal: Int? = null, val usedBds: Int? = null,
) : SensorEvent

/** Средний уровень автоматической регулировки усиления приёмника GNSS (дБ) по n измерениям. */
data class AgcEvent(override val tMs: Double, val agcDb: Float, val n: Int) : SensorEvent
```
В `isWritable`: `is AgcEvent -> e.agcDb.isFinite()`.
В `line`:
```kotlin
        is GnssStatusEvent -> {
            fun f(v: Float?) = if (v?.isFinite() == true) v else null
            "{\"t\":${e.tMs},\"k\":\"gnss\",\"sats\":${e.sats},\"used\":${e.used},\"cn0\":${f(e.cn0Mean)}," +
                "\"cn0_std\":${f(e.cn0Std)},\"cn0_max\":${f(e.cn0Max)},\"used_gps\":${e.usedGps}," +
                "\"used_glo\":${e.usedGlo},\"used_gal\":${e.usedGal},\"used_bds\":${e.usedBds}}"
        }
        is AgcEvent -> "{\"t\":${e.tMs},\"k\":\"agc\",\"agc_db\":${e.agcDb},\"n\":${e.n}}"
```
В `parse` (рядом с `fOrNull` добавить `fun iOrNull(key: String) = (o[key] as? JsonPrimitive)?.intOrNull` и импорт `kotlinx.serialization.json.intOrNull`):
```kotlin
            "gnss" -> GnssStatusEvent(t, o.getValue("sats").jsonPrimitive.int, o.getValue("used").jsonPrimitive.int,
                fOrNull("cn0"), fOrNull("cn0_std"), fOrNull("cn0_max"),
                iOrNull("used_gps"), iOrNull("used_glo"), iOrNull("used_gal"), iOrNull("used_bds"))
            "agc" -> AgcEvent(t, f("agc_db"), o.getValue("n").jsonPrimitive.int)
```
В `Ekf2d.kt`:
```kotlin
    /** χ²-расстояние фикса позиции до прогноза (как в гейте updatePosition), без изменения состояния. */
    fun positionD2(e: Double, n: Double, sigma: Double): Double {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        val r = sigma * sigma
        val s00 = p[0] + r; val s01 = p[1]; val s10 = p[5]; val s11 = p[6] + r
        val det = s00 * s11 - s01 * s10
        val ye = e - x[0]; val yn = n - x[1]
        return (ye * (s11 * ye - s01 * yn) + yn * (-s10 * ye + s00 * yn)) / det
    }
```
Индексы `p[1]`, `p[5]`, `p[6]` соответствуют `idx(0,1)`, `idx(1,0)`, `idx(1,1)` при N = 5. Если в файле есть `idx`, лучше использовать его.

- [ ] **Step 3: Тесты зелёные**

Run: `./gradlew :core:test` (с тем же `JAVA_HOME`)
Expected: `BUILD SUCCESSFUL`, 82 теста.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): satellite C/N0 stats, per-constellation counts, AGC events; EKF fix distance

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Монитор GNSS

> Редакция после ревью (решение владельца): выход из «подмены» только по положительным доказательствам, устойчивость к городским выбросам GPS, AGC-база не подстраивается под падение.

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/GnssMonitor.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/GnssMonitorTest.kt`

**Interfaces:**
- Consumes: `LocEvent`, `GnssStatusEvent`, `AgcEvent`, `Geo.haversineM`.
- Produces:
  - `enum class GnssHealth { GOOD, DEGRADED, UNTRUSTED }`
  - `enum class GnssReason { FIX_GAP, NO_FIX, FEW_SATS, LOW_CN0, AGC_DROP, POOR_ACCURACY, JUMP, INNOVATION, UNIFORM_CN0 }`
  - `data class Assessment(val health: GnssHealth, val reasons: Set<GnssReason>)`
  - `data class MonitorConfig(...)` — значения по умолчанию в коде ниже.
  - `class GnssMonitor(config)` с методами:
    - `onStatus(e)`, `onAgc(e)`;
    - `onVisualFix(tMs: Double, lat: Double, lon: Double, sigmaM: Double)` — принятая визуальная фиксация;
    - `onFix(e: LocEvent, innovationD2: Double?, distToFilterM: Double?, filterSigmaM: Double?)`. Здесь `innovationD2` — χ²-расстояние фикса до прогноза фильтра, `distToFilterM` — расстояние от фикса до оценки фильтра в метрах, `filterSigmaM` — `posSigma()` фильтра. Все три равны `null`, если фильтр не инициализирован. `null` в `innovationD2` сбрасывает счётчик плохих фиксов подряд.
    - `consumeReinit(): Boolean`;
    - `assess(tMs: Double): Assessment`.

Правила:
- **Разрыв фиксов:** больше `fixGapMs` → `FIX_GAP` (`DEGRADED`); больше `noFixMs` → `NO_FIX` (`UNTRUSTED`).
- **`JUMP`** — расстояние между соседними фиксами больше `maxJumpMps·dt + jumpAccFactor·(acc₁ + acc₂)`. Держится `jumpHoldMs`. Фикс со временем не позже предыдущего игнорируется целиком.
- **`UNIFORM_CN0`** — не меньше `uniformMinCount` из последних `uniformWindow` статусов имеют разброс C/N0 < `uniformCn0StdDb` при `used ≥ uniformMinUsed`, и последний статус свежий.
- **`INNOVATION`** фиксируется после `innovationCount` фиксов подряд, каждый из которых плох сразу по двум независимым признакам: d² > гейта **и** `distToFilterM != null && distToFilterM > relockMaxDistM` (иначе расхождение можно списать на выброс χ², а не на реальный отрыв от фильтра). Снимается только после `relockOkMs` непрерывных «положительных доказательств» — каждое GNSS-фикса выполняет хотя бы одно:
  - **визуальное согласие:** последняя визуальная фиксация отстоит от фикса по времени не больше чем на `visualAgreeMs`, а по расстоянию — не больше `max(visualAgreeM, 2·σ_vis)`;
  - **точный фильтр:** `filterSigmaM ≤ relockMaxSigmaM` и `distToFilterM ≤ relockMaxDistM`.
  
  Фикс без доказательств обнуляет отсчёт. Если хотя бы одно доказательство в отсчёте было только визуальным (фильтр неточен или далеко), при снятии фиксации ставится запрос на переинициализацию фильтра по GNSS. Больше никаких путей снятия нет, в том числе «по таймауту»: подмена с правдоподобными признаками не должна вернуть систему в `GOOD`. Новая фиксация сбрасывает невостребованный запрос.
- **Базовый уровень AGC:**
  - экспоненциальное среднее с временной постоянной `agcTauMs`;
  - обновляется, только если остальные признаки чистые и значение не ниже базы на `agcFreezeDb` и больше (под падение база не подстраивается);
  - если `AGC_DROP` держится `agcRelearnMs` при чистых признаках статуса, база переустанавливается на текущее значение (зарядка, смена диапазонов).
- **`UNTRUSTED`**, если есть причина из набора {`NO_FIX`, `JUMP`, `INNOVATION`, `UNIFORM_CN0`}; `DEGRADED` — любая другая причина.
- **Окно `UNIFORM_CN0`:** если разрыв между соседними статусами превышает `statusStaleMs`, накопленное окно последних флагов сбрасывается — старые (возможно, ещё не подменённые) статусы не должны продолжать влиять на решение после долгого молчания приёмника.

- [ ] **Step 1: Падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/GnssMonitorTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GnssMonitorTest {
    private fun fix(t: Double, lat: Double = 55.75, lon: Double = 37.6, acc: Float = 4f) =
        LocEvent(t, lat, lon, acc, 10f, 0.5f, 90f, 2f)
    private fun status(t: Double, used: Int = 14, cn0: Float = 35f, std: Float = 5f) =
        GnssStatusEvent(t, 30, used, cn0, std, cn0 + 9f, 6, 4, 3, 1)
    private fun GnssMonitor.goodSecond(t: Double) { onStatus(status(t)); onFix(fix(t), 1.0, 2.0, 4.0) }
    private fun GnssMonitor.latchAt(t0: Double) {
        for (i in 0..2) onFix(fix(t0 + i * 1_000.0), 50.0, 200.0, 5.0)
    }

    @Test fun cleanSignalIsGood() {
        val m = GnssMonitor(); m.goodSecond(0.0)
        assertEquals(Assessment(GnssHealth.GOOD, emptySet()), m.assess(500.0))
    }

    @Test fun fixGapDegradesThenNoFixIsUntrusted() {
        val m = GnssMonitor(); m.goodSecond(0.0)
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.FIX_GAP)), m.assess(3_100.0))
        val a = m.assess(10_100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health); assertTrue(GnssReason.NO_FIX in a.reasons)
    }

    @Test fun satelliteAndCn0Boundaries() {
        val ok = GnssMonitor(); ok.onFix(fix(0.0), 1.0, 1.0, 4.0); ok.onStatus(status(0.0, used = 5, cn0 = 25f))
        assertEquals(GnssHealth.GOOD, ok.assess(100.0).health)
        val bad = GnssMonitor(); bad.onFix(fix(0.0), 1.0, 1.0, 4.0); bad.onStatus(status(0.0, used = 4, cn0 = 24.9f))
        assertEquals(setOf(GnssReason.FEW_SATS, GnssReason.LOW_CN0), bad.assess(100.0).reasons)
    }

    @Test fun staleStatusIsIgnored() {
        val m = GnssMonitor()
        m.onStatus(status(0.0, used = 3)); m.onFix(fix(9_000.0), 1.0, 1.0, 4.0)
        assertEquals(GnssHealth.GOOD, m.assess(9_100.0).health)
    }

    @Test fun uniformCn0NeedsThreeOfFive() {
        val m = GnssMonitor()
        for (i in 0..4) m.onStatus(status(i * 1_000.0, used = 12, cn0 = 40f, std = 3f))
        for (i in 5..6) m.onStatus(status(i * 1_000.0, used = 12, cn0 = 40f, std = 0.8f))
        m.onFix(fix(6_000.0), 1.0, 1.0, 4.0)
        assertFalse(GnssReason.UNIFORM_CN0 in m.assess(6_100.0).reasons) // 2 из 5
        m.onStatus(status(7_000.0, used = 12, cn0 = 40f, std = 0.8f)); m.onFix(fix(7_000.0), 1.0, 1.0, 4.0)
        val a = m.assess(7_100.0)
        assertEquals(GnssHealth.UNTRUSTED, a.health); assertTrue(GnssReason.UNIFORM_CN0 in a.reasons)
    }

    @Test fun jumpAccountsForFixAccuracy() {
        fun jumped(dLatDeg: Double, acc2: Float): Boolean {
            val m = GnssMonitor()
            m.onFix(fix(0.0), null, null, null)
            m.onFix(fix(1_000.0, lat = 55.75 + dLatDeg, acc = acc2), null, null, null)
            return GnssReason.JUMP in m.assess(1_100.0).reasons
        }
        assertTrue(jumped(0.00135, 4f))    // ~150 м > 70 + 3·8 = 94
        assertFalse(jumped(0.00081, 4f))   // ~90 м
        assertFalse(jumped(0.00135, 30f))  // ~150 м < 70 + 3·34 = 172
    }

    @Test fun outOfOrderFixIsIgnored() {
        val m = GnssMonitor()
        m.onFix(fix(2_000.0), null, null, null)
        m.onFix(fix(1_000.0, lat = 55.80), null, null, null)
        assertFalse(GnssReason.JUMP in m.assess(2_100.0).reasons)
    }

    @Test fun innovationLatchNeedsConsecutiveBadFixes() {
        val m = GnssMonitor()
        m.onFix(fix(0.0), 50.0, 100.0, 5.0); m.onFix(fix(1_000.0), null, null, null)
        m.onFix(fix(2_000.0), 50.0, 100.0, 5.0); m.onFix(fix(3_000.0), 50.0, 100.0, 5.0)
        assertFalse(GnssReason.INNOVATION in m.assess(3_100.0).reasons) // null разорвал серию
        m.onFix(fix(4_000.0), 50.0, 100.0, 5.0)
        assertEquals(GnssHealth.UNTRUSTED, m.assess(4_100.0).health)
    }

    @Test fun relocksWhenFilterIsTrustworthyAndClose() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..13) m.onFix(fix(i * 1_000.0), 1.0, 10.0, 8.0)
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
        assertFalse(m.consumeReinit())
    }

    @Test fun smallD2FromGrownSigmaDoesNotRelock() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..60) m.onFix(fix(i * 1_000.0), 1.0, 100.0, 60.0) // «согласуется» только из-за большой σ
        assertTrue(GnssReason.INNOVATION in m.assess(60_100.0).reasons)
        assertFalse(m.consumeReinit())
    }

    @Test fun visualAgreementRelocksAndRequestsReinit() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..13) {
            val t = i * 1_000.0
            m.onVisualFix(t - 200.0, 55.7501, 37.6, 8.0)       // ~11 м от GNSS
            m.onFix(fix(t), 30.0, 120.0, 80.0)                  // фильтр уплыл
        }
        assertFalse(GnssReason.INNOVATION in m.assess(13_100.0).reasons)
        assertTrue(m.consumeReinit()); assertFalse(m.consumeReinit())
    }

    @Test fun visualDisagreementKeepsLatch() {
        val m = GnssMonitor(); m.latchAt(0.0)
        for (i in 3..30) {
            val t = i * 1_000.0
            m.onVisualFix(t - 200.0, 55.752, 37.6, 8.0)        // ~220 м от GNSS
            m.onFix(fix(t), 30.0, 120.0, 80.0)
        }
        assertTrue(GnssReason.INNOVATION in m.assess(30_100.0).reasons)
    }

    @Test fun agcDropDegrades() {
        val m = GnssMonitor()
        for (i in 0..20) { val t = i * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, 2f, 10)) }
        m.goodSecond(21_000.0); m.onAgc(AgcEvent(21_000.0, -5f, 10))
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.AGC_DROP)), m.assess(21_100.0))
    }

    @Test fun slowAgcRampIsNotAbsorbed() {
        val m = GnssMonitor()
        for (i in 0..20) { val t = i * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, 2f, 10)) }
        for (k in 1..120) {
            val t = (20 + k) * 1_000.0
            m.goodSecond(t); m.onAgc(AgcEvent(t, 2f - 0.1f * k, 10))
        }
        assertTrue(GnssReason.AGC_DROP in m.assess(140_100.0).reasons)
    }

    @Test fun persistentAgcShiftIsRelearned() {
        val m = GnssMonitor()
        for (i in 0..20) { val t = i * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, 2f, 10)) }
        for (k in 1..305) { val t = (20 + k) * 1_000.0; m.goodSecond(t); m.onAgc(AgcEvent(t, -8f, 10)) }
        assertFalse(GnssReason.AGC_DROP in m.assess(325_100.0).reasons)
    }

    @Test fun poorAccuracyDegrades() {
        val m = GnssMonitor(); m.onFix(fix(0.0, acc = 35f), 1.0, 1.0, 4.0)
        assertEquals(Assessment(GnssHealth.DEGRADED, setOf(GnssReason.POOR_ACCURACY)), m.assess(100.0))
    }
}
```
Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL (нет новых сигнатур и причин).

- [ ] **Step 2: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/GnssMonitor.kt`:
```kotlin
package io.visnav.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

enum class GnssHealth { GOOD, DEGRADED, UNTRUSTED }
enum class GnssReason { FIX_GAP, NO_FIX, FEW_SATS, LOW_CN0, AGC_DROP, POOR_ACCURACY, JUMP, INNOVATION, UNIFORM_CN0 }
data class Assessment(val health: GnssHealth, val reasons: Set<GnssReason>)

data class MonitorConfig(
    val fixGapMs: Double = 3_000.0,
    val noFixMs: Double = 10_000.0,
    val statusStaleMs: Double = 5_000.0,
    val minUsed: Int = 5,
    val minCn0: Float = 25f,            // дБ·Гц, средний по спутникам в решении
    val maxAccM: Float = 20f,           // Android: радиус 68 %
    val maxJumpMps: Double = 70.0,
    val jumpAccFactor: Double = 3.0,
    val jumpHoldMs: Double = 10_000.0,
    val innovationGate: Double = 13.8,  // χ², 2 ст. свободы, 99.9 %
    val innovationCount: Int = 3,
    val relockOkMs: Double = 10_000.0,
    val relockMaxSigmaM: Double = 30.0,
    val relockMaxDistM: Double = 30.0,
    val visualAgreeMs: Double = 2_000.0,
    val visualAgreeM: Double = 30.0,
    val uniformCn0StdDb: Float = 1.5f,  // у подменного сигнала все спутники почти одной силы
    val uniformMinUsed: Int = 6,
    val uniformWindow: Int = 5,
    val uniformMinCount: Int = 3,
    val agcDropDb: Float = 6f,
    val agcTauMs: Double = 20_000.0,
    val agcFreezeDb: Float = 1f,
    val agcRelearnMs: Double = 300_000.0,
)

private val UNTRUSTED_REASONS = setOf(GnssReason.NO_FIX, GnssReason.JUMP, GnssReason.INNOVATION, GnssReason.UNIFORM_CN0)

/**
 * Детектор деградации, глушения и подмены GNSS (FR-14). Время — мс настенных часов телефона.
 * Фиксация «расхождение с фильтром» снимается только по положительным доказательствам (визуальная
 * фиксация рядом с GNSS или точный фильтр рядом с GNSS), а не по таймауту: иначе правдоподобная
 * подмена со временем вернула бы систему в GOOD.
 */
class GnssMonitor(private val config: MonitorConfig = MonitorConfig()) {
    private data class VisualFix(val tMs: Double, val lat: Double, val lon: Double, val sigmaM: Double)

    private var lastFix: LocEvent? = null
    private var lastStatus: GnssStatusEvent? = null
    private val uniformFlags = ArrayDeque<Boolean>()
    private var lastAgc: AgcEvent? = null
    private var agcBaseline: Double? = null
    private var lastAgcT: Double? = null
    private var agcDropSince: Double? = null
    private var lastVisual: VisualFix? = null
    private var jumpUntil = Double.NEGATIVE_INFINITY
    private var badInnovations = 0
    private var latched = false
    private var okSince: Double? = null
    private var okNeededVisual = false
    private var reinit = false

    fun onStatus(e: GnssStatusEvent) {
        val prev = lastStatus
        if (prev != null && e.tMs - prev.tMs > config.statusStaleMs) uniformFlags.clear()
        lastStatus = e
        val std = e.cn0Std
        uniformFlags.addLast(std != null && e.used >= config.uniformMinUsed && std < config.uniformCn0StdDb)
        while (uniformFlags.size > config.uniformWindow) uniformFlags.removeFirst()
    }

    fun onVisualFix(tMs: Double, lat: Double, lon: Double, sigmaM: Double) {
        lastVisual = VisualFix(tMs, lat, lon, sigmaM)
    }

    fun onAgc(e: AgcEvent) {
        lastAgc = e
        if (!e.agcDb.isFinite()) return
        val prevT = lastAgcT
        lastAgcT = e.tMs
        val b = agcBaseline
        if (b == null) {
            if (reasonsWithoutAgc(e.tMs).isEmpty()) agcBaseline = e.agcDb.toDouble()
            return
        }
        if (e.agcDb < b - config.agcDropDb) {
            val since = agcDropSince ?: e.tMs.also { agcDropSince = it }
            if (e.tMs - since >= config.agcRelearnMs && statusReasons(e.tMs).isEmpty()) {
                agcBaseline = e.agcDb.toDouble(); agcDropSince = null
            }
            return
        }
        agcDropSince = null
        if (reasonsWithoutAgc(e.tMs).isEmpty() && e.agcDb >= b - config.agcFreezeDb) {
            val dt = if (prevT == null) 0.0 else max(0.0, e.tMs - prevT)
            val alpha = 1 - exp(-dt / config.agcTauMs)
            agcBaseline = b + alpha * (e.agcDb - b)
        }
    }

    fun onFix(e: LocEvent, innovationD2: Double?, distToFilterM: Double?, filterSigmaM: Double?) {
        val prev = lastFix
        if (prev != null && e.tMs <= prev.tMs) return
        if (prev != null) {
            val dt = (e.tMs - prev.tMs) / 1000.0
            val dist = Geo.haversineM(prev.lat, prev.lon, e.lat, e.lon)
            if (dist > config.maxJumpMps * dt + config.jumpAccFactor * (prev.accM + e.accM)) {
                jumpUntil = e.tMs + config.jumpHoldMs
            }
        }
        lastFix = e
        if (!latched) {
            if (innovationD2 == null) { badInnovations = 0; return }
            val bad = innovationD2 > config.innovationGate &&
                distToFilterM != null && distToFilterM > config.relockMaxDistM
            badInnovations = if (bad) badInnovations + 1 else 0
            if (badInnovations >= config.innovationCount) {
                latched = true; okSince = null; okNeededVisual = false; reinit = false
            }
            return
        }
        val v = lastVisual
        val visualOk = v != null && abs(e.tMs - v.tMs) <= config.visualAgreeMs &&
            Geo.haversineM(e.lat, e.lon, v.lat, v.lon) <= max(config.visualAgreeM, 2 * v.sigmaM)
        val filterOk = filterSigmaM != null && filterSigmaM <= config.relockMaxSigmaM &&
            distToFilterM != null && distToFilterM <= config.relockMaxDistM
        if (!visualOk && !filterOk) { okSince = null; okNeededVisual = false; return }
        val since = okSince ?: e.tMs.also { okSince = it; okNeededVisual = false }
        if (!filterOk) okNeededVisual = true
        if (e.tMs - since >= config.relockOkMs) {
            latched = false; okSince = null; badInnovations = 0
            if (okNeededVisual) reinit = true
            okNeededVisual = false
        }
    }

    /** true один раз после снятия фиксации по визуальному согласию: переинициализировать фильтр по GNSS. */
    fun consumeReinit(): Boolean = reinit.also { reinit = false }

    fun assess(tMs: Double): Assessment {
        val r = reasonsWithoutAgc(tMs).toMutableSet()
        if (agcDropped(tMs)) r += GnssReason.AGC_DROP
        val health = when {
            r.any { it in UNTRUSTED_REASONS } -> GnssHealth.UNTRUSTED
            r.isNotEmpty() -> GnssHealth.DEGRADED
            else -> GnssHealth.GOOD
        }
        return Assessment(health, r)
    }

    private fun reasonsWithoutAgc(tMs: Double): Set<GnssReason> {
        val r = mutableSetOf<GnssReason>()
        val fix = lastFix
        val gap = if (fix == null) Double.POSITIVE_INFINITY else tMs - fix.tMs
        when {
            gap > config.noFixMs -> r += GnssReason.NO_FIX
            gap > config.fixGapMs -> r += GnssReason.FIX_GAP
            fix != null && fix.accM > config.maxAccM -> r += GnssReason.POOR_ACCURACY
        }
        r += statusReasons(tMs)
        if (tMs < jumpUntil) r += GnssReason.JUMP
        if (latched) r += GnssReason.INNOVATION
        return r
    }

    private fun statusReasons(tMs: Double): Set<GnssReason> {
        val s = lastStatus ?: return emptySet()
        if (tMs - s.tMs > config.statusStaleMs) return emptySet()
        val r = mutableSetOf<GnssReason>()
        if (s.used < config.minUsed) r += GnssReason.FEW_SATS
        val cn0 = s.cn0Mean
        if (cn0 != null && cn0 < config.minCn0) r += GnssReason.LOW_CN0
        if (uniformFlags.count { it } >= config.uniformMinCount) r += GnssReason.UNIFORM_CN0
        return r
    }

    private fun agcDropped(tMs: Double): Boolean {
        val a = lastAgc ?: return false
        val b = agcBaseline ?: return false
        if (tMs - a.tMs > config.statusStaleMs) return false
        return a.agcDb < b - config.agcDropDb
    }
}
```

- [ ] **Step 3: Тесты зелёные**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 108 тестов (после ревью добавлены тесты на метрическое условие фиксации, правила повторного захвата и пороги около границ). Если тест на пороге падает, сначала проследить его вручную. Пороги `MonitorConfig` не менять, а сообщить NEEDS_CONTEXT с посчитанными значениями.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): GNSS health monitor for degradation, jamming and spoofing (FR-14)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Режимы навигации с гистерезисом

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/ModeManager.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/ModeManagerTest.kt`

**Interfaces:**
- Consumes: `GnssHealth`.
- Produces:
  - `enum class NavMode { GNSS, FUSED, VISUAL, DEAD_RECKONING }` — от лучшего к худшему.
  - `data class ModeConfig(worseDelayMs = 2_000.0, betterDelayMs = 10_000.0, visualFreshMs = 10_000.0)`
  - `class ModeManager(config)` с полем `mode: NavMode` (начально `GNSS`) и методом `update(tMs: Double, health: GnssHealth, lastVisualOkMs: Double?): NavMode`.

Целевой режим:
- `GOOD` → `GNSS`;
- `DEGRADED` → `FUSED`;
- `UNTRUSTED` → `VISUAL`, если последняя принятая визуальная фиксация не старше `visualFreshMs`, иначе `DEAD_RECKONING`.

Переход выполняется, когда цель держится непрерывно:
- ухудшение — `worseDelayMs`;
- улучшение — `betterDelayMs`;
- исключение: из `DEAD_RECKONING` в `VISUAL` переход сразу, как только визуальная фиксация снова свежая.

- [ ] **Step 1: Падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/ModeManagerTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ModeManagerTest {
    @Test fun degradesAfterTwoSecondsAndRecoversAfterTen() {
        val m = ModeManager()
        assertEquals(NavMode.GNSS, m.update(0.0, GnssHealth.GOOD, null))
        assertEquals(NavMode.GNSS, m.update(1_000.0, GnssHealth.DEGRADED, null))
        assertEquals(NavMode.GNSS, m.update(2_900.0, GnssHealth.DEGRADED, null))
        assertEquals(NavMode.FUSED, m.update(3_000.0, GnssHealth.DEGRADED, null))
        assertEquals(NavMode.FUSED, m.update(4_000.0, GnssHealth.GOOD, null))
        assertEquals(NavMode.FUSED, m.update(13_900.0, GnssHealth.GOOD, null))
        assertEquals(NavMode.GNSS, m.update(14_000.0, GnssHealth.GOOD, null))
    }

    @Test fun flickerDoesNotSwitch() {
        val m = ModeManager()
        m.update(0.0, GnssHealth.GOOD, null)
        m.update(1_000.0, GnssHealth.UNTRUSTED, null)
        m.update(2_500.0, GnssHealth.GOOD, null)   // цель сбросилась
        assertEquals(NavMode.GNSS, m.update(3_500.0, GnssHealth.UNTRUSTED, null))
    }

    @Test fun untrustedChoosesVisualOrDeadReckoningByVisualFreshness() {
        val m = ModeManager()
        m.update(0.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 0.0)
        assertEquals(NavMode.VISUAL, m.update(2_000.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 1_500.0))
        m.update(12_000.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 1_500.0)  // фиксация устарела (>10 с)
        assertEquals(NavMode.DEAD_RECKONING, m.update(14_000.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 1_500.0))
        assertEquals(NavMode.VISUAL, m.update(14_500.0, GnssHealth.UNTRUSTED, lastVisualOkMs = 14_400.0)) // сразу
    }

    @Test fun visualToGnssNeedsTenSecondsOfGoodSignal() {
        val m = ModeManager()
        m.update(0.0, GnssHealth.UNTRUSTED, 0.0); m.update(2_000.0, GnssHealth.UNTRUSTED, 1_900.0)
        assertEquals(NavMode.VISUAL, m.update(3_000.0, GnssHealth.GOOD, 2_900.0))
        assertEquals(NavMode.GNSS, m.update(13_000.0, GnssHealth.GOOD, 12_900.0))
    }
}
```

Run: `./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: ModeManager`).

- [ ] **Step 2: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/ModeManager.kt`:
```kotlin
package io.visnav.core

/** Режимы FR-15 в порядке ухудшения. */
enum class NavMode { GNSS, FUSED, VISUAL, DEAD_RECKONING }

data class ModeConfig(
    val worseDelayMs: Double = 2_000.0,
    val betterDelayMs: Double = 10_000.0,
    val visualFreshMs: Double = 10_000.0,
)

/** Переключение режимов с гистерезисом: ухудшение быстро, улучшение осторожно. */
class ModeManager(private val config: ModeConfig = ModeConfig()) {
    var mode: NavMode = NavMode.GNSS
        private set
    private var pending: NavMode? = null
    private var pendingSince = 0.0

    fun update(tMs: Double, health: GnssHealth, lastVisualOkMs: Double?): NavMode {
        val visualFresh = lastVisualOkMs != null && tMs - lastVisualOkMs <= config.visualFreshMs
        val target = when (health) {
            GnssHealth.GOOD -> NavMode.GNSS
            GnssHealth.DEGRADED -> NavMode.FUSED
            GnssHealth.UNTRUSTED -> if (visualFresh) NavMode.VISUAL else NavMode.DEAD_RECKONING
        }
        if (target == mode) { pending = null; return mode }
        if (pending != target) { pending = target; pendingSince = tMs }
        val delay = when {
            mode == NavMode.DEAD_RECKONING && target == NavMode.VISUAL -> 0.0
            target.ordinal > mode.ordinal -> config.worseDelayMs
            else -> config.betterDelayMs
        }
        if (tMs - pendingSince >= delay) { mode = target; pending = null }
        return mode
    }
}
```

- [ ] **Step 3: Тесты зелёные**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 102 теста.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): navigation mode manager with hysteresis (FR-15)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Общий `Localizer` в `:core`, `Replayer` как тонкая обёртка

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/Localizer.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/TrajectoryFormat.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Replayer.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/TrajectoryWriter.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/LocalizerTest.kt`, существующие тесты `:replay` должны остаться зелёными

**Interfaces:**
- Consumes: `Ekf2d`, `Enu`, `YawRate`, `StationaryDetector`, `GeoIndex`, `RefPack`, `GnssMonitor`, `ModeManager`, события датчиков.
- Produces:
  - `data class LocalizerConfig(visual = true, acceptSim = 0.5f, minRadiusM = 100.0, maxRadiusM = 3000.0, k = 5, filter = FilterConfig(), monitor = true, monitorConfig = MonitorConfig(), modeConfig = ModeConfig(), fusedSigmaScale = 3.0)`.
  - `data class LocalizerOutput(tMs: Long, lat, lon, sigmaM, visSim: Float?, visAccepted: Boolean?, visState: String, stationary: Boolean, mode: NavMode, health: GnssHealth, reasons: Set<GnssReason>)`.
  - `class Localizer(pack, config)` с полями `initialized` и `mode` и методами `onSensor(e: SensorEvent)` и `onFrame(tMs: Long, desc: FloatArray?): LocalizerOutput?` (null до инициализации фильтра).
  - `object TrajectoryFormat` с методами:
    - `row(o: LocalizerOutput, inOutage: Boolean, injected: String?): String` — поля M2a в прежнем порядке, затем `,"mode":"gnss","health":"good","reasons":[...],"injected":null|"…"`; значения в нижнем регистре, нечисловые числа как `null`;
    - `modeName(m: NavMode): String` — `gnss`, `fused`, `visual`, `dead_reckoning`.
  - `TrajPoint` в `:replay` получает поля `mode`, `health`, `reasons`, `injected` (по умолчанию `injected = null`). `Replayer.run(session, outages)` сохраняет прежнюю сигнатуру.

Алгоритм `Localizer` — это алгоритм `Replayer` из M2a (гироскоп → предсказание; акселерометр → вертикаль, детектор стоянки и нулевая скорость; кадр → поиск в окне), с такими изменениями:
- **`LocEvent` после инициализации:**
  1. `predictTo(t)`.
  2. Считаются `en` и `posSigma`.
  3. Если монитор включён, вызывается `monitor.onFix(ev, ekf.positionD2(en[0], en[1], posSigma), hypot(en[0] − x[0], en[1] − x[1]), ekf.posSigma())`, иначе `onFix(ev, null, null, null)`.
  4. Если `monitor.consumeReinit()` — фильтр переинициализируется по фиксу (позиция; курс и скорость из фикса, если они есть, иначе текущие), и на этом обработка события заканчивается.
  5. Обновляется режим (см. ниже).
  6. Затем по режиму: `GNSS` — обновления с σ как в M2a; `FUSED` — все три σ × `fusedSigmaScale`; `VISUAL` и `DEAD_RECKONING` — без обновлений.
  7. Без монитора режим всегда `GNSS` (поведение M2a).
- **`GnssStatusEvent` и `AgcEvent`** передаются в монитор.
- **Кадр:** как в M2a, плюс при принятой фиксации — `lastVisualOk = t` и `monitor.onVisualFix(t, lat, lon, σ_vis)` (координаты эталона и σ из M2a: 8 или 15 м), затем обновление режима. Выход содержит режим, состояние и причины.
- **«Обновление режима»:** `assessment = monitor.assess(t)`; `modes.update(t, assessment.health, lastVisualOk)`.
- **Инициализация** — как в M2a, по первому `LocEvent` со скоростью ≥ 3 м/с и курсом; σ ограничиваются снизу (функция `sigma(...)` переезжает из `Replayer` в `Localizer`).

- [ ] **Step 1: Перенести код**

1. Создать `Localizer.kt`: перенести из `Replayer.run` цикл обработки событий в методы `onSensor`/`onFrame`, применив изменения выше. Поля `ekf`, `yaw`, `stationary`, `enu`, `lastT`, `lastOmega`, `lastZupt` становятся полями класса. Константы и `sigma(...)` тоже переносятся.
2. Создать `TrajectoryFormat.kt` с `row(...)`: перенести форматирование строки и `num(...)` из `TrajectoryWriter`, добавив новые поля. `TrajectoryWriter` вызывает `TrajectoryFormat.row`.
3. `Replayer.run`:
   - строит ленту событий как раньше;
   - `LocEvent` внутри пропадания отбрасывает;
   - остальные события датчиков передаёт в `localizer.onSensor`;
   - для кадра берёт дескриптор (`descriptors.indexOf`, при отсутствии `null`) и вызывает `localizer.onFrame`;
   - превращает `LocalizerOutput` в `TrajPoint` (`inOutage` как раньше, `injected = null`);
   - `ReplayConfig` получает поле `monitor: Boolean = true` и передаёт в `Localizer` поля `LocalizerConfig`.

- [ ] **Step 2: Тесты `Localizer`**

`android/core/src/test/kotlin/io/visnav/core/LocalizerTest.kt`. Три теста на синтетической прямой: 10 м/с на восток, IMU 100 Гц с вибрацией акселерометра (как в `ReplayerTest`), GNSS и статус 1 Гц (`used = 14`, `cn0 = 35`, `cn0Std = 5`), кадры 2 Гц с дескриптором-«отпечатком» ближайшего эталона каждые 10 м. Эталоны и дескрипторы строятся так же, как в `android/replay/src/test/kotlin/io/visnav/replay/ReplayerTest.kt` (скопировать вспомогательные функции в тест, они короткие):
- `cleanDriveStaysInGnssMode`: после инициализации все выходы в режиме `GNSS`, состояние `GOOD`.
- `gnssDropSwitchesToVisualWithinFiveSeconds`: с 30-й по 60-ю секунду `LocEvent` не подаются. Режим становится `VISUAL` не позже чем через 5 с после 30-й секунды; ошибка позиции в окне ≤ 15 м (P95); через ≤ 10 с после 60-й секунды режим снова `GNSS`.
- `monitorOffAlwaysUsesGnss`: при `monitor = false` режим всегда `GNSS`.

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test :replay:test`
Expected: `BUILD SUCCESSFUL`: `:core` — 105 тестов, `:replay` — 17 прежних (строковые проверки формата траектории обновлены под новые поля).

Если какой-то прежний тест `:replay` меняет результат из-за гистерезиса (например, GNSS возвращается в фильтр через 10 с после конца пропадания), пороги не ослаблять. Нужно объяснить в отчёте, почему изменилось поведение, и сообщить NEEDS_CONTEXT, если тест проверял именно этот момент.

- [ ] **Step 3: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src android/replay/src
git commit -m "refactor: shared Localizer in core with GNSS monitor and modes; replay uses it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Подмена и глушение в replay

**Files:**
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Replayer.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/TrajectoryWriter.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Main.kt`
- Test: `android/replay/src/test/kotlin/io/visnav/replay/InjectionTest.kt`, `MainTest.kt` (дополнить)

**Interfaces:**
- Produces:
  - `data class Jam(startMs: Long, endMs: Long)` и `data class Spoof(startMs: Long, endMs: Long, eastM: Double, northM: Double)` с методом `contains(tMs: Double)` (полуоткрытый интервал, как у `Outage`).
  - `Replayer.run(session, outages, jams: List<Jam> = emptyList(), spoofs: List<Spoof> = emptyList())`.
  - Заголовок траектории: `,"monitor":…,"jams":[[s,e],…],"spoofs":[[s,e,east,north],…]` после `"outages"`.
  - CLI: `--jam START_S:LEN_S`, `--spoof START_S:LEN_S:EAST_M:NORTH_M` (повторяемые) и `--no-monitor`.

Подмешивание (до передачи в `Localizer`):
- **Пропадание** (как было): `LocEvent` отбрасывается.
- **Глушение:**
  - `LocEvent` отбрасывается;
  - `GnssStatusEvent` заменяется копией с `used = 0`, `cn0Mean`, `cn0Std` и `cn0Max` равными `null`, все счётчики по системам равны 0;
  - `AgcEvent` заменяется копией с `agcDb − 15`.
- **Подмена:** `LocEvent` сдвигается на `(eastM, northM)`: `lat += northM / (π/180 · R)`, `lon += eastM / (π/180 · R · cos lat)`, где R — `Geo.EARTH_RADIUS_M`. Остальное без изменений.
- `TrajPoint.injected`: `"outage"`, `"jam"`, `"spoof"` или `null` по времени кадра. При пересечении окон приоритет такой: подмена > глушение > пропадание.

- [ ] **Step 1: Падающие тесты**

`InjectionTest.kt` — на синтетической сессии из Task 4 (построитель сессии со статусом GNSS 1 Гц вынести в общий тестовый helper в `:replay`):
- `spoofIsDetectedAndIgnored`: подмена 200 м на север с 40-й по 70-ю секунду. Первая точка `health == UNTRUSTED` не позже 5 с после 40-й секунды. P95 ошибки относительно истины внутри окна ≤ 15 м. При `monitor = false` ошибка в конце окна > 100 м (то есть монитор действительно работает).
- `jamLeavesGnssModeQuickly`: глушение с 40-й по 70-ю секунду. Первая точка с режимом не `GNSS` — не позже 5 с после 40-й секунды. Причины включают `FEW_SATS` или `NO_FIX`.
- `cleanSessionHasNoFalseUntrusted`: без подмешивания доля точек `UNTRUSTED` после первых 10 с равна 0.
- `trajectoryHeaderAndRowsCarryInjection`: строка заголовка содержит `"monitor":true,"jams":[[…]],"spoofs":[[…]]`, у точки в окне подмены `"injected":"spoof"`.

В `MainTest.kt` — разбор `--jam 10:5` и `--spoof 10:5:0:200`; неверные формы (`--spoof 10:5:0`, отрицательная длина, NaN) завершаются кодом 2.

Run: `./gradlew :replay:test`
Expected: FAIL при компиляции (`Unresolved reference: Jam`).

- [ ] **Step 2: Реализовать** по описанию выше. Разбор аргументов делается как у существующего `parseOutage`: чистые функции с тестами, `exitProcess(2)` только в `main`.

- [ ] **Step 3: Тесты зелёные**

Run: `./gradlew :core:test :replay:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, `:replay` — не меньше 23 тестов. Если задержка или точность не укладываются в целевые значения, пороги не менять: подобрать `MonitorConfig` или `ModeConfig` в пределах разумного и описать это в отчёте, иначе сообщить NEEDS_CONTEXT с цифрами.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/replay/src
git commit -m "feat(replay): inject GNSS jamming and spoofing, compare with monitor off

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Оценка монитора (`vpr-m2 monitor-eval`)

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/monitoreval.py`
- Modify: `research/vpr_bench/src/vpr_bench/m2cli.py` (подкоманда `monitor-eval`)
- Modify: `research/vpr_bench/src/vpr_bench/replayeval.py` (`read_trajectory` читает новые поля: `mode`, `health`, `reasons`, `injected` — необязательные, по умолчанию `None` / `[]`)
- Test: `research/vpr_bench/tests/test_monitoreval.py`

**Interfaces:**
- Consumes: `replayeval.read_trajectory`, `TrajRow`; `fieldlog.read_log`, `fieldlog.gps_track`; `query.clean_track`, `query.pose_at`; `geo.interpolate_track`, `geo.haversine_m`.
- Produces:
  - `TrajRow` получает необязательные поля `mode: str | None = None`, `health: str | None = None`, `reasons: tuple[str, ...] = ()`, `injected: str | None = None` в конце.
  - `WindowResult(kind: str, start_ms: int, end_ms: int, latency_s: float | None, p95_err_m: float)`.
  - `MonitorResult(windows: list[WindowResult], false_untrusted_pct: float, non_gnss_clean_pct: float, real_spoof_recall_pct: float | None, n_real_spoof_s: int)`.
  - `evaluate_monitor(header, rows, log_frames, warmup_s=60.0, min_speed_mps=2.0) -> MonitorResult`.
  - `render_monitor_report(r) -> str`.
  - CLI: `vpr-m2 monitor-eval --traj FILE --log SESSION.jsonl --out REPORT.md` (с той же проверкой совпадения сессий, что у `replay-eval`).

Определения:
- **Задержка:**
  - окно подмены — время от начала окна до первой строки внутри окна с `health == "untrusted"`;
  - окно глушения — до первой строки с `mode != "gnss"`;
  - если такой строки нет — `None`.
- **Чистое время** — строки вне всех окон (пропадания, глушения, подмены), после `warmup_s` от начала сессии и в движении (≥ 2 м/с по `pose_at`).
  - `false_untrusted_pct` — доля чистых строк с `health == "untrusted"`;
  - `non_gnss_clean_pct` — доля чистых строк с `mode != "gnss"`.
- **`p95_err_m`** — P95 ошибки строк внутри окна относительно истинной позиции (интерполированный GPS после `clean_track`), `method="higher"`.
- **Реальная подмена:**
  - моменты GPS-точек журнала, которые `clean_track` удалил как подмену или оторванные (`gps_track` до очистки минус после), в целых секундах — это «метки подмены»;
  - `real_spoof_recall_pct` — доля меток, для которых ближайшая строка траектории в пределах ±1 с имеет `health == "untrusted"`;
  - если меток нет — `None`.
- **Вердикт:**
  - все окна подмены с задержкой ≤ 5 с;
  - все окна глушения с задержкой ≤ 5 с;
  - `false_untrusted_pct ≤ 1`;
  - P95 в окнах подмены ≤ 15 м;
  - полнота ≥ 90 %, если есть метки реальной подмены.
  - Каждый пункт помечается ✅/❌, а пункт без данных — «⚠️ нет данных».

- [ ] **Step 1: Падающие тесты**

`research/vpr_bench/tests/test_monitoreval.py` — синтетика по образцу `tests/test_replayeval.py`: GPS 1 Гц, езда на север 10 м/с, строки траектории 1 Гц, заголовок с `"spoofs"` и `"jams"`. Покрыть:
1. подмена обнаружена на 3-й секунде → задержка 3.0 и ✅;
2. подмена не обнаружена → задержка `None` и ❌;
3. глушение: режим `fused` со 2-й секунды → задержка 2.0;
4. 2 строки `untrusted` из 200 чистых → 1.0 % и ✅, 3 из 200 → ❌;
5. журнал, где 10 GPS-точек подряд уведены на 3 км (их удалит `clean_track`), и траектория с `untrusted` на 9 из них → полнота 90 %, ✅;
6. траектория в формате M2a без новых полей читается (`mode` равен `None`), а `monitor-eval` пишет «⚠️ нет данных» для пунктов про окна;
7. CLI при несовпадении сессий возвращает код 2.

- [ ] **Step 2: Реализовать `monitoreval.py`, подкоманду, чтение новых полей в `read_trajectory`**

- [ ] **Step 3: Тесты зелёные**

Run: `cd /Users/vvnovg/navigator/research/vpr_bench && uv run pytest -q`
Expected: не меньше 167 passed, 4 deselected.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench research/vpr_bench/tests
git commit -m "feat(vpr-bench): monitor-eval — detection latency, false alarms, spoof-zone recall

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Телефон — статистика спутников, AGC, фильтр в реальном времени, режим на экране

**Files:**
- Modify: `android/app/src/main/kotlin/io/visnav/app/GpsSource.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Screen.kt`

**Interfaces:**
- Consumes: `Localizer`, `LocalizerConfig`, `LocalizerOutput`, `TrajectoryFormat`, `NavMode`, `GnssStatusEvent` (с новыми полями), `AgcEvent`.
- Produces:
  - `GpsSource.onAgc: ((AgcEvent) -> Unit)?`.
  - Файл `session-<ms>-<mode>.fusion.jsonl`: заголовок `{"type":"fusion","monitor":true,"session_started_ms":…,"refpack_created_at":…}`, строки `TrajectoryFormat.row(out, false, null)`.
  - В `UiState` поля `navMode: NavMode?`, `sigmaM: Double?`, `gnssReasons: Set<GnssReason>`.

- [ ] **Step 1: Статистика спутников**

В `gnssCallback` для спутников с `usedInFix(i)` посчитать:
- средний C/N0, стандартное отклонение (по генеральной совокупности) и максимум;
- счётчики по `status.getConstellationType(i)`: `CONSTELLATION_GPS`, `CONSTELLATION_GLONASS`, `CONSTELLATION_GALILEO`, `CONSTELLATION_BEIDOU`.

Создавать `GnssStatusEvent` со всеми полями. Если спутников в решении нет, C/N0-поля равны `null`, счётчики — 0.

- [ ] **Step 2: AGC**

Зарегистрировать `GnssMeasurementsEvent.Callback`: при API ≥ 30 через `registerGnssMeasurementsCallback(gnssExecutor, cb)`, при API 29 через `registerGnssMeasurementsCallback(cb, Handler(Looper.getMainLooper()))`. В `onGnssMeasurementsReceived(event)` собрать уровни AGC:
- при API ≥ 33 — из `event.gnssAutomaticGainControls` (`levelDb`), если список не пуст;
- иначе — из `event.measurements` с `hasAutomaticGainControlLevelDb()` (`automaticGainControlLevelDb`).

Если значений нет, ничего не отправлять. Иначе `onAgc?.invoke(AgcEvent(System.currentTimeMillis().toDouble(), mean.toFloat(), count))`. Отписку добавить в `stop()`. Устаревшие API оборачиваются `@Suppress("DEPRECATION")`.

- [ ] **Step 3: `Localizer` в сессии**

В `M1Controller.start()` после создания журналов:
- создать `Localizer(b.pack, LocalizerConfig())` и объект-замок `fusionLock`;
- все события датчиков (`sensors` sink, `gps.onLoc`, `gps.onGnss`, `gps.onAgc`) идут и в журнал датчиков, и в `synchronized(fusionLock) { localizer.onSensor(e) }`;
- в `onDescriptor` после записи дескриптора: `val out = synchronized(fusionLock) { localizer.onFrame(t, d) }`; если `out != null`, строка пишется в `.fusion.jsonl` (writer закрывается вместе с остальными журналами по тем же путям) и обновляется состояние: `navMode`, `sigmaM`, `gnssReasons`;
- ошибка `Localizer` на кадре считается в `errors` и не останавливает запись. Сообщение в статусе появляется один раз, как у ошибок дескриптора.

Журнал M1 (`.jsonl`) и поиск `LocalizationPipeline` не меняются: критерий M1 считается по-прежнему.

- [ ] **Step 4: Экран**

В `M1Screen` при `navMode != null` показать строку режима с цветом (Global Constraints), `±N м` (округлённое `sigmaM`) и причины, если они есть, в читаемом виде:
- `NO_FIX` → «нет фикса»;
- `FEW_SATS` → «мало спутников»;
- `LOW_CN0` → «слабый сигнал»;
- `AGC_DROP` → «глушение (AGC)»;
- `POOR_ACCURACY` → «низкая точность»;
- `JUMP` → «скачок»;
- `INNOVATION` → «расхождение с фильтром»;
- `UNIFORM_CN0` → «признак подмены».

Цвета: `Color(0xFF2E7D32)` зелёный, `Color(0xFF1565C0)` синий, `Color(0xFFEF6C00)` оранжевый.

- [ ] **Step 5: Сборка**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test :replay:test :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, 0 ошибок lint. Отличия API `GnssMeasurementsEvent`/`GnssAutomaticGainControl` на `compileSdk 35` от описанного — поправить по документации и описать в отчёте.

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/app/src
git commit -m "feat(app): on-device filter with GNSS monitor and modes, satellite stats and AGC recording

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Протокол M2c

**Files:**
- Create: `docs/research/m2c-monitor.md`

- [ ] **Step 1: Документ**

`docs/research/m2c-monitor.md`:
````markdown
# M2c: монитор GNSS и режимы навигации

## Что пишет приложение
Помимо файлов M2a (`.jsonl`, `.sensors.jsonl`, `.desc`) — `.fusion.jsonl`: оценка фильтра на телефоне
по каждому кадру (позиция, радиус доверия, режим, состояние GNSS, причины). В `.sensors.jsonl` у событий
`gnss` появились разброс и максимум C/N0 и число спутников по системам, добавились события `agc`
(усиление приёмника; есть не на всех телефонах). На экране — режим с цветом и «±N м».

## 1. Подмешивание на чистых поездках (replay)
Поездки M2a вне зоны подмены. Набор окон для сессии ≥ 25 мин:
`--spoof 300:60:0:300 --spoof 900:60:500:0 --jam 600:60`.

```bash
cd /Users/vvnovg/navigator/android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
S=/Users/vvnovg/navigator/research/vpr_bench/data/m1/logs/session-<ms>-gps
R=/Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle
I="--spoof 300:60:0:300 --spoof 900:60:500:0 --jam 600:60"
./gradlew -q :replay:run --args="--session $S --refpack $R --out $S.m2c.jsonl $I"
./gradlew -q :replay:run --args="--session $S --refpack $R --out $S.m2c-off.jsonl $I --no-monitor"
cd /Users/vvnovg/navigator/research/vpr_bench
uv run vpr-m2 monitor-eval --traj $S.m2c.jsonl --log $S.jsonl --out data/m2c/monitor.md
uv run vpr-m2 monitor-eval --traj $S.m2c-off.jsonl --log $S.jsonl --out data/m2c/monitor-off.md
```

## 2. Реальная зона подмены
Одна поездка по маршруту, который проходит по краю зоны подмены в центре (Бульварное кольцо, набережные
у Кремля) и возвращается за Садовое кольцо. Обычная езда по правилам; телефон пишет всё как обычно.
`monitor-eval` сам размечает подмену по журналу GPS (точки, которые удаляет фильтр качества трека M0) и
считает, какую долю этих секунд монитор распознал. Истинной позиции внутри зоны нет — точность там не
оценивается.

```bash
uv run vpr-m2 monitor-eval --traj <сессия>.fusion.jsonl --log <сессия>.jsonl --out data/m2c/real-spoof.md
```
(`.fusion.jsonl` — вывод фильтра на телефоне в реальном времени; можно сравнить с replay той же сессии.)

## Итоги (заполнить)
- Сессии и телефон: …
- Таблицы из monitor.md, monitor-off.md, real-spoof.md: …
- Параметры монитора, если меняли MonitorConfig/ModeConfig: …
- Есть ли AGC на телефоне: …

## Критерий M2c (предложение плана; владелец может скорректировать)
- [ ] Подмена ≥ 100 м обнаруживается ≤ 5 с — факт: …
- [ ] Глушение: выход из режима GNSS ≤ 5 с — факт: …
- [ ] Ложное UNTRUSTED ≤ 1 % чистого времени движения — факт: …
- [ ] P95 ошибки в окнах подмены ≤ 15 м (с визуальными фиксациями) — факт: …
- [ ] Реальная зона подмены: полнота ≥ 90 % — факт: …
````

- [ ] **Step 2: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m2c-monitor.md
git commit -m "docs: M2c monitor evaluation protocol

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
