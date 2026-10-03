# M2b: привязка к дорожному графу OSM — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Привязка позиции к дорожному графу OpenStreetMap (map matching, FR-12) с целью NFR-3: правильная дорога ≥ 98 % времени на replay.
- Когда привязка уверена, а GNSS в фильтр давно не поступал, дорога подсказывает фильтру поперечное положение и курс. Это сдерживает дрейф счисления пути (NFR-5) в тоннелях и при пропадании GPS.
- Работает и на телефоне в реальном времени, и в replay.

**Architecture:**
- **На ПК** (`vpr-m2 pack-roads`):
  - из выгрузки Geofabrik (`.osm.pbf`) через pyosmium отбираются проезжие дороги в коридоре ±300 м вокруг треков поездок;
  - граф упаковывается в бинарный `roadpack` («VNRD»): узлы и рёбра-отрезки с OSM way id, флагами (односторонняя, тоннель, мост) и классом дороги.
- **В `:core`:**
  - `RoadPack` читает файл;
  - `RoadIndex` переводит граф в плоские координаты фильтра и ищет ближайшие рёбра по сетке;
  - `MapMatcher` — онлайн-HMM по рёбрам с направлением движения (модель Newson–Krumm, прямой проход Витерби без задержки), выдаёт ребро, точку на оси дороги и уверенность;
  - `Localizer` на каждом кадре делает шаг привязки по оценке фильтра. Если уверенность ≥ 0,9, рядом нет перекрёстка, машина едет и GNSS не сливался в фильтр дольше 2 с, в `Ekf2d` подаются два псевдоизмерения: поперечное смещение до оси дороги (σ по классу дороги) и курс вдоль дороги (σ 10°).
- **Вывод:** результат пишется в строку траектории и `.fusion.jsonl`.
- **Оценка** (`vpr-m2 road-eval`): эталон — офлайн-привязка очищенного GPS-трека к тому же графу (HMM с обратным проходом на Python). Метрика — доля строк в движении, где онлайн-дорога совпадает с эталонной.

**Tech Stack:** Kotlin 2.1 (JVM 17), Android, Python 3.11+ (numpy, pyosmium как необязательная зависимость `osm`), JUnit 4 / kotlin.test, pytest.

## Global Constraints

- **Цели M2b:**
  - NFR-3: правильная дорога ≥ 98 % времени движения (≥ 2 м/с) на replay. Отдельно показывается доля внутри пропаданий и подмешанных окон, цель та же.
  - Предложение плана: подсказка дорогой не ухудшает дрейф NFR-5 на replay; сравнивается прогон с ограничением и с `--no-road-constraint`.
- **Коридор графа** по умолчанию ±300 м вокруг маршрута (SPEC, сценарий 4).
- **Формат `roadpack` v1** (little-endian), файлы `roadpack.bin` и `roadpack.json`:
  - заголовок 16 байт: `magic "VNRD"` (4 байта), `version u16 = 1`, `reserved u16 = 0`, `node_count u32`, `edge_count u32`;
  - затем массивы: `lats f64[n]`, `lons f64[n]`, `way i64[m]`, `from i32[m]`, `to i32[m]`, `flags u8[m]`, `cls u8[m]`;
  - размер файла ровно `16 + 16·n + 18·m`;
  - флаги: `1` — односторонняя (ехать можно только `from → to`), `2` — тоннель, `4` — мост;
  - ребро — прямой отрезок между соседними узлами OSM-линии;
  - `roadpack.json`: `format = "VNRD/1"`, `created_at` (ISO UTC), `source`, `node_count`, `edge_count`, `buffer_m`, `attribution`.
- **Коды классов дорог** (общие для Python и Kotlin):

  | Код | `highway` |
  |---|---|
  | 1 | motorway, motorway_link |
  | 2 | trunk, trunk_link |
  | 3 | primary, primary_link |
  | 4 | secondary, secondary_link |
  | 5 | tertiary, tertiary_link |
  | 6 | unclassified |
  | 7 | residential |
  | 8 | living_street |
  | 9 | service |

  Сигма поперечного псевдоизмерения: классы 1–2 — 7 м, класс 3 — 6 м, класс 4 — 5 м, остальные — 4 м.
- **Строка траектории и `.fusion.jsonl`:** поля M2a и M2c без изменений. В конец добавляются `"way_id"`, `"road_lat"`, `"road_lon"`, `"road_conf"`, `"road_used"`; без графа все они `null`.
- **Заголовок replay** получает `"roads"` (`created_at` графа или `null`) и `"road_constraint"` после `"spoofs"`. Заголовок fusion получает `"roads"`.
- **Лицензия данных OSM:** ODbL 1.0.
  - `roadpack` — производная база данных, в репозиторий не коммитится (`research/vpr_bench/data/` в `.gitignore`).
  - Отчёты и экран приложения при загруженном графе показывают «© участники OpenStreetMap».
- **Зависимости:**
  - pyosmium (BSD-2-Clause) — только на ПК, как необязательная группа `osm` в `pyproject.toml`;
  - в приложение ничего нового не добавляется.
- **Сеть:** тесты не ходят в сеть. Выгрузку `.osm.pbf` скачивает владелец.
- **Android:** без Google Play Services, `minSdk 29`.
- **FR-20:** изображения не сохраняются.
- **Команды Gradle** — из `/Users/vvnovg/navigator/android` с `JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"`.
- **Команды Python** — из `/Users/vvnovg/navigator/research/vpr_bench` через `uv run`. Для задач с OSM сначала выполнить `uv sync --extra dev --extra osm`.
- **Коммиты** заканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Текущие счётчики тестов:** `:core` — 129, `:replay` — 31, Python — 178 passed / 4 deselected.

---

## Структура файлов

```
research/vpr_bench/
├── pyproject.toml                      Task 2  необязательная группа osm = ["osmium>=3.7"]
├── src/vpr_bench/roadpack.py           Task 1  RoadGraph, write_roadpack, read_roadpack, FLAG_*
├── src/vpr_bench/osmgraph.py           Task 2  RawWay, road_class, direction, build_graph, read_ways
├── src/vpr_bench/roadmatch.py          Task 7  RoadNet, Snap, match_track (офлайн-эталон)
├── src/vpr_bench/roadeval.py           Task 7  RoadResult, evaluate_roads, render_road_report
├── src/vpr_bench/replayeval.py         Task 7  TrajRow + way_id/road_lat/road_lon/road_conf/road_used
├── src/vpr_bench/m2cli.py              Task 2, 7  подкоманды pack-roads, road-eval
├── tests/data/roadpack_fixture/        Task 1  общий с Kotlin эталон формата (синтетический, не OSM)
├── tests/_roads.py                     Task 7  тестовый построитель графов в метрах
├── tests/test_roadpack.py              Task 1
├── tests/test_osmgraph.py              Task 2
├── tests/test_roadmatch.py             Task 7
└── tests/test_roadeval.py              Task 7

android/core/src/main/kotlin/io/visnav/core/
├── RoadPack.kt                         Task 3  RoadPack, RoadPackMeta, RoadClass
├── RoadIndex.kt                        Task 3  RoadProjection, RoadIndex
├── MapMatcher.kt                       Task 4  MatchConfig, RoadMatch, MapMatcher
├── Ekf2d.kt                            Task 5  + updateLateral()
├── Localizer.kt                        Task 5  roads, RoadInfo, подсказка фильтру
└── TrajectoryFormat.kt                 Task 5  поля дороги в строке, "roads" в заголовке fusion
android/core/src/test/kotlin/io/visnav/core/
├── RoadTestGraphs.kt                   Task 3  EdgeSpec, roadPackOf, straightRoad
├── RoadPackTest.kt, RoadIndexTest.kt   Task 3
├── MapMatcherTest.kt                   Task 4
└── LocalizerRoadTest.kt, Ekf2dTest.kt  Task 5

android/replay/src/main/kotlin/io/visnav/replay/
├── Replayer.kt, TrajectoryWriter.kt    Task 6  roads, roadConstraint, заголовок
└── Main.kt                             Task 6  --roads DIR, --no-road-constraint, loadRoads()
android/replay/src/test/kotlin/io/visnav/replay/RoadReplayTest.kt   Task 6

android/app/src/main/kotlin/io/visnav/app/
├── Bundle.kt                           Task 8  необязательный roadpack
├── M1Controller.kt                     Task 8  Localizer с графом, заголовок fusion, UiState
└── M1Screen.kt                         Task 8  строка «Дорога», атрибуция OSM

docs/research/m2b-roads.md              Task 9  протокол
```

---

### Task 1: Формат `roadpack` на Python и общий эталон формата

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/roadpack.py`
- Create: `research/vpr_bench/tests/test_roadpack.py`
- Create: `research/vpr_bench/tests/data/roadpack_fixture/roadpack.bin`, `roadpack.json` (генерируются в Step 3)

**Interfaces:**
- Produces:
  - `FLAG_ONEWAY = 1`, `FLAG_TUNNEL = 2`, `FLAG_BRIDGE = 4`;
  - `@dataclass(frozen=True) RoadGraph(node_lats, node_lons, edge_way, edge_from, edge_to, edge_flags, edge_class)` — numpy-массивы `float64, float64, int64, int32, int32, uint8, uint8`;
  - `write_roadpack(out_dir: Path, g: RoadGraph, meta: dict) -> Path`;
  - `read_roadpack(dir_: Path) -> tuple[RoadGraph, dict]`.
  - Эталонный каталог `tests/data/roadpack_fixture/` читает Kotlin-тест в Task 3.

- [ ] **Step 1: Падающие тесты** — `tests/test_roadpack.py`:

```python
from pathlib import Path

import numpy as np
import pytest

from vpr_bench.roadpack import FLAG_ONEWAY, FLAG_TUNNEL, RoadGraph, read_roadpack, write_roadpack

FIXTURE_DIR = Path(__file__).parent / "data" / "roadpack_fixture"
FIXTURE_META = {"created_at": "2026-10-02T00:00:00Z", "source": "fixture", "buffer_m": 300.0,
                "attribution": "© участники OpenStreetMap, ODbL 1.0"}


def fixture_graph() -> RoadGraph:
    """3 узла, 2 ребра: way 101 (0→1, двусторонняя, residential), way 202 (1→2, односторонняя, тоннель, primary)."""
    return RoadGraph(
        node_lats=np.array([55.75, 55.751, 55.751]), node_lons=np.array([37.60, 37.60, 37.602]),
        edge_way=np.array([101, 202], dtype=np.int64), edge_from=np.array([0, 1], dtype=np.int32),
        edge_to=np.array([1, 2], dtype=np.int32),
        edge_flags=np.array([0, FLAG_ONEWAY | FLAG_TUNNEL], dtype=np.uint8),
        edge_class=np.array([7, 3], dtype=np.uint8),
    )


def test_roundtrip(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)
    g, meta = read_roadpack(tmp_path)
    assert (tmp_path / "roadpack.bin").stat().st_size == 16 + 16 * 3 + 18 * 2
    np.testing.assert_array_equal(g.node_lons, [37.60, 37.60, 37.602])
    assert list(g.edge_way) == [101, 202] and list(g.edge_flags) == [0, 3] and list(g.edge_class) == [7, 3]
    assert meta["format"] == "VNRD/1" and meta["node_count"] == 3 and meta["edge_count"] == 2
    assert meta["created_at"] == "2026-10-02T00:00:00Z"


def test_committed_fixture_matches_writer(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)
    assert (tmp_path / "roadpack.bin").read_bytes() == (FIXTURE_DIR / "roadpack.bin").read_bytes()
    g, meta = read_roadpack(FIXTURE_DIR)
    assert meta["edge_count"] == 2 and list(g.edge_to) == [1, 2]


def test_rejects_bad_magic_and_size(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)
    raw = (tmp_path / "roadpack.bin").read_bytes()
    (tmp_path / "roadpack.bin").write_bytes(b"XXXX" + raw[4:])
    with pytest.raises(ValueError, match="magic"):
        read_roadpack(tmp_path)
    (tmp_path / "roadpack.bin").write_bytes(raw[:-1])
    with pytest.raises(ValueError, match="size"):
        read_roadpack(tmp_path)


def test_rejects_edge_out_of_range():
    g = fixture_graph()
    with pytest.raises(ValueError, match="range"):
        RoadGraph(g.node_lats, g.node_lons, g.edge_way, g.edge_from, np.array([1, 3], dtype=np.int32),
                  g.edge_flags, g.edge_class)
```

Run: `uv run pytest tests/test_roadpack.py -q`
Expected: FAIL (`ModuleNotFoundError: vpr_bench.roadpack`).

- [ ] **Step 2: Реализация** — `src/vpr_bench/roadpack.py`:

```python
"""roadpack v1 — дорожный граф OSM для телефона. Формат описан в плане M2b (Global Constraints)."""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"VNRD"
VERSION = 1
HEADER = struct.Struct("<4sHHII")
FLAG_ONEWAY = 1
FLAG_TUNNEL = 2
FLAG_BRIDGE = 4


@dataclass(frozen=True)
class RoadGraph:
    node_lats: np.ndarray   # float64[n]
    node_lons: np.ndarray   # float64[n]
    edge_way: np.ndarray    # int64[m]  — OSM way id
    edge_from: np.ndarray   # int32[m]
    edge_to: np.ndarray     # int32[m]
    edge_flags: np.ndarray  # uint8[m]  — FLAG_*
    edge_class: np.ndarray  # uint8[m]  — код класса дороги

    def __post_init__(self) -> None:
        n, m = len(self.node_lats), len(self.edge_from)
        if len(self.node_lons) != n:
            raise ValueError("node_lats and node_lons must have equal length")
        if not (len(self.edge_to) == len(self.edge_way) == len(self.edge_flags) == len(self.edge_class) == m):
            raise ValueError("edge arrays must have equal length")
        if m and (min(self.edge_from.min(), self.edge_to.min()) < 0
                  or max(self.edge_from.max(), self.edge_to.max()) >= n):
            raise ValueError("edge node index out of range")


def write_roadpack(out_dir: Path, g: RoadGraph, meta: dict) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    n, m = len(g.node_lats), len(g.edge_from)
    with (out_dir / "roadpack.bin").open("wb") as f:
        f.write(HEADER.pack(MAGIC, VERSION, 0, n, m))
        f.write(np.asarray(g.node_lats, dtype="<f8").tobytes())
        f.write(np.asarray(g.node_lons, dtype="<f8").tobytes())
        f.write(np.asarray(g.edge_way, dtype="<i8").tobytes())
        f.write(np.asarray(g.edge_from, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_to, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_flags, dtype="u1").tobytes())
        f.write(np.asarray(g.edge_class, dtype="u1").tobytes())
    full = {**meta, "format": "VNRD/1", "node_count": n, "edge_count": m}
    (out_dir / "roadpack.json").write_text(json.dumps(full, ensure_ascii=False, indent=2) + "\n")
    return out_dir / "roadpack.bin"


def read_roadpack(dir_: Path) -> tuple[RoadGraph, dict]:
    raw = (dir_ / "roadpack.bin").read_bytes()
    if len(raw) < HEADER.size:
        raise ValueError("roadpack too short")
    magic, version, _, n, m = HEADER.unpack_from(raw, 0)
    if magic != MAGIC:
        raise ValueError("not a roadpack (bad magic)")
    if version != VERSION:
        raise ValueError(f"unsupported roadpack version={version}")
    expected = HEADER.size + 16 * n + 18 * m
    if len(raw) != expected:
        raise ValueError(f"roadpack size {len(raw)} != expected {expected}")
    off = HEADER.size

    def take(dtype: str, count: int, size: int) -> np.ndarray:
        nonlocal off
        arr = np.frombuffer(raw, dtype=dtype, count=count, offset=off).copy()
        off += count * size
        return arr

    lats, lons = take("<f8", n, 8), take("<f8", n, 8)
    way = take("<i8", m, 8)
    fr, to = take("<i4", m, 4), take("<i4", m, 4)
    flags, cls = take("u1", m, 1), take("u1", m, 1)
    meta = json.loads((dir_ / "roadpack.json").read_text())
    return RoadGraph(lats, lons, way, fr.astype(np.int32), to.astype(np.int32), flags, cls), meta
```

- [ ] **Step 3: Создать эталон.** Эталон синтетический, а не данные OSM, поэтому его можно коммитить.

```bash
cd /Users/vvnovg/navigator/research/vpr_bench
uv run python -c "
from pathlib import Path
import sys; sys.path.insert(0, 'tests')
from test_roadpack import fixture_graph, FIXTURE_META, FIXTURE_DIR
from vpr_bench.roadpack import write_roadpack
write_roadpack(FIXTURE_DIR, fixture_graph(), FIXTURE_META)
"
ls -l tests/data/roadpack_fixture
```
Expected: `roadpack.bin` размером 100 байт и `roadpack.json`.

- [ ] **Step 4: Тесты зелёные.**

Run: `uv run pytest -q`
Expected: не меньше 182 passed, 4 deselected.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/roadpack.py research/vpr_bench/tests/test_roadpack.py research/vpr_bench/tests/data/roadpack_fixture
git commit -m "feat(vpr-bench): roadpack v1 format for the OSM road graph

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Дорожный граф из OSM и `vpr-m2 pack-roads`

**Files:**
- Modify: `research/vpr_bench/pyproject.toml`: в `[project.optional-dependencies]` добавить `osm = ["osmium>=3.7"]`.
- Create: `research/vpr_bench/src/vpr_bench/osmgraph.py`
- Modify: `research/vpr_bench/src/vpr_bench/m2cli.py`: подкоманда `pack-roads`.
- Test: `research/vpr_bench/tests/test_osmgraph.py`

**Interfaces:**
- Consumes: `RoadGraph`, `write_roadpack`, `FLAG_*` (Task 1); `db_builder.Corridor(tracks, buffer_m)` с методами `.bbox() -> BBox` и `.contains(lat, lon) -> bool`; `query.parse_gpx`, `query.clean_track`, `fieldlog.read_log`, `fieldlog.gps_track`; `geo.BBox(min_lon, min_lat, max_lon, max_lat)`.
- Produces:
  - `ROAD_CLASS: dict[str, int]`;
  - `@dataclass(frozen=True) RawWay(id: int, tags: dict[str, str], nodes: list[tuple[int, float, float]])` — узлы в виде `(osm_id, lat, lon)`;
  - `road_class(tags) -> int | None`;
  - `direction(tags) -> int`: `1` — только по ходу линии, `-1` — только против, `0` — в обе стороны;
  - `build_graph(ways, keep=None) -> RoadGraph`;
  - `read_ways(path, bbox=None) -> list[RawWay]`;
  - CLI `vpr-m2 pack-roads --pbf FILE [--gpx F]... [--log S.jsonl]... [--buffer-m 300] --out DIR`.

Правила отбора:
- дорога берётся, если `highway` есть в `ROAD_CLASS`;
- исключаются:
  - `area=yes`;
  - `access` или `motor_vehicle` со значением `no` или `private`;
  - `service` со значением `parking_aisle`, `drive-through` или `emergency_access`.

Направление:
- `oneway=yes|true|1` → `1`;
- `oneway=-1` → `-1`;
- `oneway=no` → `0`;
- иначе `junction=roundabout|circular` или `highway=motorway` → `1`;
- иначе `0`.

При `-1` порядок узлов разворачивается, и ребро помечается односторонним.

Флаги:
- тоннель — `tunnel=yes|building_passage` или `covered=yes`;
- мост — `bridge` есть и не равен `no`.

Ребро строится для каждой пары соседних узлов. Пара с одинаковым узлом пропускается. С фильтром `keep` ребро остаётся, если `keep` истинно хотя бы для одного конца. Узлы без рёбер в граф не попадают.

`read_ways`:
- читает файл через `osmium.SimpleHandler` с `locations=True`;
- узел без координат (`not n.location.valid()`) разрывает линию на части;
- части короче 2 узлов отбрасываются;
- с `bbox` часть остаётся, только если хотя бы один её узел внутри.

- [ ] **Step 1: Падающие тесты** — `tests/test_osmgraph.py`:

```python
import json

import pytest

from vpr_bench.m2cli import main
from vpr_bench.osmgraph import RawWay, build_graph, direction, road_class
from vpr_bench.roadpack import FLAG_BRIDGE, FLAG_ONEWAY, FLAG_TUNNEL, read_roadpack


def test_road_class_filters():
    assert road_class({"highway": "residential"}) == 7
    assert road_class({"highway": "primary_link"}) == 3
    assert road_class({"highway": "footway"}) is None
    assert road_class({"highway": "service", "service": "parking_aisle"}) is None
    assert road_class({"highway": "service"}) == 9
    assert road_class({"highway": "residential", "access": "private"}) is None
    assert road_class({"highway": "pedestrian", "area": "yes"}) is None


def test_direction():
    assert direction({"oneway": "yes"}) == 1
    assert direction({"oneway": "-1"}) == -1
    assert direction({"junction": "roundabout"}) == 1
    assert direction({"highway": "motorway"}) == 1
    assert direction({"highway": "motorway", "oneway": "no"}) == 0
    assert direction({"highway": "primary"}) == 0


def _way(i, tags, *nodes):
    return RawWay(i, {"highway": "residential", **tags}, list(nodes))


def test_build_graph_edges_flags_and_shared_nodes():
    a, b, c, d = (1, 55.75, 37.60), (2, 55.751, 37.60), (3, 55.752, 37.60), (4, 55.751, 37.602)
    g = build_graph([
        _way(10, {}, a, b, c),
        _way(11, {"oneway": "-1", "tunnel": "yes"}, b, d),
        _way(12, {"bridge": "viaduct"}, c, c, d),   # повтор узла пропускается
    ])
    assert len(g.node_lats) == 4 and len(g.edge_from) == 5
    assert list(g.edge_way) == [10, 10, 11, 12, 12]
    k = 2  # way 11 развёрнут: d → b
    assert (g.node_lons[g.edge_from[k]], g.node_lons[g.edge_to[k]]) == (37.602, 37.60)
    assert g.edge_flags[k] == FLAG_ONEWAY | FLAG_TUNNEL
    assert g.edge_flags[3] == FLAG_BRIDGE and g.edge_flags[0] == 0
    assert g.edge_to[0] == g.edge_from[1]  # узел b общий


def test_build_graph_keep_drops_far_edges_and_nodes():
    near1, near2, far1, far2 = (1, 55.75, 37.60), (2, 55.751, 37.60), (3, 56.0, 38.0), (4, 56.001, 38.0)
    g = build_graph([_way(1, {}, near1, near2), _way(2, {}, far1, far2)], keep=lambda lat, lon: lat < 55.9)
    assert len(g.edge_from) == 1 and len(g.node_lats) == 2


OSM_XML = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" version="1" lat="55.7500" lon="37.6000"/>
  <node id="2" version="1" lat="55.7509" lon="37.6000"/>
  <node id="3" version="1" lat="55.7518" lon="37.6000"/>
  <node id="4" version="1" lat="55.7509" lon="37.6020"/>
  <way id="10" version="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
  <way id="11" version="1"><nd ref="2"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
  <way id="12" version="1"><nd ref="1"/><nd ref="4"/><tag k="highway" v="footway"/></way>
  <way id="13" version="1"><nd ref="1"/><nd ref="99"/><nd ref="3"/><nd ref="4"/><tag k="highway" v="service"/></way>
</osm>
"""

GPX = """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
<trkpt lat="55.7500" lon="37.6000"><time>2026-10-02T10:00:00Z</time></trkpt>
<trkpt lat="55.7518" lon="37.6000"><time>2026-10-02T10:00:20Z</time></trkpt>
</trkseg></trk></gpx>"""


def test_read_ways_splits_at_missing_nodes(tmp_path):
    pytest.importorskip("osmium")
    from vpr_bench.osmgraph import read_ways
    p = tmp_path / "t.osm"
    p.write_text(OSM_XML)
    ways = read_ways(p)
    assert sorted(w.id for w in ways) == [10, 11, 13]       # footway отброшен
    w13 = [w for w in ways if w.id == 13]
    assert len(w13) == 1 and [n[0] for n in w13[0].nodes] == [3, 4]  # узел 99 разорвал линию


def test_pack_roads_cli(tmp_path):
    pytest.importorskip("osmium")
    (tmp_path / "t.osm").write_text(OSM_XML)
    (tmp_path / "t.gpx").write_text(GPX)
    out = tmp_path / "roads"
    assert main(["pack-roads", "--pbf", str(tmp_path / "t.osm"), "--gpx", str(tmp_path / "t.gpx"),
                 "--out", str(out)]) == 0
    g, meta = read_roadpack(out)
    assert len(g.edge_from) == 4 and len(g.node_lats) == 4
    assert meta["source"] == "t.osm" and meta["buffer_m"] == 300.0 and "OpenStreetMap" in meta["attribution"]


def test_pack_roads_without_track_returns_2(tmp_path):
    (tmp_path / "t.osm").write_text(OSM_XML)
    assert main(["pack-roads", "--pbf", str(tmp_path / "t.osm"), "--out", str(tmp_path / "r")]) == 2
```

Run: `uv sync --extra dev --extra osm && uv run pytest tests/test_osmgraph.py -q`
Expected: FAIL (`ModuleNotFoundError: vpr_bench.osmgraph`).

- [ ] **Step 2: Реализация** — `src/vpr_bench/osmgraph.py`:

```python
"""Дорожный граф из OSM: отбор проезжих дорог, направление, тоннели и мосты, разбиение на рёбра-отрезки."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

import numpy as np

from vpr_bench.geo import BBox
from vpr_bench.roadpack import FLAG_BRIDGE, FLAG_ONEWAY, FLAG_TUNNEL, RoadGraph

ROAD_CLASS = {
    "motorway": 1, "motorway_link": 1, "trunk": 2, "trunk_link": 2, "primary": 3, "primary_link": 3,
    "secondary": 4, "secondary_link": 4, "tertiary": 5, "tertiary_link": 5, "unclassified": 6,
    "residential": 7, "living_street": 8, "service": 9,
}
_NO_ACCESS = {"no", "private"}
_EXCLUDED_SERVICE = {"parking_aisle", "drive-through", "emergency_access"}


@dataclass(frozen=True)
class RawWay:
    id: int
    tags: dict[str, str]
    nodes: list[tuple[int, float, float]]  # (osm id, lat, lon)


def road_class(tags: dict[str, str]) -> int | None:
    cls = ROAD_CLASS.get(tags.get("highway", ""))
    if cls is None or tags.get("area") == "yes":
        return None
    if tags.get("access") in _NO_ACCESS or tags.get("motor_vehicle") in _NO_ACCESS:
        return None
    if cls == ROAD_CLASS["service"] and tags.get("service") in _EXCLUDED_SERVICE:
        return None
    return cls


def direction(tags: dict[str, str]) -> int:
    """1 — ехать можно только по ходу линии, -1 — только против, 0 — в обе стороны."""
    ow = tags.get("oneway", "")
    if ow in ("yes", "true", "1"):
        return 1
    if ow == "-1":
        return -1
    if ow == "no":
        return 0
    if tags.get("junction") in ("roundabout", "circular") or tags.get("highway") == "motorway":
        return 1
    return 0


def _flags(tags: dict[str, str], d: int) -> int:
    f = FLAG_ONEWAY if d != 0 else 0
    if tags.get("tunnel") in ("yes", "building_passage") or tags.get("covered") == "yes":
        f |= FLAG_TUNNEL
    if tags.get("bridge") not in (None, "no"):
        f |= FLAG_BRIDGE
    return f


def build_graph(ways: Iterable[RawWay], keep: Callable[[float, float], bool] | None = None) -> RoadGraph:
    index: dict[int, int] = {}
    lats: list[float] = []
    lons: list[float] = []
    kept: dict[int, bool] = {}
    e_from: list[int] = []
    e_to: list[int] = []
    e_way: list[int] = []
    e_flags: list[int] = []
    e_cls: list[int] = []

    def inside(node: tuple[int, float, float]) -> bool:
        if keep is None:
            return True
        if node[0] not in kept:
            kept[node[0]] = bool(keep(node[1], node[2]))
        return kept[node[0]]

    def idx(node: tuple[int, float, float]) -> int:
        if node[0] not in index:
            index[node[0]] = len(lats)
            lats.append(node[1])
            lons.append(node[2])
        return index[node[0]]

    for w in ways:
        cls = road_class(w.tags)
        if cls is None or len(w.nodes) < 2:
            continue
        d = direction(w.tags)
        flags = _flags(w.tags, d)
        nodes = w.nodes if d >= 0 else list(reversed(w.nodes))
        for a, b in zip(nodes, nodes[1:]):
            if a[0] == b[0] or not (inside(a) or inside(b)):
                continue
            e_from.append(idx(a))
            e_to.append(idx(b))
            e_way.append(w.id)
            e_flags.append(flags)
            e_cls.append(cls)
    return RoadGraph(
        np.array(lats, dtype=np.float64), np.array(lons, dtype=np.float64), np.array(e_way, dtype=np.int64),
        np.array(e_from, dtype=np.int32), np.array(e_to, dtype=np.int32),
        np.array(e_flags, dtype=np.uint8), np.array(e_cls, dtype=np.uint8),
    )


def _in_bbox(b: BBox, lat: float, lon: float) -> bool:
    return b.min_lat <= lat <= b.max_lat and b.min_lon <= lon <= b.max_lon


def read_ways(path: Path, bbox: BBox | None = None) -> list[RawWay]:
    """Проезжие линии из .osm.pbf/.osm. Узел без координат разрывает линию на части."""
    import osmium  # необязательная зависимость: uv sync --extra osm

    out: list[RawWay] = []

    def flush(way_id: int, tags: dict[str, str], run: list[tuple[int, float, float]]) -> None:
        if len(run) >= 2 and (bbox is None or any(_in_bbox(bbox, la, lo) for _, la, lo in run)):
            out.append(RawWay(way_id, tags, list(run)))

    class Handler(osmium.SimpleHandler):
        def __init__(self) -> None:
            super().__init__()

        def way(self, w) -> None:
            if "highway" not in w.tags:
                return
            tags = {t.k: t.v for t in w.tags}
            if road_class(tags) is None:
                return
            run: list[tuple[int, float, float]] = []
            for n in w.nodes:
                if not n.location.valid():
                    flush(w.id, tags, run)
                    run = []
                    continue
                run.append((n.ref, n.location.lat, n.location.lon))
            flush(w.id, tags, run)

    Handler().apply_file(str(path), locations=True)
    return out
```

В `m2cli.py`:
1. Импорты: `from datetime import datetime, timezone`, `from vpr_bench.fieldlog import gps_track, read_log` (если `read_log` уже импортирован — только `gps_track`), `from vpr_bench.roadpack import write_roadpack`.
2. В `build_parser`:

```python
    pr = sub.add_parser("pack-roads", help="упаковать дорожный граф OSM в коридоре вокруг треков (roadpack)")
    pr.add_argument("--pbf", required=True, type=Path, help="выгрузка OSM (.osm.pbf или .osm)")
    pr.add_argument("--gpx", action="append", default=[], type=Path, help="трек поездки GPX")
    pr.add_argument("--log", action="append", default=[], type=Path, help="журнал кадров сессии (.jsonl): его GPS-трек")
    pr.add_argument("--buffer-m", type=float, default=300.0)
    pr.add_argument("--out", required=True, type=Path)
```

3. Функция и ранний выход в начале `main` (до чтения траектории):

```python
def _pack_roads(args) -> int:
    from vpr_bench.db_builder import Corridor
    from vpr_bench.osmgraph import build_graph, read_ways
    from vpr_bench.query import clean_track, parse_gpx

    tracks = [parse_gpx(p) for p in args.gpx]
    # Трек из журнала очищаем от подмены, иначе коридор уедет к точке, куда «прыгал» GPS.
    tracks += [clean_track(gps_track(read_log(p)[1]), max_hdop=None)[0] for p in args.log]
    tracks = [t for t in tracks if t]
    if not tracks:
        print("error: need at least one non-empty --gpx or --log track", file=sys.stderr)
        return 2
    corridor = Corridor(tracks, args.buffer_m)
    graph = build_graph(read_ways(args.pbf, corridor.bbox()), keep=corridor.contains)
    if len(graph.edge_from) == 0:
        print("error: no drivable roads in the corridor", file=sys.stderr)
        return 2
    meta = {
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": args.pbf.name, "buffer_m": args.buffer_m,
        "attribution": "© участники OpenStreetMap, ODbL 1.0",
    }
    write_roadpack(args.out, graph, meta)
    print(f"nodes={len(graph.node_lats)} edges={len(graph.edge_from)} -> {args.out}")
    return 0
```

```python
    args = build_parser().parse_args(argv)
    if args.command == "pack-roads":
        return _pack_roads(args)
```

- [ ] **Step 3: Тесты зелёные.**

Run: `uv run pytest -q`
Expected: не меньше 189 passed, 4 deselected. Если pyosmium не ставится (нет wheel), тесты с `importorskip` пропускаются: сообщить NEEDS_CONTEXT с текстом ошибки установки.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/pyproject.toml research/vpr_bench/uv.lock research/vpr_bench/src/vpr_bench/osmgraph.py research/vpr_bench/src/vpr_bench/m2cli.py research/vpr_bench/tests/test_osmgraph.py
git commit -m "feat(vpr-bench): pack-roads — OSM drivable road graph in a corridor around drives

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `RoadPack` и `RoadIndex` в `:core`

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/RoadPack.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/RoadIndex.kt`
- Create: `android/core/src/test/kotlin/io/visnav/core/RoadTestGraphs.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/RoadPackTest.kt`, `RoadIndexTest.kt`

**Interfaces:**
- Consumes: `Enu(lat0, lon0)` (`toEn`, `toLatLon`); эталон `research/vpr_bench/tests/data/roadpack_fixture/` (Task 1).
- Produces:
  - `RoadPack(lats, lons, way: LongArray, from: IntArray, to: IntArray, flags: ByteArray, cls: ByteArray)`:
    - свойства `nodeCount`, `edgeCount`;
    - методы `oneway(edge)`, `tunnel(edge)`, `roadClass(edge): Int`;
    - `RoadPack.parse(ByteBuffer)`;
    - константы `RoadPack.FLAG_ONEWAY/FLAG_TUNNEL/FLAG_BRIDGE`.
  - `RoadPackMeta(format, createdAt, source, nodeCount, edgeCount)`, `RoadPackMeta.parse(text)`.
  - `object RoadClass`: константы `MOTORWAY = 1` … `SERVICE = 9`, функция `lateralSigmaM(cls: Int): Double`.
  - `data class RoadProjection(edge: Int, t: Double, e: Double, n: Double, distM: Double)`.
  - `RoadIndex(pack, enu, cellM = 50.0)`:
    - поля `pack`, `nodeE`, `nodeN`, `length`, `bearing` (азимут `from → to`, рад от севера по часовой), `degree`, `outNodes`, `outLen`;
    - методы `project(edge, e, n)`, `near(e, n, radiusM): List<RoadProjection>`, `routeFrom(node, limitM): Map<Int, Double>`.
  - Тестовые помощники `EdgeSpec`, `roadPackOf(enu, nodes, edges)`, `straightRoad(...)` — их используют Task 4 и 5.

- [ ] **Step 1: Помощник тестов** — `RoadTestGraphs.kt`:

```kotlin
package io.visnav.core

/** Ребро тестового графа: индексы узлов, wayId, флаги RoadPack.FLAG_*, класс RoadClass. */
data class EdgeSpec(val from: Int, val to: Int, val way: Long, val flags: Int = 0, val cls: Int = RoadClass.RESIDENTIAL)

/** Тестовый граф: узлы заданы в метрах (восток, север) относительно enu. */
fun roadPackOf(enu: Enu, nodes: List<Pair<Double, Double>>, edges: List<EdgeSpec>): RoadPack {
    val lats = DoubleArray(nodes.size)
    val lons = DoubleArray(nodes.size)
    nodes.forEachIndexed { i, (e, n) -> val ll = enu.toLatLon(e, n); lats[i] = ll[0]; lons[i] = ll[1] }
    return RoadPack(
        lats, lons, LongArray(edges.size) { edges[it].way }, IntArray(edges.size) { edges[it].from },
        IntArray(edges.size) { edges[it].to }, ByteArray(edges.size) { edges[it].flags.toByte() },
        ByteArray(edges.size) { edges[it].cls.toByte() },
    )
}

/**
 * Прямой участок дороги от (e0, n0) до (e1, n1) с узлами через stepM, один wayId. Узлы нумеруются с
 * nodeOffset — так несколько участков склеиваются в один граф (общие узлы передаются через startNode).
 */
fun straightRoad(
    e0: Double, n0: Double, e1: Double, n1: Double, stepM: Double, way: Long, nodeOffset: Int,
    flags: Int = 0, cls: Int = RoadClass.RESIDENTIAL, startNode: Int? = null,
): Pair<List<Pair<Double, Double>>, List<EdgeSpec>> {
    val len = kotlin.math.hypot(e1 - e0, n1 - n0)
    val k = maxOf(1, kotlin.math.round(len / stepM).toInt())
    val nodes = ArrayList<Pair<Double, Double>>()
    val ids = ArrayList<Int>()
    for (i in 0..k) {
        if (i == 0 && startNode != null) { ids.add(startNode); continue }
        nodes.add(Pair(e0 + (e1 - e0) * i / k, n0 + (n1 - n0) * i / k))
        ids.add(nodeOffset + nodes.size - 1)
    }
    val edges = (0 until k).map { EdgeSpec(ids[it], ids[it + 1], way, flags, cls) }
    return nodes to edges
}
```

- [ ] **Step 2: Падающие тесты** — `RoadPackTest.kt`:

```kotlin
package io.visnav.core

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoadPackTest {
    private val fixture = File("../../research/vpr_bench/tests/data/roadpack_fixture")

    @Test fun parsesCrossLanguageFixture() {
        val pack = RoadPack.parse(ByteBuffer.wrap(File(fixture, "roadpack.bin").readBytes()))
        assertEquals(3, pack.nodeCount); assertEquals(2, pack.edgeCount)
        assertEquals(37.602, pack.lons[2], 1e-12)
        assertEquals(listOf(101L, 202L), pack.way.toList())
        assertFalse(pack.oneway(0)); assertTrue(pack.oneway(1)); assertTrue(pack.tunnel(1))
        assertEquals(RoadClass.RESIDENTIAL, pack.roadClass(0)); assertEquals(RoadClass.PRIMARY, pack.roadClass(1))
        val meta = RoadPackMeta.parse(File(fixture, "roadpack.json").readText())
        assertEquals("2026-10-02T00:00:00Z", meta.createdAt); assertEquals(2, meta.edgeCount)
    }

    @Test fun rejectsBadMagicAndSize() {
        val raw = File(fixture, "roadpack.bin").readBytes()
        assertFailsWith<IllegalArgumentException> { RoadPack.parse(ByteBuffer.wrap(raw.copyOf(raw.size - 1))) }
        val bad = raw.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { RoadPack.parse(ByteBuffer.wrap(bad)) }
    }

    @Test fun rejectsEdgeOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            RoadPack(doubleArrayOf(0.0), doubleArrayOf(0.0), longArrayOf(1), intArrayOf(0), intArrayOf(1),
                byteArrayOf(0), byteArrayOf(7))
        }
    }

    @Test fun lateralSigmaByClass() {
        assertEquals(7.0, RoadClass.lateralSigmaM(RoadClass.MOTORWAY))
        assertEquals(6.0, RoadClass.lateralSigmaM(RoadClass.PRIMARY))
        assertEquals(4.0, RoadClass.lateralSigmaM(RoadClass.RESIDENTIAL))
    }
}
```

`RoadIndexTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoadIndexTest {
    private val enu = Enu(55.75, 37.60)

    @Test fun projectClampsToSegmentEnds() {
        val idx = RoadIndex(roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0), listOf(EdgeSpec(0, 1, 1))), enu)
        val mid = idx.project(0, 50.0, 10.0)
        assertEquals(0.5, mid.t, 1e-6); assertEquals(10.0, mid.distM, 1e-6)
        assertEquals(0.0, idx.project(0, -20.0, 0.0).t); assertEquals(20.0, idx.project(0, -20.0, 0.0).distM, 1e-6)
        assertEquals(1.0, idx.project(0, 130.0, 0.0).t)
        assertEquals(Math.PI / 2, idx.bearing[0], 1e-6)  // на восток
    }

    @Test fun nearFindsLongEdgesAcrossCellsSortedByDistance() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 30.0, 1000.0 to 30.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(2, 3, 2)))
        val idx = RoadIndex(pack, enu)
        val hits = idx.near(500.0, 5.0, 50.0)
        assertEquals(listOf(0, 1), hits.map { it.edge })
        assertEquals(5.0, hits[0].distM, 1e-3); assertEquals(25.0, hits[1].distM, 1e-3)
        assertTrue(idx.near(500.0, 100.0, 50.0).isEmpty())
    }

    @Test fun degreeAndOnewayAdjacency() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1, flags = RoadPack.FLAG_ONEWAY)))
        val idx = RoadIndex(pack, enu)
        assertEquals(listOf(1, 2, 1), idx.degree.toList())
        assertTrue(1 in idx.outNodes[0].toList() && 2 in idx.outNodes[1].toList() && 0 in idx.outNodes[1].toList())
        assertFalse(1 in idx.outNodes[2].toList())  // по односторонней назад нельзя
    }

    @Test fun routeFromRespectsOnewayAndLimit() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1, flags = RoadPack.FLAG_ONEWAY)))
        val idx = RoadIndex(pack, enu)
        assertEquals(200.0, idx.routeFrom(0, 500.0)[2]!!, 1e-3)
        assertFalse(0 in idx.routeFrom(2, 500.0))
        val limited = idx.routeFrom(0, 150.0)
        assertTrue(1 in limited); assertFalse(2 in limited)
    }
}
```

Run: `./gradlew :core:test --tests 'io.visnav.core.Road*'`
Expected: FAIL при компиляции (`Unresolved reference: RoadPack`).

- [ ] **Step 3: Реализация** — `RoadPack.kt`:

```kotlin
package io.visnav.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * roadpack v1 («VNRD»): дорожный граф OSM. Заголовок 16 байт (magic, version u16, reserved u16, nodeCount u32,
 * edgeCount u32), затем lats f64[n], lons f64[n], way i64[m], from i32[m], to i32[m], flags u8[m], cls u8[m]
 * (little-endian). Ребро — прямой отрезок между соседними узлами OSM-линии; по односторонней — только from → to.
 */
class RoadPack(
    val lats: DoubleArray, val lons: DoubleArray,
    val way: LongArray, val from: IntArray, val to: IntArray,
    val flags: ByteArray, val cls: ByteArray,
) {
    val nodeCount: Int get() = lats.size
    val edgeCount: Int get() = from.size

    init {
        require(lons.size == lats.size) { "lats/lons size mismatch" }
        val m = from.size
        require(to.size == m && way.size == m && flags.size == m && cls.size == m) { "edge arrays size mismatch" }
        for (k in 0 until m) {
            require(from[k] in 0 until nodeCount && to[k] in 0 until nodeCount) { "edge $k: node index out of range" }
        }
    }

    fun oneway(edge: Int): Boolean = flags[edge].toInt() and FLAG_ONEWAY != 0
    fun tunnel(edge: Int): Boolean = flags[edge].toInt() and FLAG_TUNNEL != 0
    fun roadClass(edge: Int): Int = cls[edge].toInt() and 0xFF

    companion object {
        const val MAGIC = 0x44524E56 // "VNRD" как little-endian int
        const val FLAG_ONEWAY = 1
        const val FLAG_TUNNEL = 2
        const val FLAG_BRIDGE = 4
        private const val HEADER_SIZE = 16

        fun parse(buf: ByteBuffer): RoadPack {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val total = b.remaining().toLong()
            require(total >= HEADER_SIZE) { "roadpack too short" }
            require(b.int == MAGIC) { "not a roadpack (bad magic)" }
            val version = b.short.toInt() and 0xFFFF
            b.short // reserved
            require(version == 1) { "unsupported roadpack version=$version" }
            val n = b.int
            val m = b.int
            require(n >= 0 && m >= 0) { "bad header nodes=$n edges=$m" }
            val expected = HEADER_SIZE + 16L * n + 18L * m
            require(total == expected) { "roadpack size $total != expected $expected" }
            val lats = DoubleArray(n).also { b.asDoubleBuffer().get(it) }; b.position(b.position() + 8 * n)
            val lons = DoubleArray(n).also { b.asDoubleBuffer().get(it) }; b.position(b.position() + 8 * n)
            val way = LongArray(m).also { b.asLongBuffer().get(it) }; b.position(b.position() + 8 * m)
            val from = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
            val to = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
            val flags = ByteArray(m).also { b.get(it) }
            val cls = ByteArray(m).also { b.get(it) }
            return RoadPack(lats, lons, way, from, to, flags, cls)
        }
    }
}

@Serializable
data class RoadPackMeta(
    val format: String,
    @SerialName("created_at") val createdAt: String,
    val source: String,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("edge_count") val edgeCount: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): RoadPackMeta = json.decodeFromString(serializer(), text)
    }
}

/** Коды классов дорог (общие с vpr_bench.osmgraph.ROAD_CLASS). */
object RoadClass {
    const val MOTORWAY = 1
    const val TRUNK = 2
    const val PRIMARY = 3
    const val SECONDARY = 4
    const val TERTIARY = 5
    const val UNCLASSIFIED = 6
    const val RESIDENTIAL = 7
    const val LIVING_STREET = 8
    const val SERVICE = 9

    /** Сигма поперечного псевдоизмерения — примерно полуширина проезжей части. */
    fun lateralSigmaM(cls: Int): Double = when (cls) {
        MOTORWAY, TRUNK -> 7.0
        PRIMARY -> 6.0
        SECONDARY -> 5.0
        else -> 4.0
    }
}
```

`RoadIndex.kt`:

```kotlin
package io.visnav.core

import java.util.PriorityQueue
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Проекция точки на ребро: t ∈ [0, 1] от from к to, (e, n) — ближайшая точка ребра, distM — расстояние до неё. */
data class RoadProjection(val edge: Int, val t: Double, val e: Double, val n: Double, val distM: Double)

/**
 * Граф в плоских координатах фильтра [enu]: сетка ячеек для поиска ближайших рёбер и направленные
 * смежности для путей по графу (по одностороннему ребру — только from → to).
 */
class RoadIndex(val pack: RoadPack, val enu: Enu, private val cellM: Double = 50.0) {
    val nodeE = DoubleArray(pack.nodeCount)
    val nodeN = DoubleArray(pack.nodeCount)
    val length = DoubleArray(pack.edgeCount)
    /** Азимут ребра from → to, рад от севера по часовой. */
    val bearing = DoubleArray(pack.edgeCount)
    /** Число рёбер, сходящихся в узле (без учёта направления). */
    val degree = IntArray(pack.nodeCount)
    val outNodes: Array<IntArray>
    val outLen: Array<DoubleArray>
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until pack.nodeCount) {
            val en = enu.toEn(pack.lats[i], pack.lons[i]); nodeE[i] = en[0]; nodeN[i] = en[1]
        }
        val outN = Array(pack.nodeCount) { ArrayList<Int>() }
        val outL = Array(pack.nodeCount) { ArrayList<Double>() }
        for (k in 0 until pack.edgeCount) {
            val a = pack.from[k]; val b = pack.to[k]
            val de = nodeE[b] - nodeE[a]; val dn = nodeN[b] - nodeN[a]
            length[k] = hypot(de, dn); bearing[k] = atan2(de, dn)
            degree[a]++; degree[b]++
            outN[a].add(b); outL[a].add(length[k])
            if (!pack.oneway(k)) { outN[b].add(a); outL[b].add(length[k]) }
            for (cx in cell(minOf(nodeE[a], nodeE[b]))..cell(maxOf(nodeE[a], nodeE[b]))) {
                for (cy in cell(minOf(nodeN[a], nodeN[b]))..cell(maxOf(nodeN[a], nodeN[b]))) {
                    grid.getOrPut(key(cx, cy)) { ArrayList() }.add(k)
                }
            }
        }
        outNodes = Array(pack.nodeCount) { outN[it].toIntArray() }
        outLen = Array(pack.nodeCount) { outL[it].toDoubleArray() }
    }

    private fun cell(v: Double): Int = floor(v / cellM).toInt()
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    fun project(edge: Int, e: Double, n: Double): RoadProjection {
        val a = pack.from[edge]; val b = pack.to[edge]
        val de = nodeE[b] - nodeE[a]; val dn = nodeN[b] - nodeN[a]
        val len2 = de * de + dn * dn
        val t = if (len2 == 0.0) 0.0 else (((e - nodeE[a]) * de + (n - nodeN[a]) * dn) / len2).coerceIn(0.0, 1.0)
        val pe = nodeE[a] + t * de; val pn = nodeN[a] + t * dn
        return RoadProjection(edge, t, pe, pn, hypot(e - pe, n - pn))
    }

    /** Рёбра ближе radiusM к точке, по возрастанию расстояния. */
    fun near(e: Double, n: Double, radiusM: Double): List<RoadProjection> {
        val seen = HashSet<Int>()
        val out = ArrayList<RoadProjection>()
        for (cx in cell(e - radiusM)..cell(e + radiusM)) for (cy in cell(n - radiusM)..cell(n + radiusM)) {
            val edges = grid[key(cx, cy)] ?: continue
            for (k in edges) if (seen.add(k)) {
                val p = project(k, e, n)
                if (p.distM <= radiusM) out.add(p)
            }
        }
        out.sortBy { it.distM }
        return out
    }

    /** Кратчайшие расстояния по графу из node с учётом односторонних, не дальше limitM (Дейкстра). */
    fun routeFrom(node: Int, limitM: Double): Map<Int, Double> {
        val dist = HashMap<Int, Double>()
        dist[node] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        pq.add(0.0 to node)
        while (pq.isNotEmpty()) {
            val (d, u) = pq.poll()
            if (d > (dist[u] ?: Double.MAX_VALUE)) continue
            val ns = outNodes[u]; val ls = outLen[u]
            for (i in ns.indices) {
                val nd = d + ls[i]
                if (nd > limitM) continue
                if (nd < (dist[ns[i]] ?: Double.MAX_VALUE)) { dist[ns[i]] = nd; pq.add(nd to ns[i]) }
            }
        }
        return dist
    }
}
```

- [ ] **Step 4: Тесты зелёные.**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, `:core` — не меньше 137 тестов.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): roadpack reader and road index with grid search and routing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `MapMatcher` — онлайн-HMM по рёбрам

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/MapMatcher.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/MapMatcherTest.kt`

**Interfaces:**
- Consumes: `RoadIndex`, `RoadProjection`, `RoadPack`, `wrapAngle` (Ekf2d.kt).
- Produces:
  - `data class MatchConfig(searchRadiusM = 50.0, maxCandidates = 8, minEmissionSigmaM = 5.0, headingSigmaDeg = 30.0, minHeadingSpeedMps = 3.0, betaM = 10.0, teleportPenalty = 20.0, junctionM = 20.0)`;
  - `data class RoadMatch(edge: Int, wayId: Long, e: Double, n: Double, distM: Double, travelBearing: Double, confidence: Double, roadClass: Int, tunnel: Boolean, nearJunction: Boolean)`;
  - `class MapMatcher(index, config = MatchConfig())` с методами `step(e, n, sigmaM, psi, speedMps): RoadMatch?` и `reset()`.

Модель:
- **Состояние** — ребро и направление движения по нему; для одностороннего ребра — только вперёд.
- **Эмиссия:**
  - `−½(d/σe)²`, где `σe = max(σ фильтра, 5 м)`;
  - при скорости ≥ 3 м/с ещё `−½(Δψ/30°)²`, где Δψ — разница курса фильтра и направления движения по ребру.
- **Переход:**
  - `−|путь по графу − пройденное фильтром расстояние| / β`, β = 10 м;
  - путь по тому же ребру в том же направлении назад больше чем на 5 м невозможен;
  - путь к другому ребру = остаток до выходного узла + кратчайший путь до входного узла + расстояние от входа до проекции; поиск ограничен `2·пройдено + 50 м`;
  - если пути нет, переход всё равно возможен, но со штрафом `teleportPenalty = 20`. Иначе ошибочно выбранная параллельная улица, не связанная с правильной в пределах поиска, держала бы привязку сколь угодно долго. Со штрафом привязка переключается, когда разница в эмиссиях накопится (при 25 м между улицами и σ 8 м — за 2–3 с).
- **Новая цепочка** начинается, если предыдущих состояний нет (после потери дорог или `reset()`).
- **Нормировка и отсечение:** очки сдвигаются так, чтобы максимум был 0; состояния ниже −30 отбрасываются.
- **Уверенность** — доля `exp(очков)` состояний с тем же wayId, что у лучшего.
- **`nearJunction`:** до ближайшего конца ребра ≤ 20 м, и у этого узла степень ≠ 2 (перекрёсток или тупик).

- [ ] **Step 1: Падающие тесты** — `MapMatcherTest.kt`:

```kotlin
package io.visnav.core

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapMatcherTest {
    private val enu = Enu(55.75, 37.60)

    private fun graph(vararg parts: Pair<List<Pair<Double, Double>>, List<EdgeSpec>>): RoadPack =
        roadPackOf(enu, parts.flatMap { it.first }, parts.flatMap { it.second })

    /** Две параллельные двусторонние дороги: way 1 по n = 0, way 2 по n = gapM; e от 0 до 1000, узлы через 100 м. */
    private fun parallel(gapM: Double, flagsA: Int = 0): RoadPack {
        val a = straightRoad(0.0, 0.0, 1000.0, 0.0, 100.0, 1, 0, flags = flagsA)
        val b = straightRoad(0.0, gapM, 1000.0, gapM, 100.0, 2, a.first.size)
        return graph(a, b)
    }

    @Test fun staysOnTrueRoadNextToParallelOne() {
        val m = MapMatcher(RoadIndex(parallel(25.0), enu))
        val rnd = Random(1)
        val ways = (0 until 100).map { k ->
            m.step(10.0 * k, 8.0 * rnd.nextGaussian(), 8.0, Math.PI / 2, 10.0)?.wayId
        }
        val onA = ways.drop(3).count { it == 1L }
        assertTrue(onA >= 95, "on way 1: $onA of 97")
    }

    @Test fun followsTurnAtJunction() {
        // Way 1 на восток до (500, 0); там перекрёсток: way 3 на север, way 4 прямо на восток.
        val a = straightRoad(0.0, 0.0, 500.0, 0.0, 100.0, 1, 0)
        val junction = a.first.size - 1
        val c = straightRoad(500.0, 0.0, 500.0, 500.0, 100.0, 3, a.first.size, startNode = junction)
        val d = straightRoad(500.0, 0.0, 1000.0, 0.0, 100.0, 4, a.first.size + c.first.size, startNode = junction)
        val m = MapMatcher(RoadIndex(graph(a, c, d), enu))
        val rnd = Random(2)
        // На восток до перекрёстка, затем на север; (e, n, курс).
        val path = (0..50).map { Triple(10.0 * it, 0.0, Math.PI / 2) } + (1..50).map { Triple(500.0, 10.0 * it, 0.0) }
        val results = path.map { (e, n, psi) ->
            Triple(e, n, m.step(e + 3 * rnd.nextGaussian(), n + 3 * rnd.nextGaussian(), 5.0, psi, 10.0))
        }
        assertTrue(results.filter { it.second == 0.0 && it.first <= 450.0 }.all { it.third?.wayId == 1L })
        assertTrue(results.filter { it.second >= 50.0 }.all { it.third?.wayId == 3L })
    }

    @Test fun onewayForbidsWrongDirection() {
        // Way 1 односторонняя на восток, way 2 двусторонняя в 15 м; машина едет на запад ровно посередине.
        val m = MapMatcher(RoadIndex(parallel(15.0, flagsA = RoadPack.FLAG_ONEWAY), enu))
        val res = (0 until 80).map { k -> m.step(1000.0 - 10.0 * k, 7.5, 8.0, -Math.PI / 2, 10.0) }
        val tail = res.drop(5)
        assertTrue(tail.count { it?.wayId == 2L } >= 72, "way 2: ${tail.count { it?.wayId == 2L }}")
        assertTrue(tail.all { (it?.confidence ?: 0.0) >= 0.9 })
    }

    @Test fun offRoadReturnsNullAndRecovers() {
        val m = MapMatcher(RoadIndex(parallel(500.0), enu))
        repeat(5) { assertEquals(1L, m.step(10.0 * it, 0.0, 5.0, Math.PI / 2, 10.0)?.wayId) }
        assertNull(m.step(60.0, 250.0, 5.0, Math.PI / 2, 10.0))
        assertNull(m.step(70.0, 250.0, 5.0, Math.PI / 2, 10.0))
        assertEquals(1L, m.step(80.0, 0.0, 5.0, Math.PI / 2, 10.0)?.wayId)
    }

    @Test fun confidenceLowWhenAmbiguousHighWhenAlone() {
        val ambiguous = MapMatcher(RoadIndex(parallel(10.0), enu))
        val amb = (0 until 30).map { ambiguous.step(10.0 * it, 5.0, 8.0, Math.PI / 2, 10.0)!!.confidence }
        assertTrue(amb.drop(3).all { it < 0.9 }, "ambiguous: $amb")
        val alone = MapMatcher(RoadIndex(parallel(500.0), enu))
        val single = (0 until 30).map { alone.step(10.0 * it, 2.0, 8.0, Math.PI / 2, 10.0)!!.confidence }
        assertTrue(single.drop(3).all { it > 0.95 }, "alone: $single")
    }

    @Test fun nearJunctionFlag() {
        val a = straightRoad(0.0, 0.0, 500.0, 0.0, 100.0, 1, 0)
        val junction = a.first.size - 1
        val c = straightRoad(500.0, 0.0, 500.0, 500.0, 100.0, 3, a.first.size, startNode = junction)
        val d = straightRoad(500.0, 0.0, 1000.0, 0.0, 100.0, 4, a.first.size + c.first.size, startNode = junction)
        val m = MapMatcher(RoadIndex(graph(a, c, d), enu))
        val mid = m.step(250.0, 0.0, 5.0, Math.PI / 2, 10.0)
        assertNotNull(mid); assertTrue(!mid.nearJunction)
        m.reset()
        val atJunction = m.step(490.0, 0.0, 5.0, Math.PI / 2, 10.0)
        assertNotNull(atJunction); assertTrue(atJunction.nearJunction)
    }
}
```

Run: `./gradlew :core:test --tests 'io.visnav.core.MapMatcherTest'`
Expected: FAIL при компиляции (`Unresolved reference: MapMatcher`).

- [ ] **Step 2: Реализация** — `MapMatcher.kt`:

```kotlin
package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

data class MatchConfig(
    val searchRadiusM: Double = 50.0,
    val maxCandidates: Int = 8,
    val minEmissionSigmaM: Double = 5.0,
    val headingSigmaDeg: Double = 30.0,
    val minHeadingSpeedMps: Double = 3.0,
    val betaM: Double = 10.0,
    /** Штраф перехода, если по графу пути нет: позволяет выйти с ошибочно выбранной несвязанной улицы. */
    val teleportPenalty: Double = 20.0,
    val junctionM: Double = 20.0,
)

/** Результат шага: ребро и направление движения по нему (travelBearing), точка на оси дороги, уверенность ∈ [0, 1]. */
data class RoadMatch(
    val edge: Int, val wayId: Long, val e: Double, val n: Double, val distM: Double,
    val travelBearing: Double, val confidence: Double, val roadClass: Int, val tunnel: Boolean,
    val nearJunction: Boolean,
)

/**
 * Онлайн-привязка к дорогам: HMM по рёбрам графа (Newson–Krumm), прямой проход Витерби без задержки.
 * Состояние — ребро и направление движения (по одностороннему ребру — только вперёд). Эмиссия — расстояние
 * до ребра и расхождение курса; переход — |путь по графу − пройденное фильтром расстояние| / β.
 */
class MapMatcher(private val index: RoadIndex, private val config: MatchConfig = MatchConfig()) {
    private class State(val proj: RoadProjection, val forward: Boolean, val score: Double)

    private var prev: List<State> = emptyList()
    private var prevE = 0.0
    private var prevN = 0.0

    fun reset() { prev = emptyList() }

    fun step(e: Double, n: Double, sigmaM: Double, psi: Double, speedMps: Double): RoadMatch? {
        val projections = index.near(e, n, config.searchRadiusM).take(config.maxCandidates)
        if (projections.isEmpty()) { reset(); return null }
        val sigE = max(sigmaM, config.minEmissionSigmaM)
        val sigPsi = Math.toRadians(config.headingSigmaDeg)
        val useHeading = speedMps >= config.minHeadingSpeedMps
        val trav = hypot(e - prevE, n - prevN)
        val limit = 2 * trav + 50.0
        val cache = HashMap<Int, Map<Int, Double>>()
        val cand = ArrayList<State>()
        val emission = ArrayList<Double>()
        val viaPrev = ArrayList<Double>()
        for (p in projections) for (forward in booleanArrayOf(true, false)) {
            if (!forward && index.pack.oneway(p.edge)) continue
            val z = p.distM / sigE
            var em = -0.5 * z * z
            if (useHeading) {
                val d = wrapAngle(psi - travelBearing(p.edge, forward)) / sigPsi
                em -= 0.5 * d * d
            }
            var best = Double.NEGATIVE_INFINITY
            for (s in prev) {
                val r = route(s, p, forward, cache, limit)
                val tr = if (r == null) -config.teleportPenalty else -abs(r - trav) / config.betaM
                best = max(best, s.score + tr)
            }
            cand.add(State(p, forward, 0.0)); emission.add(em); viaPrev.add(best)
        }
        // Нет предыдущих состояний (начало или потеря дорог) — цепочка начинается заново.
        val restart = prev.isEmpty()
        val scores = DoubleArray(cand.size) { if (restart) emission[it] else viaPrev[it] + emission[it] }
        val top = scores.max()
        val states = cand.indices.filter { scores[it] - top > PRUNE }
            .map { State(cand[it].proj, cand[it].forward, scores[it] - top) }
        prev = states; prevE = e; prevN = n

        val best = states.maxBy { it.score }
        val way = index.pack.way[best.proj.edge]
        var total = 0.0
        var sameWay = 0.0
        for (s in states) {
            val w = exp(s.score)
            total += w
            if (index.pack.way[s.proj.edge] == way) sameWay += w
        }
        val p = best.proj
        return RoadMatch(
            p.edge, way, p.e, p.n, p.distM, travelBearing(p.edge, best.forward), sameWay / total,
            index.pack.roadClass(p.edge), index.pack.tunnel(p.edge), nearJunction(p),
        )
    }

    private fun travelBearing(edge: Int, forward: Boolean): Double =
        if (forward) index.bearing[edge] else wrapAngle(index.bearing[edge] + PI)

    private fun along(t: Double, len: Double, forward: Boolean): Double = if (forward) t * len else (1 - t) * len

    /** Путь по графу от состояния s до проекции p при движении forward; null — пути нет в пределах limit. */
    private fun route(
        s: State, p: RoadProjection, forward: Boolean, cache: HashMap<Int, Map<Int, Double>>, limit: Double,
    ): Double? {
        val sLen = index.length[s.proj.edge]
        val sPos = along(s.proj.t, sLen, s.forward)
        if (s.proj.edge == p.edge && s.forward == forward) {
            val d = along(p.t, sLen, forward) - sPos
            return if (d >= -BACKTRACK_TOLERANCE_M) abs(d) else null
        }
        val exit = if (s.forward) index.pack.to[s.proj.edge] else index.pack.from[s.proj.edge]
        val entry = if (forward) index.pack.from[p.edge] else index.pack.to[p.edge]
        val mid = cache.getOrPut(exit) { index.routeFrom(exit, limit) }[entry] ?: return null
        return (sLen - sPos) + mid + along(p.t, index.length[p.edge], forward)
    }

    private fun nearJunction(p: RoadProjection): Boolean {
        val a = index.pack.from[p.edge]; val b = index.pack.to[p.edge]
        val len = index.length[p.edge]
        return (index.degree[a] != 2 && p.t * len <= config.junctionM) ||
            (index.degree[b] != 2 && (1 - p.t) * len <= config.junctionM)
    }

    private companion object {
        const val BACKTRACK_TOLERANCE_M = 5.0
        const val PRUNE = -30.0
    }
}
```

- [ ] **Step 3: Тесты зелёные.**

Run: `./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, `:core` — не меньше 143 тестов. Пороги тестов не ослаблять. Если тест не проходит, сначала проверить модель по описанию выше. Если расхождение в самой модели, сообщить NEEDS_CONTEXT с цифрами.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): online HMM map matcher over road graph edges

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Подсказка фильтру дорогой и поля дороги в `Localizer`

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/Ekf2d.kt`: метод `updateLateral`.
- Modify: `android/core/src/main/kotlin/io/visnav/core/Localizer.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/TrajectoryFormat.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/LocalizerRoadTest.kt` (новый); `Ekf2dTest.kt` (дополнить); тест заголовка и строки `TrajectoryFormat` (обновить точные строки и добавить случай с дорогой).

**Interfaces:**
- Consumes: `MapMatcher`, `MatchConfig`, `RoadIndex`, `RoadPack`, `RoadClass.lateralSigmaM` (Task 3–4).
- Produces:
  - `Ekf2d.updateLateral(e0: Double, n0: Double, theta: Double, sigma: Double): Boolean`;
  - `data class RoadInfo(wayId: Long, lat: Double, lon: Double, confidence: Double, used: Boolean)`;
  - `LocalizerOutput` получает последнее поле `road: RoadInfo? = null`;
  - конструктор `Localizer(pack, config = LocalizerConfig(), roads: RoadPack? = null)`;
  - новые поля `LocalizerConfig`: `roadConstraint: Boolean = true`, `minRoadConfidence: Double = 0.9`, `roadHeadingSigmaDeg: Double = 10.0`, `gnssQuietMs: Double = 2000.0`, `minRoadSpeedMps: Double = 2.0`, `matchConfig: MatchConfig = MatchConfig()`;
  - `TrajectoryFormat.row(...)` дописывает `,"way_id":…,"road_lat":…,"road_lon":…,"road_conf":…,"road_used":…` перед закрывающей скобкой;
  - `TrajectoryFormat.fusionHeader(sessionStartedMs, refpackCreatedAt, roadsCreatedAt: String? = null)` добавляет `,"roads":…` в конец.

Поведение `Localizer`:
- **Инициализация фильтра** (первый `LocEvent`):
  - `matcher = roads?.let { MapMatcher(RoadIndex(it, enu!!), config.matchConfig) }`;
  - `lastGnssFusedT = t`.
- **Переинициализация по монитору:** `matcher?.reset()`, `lastGnssFusedT = t`.
- **`applyFix`:** `lastGnssFusedT = ev.tMs`, если `updatePosition` принят.
- **`onFrame`:** после визуального блока и до `updateMode` выполняется `val road = matcher?.let { stepRoad(it, t) }`, результат идёт в вывод.
- **`stepRoad`:**
  - делает шаг привязки по `ekf.x[0..3]` и `ekf.posSigma()`;
  - подсказку фильтру применяет, только если выполнены все условия:
    - `config.roadConstraint`;
    - `t − lastGnssFusedT > gnssQuietMs`;
    - уверенность ≥ `minRoadConfidence`;
    - `!nearJunction`;
    - скорость фильтра ≥ `minRoadSpeedMps`;
  - подсказка: `updateLateral(точка на оси, travelBearing, RoadClass.lateralSigmaM(класс))`, а если она принята — `updateHeading(travelBearing, 10°)`;
  - `used` = принят ли `updateLateral`.

- [ ] **Step 1: Падающие тесты.**

В `Ekf2dTest.kt` добавить:

```kotlin
    @Test fun updateLateralPullsOnlyAcrossTheRoad() {
        val ekf = Ekf2d()
        ekf.init(0.0, 10.0, Math.PI / 2, 10.0, 10.0, 0.1, 1.0)
        // Дорога на восток через (0, 0): поперечное смещение = 10 м к северу.
        assertTrue(ekf.updateLateral(0.0, 0.0, Math.PI / 2, 4.0))
        assertTrue(ekf.x[1] < 3.0, "n=${ekf.x[1]}")
        assertEquals(0.0, ekf.x[0], 1e-9)
    }

    @Test fun updateLateralIsGated() {
        val ekf = Ekf2d()
        ekf.init(0.0, 1000.0, Math.PI / 2, 10.0, 3.0, 0.1, 1.0)
        assertFalse(ekf.updateLateral(0.0, 0.0, Math.PI / 2, 4.0))
        assertEquals(1000.0, ekf.x[1], 1e-9)
    }
```

(Если в `Ekf2dTest.kt` нет импортов `assertFalse`, `assertTrue` или `assertEquals` из `kotlin.test`, добавить их.)

`LocalizerRoadTest.kt`:

```kotlin
package io.visnav.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 10 м/с на восток 120 с; GNSS 1 Гц вне окна [30 с, 90 с); в окне гироскоп смещён на biasRadS, курс
 * счисления уплывает. Кадры 2 Гц без дескрипторов (visual = false). Дорога way 1 — по треку (n = 0),
 * way 2 — параллельно в 25 м к северу.
 */
class LocalizerRoadTest {
    private val enu = Enu(55.75, 37.60)
    private val t0 = 1_700_000_000_000L
    private val pack = RefPack(1, 1, doubleArrayOf(55.75), doubleArrayOf(37.60), floatArrayOf(0f), shortArrayOf(0))

    private fun roads(): RoadPack {
        val a = straightRoad(-200.0, 0.0, 1500.0, 0.0, 100.0, 1, 0)
        val b = straightRoad(-200.0, 25.0, 1500.0, 25.0, 100.0, 2, a.first.size)
        return roadPackOf(enu, a.first + b.first, a.second + b.second)
    }

    private fun run(config: LocalizerConfig, roads: RoadPack?, biasRadS: Float = 0.005f): List<LocalizerOutput> {
        val localizer = Localizer(pack, config, roads)
        val out = ArrayList<LocalizerOutput>()
        for (step in 0..12_000) {
            val tMs = t0 + step * 10L
            val t = tMs.toDouble()
            val rel = tMs - t0
            val inGap = rel in 30_000 until 90_000
            localizer.onSensor(AccelEvent(t, 0f, 0f, 9.81f + 0.3f * sin(step.toFloat())))
            localizer.onSensor(GyroEvent(t, 0f, 0f, if (inGap) biasRadS else 0f))
            if (step % 100 == 0 && !inGap) {
                val ll = enu.toLatLon(step * 0.1, 0.0)
                localizer.onSensor(LocEvent(t, ll[0], ll[1], 3f, 10f, 0.3f, 90f, 2f))
            }
            if (step % 50 == 0) localizer.onFrame(tMs, null)?.let { out.add(it) }
        }
        return out
    }

    private fun inGap(o: LocalizerOutput) = (o.tMs - t0) in 33_000 until 90_000
    private fun lateralErrM(o: LocalizerOutput) = abs(enu.toEn(o.lat, o.lon)[1])

    @Test fun roadConstraintBoundsDeadReckoningDrift() {
        val with = run(LocalizerConfig(visual = false), roads()).filter(::inGap)
        val without = run(LocalizerConfig(visual = false), null).filter(::inGap)
        val maxWith = with.maxOf(::lateralErrM)
        val maxWithout = without.maxOf(::lateralErrM)
        assertTrue(maxWithout > 30.0, "drift without roads must exist: $maxWithout")
        assertTrue(maxWith <= 10.0, "with roads: $maxWith")
        assertTrue(with.count { it.road?.wayId == 1L } >= with.size * 98 / 100)
        assertTrue(with.count { it.road?.used == true } >= with.size * 80 / 100)
    }

    @Test fun constraintNotUsedWhileGnssIsFused() {
        val before = run(LocalizerConfig(visual = false), roads()).filter { (it.tMs - t0) < 30_000 }
        assertTrue(before.isNotEmpty() && before.all { it.road != null && !it.road!!.used })
    }

    @Test fun noRoadsMeansNoRoadInfo() {
        assertTrue(run(LocalizerConfig(visual = false), null).all { it.road == null })
    }

    @Test fun constraintOffStillReportsMatch() {
        val gap = run(LocalizerConfig(visual = false, roadConstraint = false), roads()).filter(::inGap)
        assertTrue(gap.all { it.road != null && !it.road!!.used })
        assertTrue(gap.maxOf(::lateralErrM) > 30.0)
    }
}
```

В тесте `TrajectoryFormat` (файл, где сейчас проверяется `fusionHeader`) добавить:

```kotlin
    @Test fun rowCarriesRoadFields() {
        val base = LocalizerOutput(1L, 55.0, 37.0, 3.0, null, null, "off", false, NavMode.GNSS, GnssHealth.GOOD, emptySet())
        assertTrue(TrajectoryFormat.row(base, false, null).endsWith(
            ",\"way_id\":null,\"road_lat\":null,\"road_lon\":null,\"road_conf\":null,\"road_used\":null}"))
        val withRoad = base.copy(road = RoadInfo(42L, 55.1, 37.1, 0.95, true))
        assertTrue(TrajectoryFormat.row(withRoad, false, null).endsWith(
            ",\"way_id\":42,\"road_lat\":55.1,\"road_lon\":37.1,\"road_conf\":0.95,\"road_used\":true}"))
    }

    @Test fun fusionHeaderCarriesRoads() {
        assertTrue(TrajectoryFormat.fusionHeader(1L, "c", "r1").endsWith(",\"roads\":\"r1\"}"))
        assertTrue(TrajectoryFormat.fusionHeader(1L, "c").endsWith(",\"roads\":null}"))
    }
```

Run: `./gradlew :core:test`
Expected: FAIL при компиляции (`Unresolved reference: updateLateral`, `RoadInfo`).

- [ ] **Step 2: Реализация.**

`Ekf2d.kt`, после `updateHeading`:

```kotlin
    /**
     * Псевдоизмерение «на оси дороги»: прямая через (e0, n0) с азимутом theta (рад от севера по часовой).
     * Невязка — поперечное смещение до прямой; вдоль дороги состояние не тянется.
     */
    fun updateLateral(e0: Double, n0: Double, theta: Double, sigma: Double): Boolean {
        require(sigma > 0 && sigma.isFinite()) { "sigma must be positive and finite" }
        val ne = cos(theta); val nn = -sin(theta) // нормаль к направлению (sin θ, cos θ)
        val offset = ne * (x[0] - e0) + nn * (x[1] - n0)
        return update(
            arrayOf(doubleArrayOf(ne, nn, 0.0, 0.0, 0.0)), doubleArrayOf(-offset), doubleArrayOf(sigma * sigma),
            config.gateChi2Scalar,
        )
    }
```

`Localizer.kt`:

1. В `LocalizerConfig` дописать поля из Interfaces.
2. Рядом с `LocalizerOutput` объявить `RoadInfo` и добавить в `LocalizerOutput` последнее поле:

```kotlin
/** Привязка к дороге на кадре: OSM way, точка на оси, уверенность, применена ли подсказка фильтру. */
data class RoadInfo(val wayId: Long, val lat: Double, val lon: Double, val confidence: Double, val used: Boolean)
```

```kotlin
    val mode: NavMode, val health: GnssHealth, val reasons: Set<GnssReason>,
    val road: RoadInfo? = null,
```

3. Конструктор: `class Localizer(private val pack: RefPack, private val config: LocalizerConfig = LocalizerConfig(), private val roads: RoadPack? = null)`. Поля:

```kotlin
    private var matcher: MapMatcher? = null
    private var lastGnssFusedT = Double.NEGATIVE_INFINITY
```

4. В ветке инициализации после `ekf.init(...)` и `lastT = t`:

```kotlin
                matcher = roads?.let { MapMatcher(RoadIndex(it, enu!!), config.matchConfig) }
                lastGnssFusedT = t
```

5. В ветке переинициализации после `ekf.init(en[0], en[1], psi, v, ...)`:

```kotlin
            matcher?.reset()
            lastGnssFusedT = t
```

6. В `applyFix` заменить строку обновления позиции:

```kotlin
        if (ekf.updatePosition(en[0], en[1], posSigma * scale)) lastGnssFusedT = ev.tMs
```

7. В `onFrame` перед комментарием «Режим и состояние обновляются на каждом кадре…»:

```kotlin
        val road = matcher?.let { stepRoad(it, t) }
```

В `return LocalizerOutput(...)` последним аргументом передать `road`.

8. Новый метод:

```kotlin
    /** Шаг привязки к дороге; подсказка фильтру — только когда GNSS давно не сливался и привязка уверена. */
    private fun stepRoad(m: MapMatcher, t: Double): RoadInfo? {
        val match = m.step(ekf.x[0], ekf.x[1], ekf.posSigma(), ekf.x[2], ekf.x[3]) ?: return null
        var used = false
        if (config.roadConstraint && t - lastGnssFusedT > config.gnssQuietMs &&
            match.confidence >= config.minRoadConfidence && !match.nearJunction && ekf.x[3] >= config.minRoadSpeedMps
        ) {
            used = ekf.updateLateral(match.e, match.n, match.travelBearing, RoadClass.lateralSigmaM(match.roadClass))
            if (used) ekf.updateHeading(match.travelBearing, Math.toRadians(config.roadHeadingSigmaDeg))
        }
        val ll = enu!!.toLatLon(match.e, match.n)
        return RoadInfo(match.wayId, ll[0], ll[1], match.confidence, used)
    }
```

`TrajectoryFormat.kt`:

```kotlin
    /** Заголовок журнала fusion; строки экранируются как JSON. */
    fun fusionHeader(sessionStartedMs: Long, refpackCreatedAt: String, roadsCreatedAt: String? = null): String =
        "{\"type\":\"fusion\",\"monitor\":true,\"session_started_ms\":$sessionStartedMs," +
            "\"refpack_created_at\":${JsonPrimitive(refpackCreatedAt)},\"roads\":${str(roadsCreatedAt)}}"

    fun row(o: LocalizerOutput, inOutage: Boolean, injected: String?): String {
        val reasons = o.reasons.joinToString(",") { JsonPrimitive(it.name.lowercase()).toString() }
        val r = o.road
        return "{\"t_ms\":${o.tMs},\"lat\":${num(o.lat)},\"lon\":${num(o.lon)},\"sigma_m\":${num(o.sigmaM)}," +
            "\"outage\":$inOutage,\"vis_sim\":${num(o.visSim)},\"vis_ok\":${o.visAccepted}," +
            "\"vis_state\":${JsonPrimitive(o.visState)},\"stationary\":${o.stationary}," +
            "\"mode\":\"${modeName(o.mode)}\",\"health\":\"${o.health.name.lowercase()}\"," +
            "\"reasons\":[$reasons],\"injected\":${str(injected)}," +
            "\"way_id\":${r?.wayId ?: "null"},\"road_lat\":${num(r?.lat)},\"road_lon\":${num(r?.lon)}," +
            "\"road_conf\":${num(r?.confidence)},\"road_used\":${r?.used ?: "null"}}"
    }

    /** JSON has no NaN/Infinity literal; a non-finite value is written as `null` to keep every line valid JSON. */
    private fun num(v: Double?): String = if (v != null && v.isFinite()) v.toString() else "null"
    private fun num(v: Float?): String = if (v != null && v.isFinite()) v.toString() else "null"
    private fun str(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()
```

Существующие тесты, которые сверяют строки траектории и заголовок fusion целиком (в `:core` и `:replay`), обновить под новые поля. Пороги и проверки поведения не трогать.

- [ ] **Step 3: Тесты зелёные.**

Run: `./gradlew :core:test :replay:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, `:core` — не меньше 151 теста, `:replay` — 31.

Если `roadConstraintBoundsDeadReckoningDrift` не укладывается в 10 м, порог не менять. Разрешено подобрать `MatchConfig` или `roadHeadingSigmaDeg` в разумных пределах и описать это в отчёте. Если не помогло, сообщить NEEDS_CONTEXT с максимальной ошибкой и долей `used`.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src android/replay/src
git commit -m "feat(core): road constraint in the filter and road fields in trajectory output

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Граф дорог в replay

**Files:**
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Replayer.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/TrajectoryWriter.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Main.kt`
- Test: `android/replay/src/test/kotlin/io/visnav/replay/RoadReplayTest.kt` (новый), `MainTest.kt` (дополнить)

**Interfaces:**
- Consumes: `RoadPack`, `RoadPackMeta`, `RoadInfo`, `Localizer(pack, config, roads)` (Task 3, 5).
- Produces:
  - `ReplayConfig.roadConstraint: Boolean = true`;
  - `Replayer(pack, config, roads: RoadPack? = null)`;
  - `TrajPoint.road: RoadInfo? = null` — последнее поле;
  - `TrajectoryWriter.write(..., roadsCreatedAt: String? = null)` — последний параметр. Заголовок после `"spoofs":[…]` получает `"roads":<строка или null>,"road_constraint":<bool>`;
  - `internal fun loadRoads(dir: File): Pair<RoadPack, RoadPackMeta>` — бросает `IllegalArgumentException` с путём файла;
  - CLI: `--roads DIR`, `--no-road-constraint`.

- [ ] **Step 1: Падающие тесты** — `RoadReplayTest.kt`. Тестовые помощники `:core` из других модулей не видны, поэтому построитель графа короткий и свой:

```kotlin
package io.visnav.replay

import io.visnav.core.RoadPack
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class RoadReplayTest {
    /** Дорога way 1 вдоль синтетического трека (n = 0), e от −200 до 1500 м, узлы через 100 м. */
    private fun roads(): RoadPack {
        val es = (-2..15).map { it * 100.0 }
        val ll = es.map { SyntheticSession.enu.toLatLon(it, 0.0) }
        val m = es.size - 1
        return RoadPack(
            DoubleArray(es.size) { ll[it][0] }, DoubleArray(es.size) { ll[it][1] }, LongArray(m) { 1L },
            IntArray(m) { it }, IntArray(m) { it + 1 }, ByteArray(m), ByteArray(m) { 7 },
        )
    }

    private fun session(): SessionData = SyntheticSession.session(createTempDirectory().toFile())

    @Test fun replayWithRoadsWritesRoadFields() {
        val outage = Outage(SyntheticSession.T0 + 30_000, SyntheticSession.T0 + 90_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false), roads())
            .run(session(), listOf(outage))
        val inOut = points.filter { it.inOutage && it.tMs >= SyntheticSession.T0 + 33_000 }
        assertTrue(inOut.count { it.road?.wayId == 1L } >= inOut.size * 98 / 100)
        assertTrue(inOut.count { it.road?.used == true } >= inOut.size * 80 / 100)
        assertTrue(points.filter { it.tMs < SyntheticSession.T0 + 30_000 }.none { it.road?.used == true })
    }

    @Test fun headerAndRowsCarryRoads() {
        val s = session()
        val config = ReplayConfig(visual = false, roadConstraint = false)
        val points = Replayer(SyntheticSession.pack(), config, roads()).run(s, emptyList())
        val f = File(createTempDirectory().toFile(), "t.jsonl")
        TrajectoryWriter.write(f, s, config, emptyList(), points, roadsCreatedAt = "r1")
        val lines = f.readLines()
        assertTrue(lines[0].contains("\"spoofs\":[],\"roads\":\"r1\",\"road_constraint\":false,"), lines[0])
        assertTrue(lines[1].contains("\"way_id\":1,"), lines[1])
    }
}
```

В `MainTest.kt` добавить:

```kotlin
    @Test fun loadRoadsReadsCrossLanguageFixture() {
        val (pack, meta) = loadRoads(File("../../research/vpr_bench/tests/data/roadpack_fixture"))
        assertEquals(2, pack.edgeCount); assertEquals("2026-10-02T00:00:00Z", meta.createdAt)
    }

    @Test fun loadRoadsFailsClearlyWithoutFiles() {
        val e = assertFailsWith<IllegalArgumentException> { loadRoads(createTempDirectory().toFile()) }
        assertTrue(e.message!!.contains("roadpack"))
    }
```

(Если нужных импортов `java.io.File`, `kotlin.io.path.createTempDirectory`, `assertFailsWith` нет, добавить их.)

Run: `./gradlew :replay:test`
Expected: FAIL при компиляции.

- [ ] **Step 2: Реализация.**

`Replayer.kt`:
- в `ReplayConfig` — поле `val roadConstraint: Boolean = true`;
- в `TrajPoint` — последнее поле `val road: RoadInfo? = null`;
- `class Replayer(private val pack: RefPack, private val config: ReplayConfig, private val roads: RoadPack? = null)`;
- `Localizer` создаётся с `roadConstraint = config.roadConstraint` в `LocalizerConfig` и третьим аргументом `roads`;
- при построении `TrajPoint` последним аргументом передать `o.road`.

`TrajectoryWriter.kt`:
- в `write` добавить последний параметр `roadsCreatedAt: String? = null`;
- заголовок после `"spoofs":[$spoofJson],`:

```kotlin
                "\"roads\":${roadsCreatedAt?.let { JsonPrimitive(it).toString() } ?: "null"}," +
                "\"road_constraint\":${config.roadConstraint}," +
```

- в `LocalizerOutput(...)` последним аргументом передать `p.road`.

`Main.kt`:

```kotlin
/** Читает roadpack.json и roadpack.bin из каталога; бросает IllegalArgumentException с понятным текстом. */
internal fun loadRoads(dir: File): Pair<RoadPack, RoadPackMeta> {
    val json = File(dir, "roadpack.json")
    val bin = File(dir, "roadpack.bin")
    require(json.isFile) { "roadpack meta not found: $json" }
    require(bin.isFile) { "roadpack data not found: $bin" }
    val meta = RoadPackMeta.parse(json.readText())
    val pack = RoadPack.parse(ByteBuffer.wrap(bin.readBytes()))
    require(pack.nodeCount == meta.nodeCount && pack.edgeCount == meta.edgeCount) {
        "roadpack counts differ from $json"
    }
    return pack to meta
}
```

В `main`:
- переменные `var roadsDir: String? = null; var roadConstraint = true`;
- в разборе аргументов:

```kotlin
            "--roads" -> roadsDir = args.getOrNull(++i) ?: fail("--roads needs DIR")
            "--no-road-constraint" -> roadConstraint = false
```

- перед созданием `ReplayConfig`:

```kotlin
    val roads = roadsDir?.let { dir ->
        try { loadRoads(File(dir)) } catch (e: IllegalArgumentException) { fail(e.message ?: "bad roadpack in $dir") }
    }
    val config = ReplayConfig(visual = visual, monitor = monitor, roadConstraint = roadConstraint)
    val points = Replayer(pack, config, roads?.first).run(data, outages, jams, spoofs)
```

- `TrajectoryWriter.write(File(out), data, config, outages, points, jams, spoofs, roads?.second?.createdAt)`;
- в итоговой строке печати добавить `, ${points.count { it.road?.used == true }} with road constraint`;
- в `USAGE` дописать `[--roads DIR] [--no-road-constraint]`.

- [ ] **Step 3: Тесты зелёные.**

Run: `./gradlew :core:test :replay:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, `:replay` — не меньше 35 тестов.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/replay/src
git commit -m "feat(replay): --roads graph and --no-road-constraint for map-matching A/B

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Офлайн-эталон и `vpr-m2 road-eval`

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/roadmatch.py`
- Create: `research/vpr_bench/src/vpr_bench/roadeval.py`
- Modify: `research/vpr_bench/src/vpr_bench/replayeval.py`: `TrajRow` и `read_trajectory`.
- Modify: `research/vpr_bench/src/vpr_bench/m2cli.py`: подкоманда `road-eval`.
- Create: `research/vpr_bench/tests/_roads.py`, `tests/test_roadmatch.py`, `tests/test_roadeval.py`

**Interfaces:**
- Consumes: `RoadGraph`, `read_roadpack`, `FLAG_ONEWAY` (Task 1); `geo.M_PER_DEG_LAT`, `geo.TrackPoint(t, lat, lon)`, `geo.haversine_m`, `geo.offset_m`; `fieldlog.gps_track`, `fieldlog.FieldFrame`; `query.clean_track(track, max_speed_mps, max_hdop) -> (track, stats)`, `query.pose_at(track, t) -> (lat, lon, heading, speed) | None`.
- Produces:
  - `TrajRow` получает в конце необязательные поля `way_id: int | None = None`, `road_lat: float | None = None`, `road_lon: float | None = None`, `road_conf: float | None = None`, `road_used: bool | None = None`;
  - `roadmatch.Snap(way_id: int, lat: float, lon: float)`;
  - `roadmatch.RoadNet(g, cell_m=50.0)` с методами `.to_en`, `.to_ll`, `.near`, `.route_from`;
  - `roadmatch.match_track(net, track, radius_m=30.0, sigma_m=5.0, beta_m=10.0, max_gap_s=5.0, max_candidates=8) -> list[Snap | None]`;
  - `roadeval.RoadResult`, `evaluate_roads(header, rows, log_frames, net, min_speed_mps=2.0, tol_m=5.0)`, `render_road_report(r) -> str`;
  - CLI `vpr-m2 road-eval --traj FILE --log SESSION.jsonl --roads DIR --out REPORT.md`:
    - принимает и `.fusion.jsonl`;
    - код 2 при несовпадении сессий (как у других подкоманд);
    - код 2, если `"roads"` в заголовке есть и не равен `created_at` графа.

Определения:
- **Эталон** — `match_track` по треку `clean_track(gps_track(журнал), max_speed_mps=70, max_hdop=None)`.
- **Строка оценивается**, если `pose_at` даёт скорость ≥ 2 м/с и у ближайшей по времени точки эталона в пределах ±1 с есть привязка. Строки без эталонной привязки считаются отдельно (`no_truth_rows`).
- **Строка верна**, если `way_id` совпадает с эталоном или `(road_lat, road_lon)` ближе `tol_m = 5` м к эталонной точке. `way_id = null` — неверно и попадает в «вне дорог».
- **Окна** — строки с `outage = true` или с непустым `injected`.
- **Неверные отрезки** — подряд идущие неверные строки с промежутком ≤ 1,5 с. В отчёт идут 5 самых длинных: начало в секундах от старта сессии и длительность.
- **Вердикт:**
  - ✅, если доля ≥ 98 %, иначе ❌; это и для всей поездки, и для окон;
  - без данных — «⚠️ нет данных»;
  - если в заголовке `"roads"` равен `null`, весь отчёт — «⚠️ нет данных».

- [ ] **Step 1: Помощник и падающие тесты.**

`tests/_roads.py`:

```python
"""Тестовые графы в метрах вокруг (LAT0, LON0); узлы с одинаковыми координатами общие."""
import numpy as np

from vpr_bench.geo import offset_m
from vpr_bench.roadpack import RoadGraph

LAT0, LON0 = 55.75, 37.6


def graph_from_lines(lines):
    """lines: [(way_id, [(e, n), ...], flags, cls)] — рёбра между соседними точками каждой линии."""
    index, lats, lons = {}, [], []
    ef, et, ew, efl, ec = [], [], [], [], []

    def idx(p):
        key = (round(p[0], 3), round(p[1], 3))
        if key not in index:
            lat, lon = offset_m(LAT0, LON0, p[0], p[1])
            index[key] = len(lats)
            lats.append(lat)
            lons.append(lon)
        return index[key]

    for way, pts, flags, cls in lines:
        for a, b in zip(pts, pts[1:]):
            ef.append(idx(a)); et.append(idx(b)); ew.append(way); efl.append(flags); ec.append(cls)
    return RoadGraph(np.array(lats), np.array(lons), np.array(ew, dtype=np.int64), np.array(ef, dtype=np.int32),
                     np.array(et, dtype=np.int32), np.array(efl, dtype=np.uint8), np.array(ec, dtype=np.uint8))


def line(e0, n0, e1, n1, step=100.0):
    k = max(1, round(((e1 - e0) ** 2 + (n1 - n0) ** 2) ** 0.5 / step))
    return [(e0 + (e1 - e0) * i / k, n0 + (n1 - n0) * i / k) for i in range(k + 1)]
```

`tests/test_roadmatch.py`:

```python
import random

from _roads import LAT0, LON0, graph_from_lines, line
from vpr_bench.geo import TrackPoint, offset_m
from vpr_bench.roadmatch import RoadNet, match_track
from vpr_bench.roadpack import FLAG_ONEWAY


def _track(points, t0=1000.0):
    return [TrackPoint(t0 + i, *offset_m(LAT0, LON0, e, n)) for i, (e, n) in enumerate(points)]


def test_parallel_roads_noise_stays_on_true_road():
    net = RoadNet(graph_from_lines([(1, line(0, -100, 0, 3100), 0, 7), (2, line(25, -100, 25, 3100), 0, 7)]))
    rnd = random.Random(1)
    snaps = match_track(net, _track([(rnd.gauss(0, 5), 10.0 * i) for i in range(300)]))
    assert sum(s is not None and s.way_id == 1 for s in snaps) >= 294


def test_turn_at_junction():
    net = RoadNet(graph_from_lines([(1, line(0, 0, 500, 0), 0, 7), (3, line(500, 0, 500, 500), 0, 7),
                                    (4, line(500, 0, 1000, 0), 0, 7)]))
    pts = [(10.0 * i, 0.0) for i in range(51)] + [(500.0, 10.0 * i) for i in range(1, 51)]
    snaps = match_track(net, _track(pts))
    assert all(s.way_id == 1 for s in snaps[:45]) and all(s.way_id == 3 for s in snaps[56:])


def test_oneway_forbids_wrong_direction():
    net = RoadNet(graph_from_lines([(1, line(0, 0, 0, 1000), FLAG_ONEWAY, 7), (2, line(15, 0, 15, 1000), 0, 7)]))
    snaps = match_track(net, _track([(7.5, 1000.0 - 10.0 * i) for i in range(90)]))  # на юг, против way 1
    assert sum(s is not None and s.way_id == 2 for s in snaps) >= 88


def test_off_road_point_is_none_and_gap_splits_chain():
    net = RoadNet(graph_from_lines([(1, line(0, 0, 0, 1000), 0, 7)]))
    track = _track([(0.0, 10.0 * i) for i in range(20)])
    track[10] = TrackPoint(track[10].t, *offset_m(LAT0, LON0, 300.0, 100.0))
    late = [TrackPoint(p.t + 60.0, p.lat, p.lon) for p in _track([(0.0, 500.0 + 10.0 * i) for i in range(10)])]
    snaps = match_track(net, track + late)
    assert snaps[10] is None
    assert all(s is not None and s.way_id == 1 for i, s in enumerate(snaps) if i != 10)
```

`tests/test_roadeval.py`:

```python
import json

from _roads import LAT0, LON0, graph_from_lines, line
from vpr_bench.fieldlog import FieldFrame
from vpr_bench.geo import offset_m
from vpr_bench.m2cli import main
from vpr_bench.replayeval import read_trajectory
from vpr_bench.roadeval import evaluate_roads, render_road_report
from vpr_bench.roadmatch import RoadNet
from vpr_bench.roadpack import write_roadpack

T0 = 1_700_000_000_000
N = 200
GRAPH = graph_from_lines([(1, line(0, -100, 0, 2100), 0, 7), (2, line(25, -100, 25, 2100), 0, 7)])


def _frames():
    out = []
    for s in range(N):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        out.append(FieldFrame(T0 + 1000 * s, "gps", (lat, lon, 4.0, T0 + 1000 * s), None,
                              {"pre": 0.0, "inf": 0.0, "search": 0.0}))
    return out


def _write(tmp_path, way=lambda s: 1, east=lambda s: 0.0, outage=lambda s: False, roads="r1"):
    header = {"type": "replay", "visual": True, "outages": [], "monitor": True, "jams": [], "spoofs": [],
              "roads": roads, "road_constraint": True, "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(N):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        w = way(s)
        rlat, rlon = offset_m(LAT0, LON0, east(s), 10.0 * s)
        lines.append(json.dumps({
            "t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 3.0, "outage": outage(s), "vis_sim": None,
            "vis_ok": None, "vis_state": "off", "stationary": False, "mode": "gnss", "health": "good",
            "reasons": [], "injected": None, "way_id": w, "road_lat": rlat if w is not None else None,
            "road_lon": rlon if w is not None else None, "road_conf": 0.95 if w is not None else None,
            "road_used": outage(s) if w is not None else None,
        }))
    p = tmp_path / "t.jsonl"
    p.write_text("\n".join(lines) + "\n")
    return p


def _eval(p):
    header, rows = read_trajectory(p)
    return evaluate_roads(header, rows, _frames(), RoadNet(GRAPH))


def test_all_correct(tmp_path):
    r = _eval(_write(tmp_path))
    assert r.correct_pct == 100.0 and r.n_rows > 150
    assert "✅" in [l for l in render_road_report(r).splitlines() if "Правильная дорога" in l][0]


def test_wrong_way_counts_and_tolerance(tmp_path):
    # 10 строк на way 2 в 25 м — неверно; ещё 10 на way 2, но точка в 3 м от эталона — верно по допуску.
    p = _write(tmp_path, way=lambda s: 2 if 50 <= s < 70 else 1,
               east=lambda s: 25.0 if 50 <= s < 60 else (3.0 if 60 <= s < 70 else 0.0))
    r = _eval(p)
    assert abs(r.correct_pct - 100.0 * (r.n_rows - 10) / r.n_rows) < 1e-9
    assert r.wrong_spans and abs(r.wrong_spans[0][1] - 9.0) < 1e-6
    assert "❌" in [l for l in render_road_report(r).splitlines() if "Правильная дорога" in l][0]


def test_off_road_and_window_rows(tmp_path):
    p = _write(tmp_path, way=lambda s: None if 100 <= s < 104 else 1, outage=lambda s: 90 <= s < 130)
    r = _eval(p)
    assert r.off_road_pct > 0 and r.n_window >= 38
    assert abs(r.window_correct_pct - 100.0 * (r.n_window - 4) / r.n_window) < 1e-9
    assert r.used_pct > 0


def test_no_roads_in_header_reports_no_data(tmp_path):
    r = _eval(_write(tmp_path, roads=None))
    assert not r.has_road_fields
    assert "⚠️ нет данных" in render_road_report(r)


def _log(tmp_path):
    lines = [json.dumps({"type": "session", "started_ms": T0})]
    for f in _frames():
        g = {"lat": f.gps[0], "lon": f.gps[1], "acc_m": f.gps[2], "t_ms": f.gps[3]}
        lines.append(json.dumps({"t_ms": f.t_ms, "mode": f.mode, "gps": g, "fix": None, "lat_ms": f.lat_ms}))
    p = tmp_path / "s.jsonl"
    p.write_text("\n".join(lines) + "\n")
    return p


def test_cli_checks_roadpack_and_writes_report(tmp_path):
    roads = tmp_path / "roads"
    write_roadpack(roads, GRAPH, {"created_at": "r1", "source": "test"})
    traj, log, out = _write(tmp_path), _log(tmp_path), tmp_path / "r.md"
    assert main(["road-eval", "--traj", str(traj), "--log", str(log), "--roads", str(roads), "--out", str(out)]) == 0
    assert "NFR-3" in out.read_text() and "OpenStreetMap" in out.read_text()
    write_roadpack(roads, GRAPH, {"created_at": "other", "source": "test"})
    assert main(["road-eval", "--traj", str(traj), "--log", str(log), "--roads", str(roads), "--out", str(out)]) == 2
```

Run: `uv run pytest tests/test_roadmatch.py tests/test_roadeval.py -q`
Expected: FAIL (`ModuleNotFoundError: vpr_bench.roadmatch`).

- [ ] **Step 2: Реализация.**

`replayeval.py`:
- в `TrajRow` после `injected` дописать пять полей из Interfaces;
- в `read_trajectory` при создании `TrajRow` передать их по имени:

```python
            way_id=r.get("way_id"), road_lat=r.get("road_lat"), road_lon=r.get("road_lon"),
            road_conf=r.get("road_conf"), road_used=r.get("road_used"),
```

`roadmatch.py`:

```python
"""Офлайн-привязка GPS-трека к дорожному графу — эталон для road-eval.

Модель та же, что у онлайн-MapMatcher на телефоне (ребро с направлением движения, переход по пути
по графу со штрафом за отсутствие пути, модель Newson–Krumm), но без курса (в журнале его нет) и
с обратным проходом Витерби по всему треку.
"""
from __future__ import annotations

import heapq
import math
from dataclasses import dataclass

import numpy as np

from vpr_bench.geo import M_PER_DEG_LAT, TrackPoint
from vpr_bench.roadpack import FLAG_ONEWAY, RoadGraph

BACKTRACK_TOLERANCE_M = 5.0
TELEPORT_PENALTY = 20.0  # как MatchConfig.teleportPenalty на телефоне


@dataclass(frozen=True)
class Snap:
    way_id: int
    lat: float
    lon: float


@dataclass(frozen=True)
class _State:
    edge: int
    forward: bool
    t: float
    pe: float
    pn: float
    dist: float


class RoadNet:
    """Граф в плоских координатах вокруг центра графа, сетка ячеек и направленные смежности."""

    def __init__(self, g: RoadGraph, cell_m: float = 50.0):
        self.g = g
        self.cell_m = cell_m
        self.lat0 = float(np.mean(g.node_lats)) if len(g.node_lats) else 0.0
        self.lon0 = float(np.mean(g.node_lons)) if len(g.node_lons) else 0.0
        self.kx = M_PER_DEG_LAT * math.cos(math.radians(self.lat0))
        self.ne = (np.asarray(g.node_lons) - self.lon0) * self.kx
        self.nn = (np.asarray(g.node_lats) - self.lat0) * M_PER_DEG_LAT
        a, b = np.asarray(g.edge_from), np.asarray(g.edge_to)
        self.length = np.hypot(self.ne[b] - self.ne[a], self.nn[b] - self.nn[a])
        self.adj: list[list[tuple[int, float]]] = [[] for _ in range(len(g.node_lats))]
        self.grid: dict[tuple[int, int], list[int]] = {}
        for k in range(len(a)):
            u, v, length = int(a[k]), int(b[k]), float(self.length[k])
            self.adj[u].append((v, length))
            if not int(g.edge_flags[k]) & FLAG_ONEWAY:
                self.adj[v].append((u, length))
            for cx in range(self._cell(min(self.ne[u], self.ne[v])), self._cell(max(self.ne[u], self.ne[v])) + 1):
                for cy in range(self._cell(min(self.nn[u], self.nn[v])), self._cell(max(self.nn[u], self.nn[v])) + 1):
                    self.grid.setdefault((cx, cy), []).append(k)

    def _cell(self, v: float) -> int:
        return math.floor(v / self.cell_m)

    def to_en(self, lat: float, lon: float) -> tuple[float, float]:
        return (lon - self.lon0) * self.kx, (lat - self.lat0) * M_PER_DEG_LAT

    def to_ll(self, e: float, n: float) -> tuple[float, float]:
        return self.lat0 + n / M_PER_DEG_LAT, self.lon0 + e / self.kx

    def project(self, k: int, e: float, n: float) -> tuple[float, float, float, float]:
        """(t, pe, pn, dist) — проекция точки на ребро k."""
        a, b = int(self.g.edge_from[k]), int(self.g.edge_to[k])
        de, dn = self.ne[b] - self.ne[a], self.nn[b] - self.nn[a]
        len2 = de * de + dn * dn
        t = 0.0 if len2 == 0 else min(1.0, max(0.0, ((e - self.ne[a]) * de + (n - self.nn[a]) * dn) / len2))
        pe, pn = self.ne[a] + t * de, self.nn[a] + t * dn
        return t, float(pe), float(pn), math.hypot(e - pe, n - pn)

    def near(self, e: float, n: float, radius: float) -> list[tuple[int, float, float, float, float]]:
        """[(edge, t, pe, pn, dist)] ближе radius, по возрастанию расстояния."""
        seen, out = set(), []
        for cx in range(self._cell(e - radius), self._cell(e + radius) + 1):
            for cy in range(self._cell(n - radius), self._cell(n + radius) + 1):
                for k in self.grid.get((cx, cy), ()):
                    if k in seen:
                        continue
                    seen.add(k)
                    t, pe, pn, d = self.project(k, e, n)
                    if d <= radius:
                        out.append((k, t, pe, pn, d))
        out.sort(key=lambda x: x[4])
        return out

    def route_from(self, node: int, limit: float) -> dict[int, float]:
        dist = {node: 0.0}
        pq = [(0.0, node)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist.get(u, math.inf):
                continue
            for v, length in self.adj[u]:
                nd = d + length
                if nd <= limit and nd < dist.get(v, math.inf):
                    dist[v] = nd
                    heapq.heappush(pq, (nd, v))
        return dist


def _along(t: float, length: float, forward: bool) -> float:
    return t * length if forward else (1 - t) * length


def _route(net: RoadNet, a: _State, b: _State, limit: float, cache: dict[int, dict[int, float]]) -> float | None:
    la = float(net.length[a.edge])
    pos_a = _along(a.t, la, a.forward)
    if a.edge == b.edge and a.forward == b.forward:
        d = _along(b.t, la, b.forward) - pos_a
        return abs(d) if d >= -BACKTRACK_TOLERANCE_M else None
    exit_ = int(net.g.edge_to[a.edge] if a.forward else net.g.edge_from[a.edge])
    entry = int(net.g.edge_from[b.edge] if b.forward else net.g.edge_to[b.edge])
    if exit_ not in cache:
        cache[exit_] = net.route_from(exit_, limit)
    mid = cache[exit_].get(entry)
    return None if mid is None else (la - pos_a) + mid + _along(b.t, float(net.length[b.edge]), b.forward)


def match_track(net: RoadNet, track: list[TrackPoint], radius_m: float = 30.0, sigma_m: float = 5.0,
                beta_m: float = 10.0, max_gap_s: float = 5.0, max_candidates: int = 8) -> list[Snap | None]:
    """Привязка каждой точки трека; None — вне дорог (нет рёбер ближе radius_m)."""
    out: list[Snap | None] = [None] * len(track)
    chain: list[tuple[int, list[_State], list[float], list[int]]] = []  # (точка, состояния, очки, обратные ссылки)
    prev_en: tuple[float, float] | None = None
    prev_t: float | None = None

    def flush() -> None:
        if not chain:
            return
        j = int(np.argmax(chain[-1][2]))
        for i, states, _, back in reversed(chain):
            s = states[j]
            lat, lon = net.to_ll(s.pe, s.pn)
            out[i] = Snap(int(net.g.edge_way[s.edge]), lat, lon)
            j = back[j]
        chain.clear()

    for i, p in enumerate(track):
        e, n = net.to_en(p.lat, p.lon)
        states = [_State(k, fwd, t, pe, pn, d)
                  for k, t, pe, pn, d in net.near(e, n, radius_m)[:max_candidates]
                  for fwd in (True, False) if fwd or not int(net.g.edge_flags[k]) & FLAG_ONEWAY]
        if not states:
            flush()
            prev_en = None
            continue
        emis = [-0.5 * (s.dist / sigma_m) ** 2 for s in states]
        scores, back = emis, [-1] * len(states)
        if chain and prev_en is not None and prev_t is not None and p.t - prev_t <= max_gap_s:
            trav = math.hypot(e - prev_en[0], n - prev_en[1])
            _, pstates, pscores, _ = chain[-1]
            cache: dict[int, dict[int, float]] = {}
            limit = 2 * trav + 50.0
            new_scores, new_back = [], []
            for s, em in zip(states, emis):
                best, arg = -math.inf, -1
                for pi, (ps, psc) in enumerate(zip(pstates, pscores)):
                    r = _route(net, ps, s, limit, cache)
                    v = psc - (TELEPORT_PENALTY if r is None else abs(r - trav) / beta_m)
                    if v > best:
                        best, arg = v, pi
                new_scores.append(best + em)
                new_back.append(arg)
            scores, back = new_scores, new_back
        else:
            flush()
        top = max(scores)
        chain.append((i, states, [x - top for x in scores], back))
        prev_en, prev_t = (e, n), p.t
    flush()
    return out
```

`roadeval.py`:

```python
"""Оценка привязки к дорогам (NFR-3): доля времени движения на правильной дороге относительно офлайн-эталона."""
from __future__ import annotations

import bisect
import math
from dataclasses import dataclass

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import haversine_m
from vpr_bench.query import clean_track, pose_at
from vpr_bench.replayeval import TrajRow
from vpr_bench.roadmatch import RoadNet, Snap, match_track

TARGET_PCT = 98.0


@dataclass(frozen=True)
class RoadResult:
    has_road_fields: bool
    roads: str | None
    road_constraint: bool | None
    n_rows: int
    correct_pct: float
    n_window: int
    window_correct_pct: float
    off_road_pct: float
    used_pct: float
    no_truth_rows: int
    wrong_spans: list[tuple[float, float]]  # (начало, с от старта сессии; длительность, с)


def _pct(k: int, n: int) -> float:
    return 100.0 * k / n if n else float("nan")


def _truth_at(times: list[float], snaps: list[Snap | None], t: float) -> Snap | None:
    i = bisect.bisect_left(times, t)
    best = None
    for j in (i - 1, i):
        if 0 <= j < len(times) and abs(times[j] - t) <= 1.0:
            if best is None or abs(times[j] - t) < abs(times[best] - t):
                best = j
    return None if best is None else snaps[best]


def evaluate_roads(header: dict, rows: list[TrajRow], log_frames: list[FieldFrame], net: RoadNet,
                   min_speed_mps: float = 2.0, tol_m: float = 5.0) -> RoadResult:
    has = header.get("roads") is not None
    roads, constraint = header.get("roads"), header.get("road_constraint")
    if not has:
        nan = float("nan")
        return RoadResult(False, roads, constraint, 0, nan, 0, nan, nan, nan, 0, [])
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    snaps = match_track(net, track)
    times = [p.t for p in track]
    t0 = (header.get("session_started_ms") or 0) / 1000.0
    n = ok = nw = okw = off = used = no_truth = 0
    spans: list[list[float]] = []
    for r in rows:
        t = r.t_ms / 1000.0
        pose = pose_at(track, t)
        if pose is None or pose[3] < min_speed_mps:
            continue
        truth = _truth_at(times, snaps, t)
        if truth is None:
            no_truth += 1
            continue
        n += 1
        used += bool(r.road_used)
        if r.way_id is None:
            off += 1
            good = False
        else:
            good = r.way_id == truth.way_id or (
                r.road_lat is not None and r.road_lon is not None
                and haversine_m(r.road_lat, r.road_lon, truth.lat, truth.lon) <= tol_m)
        ok += good
        if r.outage or r.injected is not None:
            nw += 1
            okw += good
        if not good:
            if spans and t - spans[-1][1] <= 1.5:
                spans[-1][1] = t
            else:
                spans.append([t, t])
    wrong = sorted(((s - t0, e - s) for s, e in spans), key=lambda x: -x[1])[:5]
    return RoadResult(True, roads, constraint, n, _pct(ok, n), nw, _pct(okw, nw), _pct(off, n), _pct(used, n),
                      no_truth, wrong)


def _fmt(v: float) -> str:
    return "—" if math.isnan(v) else f"{v:.1f} %"


def _verdict(v: float) -> str:
    if math.isnan(v):
        return "⚠️ нет данных"
    return "✅" if v >= TARGET_PCT else "❌"


def render_road_report(r: RoadResult) -> str:
    c = {True: "включено", False: "выключено"}.get(r.road_constraint, "неизвестно")
    lines = ["# Привязка к дорогам (NFR-3)", "", f"- Граф дорог: {r.roads or 'нет'}; подсказка фильтру дорогой: {c}"]
    if not r.has_road_fields:
        lines += ["", "⚠️ нет данных: траектория без привязки к дорогам (replay без --roads?)"]
    else:
        lines += [
            f"- Строк в движении с эталоном дороги: {r.n_rows} (без эталонной привязки: {r.no_truth_rows})",
            f"- Правильная дорога: {_fmt(r.correct_pct)} — {_verdict(r.correct_pct)} (цель ≥ {TARGET_PCT:.0f} %)",
            f"- В пропаданиях и подмешанных окнах: {_fmt(r.window_correct_pct)} из {r.n_window} строк — "
            f"{_verdict(r.window_correct_pct)}",
            f"- Без привязки (вне дорог): {_fmt(r.off_road_pct)}",
            f"- Подсказка фильтру применялась: {_fmt(r.used_pct)} строк",
        ]
        if r.wrong_spans:
            lines += ["", "## Самые длинные отрезки с неверной дорогой", "",
                      "| начало, с от старта | длительность, с |", "|---|---|"]
            lines += [f"| {s:.0f} | {d:.1f} |" for s, d in r.wrong_spans]
        lines += ["", "Эталон — офлайн-привязка очищенного GPS-трека к тому же графу (HMM с обратным проходом). "
                      "Строка верна, если OSM way совпадает с эталоном или точка на дороге ближе 5 м к эталонной."]
    lines += ["", "Дорожные данные © участники OpenStreetMap, ODbL 1.0.", ""]
    return "\n".join(lines)
```

`m2cli.py`:
- в `build_parser`:

```python
    rd = sub.add_parser("road-eval", help="оценить привязку к дорогам (NFR-3)")
    rd.add_argument("--traj", required=True, type=Path)
    rd.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    rd.add_argument("--roads", required=True, type=Path, help="каталог roadpack")
    rd.add_argument("--out", required=True, type=Path)
```

- в `main`: `is_road = args.command == "road-eval"`, в `read_trajectory` передать `allow_fusion=is_monitor or is_road`;
- после общей проверки сессии:

```python
    if is_road:
        graph, meta = read_roadpack(args.roads)
        if header.get("roads") is not None and header["roads"] != meta.get("created_at"):
            print("error: trajectory was made with a different roadpack", file=sys.stderr)
            return 2
        res = evaluate_roads(header, rows, frames, RoadNet(graph))
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(render_road_report(res))
        print(f"correct={res.correct_pct:.1f} % rows={res.n_rows} -> {args.out}")
        return 0
```

- импорты: `read_roadpack` (если ещё нет), `RoadNet`, `evaluate_roads`, `render_road_report`.

- [ ] **Step 3: Тесты зелёные.**

Run: `uv run pytest -q`
Expected: не меньше 198 passed, 4 deselected.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench research/vpr_bench/tests
git commit -m "feat(vpr-bench): offline road-matching ground truth and road-eval (NFR-3)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Граф дорог в приложении

**Files:**
- Modify: `android/app/src/main/kotlin/io/visnav/app/Bundle.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Screen.kt`

**Interfaces:**
- Consumes: `RoadPack`, `RoadPackMeta`, `RoadInfo`, `Localizer(pack, config, roads)`, `TrajectoryFormat.fusionHeader(…, roadsCreatedAt)`.
- Produces:
  - `LoadedBundle` получает поля `roads: RoadPack? = null` и `roadsMeta: RoadPackMeta? = null`;
  - `UiState` получает поля `roadsLoaded: Boolean = false` и `road: RoadInfo? = null`.

- [ ] **Step 1: `Bundle.kt`.** Граф необязателен. Если положен только один из двух файлов — понятная ошибка. Граф читается до создания `OrtEmbedder`, чтобы ошибка в графе не оставляла открытую модель.

```kotlin
class LoadedBundle(
    val pack: RefPack, val meta: RefPackMeta, val embedder: OrtEmbedder,
    val roads: RoadPack? = null, val roadsMeta: RoadPackMeta? = null,
)
```

В `load` после разбора `pack` и до `val embedder = …`:

```kotlin
        val roadsBin = File(dir, "roadpack.bin")
        val roadsJson = File(dir, "roadpack.json")
        check(roadsBin.isFile == roadsJson.isFile) { "граф дорог: нужны оба файла, roadpack.bin и roadpack.json" }
        val roadsMeta = if (roadsJson.isFile) RoadPackMeta.parse(roadsJson.readText()) else null
        val roads = if (roadsBin.isFile) RoadPack.parse(ByteBuffer.wrap(roadsBin.readBytes())) else null
        if (roads != null && roadsMeta != null) {
            check(roads.nodeCount == roadsMeta.nodeCount && roads.edgeCount == roadsMeta.edgeCount) {
                "roadpack.bin не совпадает с roadpack.json"
            }
        }
```

`return LoadedBundle(pack, meta, embedder, roads, roadsMeta)`.

- [ ] **Step 2: `M1Controller.kt`.**
- `UiState`: добавить `val roadsLoaded: Boolean = false` и `val road: RoadInfo? = null`.
- Там, где после загрузки комплекта выставляется `loaded = true`, выставить и `roadsLoaded = b.roads != null` (по имени переменной комплекта в этом месте).
- Сброс при старте (строка с `navMode = null, sigmaM = null, gnssReasons = emptySet()`): добавить `road = null`.
- Заголовок: `fLog.line(TrajectoryFormat.fusionHeader(startedMs, b.meta.createdAt, b.roadsMeta?.createdAt))`.
- Фильтр: `val localizer = Localizer(b.pack, LocalizerConfig(), b.roads)`.
- Обновление состояния по кадру: `_state.update { it.copy(navMode = out.mode, sigmaM = out.sigmaM, gnssReasons = out.reasons, road = out.road) }`.

- [ ] **Step 3: `M1Screen.kt`.** Внутри блока `s.navMode?.let { m -> … }` после строки причин:

```kotlin
                if (s.roadsLoaded) {
                    Text(s.road?.let { r ->
                        "Дорога: привязана, ${Math.round(r.confidence * 100)} %" + if (r.used) ", уточняет позицию" else ""
                    } ?: "Дорога: не найдена")
                    Text("Дороги © участники OpenStreetMap", style = MaterialTheme.typography.bodySmall)
                }
```

Если `MaterialTheme` ещё не импортирован, добавить `import androidx.compose.material3.MaterialTheme`.

- [ ] **Step 4: Сборка.**

Run: `./gradlew :core:test :replay:test :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, lint — 0 ошибок.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/app/src
git commit -m "feat(app): optional OSM road graph — map matching on device, road line with OSM attribution

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Протокол M2b

**Files:**
- Create: `docs/research/m2b-roads.md`

- [ ] **Step 1: Документ** `docs/research/m2b-roads.md`:

````markdown
# M2b: привязка к дорожному графу OSM

## Что нужно
- Выгрузка OSM: Центральный федеральный округ с Geofabrik (`central-fed-district-latest.osm.pbf`). Скачивает владелец, около сотен МБ.
- Записанные поездки M2a (`.jsonl`, `.sensors.jsonl`, `.desc`) и комплект эталонов.

## 1. Граф для поездок
```bash
cd /Users/vvnovg/navigator/research/vpr_bench
mkdir -p data/osm
curl -L -o data/osm/cfd.osm.pbf https://download.geofabrik.de/russia/central-fed-district-latest.osm.pbf
# необязательно, но быстрее: вырезать Москву (brew install osmium-tool)
osmium extract -b 37.30,55.55,37.95,55.95 data/osm/cfd.osm.pbf -o data/osm/moscow.osm.pbf
uv sync --extra dev --extra osm
S=data/m1/logs/session-<ms>-gps
uv run vpr-m2 pack-roads --pbf data/osm/moscow.osm.pbf --log $S.jsonl --buffer-m 300 --out data/m2b/roads
```
В граф попадают проезжие дороги (`motorway` … `service`, без парковочных проездов и закрытых для машин) в коридоре ±300 м вокруг трека.

## 2. Replay: с подсказкой дорогой и без
```bash
cd /Users/vvnovg/navigator/android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
S=/Users/vvnovg/navigator/research/vpr_bench/data/m1/logs/session-<ms>-gps
R=/Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle
G=/Users/vvnovg/navigator/research/vpr_bench/data/m2b/roads
O="--outage 300:120 --outage 900:180"
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m2b.jsonl $O"
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m2b-nc.jsonl $O --no-road-constraint"
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m2b-dr.jsonl $O --no-visual"
cd /Users/vvnovg/navigator/research/vpr_bench
for t in m2b m2b-nc m2b-dr; do
  uv run vpr-m2 road-eval --traj $S.$t.jsonl --log $S.jsonl --roads data/m2b/roads --out data/m2b/roads-$t.md
  uv run vpr-m2 replay-eval --traj $S.$t.jsonl --log $S.jsonl --out data/m2b/replay-$t.md
done
```
- `road-eval` сравнивает дорогу каждой строки с офлайн-привязкой очищенного GPS-трека к тому же графу.
- `replay-eval` показывает, как подсказка дорогой влияет на точность и дрейф в пропаданиях. Прогон `m2b-dr` — без камеры, только счисление и дорога.

## 3. Телефон
Положить `roadpack.bin` и `roadpack.json` рядом с базой эталонов:
```bash
adb push data/m2b/roads/roadpack.bin data/m2b/roads/roadpack.json /sdcard/Android/data/io.visnav.app/files/refpack/
```
На экране появится строка «Дорога: привязана, N %» и атрибуция OSM. В `.fusion.jsonl` у каждой строки есть дорога. Её можно оценить так же:
```bash
uv run vpr-m2 road-eval --traj <сессия>.fusion.jsonl --log <сессия>.jsonl --roads data/m2b/roads --out data/m2b/roads-phone.md
```

## Лицензия
Граф — производная база данных OpenStreetMap (ODbL 1.0, © участники OpenStreetMap).
- В репозиторий он не коммитится.
- Приложение и отчёты показывают атрибуцию.
- При передаче графа другим людям он распространяется под ODbL.

## Итоги (заполнить)
- Сессии и граф (дата выгрузки, число рёбер): …
- Таблицы road-eval и replay-eval для `m2b`, `m2b-nc`, `m2b-dr`: …
- Отрезки с неверной дорогой: где и почему (параллельные улицы, развязки, нет дороги в OSM): …

## Критерий M2b (предложение плана; владелец может скорректировать)
- [ ] NFR-3: правильная дорога ≥ 98 % времени движения — факт: …
- [ ] Правильная дорога в пропаданиях ≥ 98 % — факт: …
- [ ] Подсказка дорогой не ухудшает дрейф NFR-5 (`m2b` против `m2b-nc`) — факт: …
````

- [ ] **Step 2: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m2b-roads.md
git commit -m "docs: M2b map-matching protocol

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Изменения после ревью (при выполнении)

Код в задачах выше — исходный вариант плана. На ревью в него внесены изменения, и действующая версия — в коде.

- **Task 2.** Тест `test_build_graph_edges_flags_and_shared_nodes` ожидает 4 ребра и `edge_way [10, 10, 11, 12]`: повтор узла `c, c, d` даёт одно ребро.
- **Task 4.** `MapMatcher`:
  - кандидаты берутся по дорогам (ближайший отрезок каждого way), к ним добавляются отрезки текущих состояний;
  - радиус поиска — `min(max(50, 3σ), 200)`;
  - у `RoadMatch` есть поле `fit`: `distM ≤ min(2,5σe, 50 м)`, а при движении ещё курс в пределах 30°; все пороги вынесены в `MatchConfig`;
  - близость к перекрёстку считается по `RoadIndex.junctionDist` (мультиисточниковая Дейкстра от узлов со степенью ≠ 2);
  - стоимость найденного пути ограничена `teleportPenalty`;
  - тестовые значения `junctionDist` исправлены.
- **Task 5.** Подсказка фильтру:
  - условие включает `match.fit`;
  - поперечное обновление меняет только положение (маска состояний) и только вдоль нормали к дороге;
  - поперечная дисперсия не опускается ниже `(σ_lat/2)²` — для этого раздувается R, а `used` означает «дорога ограничивает положение»;
  - курс подсказывается только при скорости ≥ 3 м/с и дисперсии курса выше `(σ_h/2)²`;
  - добавлены тесты со смещённой осью дороги и с дугой.
- **Task 7.** Офлайн-эталон повторяет отбор кандидатов по дорогам и ограничение стоимости пути из Task 4.
- **Task 8.** Атрибуция OSM видна всегда, когда граф загружен, а не только после первого фикса. Состояние экрана сбрасывается при остановке.
