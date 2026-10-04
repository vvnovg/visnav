# M3c: пакет коридора для поездки — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Поездку, которая ещё не проехана, можно подготовить на ПК цепочкой команд: маршрут нашим A* → эталоны → один пакет.
- На телефоне может лежать несколько таких пакетов, нужный выбирается в приложении.
- Маршрутизатор не разворачивается у края коридора.

**Architecture:**
- **roadpack v3** — v2 плюс список узлов-краёв коридора (узлы, у которых обрезка отбросила отрезок OSM-линии). `Router` запрещает у них разворот.
- **`pack-roads --bbox`** строит граф всего города. На этом графе `trip-plan` — подкоманда JVM-модуля `:replay` на том же `Router` из `:core` — строит маршрут через промежуточные точки и пишет плотный GPX.
- **`vpr-m3 pack-trip`** собирает каталог `trips/<имя>/` из существующих шагов `pack-refs`, `pack-roads`, `pack-map`, плюс `route.json` и `trip.json`. Модель лежит одна на все поездки, в корне.
- **Приложение** находит поездки в `files/refpack/trips/`, выбирает активную (её имя хранится в `SharedPreferences`) и грузит из её каталога базу, граф, маршрут и карту. Старая раскладка `refpack/` работает без изменений.

**Tech Stack:** Python 3.11+ (numpy, pyosmium extra `osm`), Kotlin 2.1 (JVM `:core`, `:replay`, Android `:app`, Compose), JUnit, pytest.

**Спецификация:** `docs/superpowers/specs/2026-10-04-m3c-trip-pack-design.md`.

## Global Constraints

- **Решения владельца (2026-10-04):**
  - коридор — маршрут на ПК нашим A* (`Router` из `:core`);
  - несколько поездок на телефоне, выбор в приложении;
  - края коридора отмечаются в графе.
- **Не входит в M3c:**
  - отчёт о покрытии эталонами;
  - проверка SHA-256 файлов пакета.
- **roadpack v3:** v2 + `boundaryCount u32` + `boundary i32[boundaryCount]` (индексы узлов, строго по возрастанию). Python пишет v3 по умолчанию (`version=3`), но умеет писать v1 и v2. Kotlin и Python читают v1–v3. `format` в `roadpack.json` — `VNRD/3`.
- **Край коридора** — узел графа, для которого `keep` ложно (внешний конец сохранённого отрезка). Изменено после ревью Task 1: прежнее правило «есть отброшенный отрезок» пропускало узлы, где дорога продолжается только по линии вне bbox чтения. Без `keep` (режим `--bbox`) краёв нет.
- **Router:**
  - разворот на том же ребре разрешён только в узле степени 1, который не является краем;
  - штраф `deadEndUturnPenaltyS = 120` остаётся.
- **Раскладка поездки:**
  - `<root>/model.onnx` — общая модель;
  - `<root>/trips/<имя>/` содержит `refpack.bin`, `refpack.json`, `roadpack.bin`, `roadpack.json`, `map/`, `route.json`, `trip.json`.
  - Имя: `^[A-Za-z0-9_-]{1,40}$`.
  - Поездка на телефоне — подкаталог `trips/`, в котором есть `refpack.bin`.
  - Модель берётся из каталога поездки, если она там есть, иначе из `<root>/model.onnx`.
- **Буферы `pack-trip`** (по умолчанию):
  - эталоны 300 м;
  - дороги 300 м;
  - карта 500 м.
- **GPX от `trip-plan`:**
  - `<gpx version="1.1" creator="visnav trip-plan">`, один `trk`/`trkseg`;
  - расстояние между соседними точками ≤ 25 м;
  - у каждой `trkpt` есть `<time>`: от `2000-01-01T00:00:00Z` по расстоянию со скоростью 10 м/с, в формате `Instant.toString()`. Без времени `parse_gpx` точку отбрасывает.
- **`route.json` поездки:** `{"dest_lat", "dest_lon", "dest_name"}` — последняя точка маршрута и `--dest-name`, иначе имя поездки.
- **`trip.json`:**
  - `name`, `created_at` (UTC, `%Y-%m-%dT%H:%M:%SZ`);
  - `route_km` (3 знака);
  - `model`, `onnx_sha256`;
  - `buffers_m {refs, roads, map}`;
  - `sizes_mb {refs, roads, map}`, `total_mb` (2 знака; `total_mb` не включает модель);
  - `mb_per_100km` (2 знака);
  - `nfr8_target_mb_per_100km: 50`.
- **Без новых зависимостей.** Приложение по-прежнему без интернета. FR-20: кадры не сохраняются. Данные OSM, тайлы, эталоны и пакеты не коммитятся (ODbL, CC BY-SA).
- **Сообщения в приложении** — по-русски; сообщения CLI — как в соседнем коде (`error: …` по-английски, код 2).
- **Сборка и тесты:**
  - `cd /Users/vvnovg/navigator/android && JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" ./gradlew …`
  - `cd /Users/vvnovg/navigator/research/vpr_bench && uv run pytest -q`
  - Сейчас: `:core` 260, `:replay` 38, `:app` 30, pytest 244. Lint — 0 ошибок, 35 предупреждений.

---

### Task 1: roadpack v3 и края коридора (Python)

**Files:**
- Modify: `research/vpr_bench/src/vpr_bench/roadpack.py`
- Modify: `research/vpr_bench/src/vpr_bench/osmgraph.py` (`build_graph`)
- Modify: `research/vpr_bench/src/vpr_bench/m2cli.py` (`pack-roads --bbox`)
- Create: `research/vpr_bench/tests/data/roadpack_v3_fixture/` (`roadpack.bin`, `roadpack.json`)
- Test: `research/vpr_bench/tests/test_roadpack.py`, `tests/test_osmgraph.py`, `tests/test_m2cli.py` (или файл, где сейчас тесты `pack-roads`)

**Interfaces:**
- Produces:
  - `RoadGraph.boundary_nodes: np.ndarray | None = None` — после `__post_init__` это `int32[k]`, строго возрастающие индексы узлов в `[0, n)`;
  - `write_roadpack(out_dir, g, meta, version=3)`;
  - `read_roadpack(dir_)` читает v1–v3;
  - `build_graph(..., keep=...)` заполняет `boundary_nodes`;
  - CLI: `vpr-m2 pack-roads --pbf P (--gpx G… | --log L… | --bbox minlon,minlat,maxlon,maxlat) --out D`;
  - фикстура `tests/data/roadpack_v3_fixture` для Kotlin (Task 2).

- [ ] **Step 1: Тесты.**

```python
# tests/test_osmgraph.py
def test_boundary_nodes_mark_clipped_ways_not_dead_ends():
    # Линия 1: A(0) — B(1) — C(2) — D(3); keep истинно только для A и B. Отрезок C–D отброшен,
    # поэтому C — край. B–C сохранён (B внутри). Линия 2: B — E(5), E внутри, у E продолжения нет — тупик, не край.
    pts = {1: (55.0, 37.0), 2: (55.001, 37.0), 3: (55.002, 37.0), 4: (55.003, 37.0), 5: (55.001, 37.001)}
    w1 = RawWay(10, {"highway": "residential"}, [(i, *pts[i]) for i in (1, 2, 3, 4)])
    w2 = RawWay(11, {"highway": "residential"}, [(2, *pts[2]), (5, *pts[5])])
    inside = {1, 2, 5}
    lookup = {v: k for k, v in pts.items()}
    g = build_graph([w1, w2], keep=lambda la, lo: lookup[(la, lo)] in inside)
    node_c = [i for i in range(len(g.node_lats)) if (g.node_lats[i], g.node_lons[i]) == pts[3]][0]
    assert g.boundary_nodes.tolist() == [node_c]


def test_no_boundary_without_keep():
    w = RawWay(10, {"highway": "residential"}, [(1, 55.0, 37.0), (2, 55.001, 37.0)])
    assert build_graph([w]).boundary_nodes.tolist() == []
```

```python
# tests/test_roadpack.py
FIXTURE_V3_DIR = Path(__file__).parent / "data" / "roadpack_v3_fixture"

def fixture_graph_v3() -> RoadGraph:
    g = fixture_graph_v2()
    return dataclasses.replace(g, boundary_nodes=np.array([0, 2], dtype=np.int32))

def test_v3_roundtrip(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META)
    g, meta = read_roadpack(tmp_path)
    assert meta["format"] == "VNRD/3" and g.boundary_nodes.tolist() == [0, 2]

def test_v2_still_readable_and_has_no_boundary(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META, version=2)
    g, meta = read_roadpack(tmp_path)
    assert meta["format"] == "VNRD/2" and g.boundary_nodes.tolist() == []

def test_v3_fixture_matches_writer(tmp_path):
    # Фикстура для Kotlin: пересоздаётся, если отличается (как roadpack_v2_fixture), и сравнивается побайтно.
    write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META)
    if not FIXTURE_V3_DIR.exists() or (FIXTURE_V3_DIR / "roadpack.bin").read_bytes() != (tmp_path / "roadpack.bin").read_bytes():
        FIXTURE_V3_DIR.mkdir(parents=True, exist_ok=True)
        write_roadpack(FIXTURE_V3_DIR, fixture_graph_v3(), FIXTURE_META)
    assert (FIXTURE_V3_DIR / "roadpack.bin").read_bytes() == (tmp_path / "roadpack.bin").read_bytes()

@pytest.mark.parametrize("bad", [[2, 0], [0, 0], [-1], [99]])
def test_bad_boundary_rejected(bad):
    with pytest.raises(ValueError, match="boundary"):
        dataclasses.replace(fixture_graph_v2(), boundary_nodes=np.array(bad, dtype=np.int32))

def test_v3_truncated_boundary_raises(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META)
    raw = (tmp_path / "roadpack.bin").read_bytes()
    (tmp_path / "roadpack.bin").write_bytes(raw[:-2])
    with pytest.raises(ValueError):
        read_roadpack(tmp_path)

def test_v1_v2_cannot_store_boundary(tmp_path):
    with pytest.raises(ValueError, match="boundary"):
        write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META, version=2)
```

Тест `pack-roads --bbox` (по образцу существующего теста `pack-roads` с файлом `.osm`):
- `--bbox` вокруг всех узлов → `boundary` пуст, `roadpack.json` содержит `"bbox": [..4 числа..]` и `"buffer_m": null`;
- `--bbox` вместе с `--gpx` → код 2;
- ни `--bbox`, ни треков → код 2.

- [ ] **Step 2: RED.** `uv run pytest tests/test_roadpack.py tests/test_osmgraph.py -q` — новые тесты падают.

- [ ] **Step 3: Реализация.**
  - **`RoadGraph`:**
    - поле `boundary_nodes: np.ndarray | None = None`;
    - в `__post_init__` `None` заменяется на `np.zeros(0, np.int32)`;
    - проверки «indices in [0, n)», «strictly increasing» с текстом ошибки, содержащим `boundary`.
  - **`write_roadpack`:**
    - `version in (1, 2, 3)`;
    - для v1/v2 с непустым `boundary_nodes` — `ValueError("roadpack v{version} cannot store boundary nodes")`;
    - v3 = запись v2 плюс `struct.pack("<I", k)` и `boundary_nodes.astype("<i4").tobytes()`;
    - значение по умолчанию `version=3`.
  - **`read_roadpack`:**
    - для v3 после запретов читать `k` и `int32[k]`;
    - проверка хвоста (`trailing bytes`) — после всех секций;
    - короткий файл даёт `ValueError` (обернуть `struct.error`).
  - **`build_graph`:**
    - в цикле по отрезкам: если `a[0] != b[0]` и `not (inside(a) or inside(b))`, запомнить `a[0]` и `b[0]` в `dropped_ends`;
    - в конце `boundary = sorted({index[i] for i in dropped_ends if i in index})`;
    - передать `boundary_nodes=np.array(boundary, dtype=np.int32)`.
  - **`m2cli`:**
    - `pr.add_argument("--bbox", default=None, help="min_lon,min_lat,max_lon,max_lat — граф всего района без обрезки по треку")`;
    - если `--bbox`, то треки запрещены (код 2); `read_osm(args.pbf, BBox.parse(args.bbox))`, `build_graph(ways, keep=None, restrictions=rs)`;
    - в meta `"bbox": [min_lon, min_lat, max_lon, max_lat]`, `"buffer_m": None`;
    - иначе как сейчас, плюс `"boundary_nodes": len(graph.boundary_nodes)` в meta;
    - печать: `nodes=… edges=… boundary=… -> out`.
  - Обновить docstring модуля `roadpack.py` (v3).

- [ ] **Step 4: GREEN.** `uv run pytest -q` — все тесты проходят, фикстура `roadpack_v3_fixture` создана.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src research/vpr_bench/tests
git commit -m "feat(roads): roadpack v3 with corridor boundary nodes; pack-roads --bbox for a city graph

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: roadpack v3 в Kotlin и запрет разворота у края

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/RoadPack.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/Router.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/RoadPackTest.kt`, `RouterTest.kt`

**Interfaces:**
- Consumes: фикстура `research/vpr_bench/tests/data/roadpack_v3_fixture` (Task 1): граф v2-фикстуры, `boundary = [0, 2]`.
- Produces:
  - `RoadPack(..., restrictions, boundary: IntArray = IntArray(0))` — последний параметр с умолчанием;
  - `fun isBoundary(node: Int): Boolean`;
  - `RoadPack.parse` читает v1–v3.

- [ ] **Step 1: Тесты.**

```kotlin
// RoadPackTest
private val fixtureV3 = File("../../research/vpr_bench/tests/data/roadpack_v3_fixture")

@Test fun parsesV3Boundary() {
    val pack = RoadPack.parse(ByteBuffer.wrap(File(fixtureV3, "roadpack.bin").readBytes()))
    assertTrue(pack.isBoundary(0)); assertFalse(pack.isBoundary(1)); assertTrue(pack.isBoundary(2))
    val v2 = RoadPack.parse(ByteBuffer.wrap(File(fixtureV2, "roadpack.bin").readBytes()))
    assertEquals(v2.edgeCount, pack.edgeCount)
    assertEquals(v2.restrictions, pack.restrictions)
    assertFalse((0 until v2.nodeCount).any { v2.isBoundary(it) })
}

@Test fun v3TruncatedBoundaryFails() {
    val raw = File(fixtureV3, "roadpack.bin").readBytes()
    assertFailsWith<IllegalArgumentException> { RoadPack.parse(ByteBuffer.wrap(raw.copyOf(raw.size - 2))) }
}

@Test fun boundaryMustBeIncreasingAndInRange() {
    val p = RoadPack.parse(ByteBuffer.wrap(File(fixtureV2, "roadpack.bin").readBytes()))
    for (bad in listOf(intArrayOf(1, 0), intArrayOf(0, 0), intArrayOf(-1), intArrayOf(p.nodeCount))) {
        assertFailsWith<IllegalArgumentException> {
            RoadPack(p.lats, p.lons, p.way, p.from, p.to, p.flags, p.cls, p.speedKmh, p.nameIdx, p.names, p.restrictions, bad)
        }
    }
}
```

```kotlin
// RouterTest — в pack(...) добавить параметр boundary: IntArray = IntArray(0) и передать его в RoadPack.
@Test fun noUturnAtCorridorBoundary() {
    // Та же геометрия, что в deadEndUturnLosesToBlockLoop, но без петли: отросток B(200,0)–S(200,−50).
    // Если S — край коридора (дорога за ним обрезана), разворота в S нет, и маршрута назад нет вовсе.
    val nodes = listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 200.0 to -50.0)
    val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3))
    val p = pack(nodes, edges, speeds = List(3) { 36 }, boundary = intArrayOf(3))
    assertNull(Router(RoadIndex(p, enu)).route(150.0, 0.0, Math.PI / 2, 50.0, 0.0))
}

@Test fun loopUsedWhenStubEndsAtBoundary() {
    // deadEndUturnLosesToBlockLoop с краем в S и штрафом за разворот 0: без отметки края дешевле был бы разворот
    // в отростке (300 м, 30 с + 2·5 с = 40 с < 60 с), с отметкой остаётся только петля (400 м).
    val nodes = listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 200.0 to -50.0, 200.0 to 100.0, 100.0 to 100.0)
    val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(2, 4, 4), EdgeSpec(4, 5, 5),
        EdgeSpec(5, 1, 6))
    val cheapUturn = RouterConfig(deadEndUturnPenaltyS = 0.0)
    val free = pack(nodes, edges, speeds = List(edges.size) { 36 })
    assertEquals(300.0, assertNotNull(Router(RoadIndex(free, enu), cheapUturn).route(150.0, 0.0, Math.PI / 2, 50.0, 0.0)).lengthM, 1e-6)
    val marked = pack(nodes, edges, speeds = List(edges.size) { 36 }, boundary = intArrayOf(3))
    val r = assertNotNull(Router(RoadIndex(marked, enu), cheapUturn).route(150.0, 0.0, Math.PI / 2, 50.0, 0.0))
    assertEquals(400.0, r.lengthM, 1e-6)
}
```

Существующий `uTurnAtDeadEndWhenHeadingAway` должен проходить без изменений: там тупик настоящий.

Если `300.0` в `loopUsedWhenStubEndsAtBoundary` не сходится с фактическим маршрутом без края, не ослабляйте тест. Остановитесь и сообщите: геометрия взята из существующего теста, и расчёт в комментарии нужно перепроверить.

- [ ] **Step 2: RED.** `./gradlew :core:test --tests '*RoadPackTest*' --tests '*RouterTest*'`

- [ ] **Step 3: Реализация.**
  - **`RoadPack`:**
    - параметр `val boundary: IntArray = IntArray(0)`;
    - в `init` проверки строго по возрастанию и в `[0, nodeCount)`;
    - `private val boundarySet = BooleanArray(nodeCount).also { b -> boundary.forEach { b[it] = true } }`;
    - `fun isBoundary(node: Int) = boundarySet[node]`.
  - **`parse`:**
    - `require(version in 1..3)`;
    - для v2 — прежняя проверка «остаток == 13·rc»;
    - для v3 — `remaining >= 13·rc + 4`, затем после запретов `bc = b.int`, `require(bc >= 0 && b.remaining() == 4L * bc)`, чтение `IntArray(bc)`.
  - Обновить KDoc формата (v3).
  - **`Router`**, строка с разворотом:
    ```kotlin
    // Разворот на том же ребре — только в настоящем тупике: у края коридора дорога продолжается за обрезкой.
    if (k2 == edge && (index.degree[v] > 1 || pack.isBoundary(v))) continue
    ```
    Обновить KDoc `deadEndUturnPenaltyS`: убрать «в том числе у края коридора», добавить «у края коридора (roadpack v3) разворот запрещён».

- [ ] **Step 4: GREEN.** `./gradlew :core:test :replay:test` — всё проходит.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core
git commit -m "feat(core): roadpack v3 boundary nodes; router forbids U-turns at the corridor edge

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `trip-plan` — маршрут поездки на ПК (JVM)

**Files:**
- Create: `android/replay/src/main/kotlin/io/visnav/replay/TripPlan.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Main.kt` (передача `trip-plan`)
- Modify: `android/replay/build.gradle.kts` (`applicationDefaultJvmArgs = listOf("-Xmx4g")`)
- Test: `android/replay/src/test/kotlin/io/visnav/replay/TripPlanTest.kt`

**Interfaces:**
- Consumes: `RoadPack.parse`, `RoadIndex(pack, enu)`, `Router.route(fromE, fromN, null, toE, toN)`, `Enu`.
- Produces:
  - `class TripRoute(val latLon: List<DoubleArray>, val lengthM: Double, val durationS: Double)`;
  - `fun planTrip(pack: RoadPack, waypoints: List<DoubleArray>, config: RouterConfig = RouterConfig()): TripRoute?` — `waypoints` = `[from, via…, to]` как `[lat, lon]`; `null`, если какая-то нога не построилась;
  - `fun densify(latLon: List<DoubleArray>, maxStepM: Double = 25.0): List<DoubleArray>`;
  - `fun writeGpx(file: File, latLon: List<DoubleArray>, speedMps: Double = 10.0)`;
  - `fun tripPlanMain(args: List<String>): Int` — 0 при успехе, 2 при ошибке аргументов или если маршрут не найден;
  - CLI: `./gradlew -q :replay:run --args="trip-plan --roads DIR --from LAT,LON --to LAT,LON [--via LAT,LON]... --out FILE.gpx"`.

- [ ] **Step 1: Тесты** (`TripPlanTest`, граф собирается тем же способом, что в `RoadReplayTest` или `NavReplayTest`: `roadPackOf`/`straightRoad` из `:core` test-фикстур недоступны в `:replay`, поэтому строить `RoadPack` напрямую или через временный каталог, как делает `NavReplayTest`).
  - **`planOverViaConcatenatesLegs`:**
    - сетка 3×3 улиц с шагом 200 м;
    - from — левый нижний угол, to — правый нижний, via — правый верхний;
    - длина маршрута ≥ 800 м (через via), а без via — 400 м;
    - первая точка ≈ from, последняя ≈ to (≤ 1 м).
  - **`unreachableReturnsNull`:** to на изолированной дороге в 1 км → `null`.
  - **`densifyKeepsStepAndEnds`:**
    - две точки на расстоянии 1000 м → ≥ 41 точка;
    - все шаги ≤ 25 м + 1e-6;
    - концы совпадают;
    - одинаковые соседние точки не дублируются.
  - **`gpxHasTimesAndParsesBack`:**
    - `writeGpx` для 3 точек;
    - в файле 3 `<trkpt`;
    - у каждой `<time>`, время растёт;
    - первая точка — `2000-01-01T00:00:00Z`;
    - координаты с 7 знаками.
  - **`cliErrors`:**
    - `tripPlanMain(listOf("--roads", dir, "--from", "x", "--to", "1,2", "--out", f))` → 2;
    - без `--to` → 2;
    - маршрут не найден → 2 и файл не создан;
    - успех → 0 и файл есть.

- [ ] **Step 2: RED.** `./gradlew :replay:test --tests '*TripPlanTest*'`

- [ ] **Step 3: Реализация `TripPlan.kt`.**

```kotlin
package io.visnav.replay

import io.visnav.core.Enu
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.Router
import io.visnav.core.RouterConfig
import io.visnav.core.Geo.haversineM
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant

class TripRoute(val latLon: List<DoubleArray>, val lengthM: Double, val durationS: Double)

/** Маршрут через промежуточные точки: ноги по одной, каждая — от проекции конца предыдущей. Курс неизвестен. */
fun planTrip(pack: RoadPack, waypoints: List<DoubleArray>, config: RouterConfig = RouterConfig()): TripRoute? {
    require(waypoints.size >= 2) { "need at least from and to" }
    val enu = Enu(waypoints[0][0], waypoints[0][1])
    val router = Router(RoadIndex(pack, enu), config)
    val pts = ArrayList<DoubleArray>()
    var length = 0.0; var duration = 0.0
    var cur = enu.toEn(waypoints[0][0], waypoints[0][1])
    for (w in waypoints.drop(1)) {
        val target = enu.toEn(w[0], w[1])
        val r = router.route(cur[0], cur[1], null, target[0], target[1]) ?: return null
        val legPts = r.points.map { enu.toLatLon(it[0], it[1]) }
        pts.addAll(if (pts.isEmpty()) legPts else legPts.drop(1))
        length += r.lengthM; duration += r.durationS
        cur = r.points.last()
    }
    return TripRoute(pts, length, duration)
}

fun densify(latLon: List<DoubleArray>, maxStepM: Double = 25.0): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    for (p in latLon) {
        val last = out.lastOrNull()
        if (last == null) { out += p; continue }
        val d = haversineM(last[0], last[1], p[0], p[1])
        if (d < 1e-6) continue
        val k = kotlin.math.ceil(d / maxStepM).toInt()
        for (j in 1..k) {
            val t = j.toDouble() / k
            out += doubleArrayOf(last[0] + (p[0] - last[0]) * t, last[1] + (p[1] - last[1]) * t)
        }
    }
    return out
}

fun writeGpx(file: File, latLon: List<DoubleArray>, speedMps: Double = 10.0) {
    val t0 = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
    var distM = 0.0
    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"visnav trip-plan\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><trkseg>\n")
    latLon.forEachIndexed { i, p ->
        if (i > 0) distM += haversineM(latLon[i - 1][0], latLon[i - 1][1], p[0], p[1])
        val t = Instant.ofEpochMilli(t0 + (distM / speedMps * 1000).toLong())
        sb.append("<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n".format(java.util.Locale.ROOT, p[0], p[1], t))
    }
    sb.append("</trkseg></trk>\n</gpx>\n")
    file.parentFile?.mkdirs()
    file.writeText(sb.toString())
}
```

`haversineM` — существующая `Geo.haversineM` из `io.visnav.core` (`Geo.kt`); новую не писать.

`tripPlanMain` разбирает аргументы `--roads`, `--from`, `--to`, `--via` (повторяется) и `--out` тем же способом, что `Main.kt`; координаты разбирает функцией `parseDest`. Затем:
1. читает `roadpack.bin` каталога;
2. вызывает `planTrip`; если вернулся `null`, пишет в stderr `error: no route (a waypoint is off the road graph or unreachable)` и возвращает 2;
3. вызывает `writeGpx(out, densify(route.latLon))`;
4. печатает `route %.1f km, ~%d min, %d points -> out` и возвращает 0.

В `Main.kt` первой строкой `main`:

```kotlin
if (args.firstOrNull() == "trip-plan") exitProcess(tripPlanMain(args.drop(1)))
```

Строку `usage` дополнить вариантом `replay trip-plan --roads DIR --from LAT,LON --to LAT,LON [--via LAT,LON]... --out FILE.gpx`.

- [ ] **Step 4: GREEN.** `./gradlew :core:test :replay:test`

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/replay
git commit -m "feat(replay): trip-plan — route through waypoints on a city roadpack, dense timed GPX

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `vpr-m3 pack-trip` — сборка пакета поездки

**Files:**
- Modify: `research/vpr_bench/src/vpr_bench/m3cli.py`
- Test: `research/vpr_bench/tests/test_packtrip.py`

**Interfaces:**
- Consumes:
  - `m1cli._pack(args)` — `refs`, `onnx`, `out`, `gpx`, `buffer_m`; кладёт `refpack.*` и `model.onnx` в `out`;
  - `m2cli._pack_roads(args)` — `pbf`, `gpx`, `log`, `bbox`, `buffer_m`, `out`;
  - `m3cli._pack_map(args)` — `mbtiles`, `fonts_zip`, `gpx`, `log`, `buffer_m`, `out`;
  - `parse_gpx`, `haversine_m`, `onnx_sha256`.
- Produces:
  - CLI: `vpr-m3 pack-trip --route R.gpx --name NAME --refs REFS.csv --onnx M.onnx --pbf P.osm.pbf --mbtiles T.mbtiles --fonts-zip F.zip --out ROOT [--dest-name S] [--refs-buffer-m 300] [--roads-buffer-m 300] [--map-buffer-m 500] [--force]`;
  - модульные функции-шаги `_step_refs(ns)`, `_step_roads(ns)`, `_step_map(ns)` (каждая возвращает код), которые тесты подменяют.

- [ ] **Step 1: Тесты** (`tests/test_packtrip.py`).
  - Шаги подменяются через `monkeypatch.setattr(m3cli, "_step_refs", fake)`. Фейки пишут в `ns.out` маленькие файлы:
    - refs: `refpack.bin` (10 байт), `refpack.json`, `model.onnx` с содержимым `b"M1"`;
    - roads: `roadpack.bin`, `roadpack.json`;
    - map: каталог `ns.out` с `map.json`.
  - GPX — 3 точки с временем вдоль меридиана, 2 км.
  - **`test_layout_and_trip_json`:**
    - код 0;
    - есть `ROOT/model.onnx` (`b"M1"`);
    - `ROOT/trips/work/` содержит `refpack.bin`, `roadpack.bin`, `map/map.json`, `route.json`, `trip.json`;
    - в каталоге поездки нет `model.onnx`;
    - `route.json` — последняя точка GPX и `dest_name == "work"`;
    - `trip.json`: `route_km ≈ 2.0` (±0,01), `buffers_m == {"refs": 300.0, "roads": 300.0, "map": 500.0}`, `nfr8_target_mb_per_100km == 50`, `onnx_sha256` равен SHA-256 `b"M1"`.
  - **`test_steps_get_route_and_buffers`:**
    - фейки записывают полученные `ns`;
    - у всех `gpx == [route]`;
    - буферы и `out` правильные: refs → trip dir, roads → trip dir, map → `trip/map`;
    - у roads `bbox is None` и `log == []`.
  - **`test_second_trip_same_model_ok_other_model_fails`:**
    - вторая поездка с той же моделью → 0;
    - с моделью `b"M2"` → 2, сообщение про модель, каталог второй поездки удалён.
  - **`test_existing_trip_needs_force`:** повторный запуск → 2; с `--force` → 0, старые файлы заменены.
  - **`test_bad_name_rejected`:** `"../x"`, `""`, `"a b"` → 2.
  - **`test_step_failure_propagates`:** refs возвращает 2 → код 2, каталог поездки удалён, корень и другие поездки не тронуты.
  - **`test_route_without_points`:** GPX без `<time>` → 2.

- [ ] **Step 2: RED.** `uv run pytest tests/test_packtrip.py -q`

- [ ] **Step 3: Реализация** в `m3cli.py`.
  - **Парсер** `pack-trip` с аргументами из Interfaces. Буферы `float`. `--force` — `store_true`.
  - **Шаги:**

```python
def _step_refs(ns: argparse.Namespace) -> int:
    from vpr_bench.m1cli import _pack
    return _pack(ns)

def _step_roads(ns: argparse.Namespace) -> int:
    from vpr_bench.m2cli import _pack_roads
    return _pack_roads(ns)

def _step_map(ns: argparse.Namespace) -> int:
    return _pack_map(ns)
```

  - **`_pack_trip(args)`:**
    1. Проверить имя через `re.fullmatch(r"[A-Za-z0-9_-]{1,40}", name)`, иначе `error: bad trip name` и 2.
    2. `track = parse_gpx(args.route)`; если точек меньше двух — `error: route has fewer than 2 timed points` и 2.
    3. `trip = args.out / "trips" / args.name`. Если каталог существует и нет `--force` — `error: trip exists, pass --force` и 2. С `--force` — `shutil.rmtree(trip)`.
    4. `trip.mkdir(parents=True)`. Затем в блоке `try`:
       - `_step_refs(Namespace(refs=args.refs, onnx=args.onnx, out=trip, gpx=[args.route], buffer_m=args.refs_buffer_m))`;
       - `_step_roads(Namespace(pbf=args.pbf, gpx=[args.route], log=[], bbox=None, buffer_m=args.roads_buffer_m, out=trip))`;
       - `_step_map(Namespace(mbtiles=args.mbtiles, fonts_zip=args.fonts_zip, gpx=[args.route], log=[], buffer_m=args.map_buffer_m, out=trip / "map"))`.

       Если какой-то шаг вернул не 0 — `shutil.rmtree(trip)` и вернуть его код.
    5. **Модель:**
       - `sha = onnx_sha256(trip / "model.onnx")`;
       - `root_model = args.out / "model.onnx"`;
       - если `root_model` существует и `onnx_sha256(root_model) != sha` — `rmtree(trip)`, `error: trip model differs from <root>/model.onnx — all trips share one model`, 2;
       - иначе `os.replace(trip / "model.onnx", root_model)`.
    6. **`route.json`:** последняя точка `track`, `dest_name = args.dest_name or args.name`, `ensure_ascii=False`.
    7. **`trip.json`:**
       - `route_km` — сумма `haversine_m` по точкам / 1000;
       - `sizes_mb` — сумма размеров файлов (рекурсивно): refs = `refpack.*`, roads = `roadpack.*`, map = `map/`;
       - `mb_per_100km = total_mb / max(route_km, 1e-9) * 100`.
    8. **Печать:**

       ```
       trip work: 12.3 km, refs 3.10 MB, roads 0.40 MB, map 5.20 MB, total 8.70 MB = 70.73 MB per 100 km (NFR-8 target 50, incl. refs) -> ROOT/trips/work
       ```

       Если `mb_per_100km > 50`, добавить строку `warning: over NFR-8 target`.

`onnx_sha256` находится в `vpr_bench.onnx_export`, но этот модуль импортирует `cv2` и ONNX Runtime. В `m3cli` посчитайте SHA-256 сами через `hashlib.sha256` по файлу, блоками; результат должен совпадать с `onnx_sha256` — проверить тестом. Тесты должны идти без `torch`.

- [ ] **Step 4: GREEN.** `uv run pytest -q`

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src research/vpr_bench/tests
git commit -m "feat(m3): pack-trip — one trip pack (refs, roads v3, map, route, summary) with a shared model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: поездки в приложении — раскладка, загрузка, выбор

**Files:**
- Create: `android/app/src/main/kotlin/io/visnav/app/Trips.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/Bundle.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/FrameLog.kt` (или где объявлен `SessionHeader`): необязательное поле `trip`
- Test: `android/app/src/test/kotlin/io/visnav/app/TripLayoutTest.kt`; `:core` — тест заголовка, если есть `SessionLoggerTest`

**Interfaces:**
- Produces:

```kotlin
data class TripDirs(val name: String?, val dataDir: File, val model: File)

object TripLayout {
    /** null — каталога trips/ нет (старая раскладка); иначе имена поездок (подкаталог с refpack.bin), по алфавиту. */
    fun list(root: File): List<String>?
    /** Каталог данных и модель для выбранной поездки; при исчезнувшей поездке — первая по алфавиту. */
    fun resolve(root: File, wanted: String?): Result<TripDirs>
}
```

  - `BundleLoader.load(dir: File, model: File = File(dir, "model.onnx")): LoadedBundle`;
  - `UiState.trip: String?`, `UiState.trips: List<String>`;
  - `M1Controller.selectTrip(name: String)` (без действия, если идёт запись);
  - `M1Controller.tripDataDir: File` (каталог данных выбранной поездки или `refpack/`);
  - `SessionHeader.trip: String? = null`.

- [ ] **Step 1: Тесты** (`TripLayoutTest`, временные каталоги, `try/finally`).
  - **`legacyWhenNoTripsDir`:** `list == null`; `resolve(root, null)` → `TripDirs(null, root, root/model.onnx)`.
  - **`listsOnlyDirsWithRefpack`:**
    - `trips/b/refpack.bin`, `trips/a/refpack.bin`, `trips/c/` (пусто), файл `trips/x.txt`;
    - результат `["a", "b"]`.
  - **`resolvePicksWantedOrFirst`:** wanted `"b"` → b; wanted `"gone"` → a; wanted `null` → a.
  - **`modelFromTripOrRoot`:** `trips/a/model.onnx` есть → он; нет → `root/model.onnx`.
  - **`emptyTripsDirFails`:** есть `trips/`, но поездок нет → `failure`, текст содержит «Нет поездок».

- [ ] **Step 2: RED.** `./gradlew :app:testDebugUnitTest --tests '*TripLayoutTest*'`

- [ ] **Step 3: Реализация.**
  - **`Trips.kt`** — по Interfaces. Текст ошибки: `"Нет поездок в ${File(root, "trips").absolutePath}: положите пакет vpr-m3 pack-trip через adb push"`.
  - **`BundleLoader.load(dir, model)`:** проверка файлов `refpack.bin`, `refpack.json` и `model`; текст «нет файла …» прежний.
  - **`M1Controller`:**
    - `private val prefs = context.getSharedPreferences("visnav", Context.MODE_PRIVATE)`;
    - `@Volatile var tripDataDir: File = dataDir; private set`;
    - тело `init` выносится в `private fun loadBundle(wanted: String?)` (на executor):
      1. закрыть embedder прежней базы (`bundle?.embedder?.close()`, `bundle = null`, `analyzer.set(null)`);
      2. `TripLayout.resolve(dataDir, wanted)`; при ошибке — статус с её текстом и `loaded = false`;
      3. `BundleLoader.load(dirs.dataDir, dirs.model)`;
      4. `tripDataDir = dirs.dataDir`;
      5. `prefs.edit().putString("trip", dirs.name).apply()`, если имя не null;
      6. `_state.update { it.copy(trip = dirs.name, trips = TripLayout.list(dataDir).orEmpty(), …) }`;
      7. статус начинается с `"Поездка: <имя> · "`, если имя не null; дальше как сейчас.

      Проверка `route.json` без графа и `ParityCheck.runIfPresent(dataDir, …)` остаются. Parity-файлы лежат в корне `refpack/`.
    - `init { executor.execute { loadBundle(prefs.getString("trip", null)) } }`;
    - `fun selectTrip(name: String) { if (_state.value.running) return; executor.execute { if (!runningFlag.get()) loadBundle(name) } }`;
    - в `log.header(SessionHeader(...))` передать `trip = _state.value.trip`.
  - **`SessionHeader`:** `val trip: String? = null`. Если формат `Json` в `SessionLogger` пишет `null` явно, это допустимо. Python `read_log` лишние поля не проверяет — убедитесь тестом `uv run pytest -q`.

- [ ] **Step 4: GREEN.** `./gradlew :core:test :replay:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — lint 0 ошибок, новые предупреждения перечислить.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android
git commit -m "feat(app): trip packs under refpack/trips — layout, shared model, remembered selection, trip in session header

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: выбор поездки на экране и карта поездки

**Files:**
- Modify: `android/app/src/main/kotlin/io/visnav/app/NavScreen.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Screen.kt` (строка «Поездка»)
- Modify: `android/app/src/main/kotlin/io/visnav/app/MapData.kt` (KDoc: каталог поездки)

**Interfaces:**
- Consumes: `UiState.trip`, `UiState.trips`, `M1Controller.selectTrip`, `M1Controller.tripDataDir` (Task 5).

Изменения:
- **Карта грузится заново при смене поездки:** `LaunchedEffect(s.trip, s.loaded) { mapData = withContext(Dispatchers.IO) { MapDataLoader.load(controller.tripDataDir) } }`. Вместо `controller.refpackDir` — `controller.tripDataDir` везде, в том числе в подсказке «Нет карты…» с путём для `adb push`.
- **Смена поездки во время показа карты:**
  - `RouteStore` очищается: новая сессия даёт новую версию маршрута, а до старта `nav == null`;
  - камера переходит к центру `bounds` новой карты. Для этого `onMapReady` должен сработать для новой `MapData`: обернуть `MapLibreMap` в `key(mapData)`.
- **Панель слева над кнопкой «Старт»:**
  - если `s.trips.size > 1` и запись не идёт — кнопка `OutlinedButton` «Поездка: <имя> ▾» с `DropdownMenu`, пункты — `s.trips`; выбор вызывает `controller.selectTrip(it)`;
  - если поездка одна или идёт запись — просто текст «Поездка: <имя>»;
  - при старой раскладке (`trip == null`) ничего не показывать.
- **Вкладка «Отладка»:** имя поездки уже в статусе (Task 5); отдельный элемент не нужен. Если в `M1Screen` есть заголовок с базой, добавить туда «Поездка: <имя>».

- [ ] **Step 1: Тест**, если логику выбора отображения удобно вынести в чистую функцию: `internal fun tripLabel(trip: String?, trips: List<String>, running: Boolean): TripLabel` (`None` / `Text(name)` / `Picker(name, trips)`) и `TripLabelTest` на 4 случая. Иначе только сборка.
- [ ] **Step 2: Реализация.**
- [ ] **Step 3: Сборка.** `./gradlew :core:test :replay:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:verifyNoNetworkPermissions` → `BUILD SUCCESSFUL`, lint 0 ошибок.
- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/app
git commit -m "feat(app): trip picker on the navigation tab; map reloads from the selected trip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: протокол M3c, SPEC, документы

**Files:**
- Create: `docs/research/m3c-trip.md`
- Modify: `docs/SPEC.md`: строка M3 в плане этапов и FR-5/FR-16 — примечание о пакете поездки.
- Modify: `docs/research/m3a-route.md`: ограничение «Граница коридора»; строка о roadpack (v3, края).
- Modify: `docs/research/m3b-map.md`: путь карты в поездке.

Содержание `m3c-trip.md`:
- **Что нужно:**
  - выгрузка OSM;
  - тайлы planetiler (как в M3b);
  - шрифты;
  - модель ONNX (`vpr-m1 export-onnx` / `quantize-onnx`);
  - токен Mapillary для `fetch-refs`.
- **Шаг 1. Граф города** (раз в месяц) — команда `pack-roads --bbox`.
- **Шаг 2. Маршрут** — команда `trip-plan`, с `--via`.
- **Шаг 3. Эталоны** — `vpr-bench fetch-refs --gpx route.gpx --buffer-m 300 --out …`.
- **Шаг 4. Пакет** — `vpr-m3 pack-trip …`, что печатает и что лежит в `trips/<имя>/`.
- **Шаг 5. Телефон:**
  - `adb push data/m3c/refpack/. /sdcard/Android/data/io.visnav.app/files/refpack/`;
  - выбор поездки на вкладке «Навигация»;
  - удаление поездки: `adb shell rm -r /sdcard/Android/data/io.visnav.app/files/refpack/trips/<имя>`.
- **Как устроено:**
  - края коридора;
  - одна модель на все поездки;
  - старая раскладка.
- **Ограничения:**
  - via только задаёт коридор;
  - граф города требует `-Xmx4g`;
  - без интернета;
  - нет отчёта о покрытии;
  - нет проверки целостности.
- **Что проверить в поездке:**
  - маршрут на телефоне совпадает с маршрутом `trip-plan`;
  - нет разворотов у края;
  - переключение поездок до старта.
- **Итоги (заполнить):** размеры, МБ на 100 км, замечания.

- [ ] **Step 1: Документы.** Все команды сверить с кодом Tasks 1–6: флаги, пути, значения по умолчанию.
- [ ] **Step 2: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs
git commit -m "docs: M3c trip pack protocol; SPEC and M3a/M3b notes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
