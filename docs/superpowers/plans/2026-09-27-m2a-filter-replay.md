# M2a: запись датчиков, фильтр и replay-стенд — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Телефон записывает в поездке всё, что нужно для воспроизведения: дескриптор каждого кадра, гироскоп и акселерометр (100 Гц), GNSS со скоростью и курсом, статус спутников. На ПК replay-стенд прогоняет эти записи через фильтр с искусственными пропаданиями GPS и считает NFR-1 и NFR-5.

**Architecture:**
- **Фильтр в `:core`.** Расширенный фильтр Калмана `Ekf2d` в локальной плоской системе «восток–север» с состоянием `[e, n, ψ, v, b_g]` (позиция, курс по часовой от севера, скорость, дрейф гироскопа).
- **Предсказание** — по скорости поворота курса. Её даёт гироскоп, спроецированный на вертикаль (вертикаль берётся из сглаженного акселерометра), поэтому ориентация телефона в держателе не важна.
- **Обновления:** позиция GNSS, скорость и курс GNSS, визуальная фиксация (с χ²-отсевом выбросов), нулевая скорость на стоянке.
- **Скорость во время пропадания GPS** держится моделью постоянной скорости и поправляется визуальными фиксациями и остановками. Интегрирование продольного ускорения требует калибровки установки телефона и отложено на этап OBD-II (M4).
- **Replay** — отдельный JVM-модуль `:replay` на том же коде `:core`. Он повторяет поиск по записанным дескрипторам в окне вокруг оценки фильтра, поэтому результат не зависит от окна, которое было на телефоне. Метрики считает Python (`vpr-m2 replay-eval`) против отфильтрованного GPS, записанного в той же поездке.

**Tech Stack:** Kotlin 2.1 (JVM 17, kotlinx.serialization), Android (SensorManager, LocationManager, GnssStatus), Python 3.11+ (numpy), JUnit 4, pytest.

## Место M2a в этапе M2

M2 по спецификации (раздел 9): ESKF + IMU + map matching + GNSS-монитор + replay-тесты. Критерий: NFR-1, NFR-3, NFR-5 на replay. Этап разбит на три плана:
- **M2a (этот план):** запись, фильтр, replay, NFR-1 и NFR-5.
- **M2b:** привязка к дорожному графу OSM (HMM map matching, particle filter для неоднозначных мест), NFR-3 (≥ 98 % времени на правильной дороге). Пишется после первых записей с IMU.
- **M2c:** монитор GNSS: детектор глушения и подмены по статусу спутников, C/N0, скачкам и расхождению с фильтром, а также автоматическое переключение режимов `GNSS → FUSED → VISUAL → DEAD_RECKONING` (FR-14, FR-15).

## Global Constraints

- **Критерии M2 на replay** (SPEC §5):
  - **NFR-1:** в режиме `VISUAL` (город, день) P50 ≤ 5 м, P95 ≤ 15 м;
  - **NFR-5:** дрейф в `DEAD_RECKONING` без визуальных фиксаций ≤ 3 % пройденного пути.
  - Считаются по кадрам в движении (≥ 2 м/с), истинная позиция — GPS после `clean_track` и проверки разрывов (как в M0 и M1).
  - Для NFR-5 учитываются только пропадания, за которые машина проехала ≥ 200 м.
- **Лицензии.** Новых зависимостей нет: только стандартная библиотека, kotlinx.serialization (Apache 2.0), numpy.
- **FR-20.** Изображения не сохраняются. Журнал содержит только числа, в том числе дескрипторы. Дескрипторы остаются на устройстве, пока их не заберут через `adb`.
- **Соглашения о координатах:**
  - ENU: `e` — восток, `n` — север, в метрах, относительно опорной точки.
  - Курс `ψ` — радианы от севера по часовой стрелке, `e' = v·sin ψ`, `n' = v·cos ψ`.
  - Скорость поворота `ω` — рад/с по часовой.
  - Радиус Земли 6 371 000 м, как в `Geo`.
- **Время.** Все метки — настенные часы телефона в миллисекундах, как у кадров и GPS после Task C1 M1. Метки датчиков переводятся из монотонных часов (`SensorEvent.timestamp`, отсчёт `elapsedRealtimeNanos`) через смещение, зафиксированное в начале сессии.
- **Файлы сессии** — общий префикс `logs/session-<startedMs>-<mode>`:
  - `.jsonl` — журнал кадров M1; формат не меняется, `field-eval` продолжает работать;
  - `.sensors.jsonl` — события датчиков;
  - `.desc` — дескрипторы кадров.
- **Контракт `.desc` v1** (little-endian):
  - заголовок 12 байт: `b"VNDS"`, `u16 version=1`, `u16 dtype=1` (float16), `u32 dim`;
  - затем записи: `i64 t_ms` и `f16[dim]`.
- **Контракт `.sensors.jsonl` v1.** Первая строка — `{"v":1,"type":"sensors","started_ms":<long>}`. Далее одно событие на строку, `t` — настенное время в мс (double):
  - гироскоп (рад/с): `{"t":…,"k":"g","x":…,"y":…,"z":…}`;
  - акселерометр (м/с²): `{"t":…,"k":"a","x":…,"y":…,"z":…}`;
  - GNSS: `{"t":…,"k":"loc","lat":…,"lon":…,"acc":…,"spd":…|null,"spd_acc":…|null,"brg":…|null,"brg_acc":…|null}` (скорость в м/с, курс в градусах);
  - статус спутников: `{"t":…,"k":"gnss","sats":<int>,"used":<int>,"cn0":…|null}`.
- **Android:** `minSdk = 29`; без Google Play Services.
- **Команды Gradle** — из `/Users/vvnovg/navigator/android` с `JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"`.
- **Команды Python** — из `/Users/vvnovg/navigator/research/vpr_bench` через `uv run`.
- **Коммиты** заканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Сейчас `:core` — 33 теста, Python — 143 passed / 4 deselected.

---

## Структура файлов

```
android/
├── settings.gradle.kts                          Task 7: + ":replay"
├── core/src/main/kotlin/io/visnav/core/
│   ├── Half.kt                 Task 1  (+ fromFloat)
│   ├── DescriptorLog.kt        Task 1  DescriptorLogWriter, DescriptorLog.read
│   ├── SensorLog.kt            Task 2  SensorEvent (Gyro/Accel/Loc/GnssStatus), SensorLogFormat, SensorLogger
│   ├── LocalizationPipeline.kt Task 3  (+ onDescriptor)
│   ├── Enu.kt  Ekf2d.kt        Task 4
│   └── Motion.kt               Task 5  YawRate, StationaryDetector
├── core/src/test/kotlin/io/visnav/core/
│   ├── HalfEncodeTest.kt DescriptorLogTest.kt SensorLogTest.kt   Tasks 1–2
│   ├── EnuTest.kt Ekf2dTest.kt MotionTest.kt                     Tasks 4–5
│   └── DriveSim.kt FilterIntegrationTest.kt                      Task 6
├── app/src/main/kotlin/io/visnav/app/
│   ├── SensorRecorder.kt       Task 3
│   ├── GpsSource.kt            Task 3  (+ loc/gnss события)
│   └── M1Controller.kt         Task 3  (запись .sensors.jsonl и .desc)
└── replay/                                      Task 7
    ├── build.gradle.kts
    └── src/{main,test}/kotlin/io/visnav/replay/  SessionData.kt Replayer.kt TrajectoryWriter.kt Main.kt + тесты
research/vpr_bench/
├── pyproject.toml                               Task 8: + vpr-m2
├── src/vpr_bench/replayeval.py  m2cli.py        Task 8
└── tests/test_replayeval.py                     Task 8
docs/research/m2a-replay.md                      Task 9
docs/SPEC.md                                     Task 9 (roadmap: M2a/M2b/M2c)
```

---

### Task 1: Кодирование float16 и журнал дескрипторов

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/Half.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/DescriptorLog.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/HalfEncodeTest.kt`, `android/core/src/test/kotlin/io/visnav/core/DescriptorLogTest.kt`

**Interfaces:**
- Consumes: `Half.toFloat`, `Half.LUT`.
- Produces:
  - `Half.fromFloat(f: Float): Short` — округление к ближайшему чётному; переполнение даёт ±inf, NaN остаётся NaN.
  - `class DescriptorLogWriter(file: File, val dim: Int) : Closeable` с методами `write(tMs: Long, desc: FloatArray)` и `close()`. Сброс на диск — каждые 20 записей.
  - `class DescriptorLog(val dim: Int, val times: LongArray, val descriptors: ShortArray)` с методами `descriptor(i: Int): FloatArray` и `indexOf(tMs: Long): Int` (−1, если записи нет) и функцией `DescriptorLog.read(file: File): DescriptorLog`. Обрезанная последняя запись пропускается.

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/HalfEncodeTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HalfEncodeTest {
    private fun bits(f: Float) = Half.fromFloat(f).toInt() and 0xFFFF

    @Test fun knownValues() {
        assertEquals(0x3C00, bits(1.0f))
        assertEquals(0xC000, bits(-2.0f))
        assertEquals(0x38CD, bits(0.6f))   // как numpy.float16(0.6)
        assertEquals(0x3A66, bits(0.8f))
        assertEquals(0x0001, bits(5.9604645e-8f)) // наименьшее субнормальное
        assertEquals(0x0000, bits(0.0f))
        assertEquals(0x8000, bits(-0.0f))
        assertEquals(0x7BFF, bits(65504f))
    }

    @Test fun overflowUnderflowAndSpecials() {
        assertEquals(0x7C00, bits(65520f))            // ровно посередине → к чётному → inf
        assertEquals(0x7C00, bits(1e10f))
        assertEquals(0xFC00, bits(Float.NEGATIVE_INFINITY))
        assertEquals(0x0000, bits(1e-10f))
        assertTrue(Half.toFloat(Half.fromFloat(Float.NaN)).isNaN())
    }

    @Test fun roundTripsEveryNonNanHalf() {
        for (h in 0 until 65536) {
            val f = Half.toFloat(h.toShort())
            if (f.isNaN()) continue
            assertEquals(h, bits(f), "half 0x${h.toString(16)}")
        }
    }
}
```

`android/core/src/test/kotlin/io/visnav/core/DescriptorLogTest.kt`:
```kotlin
package io.visnav.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class DescriptorLogTest {
    @Test fun writesContractLayoutAndReadsBack() {
        val f = File.createTempFile("s", ".desc")
        DescriptorLogWriter(f, dim = 3).use {
            it.write(1000L, floatArrayOf(1f, 0f, 0.5f))
            it.write(1500L, floatArrayOf(0f, -2f, 0.6f))
        }
        val raw = f.readBytes()
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("VNDS", String(raw, 0, 4))
        b.position(4)
        assertEquals(1, b.short.toInt()); assertEquals(1, b.short.toInt()); assertEquals(3, b.int)
        assertEquals(12 + 2 * (8 + 3 * 2), raw.size)
        assertEquals(1000L, b.long)

        val log = DescriptorLog.read(f)
        assertContentEquals(longArrayOf(1000L, 1500L), log.times)
        assertContentEquals(floatArrayOf(0f, -2f, 0.60009765625f), log.descriptor(1))
        assertEquals(1, log.indexOf(1500L))
        assertEquals(-1, log.indexOf(1234L))
    }

    @Test fun truncatedLastRecordIsSkipped() {
        val f = File.createTempFile("s", ".desc")
        DescriptorLogWriter(f, dim = 2).use {
            it.write(1L, floatArrayOf(1f, 1f)); it.write(2L, floatArrayOf(2f, 2f))
        }
        f.writeBytes(f.readBytes().copyOf(12 + 12 + 5))
        assertEquals(1, DescriptorLog.read(f).times.size)
    }

    @Test fun rejectsWrongDimension() {
        val f = File.createTempFile("s", ".desc")
        DescriptorLogWriter(f, dim = 2).use {
            kotlin.test.assertFailsWith<IllegalArgumentException> { it.write(1L, floatArrayOf(1f)) }
        }
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: fromFloat`, `DescriptorLogWriter`).

- [ ] **Step 3: Реализовать**

В `android/core/src/main/kotlin/io/visnav/core/Half.kt` внутри `object Half` добавить:
```kotlin
    /** float → IEEE 754 binary16, округление к ближайшему чётному (как numpy.float16). */
    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        var mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - e
            var half = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        var half = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++ // перенос в экспоненту корректен
        return (sign or half).toShort()
    }
```

`android/core/src/main/kotlin/io/visnav/core/DescriptorLog.kt`:
```kotlin
package io.visnav.core

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** .desc v1: "VNDS", u16 version=1, u16 dtype=1 (f16), u32 dim; затем записи i64 t_ms + f16[dim]. */
class DescriptorLogWriter(file: File, val dim: Int) : Closeable {
    private val out = file.outputStream().buffered()
    private val record = ByteBuffer.allocate(8 + 2 * dim).order(ByteOrder.LITTLE_ENDIAN)
    private var count = 0

    init {
        require(dim > 0) { "dim must be > 0" }
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.put("VNDS".toByteArray()).putShort(1).putShort(1).putInt(dim)
        out.write(header.array())
        out.flush()
    }

    fun write(tMs: Long, desc: FloatArray) {
        require(desc.size == dim) { "descriptor size ${desc.size} != $dim" }
        record.clear()
        record.putLong(tMs)
        for (v in desc) record.putShort(Half.fromFloat(v))
        out.write(record.array())
        if (++count % 20 == 0) out.flush()
    }

    override fun close() { out.flush(); out.close() }
}

class DescriptorLog(val dim: Int, val times: LongArray, val descriptors: ShortArray) {
    private val byTime: Map<Long, Int> = times.withIndex().associate { (i, t) -> t to i }

    fun indexOf(tMs: Long): Int = byTime[tMs] ?: -1

    fun descriptor(i: Int): FloatArray = FloatArray(dim) { Half.LUT[descriptors[i * dim + it].toInt() and 0xFFFF] }

    companion object {
        fun read(file: File): DescriptorLog {
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(b.remaining() >= 12) { "desc file too short" }
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "VNDS") { "not a VNDS file" }
            val version = b.short.toInt(); val dtype = b.short.toInt(); val dim = b.int
            require(version == 1 && dtype == 1 && dim > 0) { "unsupported desc file v=$version dtype=$dtype dim=$dim" }
            val recSize = 8 + 2 * dim
            val n = b.remaining() / recSize // обрезанный хвост отбрасывается
            val times = LongArray(n)
            val desc = ShortArray(n * dim)
            for (i in 0 until n) {
                times[i] = b.long
                for (j in 0 until dim) desc[i * dim + j] = b.short
            }
            return DescriptorLog(dim, times, desc)
        }
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 39 тестов.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): float16 encoding and binary descriptor log

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Журнал датчиков

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/SensorLog.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/SensorLogTest.kt`

**Interfaces:**
- Produces:
  - Интерфейс `sealed interface SensorEvent { val tMs: Double }` и его реализации:
    - `GyroEvent(tMs, x: Float, y: Float, z: Float)`;
    - `AccelEvent(tMs, x, y, z)`;
    - `LocEvent(tMs, lat: Double, lon: Double, accM: Float, speedMps: Float?, speedAccMps: Float?, bearingDeg: Float?, bearingAccDeg: Float?)`;
    - `GnssStatusEvent(tMs, sats: Int, used: Int, cn0Mean: Float?)`.
  - `object SensorLogFormat` с методами:
    - `header(startedMs: Long): String`;
    - `line(e: SensorEvent): String`;
    - `parse(line: String): SensorEvent?` — возвращает `null` для заголовка; бросает `IllegalArgumentException` на неизвестном `k`.
  - `class SensorLogger(file: File) : Closeable`: `header(startedMs)`; потокобезопасный `event(e)` со сбросом каждые 200 событий; `close()`.
  - `fun readSensorLog(file: File): List<SensorEvent>` — отсортирован по `tMs`, обрезанная последняя строка пропускается.

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/SensorLogTest.kt`:
```kotlin
package io.visnav.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SensorLogTest {
    @Test fun exactLineFormat() {
        assertEquals("{\"v\":1,\"type\":\"sensors\",\"started_ms\":5}", SensorLogFormat.header(5))
        assertEquals("{\"t\":1000.5,\"k\":\"g\",\"x\":0.1,\"y\":-0.2,\"z\":0.3}",
            SensorLogFormat.line(GyroEvent(1000.5, 0.1f, -0.2f, 0.3f)))
        assertEquals("{\"t\":2000.0,\"k\":\"loc\",\"lat\":55.75,\"lon\":37.6,\"acc\":4.5,\"spd\":12.5,\"spd_acc\":null,\"brg\":90.0,\"brg_acc\":null}",
            SensorLogFormat.line(LocEvent(2000.0, 55.75, 37.6, 4.5f, 12.5f, null, 90f, null)))
        assertEquals("{\"t\":3000.0,\"k\":\"gnss\",\"sats\":20,\"used\":12,\"cn0\":null}",
            SensorLogFormat.line(GnssStatusEvent(3000.0, 20, 12, null)))
    }

    @Test fun parseRoundTripsEveryType() {
        val events = listOf(
            GyroEvent(1.0, 0.1f, 0.2f, 0.3f), AccelEvent(2.0, 0f, 0f, 9.81f),
            LocEvent(3.0, 55.75, 37.6, 3f, 10f, 0.5f, 270f, 2f), GnssStatusEvent(4.0, 18, 9, 31.5f),
        )
        for (e in events) assertEquals(e, SensorLogFormat.parse(SensorLogFormat.line(e)))
        assertNull(SensorLogFormat.parse(SensorLogFormat.header(1)))
        assertFailsWith<IllegalArgumentException> { SensorLogFormat.parse("{\"t\":1.0,\"k\":\"zz\"}") }
    }

    @Test fun loggerWritesAndReaderSortsAndSkipsTruncatedTail() {
        val f = File.createTempFile("s", ".sensors.jsonl")
        SensorLogger(f).use {
            it.header(1)
            it.event(AccelEvent(20.0, 0f, 0f, 9.8f))
            it.event(GyroEvent(10.0, 0f, 0f, 0f))
        }
        f.appendText("{\"t\":30.0,\"k\":\"g\",\"x\":0.")
        val read = readSensorLog(f)
        assertEquals(listOf(10.0, 20.0), read.map { it.tMs })
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: SensorLogFormat`).

- [ ] **Step 3: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/SensorLog.kt`:
```kotlin
package io.visnav.core

import java.io.Closeable
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** События датчиков; t — настенное время телефона, мс. Формат — контракт .sensors.jsonl v1 (план M2a). */
sealed interface SensorEvent { val tMs: Double }
data class GyroEvent(override val tMs: Double, val x: Float, val y: Float, val z: Float) : SensorEvent
data class AccelEvent(override val tMs: Double, val x: Float, val y: Float, val z: Float) : SensorEvent
data class LocEvent(
    override val tMs: Double, val lat: Double, val lon: Double, val accM: Float,
    val speedMps: Float?, val speedAccMps: Float?, val bearingDeg: Float?, val bearingAccDeg: Float?,
) : SensorEvent
data class GnssStatusEvent(override val tMs: Double, val sats: Int, val used: Int, val cn0Mean: Float?) : SensorEvent

object SensorLogFormat {
    fun header(startedMs: Long): String = "{\"v\":1,\"type\":\"sensors\",\"started_ms\":$startedMs}"

    fun line(e: SensorEvent): String = when (e) {
        is GyroEvent -> "{\"t\":${e.tMs},\"k\":\"g\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}}"
        is AccelEvent -> "{\"t\":${e.tMs},\"k\":\"a\",\"x\":${e.x},\"y\":${e.y},\"z\":${e.z}}"
        is LocEvent -> "{\"t\":${e.tMs},\"k\":\"loc\",\"lat\":${e.lat},\"lon\":${e.lon},\"acc\":${e.accM}," +
            "\"spd\":${e.speedMps},\"spd_acc\":${e.speedAccMps},\"brg\":${e.bearingDeg},\"brg_acc\":${e.bearingAccDeg}}"
        is GnssStatusEvent -> "{\"t\":${e.tMs},\"k\":\"gnss\",\"sats\":${e.sats},\"used\":${e.used},\"cn0\":${e.cn0Mean}}"
    }

    fun parse(line: String): SensorEvent? {
        val o: JsonObject = Json.parseToJsonElement(line).jsonObject
        if (o["type"] != null) return null
        val t = o.getValue("t").jsonPrimitive.double
        fun f(key: String) = o.getValue(key).jsonPrimitive.float
        fun fOrNull(key: String) = (o[key] as? JsonPrimitive)?.floatOrNull
        return when (val k = o.getValue("k").jsonPrimitive.content) {
            "g" -> GyroEvent(t, f("x"), f("y"), f("z"))
            "a" -> AccelEvent(t, f("x"), f("y"), f("z"))
            "loc" -> LocEvent(t, o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double,
                f("acc"), fOrNull("spd"), fOrNull("spd_acc"), fOrNull("brg"), fOrNull("brg_acc"))
            "gnss" -> GnssStatusEvent(t, o.getValue("sats").jsonPrimitive.int, o.getValue("used").jsonPrimitive.int, fOrNull("cn0"))
            else -> throw IllegalArgumentException("unknown sensor event kind '$k'")
        }
    }
}

/** Пишется из потоков датчиков и GPS одновременно, поэтому методы синхронизированы. */
class SensorLogger(file: File) : Closeable {
    private val writer = file.bufferedWriter()
    private var count = 0

    @Synchronized fun header(startedMs: Long) {
        writer.write(SensorLogFormat.header(startedMs)); writer.newLine(); writer.flush()
    }

    @Synchronized fun event(e: SensorEvent) {
        writer.write(SensorLogFormat.line(e)); writer.newLine()
        if (++count % 200 == 0) writer.flush()
    }

    @Synchronized override fun close() { writer.flush(); writer.close() }
}

fun readSensorLog(file: File): List<SensorEvent> {
    val lines = file.readLines().filter { it.isNotBlank() }
    val out = ArrayList<SensorEvent>(lines.size)
    for ((i, line) in lines.withIndex()) {
        val e = try {
            SensorLogFormat.parse(line)
        } catch (ex: Exception) {
            if (i == lines.lastIndex) null // обрезанная последняя строка после аварийной остановки
            else throw IllegalArgumentException("${file.name}:${i + 1}: ${ex.message}", ex)
        }
        if (e != null) out.add(e)
    }
    return out.sortedBy { it.tMs }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 42 теста.

Если `Float.toString()` даёт для какого-то значения теста другое представление (например, `0.1` → `0.1`, это ожидаемо), тест не подгонять: проверить значения и сообщить NEEDS_CONTEXT.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): sensor event log format (gyro, accel, GNSS fix, satellite status)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Запись датчиков и дескрипторов в приложении

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/LocalizationPipeline.kt`
- Modify: `android/core/src/test/kotlin/io/visnav/core/LocalizationPipelineTest.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/SensorRecorder.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/GpsSource.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`

**Interfaces:**
- Consumes: `SensorLogger`, `SensorEvent`-классы (Task 2), `DescriptorLogWriter` (Task 1), `RefPack.dim`.
- Produces:
  - `LocalizationPipeline(..., onDescriptor: ((tMs: Long, desc: FloatArray) -> Unit)? = null)` — вызывается сразу после `embed`.
  - `SensorRecorder(context)` с методами `start(sink: (SensorEvent) -> Unit): Boolean` (false, если нет гироскопа или акселерометра) и `stop()`.
  - `GpsSource.onLoc: ((LocEvent) -> Unit)?` и `GpsSource.onGnss: ((GnssStatusEvent) -> Unit)?`.
  - Файлы сессии `.sensors.jsonl` и `.desc` рядом с `.jsonl`.

- [ ] **Step 1: Падающий тест на `onDescriptor`**

Добавить в `android/core/src/test/kotlin/io/visnav/core/LocalizationPipelineTest.kt` (внутри класса, рядом с существующими тестами, используя уже определённые там `pack`, `FakeEmbedder` и `clock`):
```kotlin
    @Test fun reportsDescriptorOfEveryFrame() {
        val seen = mutableListOf<Pair<Long, List<Float>>>()
        val p = LocalizationPipeline(pack, FakeEmbedder(floatArrayOf(0f, 1f, 0f)), PriorPolicy(PriorMode.GPS),
            nanoTime = clock(0, 0, 0), onDescriptor = { t, d -> seen.add(t to d.toList()) })
        p.process(1_000, ByteArray(12), 2, 2, gps = null, preMs = 0.0)
        assertEquals(listOf(1_000L to listOf(0f, 1f, 0f)), seen)
    }
```

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`No parameter with name 'onDescriptor'`).

- [ ] **Step 2: Реализовать `onDescriptor`**

В `LocalizationPipeline.kt` добавить последний параметр конструктора и вызов:
```kotlin
class LocalizationPipeline(
    private val pack: RefPack,
    private val embedder: Embedder,
    private val priorPolicy: PriorPolicy,
    private val k: Int = 5,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onDescriptor: ((tMs: Long, desc: FloatArray) -> Unit)? = null,
) {
```
и сразу после строки `val t1 = nanoTime()`:
```kotlin
        onDescriptor?.invoke(tMs, desc)
```

Run: `./gradlew :core:test` (с тем же `JAVA_HOME`)
Expected: `BUILD SUCCESSFUL`, 43 теста.

- [ ] **Step 3: `SensorRecorder.kt`**

`android/app/src/main/kotlin/io/visnav/app/SensorRecorder.kt`:
```kotlin
package io.visnav.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent as AndroidSensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.visnav.core.AccelEvent
import io.visnav.core.GyroEvent
import io.visnav.core.SensorEvent

/**
 * Гироскоп и акселерометр с периодом 10 мс на отдельном потоке. Метка SensorEvent.timestamp —
 * монотонные часы (отсчёт elapsedRealtimeNanos); переводим в настенное время телефона через
 * смещение, зафиксированное при start(), чтобы датчики, кадры и GPS были на одних часах.
 */
class SensorRecorder(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    @Volatile private var sink: ((SensorEvent) -> Unit)? = null
    private var offsetMs = 0.0

    fun start(sink: (SensorEvent) -> Unit): Boolean {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        offsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtimeNanos() / 1e6
        this.sink = sink
        val t = HandlerThread("sensors").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        sm.registerListener(this, gyro, 10_000, handler)
        sm.registerListener(this, accel, 10_000, handler)
        return true
    }

    fun stop() {
        sm.unregisterListener(this)
        sink = null
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: AndroidSensorEvent) {
        val s = sink ?: return
        val tMs = offsetMs + e.timestamp / 1e6
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> s(GyroEvent(tMs, e.values[0], e.values[1], e.values[2]))
            Sensor.TYPE_ACCELEROMETER -> s(AccelEvent(tMs, e.values[0], e.values[1], e.values[2]))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
```

- [ ] **Step 4: Расширить `GpsSource`: события `loc` и статус спутников**

В `GpsSource.kt`:
1. Добавить поля:
```kotlin
    @Volatile var onLoc: ((io.visnav.core.LocEvent) -> Unit)? = null
    @Volatile var onGnss: ((io.visnav.core.GnssStatusEvent) -> Unit)? = null
    private val gnssCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            val cb = onGnss ?: return
            var used = 0
            var cn0Sum = 0.0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) { used++; cn0Sum += status.getCn0DbHz(i) }
            }
            cb(io.visnav.core.GnssStatusEvent(System.currentTimeMillis().toDouble(), status.satelliteCount, used,
                if (used > 0) (cn0Sum / used).toFloat() else null))
        }
    }
```
2. Добавить поле `private val gnssExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()` и переписать `start()`/`stop()`:
```kotlin
    @SuppressLint("MissingPermission") // разрешение проверяет MainActivity до start()
    fun start() {
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lm.registerGnssStatusCallback(gnssExecutor, gnssCallback)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
        }
    }

    fun stop() {
        lm.removeUpdates(this)
        lm.unregisterGnssStatusCallback(gnssCallback)
    }
```
3. В конце `onLocationChanged` (после вычисления `tMs` и записи `latest`):
```kotlin
        onLoc?.invoke(io.visnav.core.LocEvent(
            tMs.toDouble(), l.latitude, l.longitude, l.accuracy,
            if (l.hasSpeed()) l.speed else null,
            if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
            if (l.hasBearing()) l.bearing else null,
            if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null,
        ))
```

- [ ] **Step 5: Записывать `.sensors.jsonl` и `.desc` в сессии**

В `M1Controller.kt`:
1. Поля: `private val sensors = SensorRecorder(context)`, `@Volatile private var sensorLog: SensorLogger? = null`, `@Volatile private var descLog: DescriptorLogWriter? = null`.
2. В `start()` сразу после создания `log` (`SessionLogger(...)`):
```kotlin
            val base = "session-$startedMs-${mode.name.lowercase()}"
            val sLog = SensorLogger(File(logDir, "$base.sensors.jsonl")).also { it.header(startedMs) }
            sensorLog = sLog
            val dLog = DescriptorLogWriter(File(logDir, "$base.desc"), b.pack.dim)
            descLog = dLog
            gps.onLoc = { sLog.event(it) }
            gps.onGnss = { sLog.event(it) }
            if (!sensors.start { sLog.event(it) }) {
                _state.update { it.copy(status = it.status + " · нет гироскопа/акселерометра — датчики не пишутся") }
            }
```
   Имя `.jsonl` журнала кадров строится из того же `base`: `File(logDir, "$base.jsonl")`.
3. Конвейер создаётся с `onDescriptor = { t, d -> dLog.write(t, d) }`. Вызывается на потоке анализа, как и `log.frame`.
4. В `stop()`, `failSession()`, в ветке ошибки `start()` и в `close()` рядом с остановкой GPS:
   - вызвать `sensors.stop()`;
   - обнулить `gps.onLoc` и `gps.onGnss`;
   - закрыть `sensorLog` и `descLog` так же, как `log`: на `executor` (в `failSession` — напрямую), каждое закрытие в своём try/catch с сообщением в статус; обнулить поля.

- [ ] **Step 6: Сборка и lint**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, 43 теста `:core`, 0 ошибок lint. Если API `Location`/`GnssStatus`/`SensorManager` на `compileSdk 35` отличается от кода выше, поправить по документации и описать в отчёте.

- [ ] **Step 7: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src android/app/src
git commit -m "feat(android): record IMU, GNSS fixes/status and frame descriptors per session

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Локальные координаты и фильтр Калмана

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/Enu.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/Ekf2d.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/EnuTest.kt`, `android/core/src/test/kotlin/io/visnav/core/Ekf2dTest.kt`

**Interfaces:**
- Consumes: `Geo.EARTH_RADIUS_M`.
- Produces:
  - `class Enu(val lat0: Double, val lon0: Double)` с методами `toEn(lat, lon): DoubleArray` (`[e, n]`) и `toLatLon(e, n): DoubleArray` (`[lat, lon]`).
  - `data class FilterConfig(accelNoise = 1.0, gyroNoise = 0.01, gyroBiasWalk = 1e-4, posNoise = 0.1, gateChi2Pos = 13.8, gateChi2Scalar = 10.8)`.
  - `class Ekf2d(config: FilterConfig = FilterConfig())`:
    - поля: `x: DoubleArray` (5: e, n, ψ, v, b_g), `p: DoubleArray` (25, построчно), `initialized: Boolean`;
    - `init(e, n, psi, v, posSigma, psiSigma, vSigma)`;
    - `predict(dt: Double, omega: Double)`;
    - `updatePosition(e, n, sigma): Boolean`, `updateSpeed(v, sigma): Boolean`, `updateHeading(psi, sigma): Boolean` — `false`, если отвергнуто χ²-отсевом;
    - `posSigma(): Double`.
  - Функция уровня файла `wrapAngle(a: Double): Double` → (−π, π].

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/EnuTest.kt`:
```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EnuTest {
    @Test fun northAndEastMetersMatchHaversine() {
        val enu = Enu(55.75, 37.6)
        val north = enu.toLatLon(0.0, 100.0)
        assertEquals(100.0, Geo.haversineM(55.75, 37.6, north[0], north[1]), 0.01)
        val east = enu.toLatLon(100.0, 0.0)
        assertEquals(100.0, Geo.haversineM(55.75, 37.6, east[0], east[1]), 0.05)
    }

    @Test fun roundTrip() {
        val enu = Enu(55.75, 37.6)
        val en = enu.toEn(55.7612, 37.6231)
        val back = enu.toLatLon(en[0], en[1])
        assertEquals(55.7612, back[0], 1e-9); assertEquals(37.6231, back[1], 1e-9)
    }
}
```

`android/core/src/test/kotlin/io/visnav/core/Ekf2dTest.kt`:
```kotlin
package io.visnav.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ekf2dTest {
    private fun filter(psi: Double = PI / 2, v: Double = 10.0) =
        Ekf2d().also { it.init(0.0, 0.0, psi, v, posSigma = 3.0, psiSigma = 0.05, vSigma = 0.5) }

    @Test fun straightEastDrive() {
        val f = filter()
        repeat(100) { f.predict(0.1, 0.0) }
        assertEquals(100.0, f.x[0], 1e-6); assertEquals(0.0, f.x[1], 1e-6)
        assertTrue(f.posSigma() > 3.0) // неопределённость растёт без измерений
    }

    @Test fun gyroTurnChangesHeadingClockwise() {
        val f = filter()
        repeat(1000) { f.predict(0.01, 0.1) } // 10 с по 0.1 рад/с по часовой
        assertEquals(wrapAngle(PI / 2 + 1.0), f.x[2], 1e-9)
    }

    @Test fun positionUpdatePullsStateAndShrinksCovariance() {
        val f = filter()
        repeat(50) { f.predict(0.1, 0.0) }
        val before = f.posSigma()
        assertTrue(f.updatePosition(60.0, 5.0, 3.0))
        assertTrue(f.x[0] > 50.0 && f.x[0] < 60.0)
        assertTrue(f.x[1] > 0.0 && f.x[1] < 5.0)
        assertTrue(f.posSigma() < before)
    }

    @Test fun estimatesGyroBiasFromGnss() {
        val f = filter()
        var truthE = 0.0
        for (s in 1..120) {
            repeat(100) { f.predict(0.01, 0.02) } // истинный поворот 0, гироскоп врёт на +0.02 рад/с
            truthE += 10.0
            f.updatePosition(truthE, 0.0, 3.0)
            f.updateHeading(PI / 2, 0.03)
            f.updateSpeed(10.0, 0.2)
        }
        assertEquals(0.02, f.x[4], 0.005)
    }

    @Test fun gateRejectsFarOutlierAndKeepsState() {
        val f = filter()
        repeat(10) { f.predict(0.1, 0.0); f.updatePosition(f.x[0], 0.0, 3.0) }
        val e = f.x[0]; val n = f.x[1]
        assertFalse(f.updatePosition(e + 500.0, n, 5.0))
        assertEquals(e, f.x[0]); assertEquals(n, f.x[1])
    }

    @Test fun headingInnovationWrapsAround() {
        val f = filter(psi = Math.toRadians(1.0), v = 0.0)
        assertTrue(f.updateHeading(Math.toRadians(359.0), 0.05))
        assertTrue(f.x[2] < Math.toRadians(1.0) && f.x[2] > Math.toRadians(-1.0)) // сдвинулось через 0, а не на 358°
    }

    @Test fun wrapAngleRange() {
        assertEquals(-PI / 2, wrapAngle(3 * PI / 2), 1e-12)
        assertEquals(PI, wrapAngle(-PI), 1e-12)
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: Enu`, `Ekf2d`).

- [ ] **Step 3: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/Enu.kt`:
```kotlin
package io.visnav.core

import kotlin.math.cos

/** Плоская система «восток–север» (м) вокруг опорной точки; для города погрешность плоскости мала. */
class Enu(val lat0: Double, val lon0: Double) {
    private val mPerDegLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_M
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat0))

    fun toEn(lat: Double, lon: Double): DoubleArray =
        doubleArrayOf((lon - lon0) * mPerDegLon, (lat - lat0) * mPerDegLat)

    fun toLatLon(e: Double, n: Double): DoubleArray =
        doubleArrayOf(lat0 + n / mPerDegLat, lon0 + e / mPerDegLon)
}
```

`android/core/src/main/kotlin/io/visnav/core/Ekf2d.kt`:
```kotlin
package io.visnav.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Угол в (−π, π]. */
fun wrapAngle(a: Double): Double {
    var r = a % (2 * PI)
    if (r <= -PI) r += 2 * PI
    if (r > PI) r -= 2 * PI
    return r
}

data class FilterConfig(
    val accelNoise: Double = 1.0,      // м/с² — насколько быстро может меняться скорость
    val gyroNoise: Double = 0.01,      // рад/с — шум скорости поворота
    val gyroBiasWalk: Double = 1e-4,   // рад/с/√с — дрейф смещения гироскопа
    val posNoise: Double = 0.1,        // м/√с — немоделируемые боковые смещения
    val gateChi2Pos: Double = 13.8,    // χ², 2 степени свободы, 99.9 %
    val gateChi2Scalar: Double = 10.8, // χ², 1 степень свободы, 99.9 %
)

/**
 * Расширенный фильтр Калмана на плоскости. Состояние x = [e, n, ψ, v, b_g]:
 * позиция (м), курс (рад, от севера по часовой), скорость (м/с), смещение гироскопа (рад/с).
 * Модель: e' = v·sin ψ, n' = v·cos ψ, ψ' = ω − b_g, v и b_g — случайное блуждание.
 */
class Ekf2d(val config: FilterConfig = FilterConfig()) {
    val x = DoubleArray(N)
    val p = DoubleArray(N * N)
    var initialized = false
        private set

    fun init(e: Double, n: Double, psi: Double, v: Double, posSigma: Double, psiSigma: Double, vSigma: Double) {
        x[0] = e; x[1] = n; x[2] = wrapAngle(psi); x[3] = v; x[4] = 0.0
        p.fill(0.0)
        p[idx(0, 0)] = posSigma * posSigma
        p[idx(1, 1)] = posSigma * posSigma
        p[idx(2, 2)] = psiSigma * psiSigma
        p[idx(3, 3)] = vSigma * vSigma
        p[idx(4, 4)] = 0.01 * 0.01
        initialized = true
    }

    fun predict(dt: Double, omega: Double) {
        check(initialized) { "filter not initialized" }
        require(dt >= 0) { "dt must be >= 0" }
        if (dt == 0.0) return
        val psi = x[2]; val v = x[3]
        val s = sin(psi); val c = cos(psi)
        x[0] += v * s * dt
        x[1] += v * c * dt
        x[2] = wrapAngle(psi + (omega - x[4]) * dt)

        val f = identity()
        f[idx(0, 2)] = v * c * dt; f[idx(0, 3)] = s * dt
        f[idx(1, 2)] = -v * s * dt; f[idx(1, 3)] = c * dt
        f[idx(2, 4)] = -dt
        val fp = mul(f, p)
        val next = mulT(fp, f)
        val q = config
        next[idx(0, 0)] += q.posNoise * q.posNoise * dt
        next[idx(1, 1)] += q.posNoise * q.posNoise * dt
        next[idx(2, 2)] += q.gyroNoise * q.gyroNoise * dt
        next[idx(3, 3)] += q.accelNoise * q.accelNoise * dt
        next[idx(4, 4)] += q.gyroBiasWalk * q.gyroBiasWalk * dt
        next.copyInto(p)
    }

    fun updatePosition(e: Double, n: Double, sigma: Double): Boolean = update(
        arrayOf(unit(0), unit(1)), doubleArrayOf(e - x[0], n - x[1]),
        doubleArrayOf(sigma * sigma, sigma * sigma), config.gateChi2Pos,
    )

    fun updateSpeed(v: Double, sigma: Double): Boolean =
        update(arrayOf(unit(3)), doubleArrayOf(v - x[3]), doubleArrayOf(sigma * sigma), config.gateChi2Scalar)

    fun updateHeading(psi: Double, sigma: Double): Boolean =
        update(arrayOf(unit(2)), doubleArrayOf(wrapAngle(psi - x[2])), doubleArrayOf(sigma * sigma), config.gateChi2Scalar)

    fun posSigma(): Double = sqrt(max(p[idx(0, 0)], p[idx(1, 1)]))

    /** Общее обновление: H — строки (m ≤ 2), y — невязка, r — дисперсии шума (диагональ). */
    private fun update(h: Array<DoubleArray>, y: DoubleArray, r: DoubleArray, gate: Double): Boolean {
        check(initialized) { "filter not initialized" }
        val m = h.size
        // PHᵀ (N×m)
        val pht = Array(N) { i -> DoubleArray(m) { k -> (0 until N).sumOf { j -> p[idx(i, j)] * h[k][j] } } }
        // S = H P Hᵀ + R (m×m)
        val s = Array(m) { a -> DoubleArray(m) { b -> (0 until N).sumOf { j -> h[a][j] * pht[j][b] } + if (a == b) r[a] else 0.0 } }
        val sInv = when (m) {
            1 -> arrayOf(doubleArrayOf(1.0 / s[0][0]))
            2 -> {
                val det = s[0][0] * s[1][1] - s[0][1] * s[1][0]
                arrayOf(doubleArrayOf(s[1][1] / det, -s[0][1] / det), doubleArrayOf(-s[1][0] / det, s[0][0] / det))
            }
            else -> error("measurement dimension $m not supported")
        }
        var d2 = 0.0
        for (a in 0 until m) for (b in 0 until m) d2 += y[a] * sInv[a][b] * y[b]
        if (d2 > gate) return false
        // K = PHᵀ S⁻¹ (N×m)
        val k = Array(N) { i -> DoubleArray(m) { b -> (0 until m).sumOf { a -> pht[i][a] * sInv[a][b] } } }
        for (i in 0 until N) x[i] += (0 until m).sumOf { a -> k[i][a] * y[a] }
        x[2] = wrapAngle(x[2])
        // Форма Джозефа: P = (I − KH) P (I − KH)ᵀ + K R Kᵀ
        val ikh = identity()
        for (i in 0 until N) for (j in 0 until N) ikh[idx(i, j)] -= (0 until m).sumOf { a -> k[i][a] * h[a][j] }
        val next = mulT(mul(ikh, p), ikh)
        for (i in 0 until N) for (j in 0 until N) next[idx(i, j)] += (0 until m).sumOf { a -> k[i][a] * r[a] * k[j][a] }
        next.copyInto(p)
        return true
    }

    private companion object {
        const val N = 5
        fun idx(i: Int, j: Int) = i * N + j
        fun identity() = DoubleArray(N * N).also { for (i in 0 until N) it[idx(i, i)] = 1.0 }
        fun unit(i: Int) = DoubleArray(N).also { it[i] = 1.0 }
        fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(N * N).also { out ->
            for (i in 0 until N) for (j in 0 until N) {
                var s = 0.0
                for (k in 0 until N) s += a[idx(i, k)] * b[idx(k, j)]
                out[idx(i, j)] = s
            }
        }
        /** a · bᵀ */
        fun mulT(a: DoubleArray, b: DoubleArray) = DoubleArray(N * N).also { out ->
            for (i in 0 until N) for (j in 0 until N) {
                var s = 0.0
                for (k in 0 until N) s += a[idx(i, k)] * b[idx(j, k)]
                out[idx(i, j)] = s
            }
        }
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 52 теста.

Если `estimatesGyroBiasFromGnss` не сходится до ±0,005, ослаблять допуск нельзя. Нужно сообщить NEEDS_CONTEXT с полученным значением `x[4]`.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): local ENU frame and 2-D EKF with gyro bias and chi-square gating

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Скорость поворота и детектор стоянки

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/Motion.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/MotionTest.kt`

**Interfaces:**
- Produces:
  - `class YawRate(alpha: Double = 0.02)` с методами `onAccel(x: Float, y: Float, z: Float)` и `headingRate(wx: Float, wy: Float, wz: Float): Double?`. Возвращает скорость изменения курса по часовой (рад/с) = −(ω·ĝ), где ĝ — единичный сглаженный вектор ускорения (он направлен вверх, когда телефон неподвижен). Пока не пришло ни одного ускорения — `null`.
  - `class StationaryDetector(windowMs: Double = 1000.0, accelStdMax: Double = 0.08, gyroMeanMax: Double = 0.01, minSamples: Int = 20)` с методами `onAccel(tMs, x, y, z)`, `onGyro(tMs, x, y, z)` и `isStationary(tMs): Boolean`.

- [ ] **Step 1: Написать падающие тесты**

`android/core/src/test/kotlin/io/visnav/core/MotionTest.kt`:
```kotlin
package io.visnav.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionTest {
    @Test fun yawRateFlatPhone() {
        val y = YawRate()
        assertNull(y.headingRate(0f, 0f, 0.1f))
        y.onAccel(0f, 0f, 9.81f)
        // поворот против часовой вокруг «вверх» = курс уменьшается
        assertEquals(-0.1, y.headingRate(0f, 0f, 0.1f)!!, 1e-6)
    }

    @Test fun yawRatePhoneInLandscapeMount() {
        val y = YawRate()
        repeat(200) { y.onAccel(9.81f, 0f, 0f) } // вертикаль вдоль оси x телефона
        assertEquals(-0.2, y.headingRate(0.2f, 0f, 0f)!!, 1e-6)
        assertEquals(0.0, y.headingRate(0f, 0.3f, 0f)!!, 1e-6) // вращение вокруг горизонтальной оси — не поворот
    }

    @Test fun stationaryWhenQuiet() {
        val d = StationaryDetector()
        for (i in 0 until 100) {
            val t = i * 10.0
            d.onAccel(t, 0f, 0f, 9.81f + 0.01f * sin(i.toFloat()))
            d.onGyro(t, 0.001f, 0f, 0f)
        }
        assertTrue(d.isStationary(990.0))
    }

    @Test fun movingWhenVibratingOrTurning() {
        val vib = StationaryDetector()
        for (i in 0 until 100) { val t = i * 10.0; vib.onAccel(t, 0f, 0f, 9.81f + if (i % 2 == 0) 0.5f else -0.5f); vib.onGyro(t, 0f, 0f, 0f) }
        assertFalse(vib.isStationary(990.0))
        val turn = StationaryDetector()
        for (i in 0 until 100) { val t = i * 10.0; turn.onAccel(t, 0f, 0f, 9.81f); turn.onGyro(t, 0f, 0f, 0.1f) }
        assertFalse(turn.isStationary(990.0))
    }

    @Test fun notEnoughSamplesIsNotStationary() {
        val d = StationaryDetector()
        for (i in 0 until 5) { d.onAccel(i * 10.0, 0f, 0f, 9.81f); d.onGyro(i * 10.0, 0f, 0f, 0f) }
        assertFalse(d.isStationary(50.0))
    }

    @Test fun oldSamplesLeaveTheWindow() {
        val d = StationaryDetector()
        for (i in 0 until 100) { d.onAccel(i * 10.0, 0f, 0f, 9.81f); d.onGyro(i * 10.0, 0f, 0f, 0f) }
        assertFalse(d.isStationary(5_000.0)) // в окне [4000, 5000] ничего нет
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: YawRate`).

- [ ] **Step 3: Реализовать**

`android/core/src/main/kotlin/io/visnav/core/Motion.kt`:
```kotlin
package io.visnav.core

import kotlin.math.sqrt

/**
 * Скорость поворота курса из гироскопа, не зависящая от ориентации телефона в держателе:
 * проекция угловой скорости на вертикаль. Вертикаль — сглаженное ускорение (неподвижный
 * акселерометр показывает +g вверх). Поворот против часовой вокруг «вверх» уменьшает курс.
 */
class YawRate(private val alpha: Double = 0.02) {
    private val g = DoubleArray(3)
    private var hasG = false

    fun onAccel(x: Float, y: Float, z: Float) {
        if (!hasG) { g[0] = x.toDouble(); g[1] = y.toDouble(); g[2] = z.toDouble(); hasG = true; return }
        g[0] += alpha * (x - g[0]); g[1] += alpha * (y - g[1]); g[2] += alpha * (z - g[2])
    }

    fun headingRate(wx: Float, wy: Float, wz: Float): Double? {
        if (!hasG) return null
        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (norm < 1e-6) return null
        return -(wx * g[0] + wy * g[1] + wz * g[2]) / norm
    }
}

/** Машина стоит, если за последнее окно модуль ускорения почти не дрожит и гироскоп почти молчит. */
class StationaryDetector(
    private val windowMs: Double = 1000.0,
    private val accelStdMax: Double = 0.08,
    private val gyroMeanMax: Double = 0.01,
    private val minSamples: Int = 20,
) {
    private val accel = ArrayDeque<Pair<Double, Double>>()
    private val gyro = ArrayDeque<Pair<Double, Double>>()

    fun onAccel(tMs: Double, x: Float, y: Float, z: Float) { accel.addLast(tMs to norm(x, y, z)) }
    fun onGyro(tMs: Double, x: Float, y: Float, z: Float) { gyro.addLast(tMs to norm(x, y, z)) }

    fun isStationary(tMs: Double): Boolean {
        val from = tMs - windowMs
        while (accel.isNotEmpty() && accel.first().first < from) accel.removeFirst()
        while (gyro.isNotEmpty() && gyro.first().first < from) gyro.removeFirst()
        if (accel.size < minSamples || gyro.size < minSamples) return false
        val mean = accel.sumOf { it.second } / accel.size
        val std = sqrt(accel.sumOf { (it.second - mean) * (it.second - mean) } / accel.size)
        val gyroMean = gyro.sumOf { it.second } / gyro.size
        return std < accelStdMax && gyroMean < gyroMeanMax
    }

    private fun norm(x: Float, y: Float, z: Float) = sqrt((x * x + y * y + z * z).toDouble())
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 58 тестов.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(android): mount-independent yaw rate and stationary detector

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Симулятор поездки и интеграционные проверки фильтра

**Files:**
- Create: `android/core/src/test/kotlin/io/visnav/core/DriveSim.kt`
- Create: `android/core/src/test/kotlin/io/visnav/core/FilterIntegrationTest.kt`

**Interfaces:**
- Consumes: `Ekf2d`, `FilterConfig`, `YawRate`, `wrapAngle` (Tasks 4–5).
- Produces (только тестовые исходники):
  - `DriveSim(seed: Long)`, `DriveSim.Segment(durationS: Double, speed: Double, turnRate: Double)`, `DriveSim.run(segments): List<DriveSim.Sample>`.
  - `Sample(tS, e, n, psi, v, gyroZ, gnss: DoubleArray?, vis: DoubleArray?)`. `gnss` = `[e, n, speed, bearingRad]` раз в 1 с, `vis` = `[e, n]` раз в 0,5 с (5 % выбросов на 300 м).

- [ ] **Step 1: Симулятор**

`android/core/src/test/kotlin/io/visnav/core/DriveSim.kt`:
```kotlin
package io.visnav.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Синтетическая поездка с шагом 10 мс: телефон лежит горизонтально (вертикаль = +z), поэтому
 * измеренный гироскоп z = −(скорость поворота по часовой) + смещение + шум. GNSS раз в 1 с,
 * визуальные фиксации раз в 0.5 с (σ = 8 м, 5 % выбросов на 300 м).
 */
class DriveSim(seed: Long) {
    data class Segment(val durationS: Double, val speed: Double, val turnRate: Double)
    data class Sample(
        val tS: Double, val e: Double, val n: Double, val psi: Double, val v: Double,
        val gyroZ: Double, val gnss: DoubleArray?, val vis: DoubleArray?,
    )

    private val rnd = Random(seed)
    private fun gauss(): Double { // Бокс — Мюллер
        val u1 = rnd.nextDouble(1e-12, 1.0); val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    fun run(
        segments: List<Segment>, startPsi: Double = Math.PI / 2, gyroBias: Double = 0.01, gyroNoise: Double = 0.01,
        gnssSigma: Double = 3.0, visSigma: Double = 8.0, outlierRate: Double = 0.05,
    ): List<Sample> {
        val dt = 0.01
        var e = 0.0; var n = 0.0; var psi = startPsi; var t = 0.0
        val out = ArrayList<Sample>()
        var step = 0
        for (seg in segments) {
            val steps = (seg.durationS / dt).toInt()
            repeat(steps) {
                e += seg.speed * sin(psi) * dt
                n += seg.speed * cos(psi) * dt
                psi = wrapAngle(psi + seg.turnRate * dt)
                t += dt; step++
                val gyroZ = -(seg.turnRate) + gyroBias + gyroNoise * gauss()
                val gnss = if (step % 100 == 0) doubleArrayOf(
                    e + gnssSigma * gauss(), n + gnssSigma * gauss(), seg.speed + 0.2 * gauss(),
                    wrapAngle(psi + Math.toRadians(2.0) * gauss()),
                ) else null
                val vis = if (step % 50 == 0) {
                    if (rnd.nextDouble() < outlierRate) doubleArrayOf(e + 300.0, n)
                    else doubleArrayOf(e + visSigma * gauss(), n + visSigma * gauss())
                } else null
                out.add(Sample(t, e, n, psi, seg.speed, gyroZ, gnss, vis))
            }
        }
        return out
    }
}
```

- [ ] **Step 2: Интеграционные тесты (NFR-5 и NFR-1 в симуляции)**

`android/core/src/test/kotlin/io/visnav/core/FilterIntegrationTest.kt`:
```kotlin
package io.visnav.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

class FilterIntegrationTest {
    private val route = listOf(
        DriveSim.Segment(60.0, 12.0, 0.0),        // 60 с прямо — фильтр сходится по GNSS
        DriveSim.Segment(20.0, 12.0, 0.05),       // плавный поворот
        DriveSim.Segment(40.0, 12.0, 0.0),
        DriveSim.Segment(15.0, 12.0, -0.1),       // скорость постоянна: без одометрии её изменение в пропадании ненаблюдаемо
        DriveSim.Segment(45.0, 12.0, 0.0),
    )

    private data class Result(val errors: List<Double>, val finalError: Double, val distance: Double)

    /** GNSS доступен первые 60 с; затем пропадание до конца маршрута (120 с). */
    private fun run(useVisual: Boolean): Result {
        val samples = DriveSim(seed = 7).run(route)
        val ekf = Ekf2d()
        val yaw = YawRate().also { it.onAccel(0f, 0f, 9.81f) }
        val outageStart = 60.0
        var lastT = 0.0
        val errors = mutableListOf<Double>()
        var distance = 0.0
        var prev: DriveSim.Sample? = null
        for (s in samples) {
            val gnss = s.gnss
            if (!ekf.initialized) {
                if (gnss != null) ekf.init(gnss[0], gnss[1], gnss[3], gnss[2], 3.0, 0.05, 0.5)
                lastT = s.tS; prev = s
                continue
            }
            ekf.predict(s.tS - lastT, yaw.headingRate(0f, 0f, s.gyroZ.toFloat())!!)
            lastT = s.tS
            val inOutage = s.tS >= outageStart
            if (!inOutage && gnss != null) {
                ekf.updatePosition(gnss[0], gnss[1], 3.0)
                ekf.updateSpeed(gnss[2], 0.3)
                ekf.updateHeading(gnss[3], Math.toRadians(3.0))
            }
            val vis = s.vis
            if (inOutage && useVisual && vis != null) ekf.updatePosition(vis[0], vis[1], 8.0)
            if (inOutage) {
                distance += hypot(s.e - prev!!.e, s.n - prev.n)
                if (vis != null) errors.add(hypot(ekf.x[0] - s.e, ekf.x[1] - s.n))
            }
            prev = s
        }
        val last = samples.last()
        return Result(errors, hypot(ekf.x[0] - last.e, ekf.x[1] - last.n), distance)
    }

    private fun quantile(xs: List<Double>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test fun deadReckoningDriftBelowThreePercent() { // NFR-5
        val r = run(useVisual = false)
        val driftPct = r.finalError / r.distance * 100
        assertTrue(driftPct <= 3.0, "drift $driftPct % over ${r.distance} m")
    }

    @Test fun visualFixesMeetNfr1() { // NFR-1
        val r = run(useVisual = true)
        val p50 = quantile(r.errors, 0.5); val p95 = quantile(r.errors, 0.95)
        assertTrue(p50 <= 5.0 && p95 <= 15.0, "P50=$p50 P95=$p95")
    }
}
```

- [ ] **Step 3: Запустить**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, 60 тестов.

Если какой-то из двух тестов не проходит, пороги NFR менять нельзя. Допустимо подобрать значения в `FilterConfig` (шумы процесса), которые передаются в `Ekf2d(...)` этого теста, и тогда описать подобранные значения в отчёте. Если не помогает — сообщить NEEDS_CONTEXT с полученными числами.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src/test
git commit -m "test(android): simulated drive checks filter against NFR-1 and NFR-5

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Replay-модуль

**Files:**
- Modify: `android/settings.gradle.kts` (`include(":core", ":app", ":replay")`)
- Create: `android/replay/build.gradle.kts`
- Create: `android/replay/src/main/kotlin/io/visnav/replay/SessionData.kt`
- Create: `android/replay/src/main/kotlin/io/visnav/replay/Replayer.kt`
- Create: `android/replay/src/main/kotlin/io/visnav/replay/TrajectoryWriter.kt`
- Create: `android/replay/src/main/kotlin/io/visnav/replay/Main.kt`
- Test: `android/replay/src/test/kotlin/io/visnav/replay/ReplayerTest.kt`

**Interfaces:**
- Consumes: `RefPack`, `RefPackMeta`, `GeoIndex`, `FrameRecord`, `SessionHeader`, `readSensorLog` и классы событий, `DescriptorLog`, `Enu`, `Ekf2d`, `FilterConfig`, `YawRate`, `StationaryDetector`, `Geo`.
- Produces:
  - `SessionData(header: SessionHeader, frames: List<FrameRecord>, sensors: List<SensorEvent>, descriptors: DescriptorLog)` и `SessionData.load(prefix: File)`. `prefix` — путь без расширения; читаются `.jsonl`, `.sensors.jsonl` и `.desc`.
  - `data class Outage(val startMs: Long, val endMs: Long)`
  - `data class ReplayConfig(visual: Boolean = true, acceptSim: Float = 0.5f, minRadiusM: Double = 100.0, maxRadiusM: Double = 3000.0, k: Int = 5, filter: FilterConfig = FilterConfig())`
  - `data class TrajPoint(tMs: Long, lat: Double, lon: Double, sigmaM: Double, inOutage: Boolean, visSim: Float?, visAccepted: Boolean?)`
  - `class Replayer(pack: RefPack, config: ReplayConfig)` с методом `run(session: SessionData, outages: List<Outage>): List<TrajPoint>` — одна точка на кадр после инициализации фильтра.
  - `object TrajectoryWriter { fun write(file, session, config, outages, points) }` — JSONL. Заголовок: `{"type":"replay","visual":…,"outages":[[start_ms,end_ms],…],"session_started_ms":…,"refpack_created_at":…}`. Точки: `{"t_ms":…,"lat":…,"lon":…,"sigma_m":…,"outage":…,"vis_sim":…|null,"vis_ok":…|null}`.
  - CLI: `./gradlew :replay:run --args="--session <prefix> --refpack <dir> --out <file> [--outage START_S:LEN_S]... [--no-visual]"`. Времена пропаданий отсчитываются в секундах от `started_ms` сессии.

Алгоритм `Replayer.run`:
1. Объединить события датчиков и кадры в одну ленту по времени (кадр — `frame.tMs.toDouble()`).
2. `GyroEvent`:
   - если фильтр инициализирован — `predict(t − lastT, ω)`, где `ω = yaw.headingRate(...) ?: 0.0`;
   - `lastT = t`, `lastOmega = ω`;
   - `stationary.onGyro`.
3. `AccelEvent`:
   - `yaw.onAccel` и `stationary.onAccel`;
   - если фильтр инициализирован, машина стоит и с прошлого ZUPT прошло ≥ 100 мс — `updateSpeed(0.0, 0.05)`.
4. `LocEvent` вне пропадания:
   - Если фильтр не инициализирован: при `spd ≥ 3` и известном `brg` создаётся `Enu(lat, lon)`, выполняется `init(0, 0, brg в радианах, spd, max(acc, 3), brg_acc в радианах ?: 10°, spd_acc ?: 1)` и `lastT = t`.
   - Иначе: `predict(t − lastT, lastOmega)`, `lastT = t`, `updatePosition(e, n, max(acc, 3))`. Если известна `spd` — `updateSpeed(spd, spd_acc ?: 0.5)`. Если известен `brg` и `spd ≥ 3` — `updateHeading(brg в радианах, brg_acc в радианах ?: 5°)`.
   - Во время пропадания `LocEvent` игнорируется.
5. Кадр (если фильтр инициализирован):
   - `predict` до `t` и оценка позиции `lat/lon`;
   - если `visual` и есть дескриптор (`descriptors.indexOf(tMs)`): поиск `k` эталонов в окне с центром в оценке и радиусом `clamp(3·posSigma, minRadiusM, maxRadiusM)`;
   - если у лучшего эталона `sim ≥ acceptSim` — `updatePosition` в его координаты с σ = 8 м при `sim ≥ 0.7` и 15 м иначе;
   - `visAccepted` = результат обновления (false, если отвергнуто χ²);
   - точка записывается после обновления.

- [ ] **Step 1: Модуль и падающий тест**

`android/settings.gradle.kts`: заменить строку `include(":core", ":app")` на `include(":core", ":app", ":replay")`.

`android/replay/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("io.visnav.replay.MainKt") }

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
```

`android/replay/src/test/kotlin/io/visnav/replay/ReplayerTest.kt`:
```kotlin
package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.DescriptorLog
import io.visnav.core.DescriptorLogWriter
import io.visnav.core.Enu
import io.visnav.core.FrameRecord
import io.visnav.core.Geo
import io.visnav.core.GyroEvent
import io.visnav.core.Half
import io.visnav.core.LatencyJson
import io.visnav.core.LocEvent
import io.visnav.core.RefPack
import io.visnav.core.SensorEvent
import io.visnav.core.SessionHeader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Синтетическая сессия: машина едет на восток 10 м/с 120 с; эталон каждые 10 м вдоль пути с
 * дескриптором-«отпечатком» своей позиции; кадры 2 Гц несут дескриптор ближайшего эталона.
 */
class ReplayerTest {
    private val enu = Enu(55.75, 37.60)
    private val n = 120
    private val dim = n + 1

    private fun oneHot(i: Int) = FloatArray(dim).also { it[i] = 1f }

    private fun pack(): RefPack {
        val count = n + 1
        val lats = DoubleArray(count); val lons = DoubleArray(count)
        val desc = ShortArray(count * dim)
        for (i in 0 until count) {
            val ll = enu.toLatLon(i * 10.0, 0.0); lats[i] = ll[0]; lons[i] = ll[1]
            desc[i * dim + i] = Half.fromFloat(1f)
        }
        return RefPack(count, dim, lats, lons, FloatArray(count), desc)
    }

    private fun session(dir: File): SessionData {
        val t0 = 1_700_000_000_000L
        val sensors = mutableListOf<SensorEvent>()
        val frames = mutableListOf<FrameRecord>()
        val descFile = File(dir, "s.desc")
        DescriptorLogWriter(descFile, dim).use { w ->
            for (step in 0..12_000) { // 100 Гц × 120 с
                val tMs = t0 + step * 10.0
                sensors += AccelEvent(tMs, 0f, 0f, 9.81f + 0.3f * kotlin.math.sin(step.toFloat())) // вибрация едущей машины
                sensors += GyroEvent(tMs, 0f, 0f, 0f)
                val eMeters = step * 0.1
                if (step % 100 == 0) {
                    val ll = enu.toLatLon(eMeters, 0.0)
                    sensors += LocEvent(tMs, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f)
                }
                if (step % 50 == 0) {
                    val t = t0 + step * 10L
                    frames += FrameRecord(tMs = t, mode = "gps", gps = null, prior = null, top = emptyList(),
                        fix = null, latMs = LatencyJson(0.0, 0.0, 0.0))
                    w.write(t, oneHot(minOf(n, (eMeters / 10.0).toInt())))
                }
            }
        }
        val header = SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = t0, mode = "gps")
        return SessionData(header, frames, sensors.sortedBy { it.tMs }, DescriptorLog.read(descFile))
    }

    private fun truthErrorM(p: TrajPoint, t0: Long): Double {
        val e = (p.tMs - t0) / 1000.0 * 10.0
        val ll = enu.toLatLon(e, 0.0)
        return Geo.haversineM(ll[0], ll[1], p.lat, p.lon)
    }

    @Test fun visualReplayTracksThroughGpsOutage() {
        val dir = createTempDir()
        val s = session(dir)
        val t0 = s.header.startedMs
        val outage = Outage(t0 + 30_000, t0 + 110_000)
        val points = Replayer(pack(), ReplayConfig()).run(s, listOf(outage))
        val inOutage = points.filter { it.inOutage }
        assertTrue(inOutage.size > 100)
        val errors = inOutage.map { truthErrorM(it, t0) }.sorted()
        assertTrue(errors[errors.size * 95 / 100] <= 15.0, "P95=${errors[errors.size * 95 / 100]}")
        assertTrue(inOutage.count { it.visAccepted == true } > inOutage.size / 2)
    }

    @Test fun deadReckoningWithoutVisualKeepsHeadingOnStraightRoad() {
        val dir = createTempDir()
        val s = session(dir)
        val t0 = s.header.startedMs
        val points = Replayer(pack(), ReplayConfig(visual = false)).run(s, listOf(Outage(t0 + 30_000, t0 + 110_000)))
        val last = points.last { it.inOutage }
        val dist = (last.tMs - (t0 + 30_000)) / 1000.0 * 10.0
        assertTrue(truthErrorM(last, t0) / dist * 100 <= 3.0)
        assertTrue(points.filter { it.inOutage }.all { it.visSim == null })
    }

    @Test fun loadsSessionFilesAndWritesTrajectory() {
        val dir = createTempDir()
        val prefix = File(dir, "session-1-gps")
        File("$prefix.jsonl").writeText(
            io.visnav.core.LogJson.line(SessionHeader(model = "m", refpackCreatedAt = "c", device = "d", startedMs = 1, mode = "gps")) + "\n" +
                io.visnav.core.LogJson.line(FrameRecord(tMs = 5, mode = "gps", gps = null, prior = null, top = emptyList(), fix = null, latMs = LatencyJson(0.0, 0.0, 0.0))) + "\n"
        )
        io.visnav.core.SensorLogger(File("$prefix.sensors.jsonl")).use { it.header(1); it.event(GyroEvent(2.0, 0f, 0f, 0f)) }
        DescriptorLogWriter(File("$prefix.desc"), 2).use { it.write(5, floatArrayOf(1f, 0f)) }
        val s = SessionData.load(prefix)
        assertEquals(1, s.frames.size); assertEquals(1, s.sensors.size); assertEquals(1, s.descriptors.times.size)

        val out = File(dir, "traj.jsonl")
        TrajectoryWriter.write(out, s, ReplayConfig(), listOf(Outage(10, 20)),
            listOf(TrajPoint(5, 55.75, 37.6, 4.0, false, null, null)))
        val lines = out.readLines()
        assertEquals("{\"type\":\"replay\",\"visual\":true,\"outages\":[[10,20]],\"session_started_ms\":1,\"refpack_created_at\":\"c\"}", lines[0])
        assertEquals("{\"t_ms\":5,\"lat\":55.75,\"lon\":37.6,\"sigma_m\":4.0,\"outage\":false,\"vis_sim\":null,\"vis_ok\":null}", lines[1])
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("replay").toFile()
}
```

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :replay:test`
Expected: FAIL при компиляции (`Unresolved reference: SessionData`).

- [ ] **Step 2: Реализовать `SessionData.kt`**

```kotlin
package io.visnav.replay

import io.visnav.core.DescriptorLog
import io.visnav.core.FrameRecord
import io.visnav.core.SensorEvent
import io.visnav.core.SessionHeader
import io.visnav.core.readSensorLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionData(
    val header: SessionHeader,
    val frames: List<FrameRecord>,
    val sensors: List<SensorEvent>,
    val descriptors: DescriptorLog,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** prefix — путь без расширения: <prefix>.jsonl, <prefix>.sensors.jsonl, <prefix>.desc. */
        fun load(prefix: File): SessionData {
            val framesFile = File("$prefix.jsonl")
            val lines = framesFile.readLines().filter { it.isNotBlank() }
            var header: SessionHeader? = null
            val frames = ArrayList<FrameRecord>()
            for ((i, line) in lines.withIndex()) {
                val parsed = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                if (parsed == null) {
                    if (i == lines.lastIndex) break // обрезанная последняя строка
                    throw IllegalArgumentException("${framesFile.name}:${i + 1}: invalid JSON")
                }
                when (parsed["type"]?.jsonPrimitive?.content) {
                    "session" -> header = json.decodeFromString(SessionHeader.serializer(), line)
                    "frame" -> frames.add(json.decodeFromString(FrameRecord.serializer(), line))
                }
            }
            return SessionData(
                requireNotNull(header) { "${framesFile.name}: no session header" },
                frames,
                readSensorLog(File("$prefix.sensors.jsonl")),
                DescriptorLog.read(File("$prefix.desc")),
            )
        }
    }
}
```

- [ ] **Step 3: Реализовать `Replayer.kt`**

```kotlin
package io.visnav.replay

import io.visnav.core.AccelEvent
import io.visnav.core.Ekf2d
import io.visnav.core.Enu
import io.visnav.core.FilterConfig
import io.visnav.core.GeoIndex
import io.visnav.core.GnssStatusEvent
import io.visnav.core.GyroEvent
import io.visnav.core.LocEvent
import io.visnav.core.RefPack
import io.visnav.core.StationaryDetector
import io.visnav.core.YawRate
import kotlin.math.max

data class Outage(val startMs: Long, val endMs: Long) {
    fun contains(tMs: Double) = tMs >= startMs && tMs < endMs
}

data class ReplayConfig(
    val visual: Boolean = true,
    val acceptSim: Float = 0.5f,
    val minRadiusM: Double = 100.0,
    val maxRadiusM: Double = 3000.0,
    val k: Int = 5,
    val filter: FilterConfig = FilterConfig(),
)

data class TrajPoint(
    val tMs: Long, val lat: Double, val lon: Double, val sigmaM: Double,
    val inOutage: Boolean, val visSim: Float?, val visAccepted: Boolean?,
)

/** Прогон записанной сессии через фильтр с искусственными пропаданиями GPS. Алгоритм — в плане M2a, Task 7. */
class Replayer(private val pack: RefPack, private val config: ReplayConfig) {
    private val index = GeoIndex(pack)

    fun run(session: SessionData, outages: List<Outage>): List<TrajPoint> {
        val ekf = Ekf2d(config.filter)
        val yaw = YawRate()
        val stationary = StationaryDetector()
        var enu: Enu? = null
        var lastT = 0.0
        var lastOmega = 0.0
        var lastZupt = Double.NEGATIVE_INFINITY
        val out = ArrayList<TrajPoint>()

        fun inOutage(t: Double) = outages.any { it.contains(t) }
        fun predictTo(t: Double) { if (ekf.initialized && t > lastT) { ekf.predict((t - lastT) / 1000.0, lastOmega); lastT = t } }

        val timeline: List<Pair<Double, Any>> =
            (session.sensors.map { it.tMs to (it as Any) } + session.frames.map { it.tMs.toDouble() to (it as Any) })
                .sortedBy { it.first }

        for ((t, ev) in timeline) {
            when (ev) {
                is GyroEvent -> {
                    val omega = yaw.headingRate(ev.x, ev.y, ev.z) ?: 0.0
                    predictTo(t)
                    lastOmega = omega
                    stationary.onGyro(t, ev.x, ev.y, ev.z)
                }
                is AccelEvent -> {
                    yaw.onAccel(ev.x, ev.y, ev.z)
                    stationary.onAccel(t, ev.x, ev.y, ev.z)
                    if (ekf.initialized && t - lastZupt >= 100.0 && stationary.isStationary(t)) {
                        predictTo(t); ekf.updateSpeed(0.0, 0.05); lastZupt = t
                    }
                }
                is LocEvent -> {
                    if (inOutage(t)) continue
                    val spd = ev.speedMps; val brg = ev.bearingDeg
                    if (!ekf.initialized) {
                        if (spd != null && spd >= 3f && brg != null) {
                            enu = Enu(ev.lat, ev.lon)
                            ekf.init(0.0, 0.0, Math.toRadians(brg.toDouble()), spd.toDouble(),
                                max(ev.accM.toDouble(), 3.0),
                                Math.toRadians((ev.bearingAccDeg ?: 10f).toDouble()),
                                (ev.speedAccMps ?: 1f).toDouble())
                            lastT = t
                        }
                        continue
                    }
                    predictTo(t)
                    val en = enu!!.toEn(ev.lat, ev.lon)
                    ekf.updatePosition(en[0], en[1], max(ev.accM.toDouble(), 3.0))
                    if (spd != null) ekf.updateSpeed(spd.toDouble(), (ev.speedAccMps ?: 0.5f).toDouble())
                    if (brg != null && spd != null && spd >= 3f) {
                        ekf.updateHeading(Math.toRadians(brg.toDouble()), Math.toRadians((ev.bearingAccDeg ?: 5f).toDouble()))
                    }
                }
                is GnssStatusEvent -> Unit // используется в M2c
                is io.visnav.core.FrameRecord -> {
                    if (!ekf.initialized) continue
                    predictTo(t)
                    var visSim: Float? = null
                    var visOk: Boolean? = null
                    val di = session.descriptors.indexOf(ev.tMs)
                    if (config.visual && di >= 0) {
                        val center = enu!!.toLatLon(ekf.x[0], ekf.x[1])
                        val radius = (3 * ekf.posSigma()).coerceIn(config.minRadiusM, config.maxRadiusM)
                        val best = index.search(session.descriptors.descriptor(di), config.k, center[0], center[1], radius).firstOrNull()
                        if (best != null) {
                            visSim = best.sim
                            if (best.sim >= config.acceptSim) {
                                val en = enu!!.toEn(pack.lats[best.index], pack.lons[best.index])
                                visOk = ekf.updatePosition(en[0], en[1], if (best.sim >= 0.7f) 8.0 else 15.0)
                            } else {
                                visOk = false
                            }
                        }
                    }
                    val ll = enu!!.toLatLon(ekf.x[0], ekf.x[1])
                    out.add(TrajPoint(ev.tMs, ll[0], ll[1], ekf.posSigma(), inOutage(t), visSim, visOk))
                }
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Реализовать `TrajectoryWriter.kt` и `Main.kt`**

`TrajectoryWriter.kt`:
```kotlin
package io.visnav.replay

import java.io.File
import kotlinx.serialization.json.JsonPrimitive

object TrajectoryWriter {
    fun write(file: File, session: SessionData, config: ReplayConfig, outages: List<Outage>, points: List<TrajPoint>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            val outageJson = outages.joinToString(",") { "[${it.startMs},${it.endMs}]" }
            w.write("{\"type\":\"replay\",\"visual\":${config.visual},\"outages\":[$outageJson]," +
                "\"session_started_ms\":${session.header.startedMs}," +
                "\"refpack_created_at\":${JsonPrimitive(session.header.refpackCreatedAt)}}")
            w.newLine()
            for (p in points) {
                w.write("{\"t_ms\":${p.tMs},\"lat\":${p.lat},\"lon\":${p.lon},\"sigma_m\":${p.sigmaM}," +
                    "\"outage\":${p.inOutage},\"vis_sim\":${p.visSim},\"vis_ok\":${p.visAccepted}}")
                w.newLine()
            }
        }
    }
}
```

`Main.kt`:
```kotlin
package io.visnav.replay

import io.visnav.core.RefPack
import io.visnav.core.RefPackMeta
import java.io.File
import java.nio.ByteBuffer
import kotlin.system.exitProcess

private const val USAGE =
    "usage: replay --session <prefix> --refpack <dir> --out <file> [--outage START_S:LEN_S]... [--no-visual]"

fun main(args: Array<String>) {
    var session: String? = null; var refpack: String? = null; var out: String? = null
    var visual = true
    val outageSpecs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--session" -> session = args.getOrNull(++i)
            "--refpack" -> refpack = args.getOrNull(++i)
            "--out" -> out = args.getOrNull(++i)
            "--outage" -> outageSpecs += args.getOrNull(++i) ?: fail("--outage needs START_S:LEN_S")
            "--no-visual" -> visual = false
            else -> fail("unknown argument ${args[i]}")
        }
        i++
    }
    if (session == null || refpack == null || out == null) fail("missing required argument")

    val data = SessionData.load(File(session))
    val meta = RefPackMeta.parse(File(refpack, "refpack.json").readText())
    if (meta.createdAt != data.header.refpackCreatedAt) {
        fail("refpack created_at ${meta.createdAt} != session ${data.header.refpackCreatedAt}")
    }
    val pack = RefPack.parse(ByteBuffer.wrap(File(refpack, "refpack.bin").readBytes()))
    val t0 = data.header.startedMs
    val outages = outageSpecs.map { spec ->
        val (start, len) = spec.split(":").map { it.toDoubleOrNull() ?: fail("bad --outage $spec") }
        if (start < 0 || len <= 0) fail("bad --outage $spec")
        Outage(t0 + (start * 1000).toLong(), t0 + ((start + len) * 1000).toLong())
    }
    val config = ReplayConfig(visual = visual)
    val points = Replayer(pack, config).run(data, outages)
    TrajectoryWriter.write(File(out), data, config, outages, points)
    println("${points.size} points (${points.count { it.inOutage }} in outages) -> $out")
}

private fun fail(message: String): Nothing {
    System.err.println("error: $message\n$USAGE")
    exitProcess(2)
}
```

- [ ] **Step 5: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew :core:test :replay:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`: `:core` — 60 тестов, `:replay` — 3.

Если `visualReplayTracksThroughGpsOutage` или `deadReckoningWithoutVisualKeepsHeadingOnStraightRoad` не проходит, пороги не менять, а сообщить NEEDS_CONTEXT с полученными ошибками.

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/settings.gradle.kts android/replay
git commit -m "feat(replay): JVM replay of recorded sessions through the filter with simulated GPS outages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Оценка replay по NFR-1 и NFR-5 (`vpr-m2 replay-eval`)

**Files:**
- Modify: `research/vpr_bench/pyproject.toml` (`vpr-m2 = "vpr_bench.m2cli:main"`)
- Create: `research/vpr_bench/src/vpr_bench/replayeval.py`
- Create: `research/vpr_bench/src/vpr_bench/m2cli.py`
- Test: `research/vpr_bench/tests/test_replayeval.py`

**Interfaces:**
- Consumes: `fieldlog.read_log`, `fieldlog.gps_track`, `query.clean_track`, `query.has_gap`, `query.pose_at`, `geo.interpolate_track`, `geo.haversine_m`.
- Produces:
  - `read_trajectory(path) -> tuple[dict, list[TrajRow]]`, где `TrajRow(t_ms: int, lat: float, lon: float, sigma_m: float, outage: bool)`.
  - `OutageResult(start_ms, end_ms, n_points, distance_m, final_err_m, drift_pct)`.
  - `ReplayResult(visual: bool, n_points: int, p50_m: float, p95_m: float, outages: list[OutageResult])`.
  - `evaluate_replay(header, rows, log_frames, min_speed_mps=2.0, max_gap_s=3.0, min_outage_dist_m=200.0) -> ReplayResult`.
  - `render_replay_report(result) -> str`. Режим с визуальными фиксациями проверяет NFR-1 (P50 ≤ 5 м и P95 ≤ 15 м), режим без них — NFR-5 (максимальный `drift_pct` среди пропаданий с `distance_m ≥ 200` не больше 3 %).
  - `vpr-m2 replay-eval --traj FILE --log SESSION.jsonl --out REPORT.md`.

Определения:
- Истинная позиция — `interpolate_track(clean_track(gps_track(frames))[0], t)`. Точка без истинной позиции пропускается, как и точка с разрывом (`has_gap`) или со скоростью ниже `min_speed_mps` по `pose_at`.
- `p50_m` и `p95_m` считаются по ошибкам всех учитываемых точек внутри пропаданий, через `np.quantile(..., method="higher")`.
- Для каждого пропадания:
  - `distance_m` — сумма расстояний между истинными позициями соседних учитываемых точек;
  - `final_err_m` — ошибка последней учитываемой точки;
  - `drift_pct = final_err_m / distance_m * 100` (NaN, если `distance_m == 0`).

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_replayeval.py`:
```python
import json

import pytest

from vpr_bench.fieldlog import FieldFrame
from vpr_bench.geo import offset_m
from vpr_bench.m2cli import main
from vpr_bench.replayeval import evaluate_replay, read_trajectory, render_replay_report

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6


def _frames(n=120):
    """Едем на север 10 м/с, GPS каждую секунду."""
    out = []
    for s in range(n):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        out.append(FieldFrame(T0 + 1000 * s, "gps", (lat, lon, 4.0, T0 + 1000 * s), None,
                              {"pre": 0.0, "inf": 0.0, "search": 0.0}))
    return out


def _traj(tmp_path, err_m, visual=True, outage=(30, 90)):
    header = {"type": "replay", "visual": visual, "outages": [[T0 + outage[0] * 1000, T0 + outage[1] * 1000]],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(1, 119):
        lat, lon = offset_m(LAT0, LON0, err_m(s), 10.0 * s)
        lines.append(json.dumps({"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0,
                                 "outage": outage[0] <= s < outage[1], "vis_sim": None, "vis_ok": None}))
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")
    return p


def test_nfr1_pass_with_small_errors(tmp_path):
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 4.0))
    r = evaluate_replay(header, rows, _frames())
    assert r.visual and r.n_points == 60
    assert r.p50_m == pytest.approx(4.0, abs=0.1)
    assert "✅" in render_replay_report(r)


def test_nfr1_fail_with_large_errors(tmp_path):
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 20.0))
    r = evaluate_replay(header, rows, _frames())
    assert r.p95_m > 15.0
    assert "❌" in render_replay_report(r)


def test_nfr5_drift_percent(tmp_path):
    # ошибка растёт линейно: 0.2 м на секунду пропадания → 2 % от 10 м/с
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 0.2 * max(0, s - 30), visual=False))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.distance_m == pytest.approx(590.0, rel=0.01)  # 59 интервалов по 10 м
    assert o.drift_pct == pytest.approx(2.0, rel=0.05)
    report = render_replay_report(r)
    assert "NFR-5" in report and "✅" in report


def test_cli_writes_report(tmp_path):
    traj = _traj(tmp_path, lambda s: 3.0)
    log = tmp_path / "s.jsonl"
    lines = [json.dumps({"v": 1, "type": "session", "model": "m", "refpack_created_at": "c", "device": "d",
                         "started_ms": T0, "mode": "gps"})]
    for f in _frames():
        lines.append(json.dumps({"v": 1, "type": "frame", "t_ms": f.t_ms, "mode": "gps",
                                 "gps": {"lat": f.gps[0], "lon": f.gps[1], "acc_m": 4.0, "t_ms": f.gps[3]},
                                 "prior": None, "top": [], "fix": None,
                                 "lat_ms": {"pre": 0.0, "inf": 0.0, "search": 0.0}}))
    log.write_text("\n".join(lines) + "\n")
    out = tmp_path / "r.md"
    assert main(["replay-eval", "--traj", str(traj), "--log", str(log), "--out", str(out)]) == 0
    assert "NFR-1" in out.read_text()
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `cd /Users/vvnovg/navigator/research/vpr_bench && uv run pytest tests/test_replayeval.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.replayeval'`.

- [ ] **Step 3: Реализовать**

`research/vpr_bench/pyproject.toml`, в `[project.scripts]`:
```toml
vpr-m2 = "vpr_bench.m2cli:main"
```

`research/vpr_bench/src/vpr_bench/replayeval.py`:
```python
"""Оценка replay-траектории (M2a) против отфильтрованного GPS той же поездки: NFR-1 и NFR-5."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import haversine_m, interpolate_track
from vpr_bench.query import clean_track, has_gap, pose_at


@dataclass(frozen=True)
class TrajRow:
    t_ms: int
    lat: float
    lon: float
    sigma_m: float
    outage: bool


@dataclass(frozen=True)
class OutageResult:
    start_ms: int
    end_ms: int
    n_points: int
    distance_m: float
    final_err_m: float
    drift_pct: float


@dataclass(frozen=True)
class ReplayResult:
    visual: bool
    n_points: int
    p50_m: float
    p95_m: float
    outages: list[OutageResult]


def read_trajectory(path: Path) -> tuple[dict, list[TrajRow]]:
    lines = [l for l in path.read_text().splitlines() if l.strip()]
    header = json.loads(lines[0])
    if header.get("type") != "replay":
        raise ValueError(f"{path}: not a replay trajectory")
    rows = [
        TrajRow(int(r["t_ms"]), r["lat"], r["lon"], r["sigma_m"], bool(r["outage"]))
        for r in map(json.loads, lines[1:])
    ]
    return header, rows


def evaluate_replay(
    header: dict,
    rows: list[TrajRow],
    log_frames: list[FieldFrame],
    min_speed_mps: float = 2.0,
    max_gap_s: float = 3.0,
    min_outage_dist_m: float = 200.0,
) -> ReplayResult:
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    times = [p.t for p in track]
    all_errors: list[float] = []
    outages: list[OutageResult] = []
    for start_ms, end_ms in header["outages"]:
        errors: list[float] = []
        gts: list[tuple[float, float]] = []
        for r in rows:
            if not (start_ms <= r.t_ms < end_ms):
                continue
            t = r.t_ms / 1000.0
            gt = interpolate_track(track, t)
            pose = pose_at(track, t)
            if gt is None or pose is None or has_gap(times, t, max_gap_s) or pose[3] < min_speed_mps:
                continue
            errors.append(haversine_m(gt[0], gt[1], r.lat, r.lon))
            gts.append(gt)
        distance = sum(haversine_m(*a, *b) for a, b in zip(gts, gts[1:]))
        final = errors[-1] if errors else float("nan")
        drift = final / distance * 100 if distance > 0 else float("nan")
        outages.append(OutageResult(start_ms, end_ms, len(errors), distance, final, drift))
        all_errors.extend(errors)
    arr = np.array(all_errors) if all_errors else np.array([float("nan")])
    return ReplayResult(
        visual=bool(header["visual"]),
        n_points=len(all_errors),
        p50_m=float(np.quantile(arr, 0.5, method="higher")),
        p95_m=float(np.quantile(arr, 0.95, method="higher")),
        outages=outages,
    )


def render_replay_report(r: ReplayResult, min_outage_dist_m: float = 200.0) -> str:
    lines = ["# Replay M2a", ""]
    if r.visual:
        ok = r.p50_m <= 5.0 and r.p95_m <= 15.0
        lines += [
            f"Режим: визуальные фиксации. NFR-1: P50 ≤ 5 м, P95 ≤ 15 м — "
            f"P50 = {r.p50_m:.1f} м, P95 = {r.p95_m:.1f} м, точек {r.n_points} {'✅' if ok else '❌'}",
        ]
    else:
        long = [o for o in r.outages if o.distance_m >= min_outage_dist_m and not math.isnan(o.drift_pct)]
        worst = max((o.drift_pct for o in long), default=float("nan"))
        ok = bool(long) and worst <= 3.0
        lines += [
            f"Режим: счисление пути без визуальных фиксаций. NFR-5: дрейф ≤ 3 % пути — "
            f"худший {worst:.2f} % по {len(long)} пропаданиям ≥ {min_outage_dist_m:.0f} м {'✅' if ok else '❌'}",
        ]
    lines += [
        "",
        "| Пропадание, с | Точек | Путь, м | Ошибка в конце, м | Дрейф, % |",
        "|---|---|---|---|---|",
    ]
    for o in r.outages:
        lines.append(
            f"| {(o.end_ms - o.start_ms) / 1000:.0f} | {o.n_points} | {o.distance_m:.0f} "
            f"| {o.final_err_m:.1f} | {o.drift_pct:.2f} |"
        )
    return "\n".join(lines) + "\n"
```

`research/vpr_bench/src/vpr_bench/m2cli.py`:
```python
"""CLI этапа M2: vpr-m2 replay-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from vpr_bench.fieldlog import read_log
from vpr_bench.replayeval import evaluate_replay, read_trajectory, render_replay_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m2")
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("replay-eval", help="оценить replay-траекторию по NFR-1/NFR-5")
    r.add_argument("--traj", required=True, type=Path)
    r.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    r.add_argument("--out", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    header, rows = read_trajectory(args.traj)
    log_header, frames = read_log(args.log)
    if log_header.get("started_ms") != header.get("session_started_ms"):
        print("error: trajectory and log are from different sessions", file=sys.stderr)
        return 2
    result = evaluate_replay(header, rows, frames)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render_replay_report(result))
    print(f"P50={result.p50_m:.1f} m P95={result.p95_m:.1f} m over {result.n_points} points -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `cd /Users/vvnovg/navigator/research/vpr_bench && uv sync --extra dev && uv run pytest -q`
Expected: 147 passed, 4 deselected.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/pyproject.toml research/vpr_bench/uv.lock research/vpr_bench/src/vpr_bench/replayeval.py research/vpr_bench/src/vpr_bench/m2cli.py research/vpr_bench/tests/test_replayeval.py
git commit -m "feat(vpr-bench): replay-eval for NFR-1 and NFR-5 against recorded GPS

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Протокол replay и обновление спецификации

**Files:**
- Create: `docs/research/m2a-replay.md`
- Modify: `docs/SPEC.md` (строка M2 в таблице этапов раздела 9)

- [ ] **Step 1: Протокол**

`docs/research/m2a-replay.md`:
````markdown
# Replay M2a: фильтр и пропадания GPS

## Запись
Та же поездка и то же приложение, что в протоколе M1 (docs/research/m1-field-test.md), режим «окно по GPS».
Приложение теперь пишет три файла на сессию в `/sdcard/Android/data/io.visnav.app/files/logs/`:
`session-<ms>-gps.jsonl` (кадры), `.sensors.jsonl` (гироскоп и акселерометр 100 Гц, GNSS со скоростью и курсом,
статус спутников) и `.desc` (дескрипторы кадров, ~30 МБ/ч). Изображения не сохраняются.

Требования: телефон жёстко закреплён (дребезг держателя попадает в гироскоп), поездка ≥ 25 минут по городу
вне зоны подмены GPS и без тоннелей — GPS нужен как истинная позиция на всём пути.

## Пропадания
GPS «выключается» программно на replay. Стандартный набор для сессии ≥ 25 мин (секунды от начала):
`--outage 120:30 --outage 300:60 --outage 600:120 --outage 900:300`.

```bash
cd /Users/vvnovg/navigator/android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
S=/Users/vvnovg/navigator/research/vpr_bench/data/m1/logs/session-<ms>-gps
R=/Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle
O="--outage 120:30 --outage 300:60 --outage 600:120 --outage 900:300"
./gradlew -q :replay:run --args="--session $S --refpack $R --out $S.replay-visual.jsonl $O"
./gradlew -q :replay:run --args="--session $S --refpack $R --out $S.replay-dr.jsonl $O --no-visual"
cd /Users/vvnovg/navigator/research/vpr_bench
uv run vpr-m2 replay-eval --traj $S.replay-visual.jsonl --log $S.jsonl --out data/m2a/replay-visual.md
uv run vpr-m2 replay-eval --traj $S.replay-dr.jsonl --log $S.jsonl --out data/m2a/replay-dr.md
```

## Итоги (заполнить)
- Сессии (дата, маршрут, длительность, телефон): …
- Таблицы из replay-visual.md и replay-dr.md: …
- Параметры фильтра (если меняли FilterConfig): …

## Критерий M2a
- [ ] NFR-1 на replay с визуальными фиксациями: P50 ≤ 5 м, P95 ≤ 15 м — факт: …
- [ ] NFR-5 на replay без визуальных фиксаций: дрейф ≤ 3 % пути — факт: …

## Что передаётся в M2b/M2c
- Эпизоды, где фильтр держался на правильной улице / съезжал на соседнюю (вход для привязки к дорогам, M2b).
- Поведение статуса спутников (`gnss` в .sensors.jsonl) в местах подмены и глушения (вход для монитора GNSS, M2c).
````

- [ ] **Step 2: Спецификация**

В `docs/SPEC.md`, раздел 9, заменить строку этапа M2 на:
```markdown
| **M2. Локализация** (8 нед.) | M2a: запись IMU/GNSS/дескрипторов, EKF, replay-стенд с пропаданиями GPS. M2b: привязка к дорожному графу OSM. M2c: монитор GNSS и переключение режимов | NFR-1, NFR-5 на replay (M2a); NFR-3 на replay (M2b) |
```

- [ ] **Step 3: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m2a-replay.md docs/SPEC.md
git commit -m "docs: M2a replay protocol and split of M2 into M2a/M2b/M2c

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
