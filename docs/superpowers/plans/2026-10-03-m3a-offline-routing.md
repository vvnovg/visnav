# M3a: офлайн-маршрут, подсказки о манёврах и перестроение — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- **Маршрут на телефоне без сети (FR-16).** Строится по графу дорог коридора (`roadpack`) от текущей позиции до точки назначения.
- **Подсказки о манёврах (FR-17).** Голосом и на экране, на русском: «Через 300 м поверните направо — Тверская улица».
- **Ведение по маршруту.** Учитывается прогресс по маршруту, съезд распознаётся, маршрут перестраивается без сети (FR-18).
- **Работа в любом режиме.** Всё это работает и в режиме GNSS, и при визуальной навигации или счислении пути. Сценарий «тоннель» из SPEC: подсказки продолжаются без GPS.
- **Проверка на replay.** Подсказки, перестроения и прибытие проверяются на записанных поездках.

**Architecture:**
- **Граф.** Формат `roadpack` получает версию 2. В ней у рёбер появляются:
  - скорость для оценки времени;
  - название улицы;
  - флаг кругового движения;
  - таблица запретов поворотов из отношений OSM `type=restriction`.

  Читатели принимают v1 и v2.
- **`:core`:**
  - `Router` — A* по направленным рёбрам. Учитываются односторонние улицы и запреты поворотов, стоимость — время в пути, курс на старте помогает не начинать «против шерсти».
  - `Maneuvers` — превращают маршрут в список манёвров: повороты по углу на перекрёстках, смена улицы, номер съезда с кругового движения, прибытие.
  - `Instructions` — дают русский текст.
  - `RouteFollower` получает каждый вывод `Localizer`. Он:
    - ведёт прогресс по маршруту;
    - выдаёт подсказки на трёх дистанциях («далеко», «близко», «сейчас»), зависящих от скорости;
    - распознаёт съезд с маршрута с учётом неопределённости позиции;
    - перестраивает маршрут;
    - определяет прибытие.
- **Журнал событий.** `NavFormat` пишет события ведения в `.nav.jsonl`: маршрут, подсказки, перестроения, прибытие.
- **Replay.** Получает флаги `--dest LAT,LON` и `--nav-out FILE`.
- **ПК.** Новый инструмент `vpr-m3 nav-eval` по GPS-треку журнала проверяет, что подсказка о каждом пройденном манёвре прозвучала вовремя, а перестроения не были ложными.
- **Приложение.** Читает точку назначения из `route.json`, произносит подсказки через Android TextToSpeech (ru-RU) и показывает следующий манёвр. Карты на экране ещё нет, она появится в M3b.

**Tech Stack:** Kotlin 2.1 (JVM 17), Android (TextToSpeech, org.json), Python 3.11+ (numpy, pyosmium как необязательная группа `osm`), JUnit / kotlin.test, pytest.

## Global Constraints

- **Решения владельца (2026-10-03):**
  - M3 делится на M3a (маршрут и подсказки), M3b (экран с картой MapLibre) и M3c (пакет коридора);
  - маршрутизатор свой, на графе `roadpack`, вместо GraphHopper. Маршрут и перестроение ограничены коридором графа. Отступление от SPEC FR-16 / §6.1 фиксируется в SPEC в Task 10;
  - приложение остаётся без разрешения INTERNET: данные кладутся через `adb push`.
- **Цели M3a** (предложение плана, владелец может скорректировать). Оцениваются на replay `vpr-m3 nav-eval`:
  - ≥ 95 % пройденных манёвров получили подсказку «близко» или «сейчас» не позже чем за 3 с до манёвра. Отдельно считается доля манёвров внутри пропаданий GPS и подмешанных окон, цель та же;
  - ложных перестроений нет: перестроение считается ложным, если в этот момент машина по GPS была ближе 15 м к активному маршруту;
  - прибытие распознано.
- **Формат `roadpack` v2** (little-endian). Заголовок как в v1, но `version = 2`. Затем массивы v1 в том же порядке. Затем:
  - `speed u8[m]` — км/ч для оценки времени, 0 — по классу;
  - `name i32[m]` — индекс в таблице названий, −1 — нет;
  - `name_count u32`, затем для каждого названия `u16` длина в байтах и UTF-8 байты;
  - `restr_count u32`, затем для каждого запрета `from_edge i32, via_node i32, to_edge i32, kind u8` (1 — `no_*`, 2 — `only_*`).

  Файл заканчивается ровно после последнего запрета. Флаг ребра `8` — круговое движение. Читатели принимают v1 (без этих полей) и v2. `roadpack.json`: `"format": "VNRD/2"`.
- **Скорость по классу** (км/ч, общая для Python и Kotlin): 1 — 90, 2 — 70, 3 — 60, 4 — 50, 5 — 40, 6 — 30, 7 — 20, 8 — 10, 9 — 10. Если у дороги есть `maxspeed`, берётся `round(min(maxspeed, 110) · 0,8)`, но не меньше 5.
- **Подсказки:**
  - тексты на русском без склонения названий: «… — Тверская улица»;
  - расстояния: меньше 100 м — округление до 10 м, меньше 1000 м — до 50 м, дальше — «1,2 км»;
  - дистанции срабатывания от скорости v (м/с, не меньше 5):
    - «далеко» — `clamp(30·v, 300, 1000)`;
    - «близко» — `clamp(8·v, 60, 200)`;
    - «сейчас» — `clamp(2,5·v, 15, 50)`;
  - каждая стадия звучит не больше одного раза на манёвр.
- **Съезд с маршрута:** расстояние до маршрута больше `max(40 м, 2,5·σ)` дольше 4 с. Перестроение — не чаще раза в 10 с.
- **`.nav.jsonl`** — одна JSON-строка на событие:
  - первая строка — заголовок `{"type":"nav","session_started_ms":…,"roads":…,"dest":[lat,lon],"outages":[[s,e]…],"jams":[…],"spoofs":[…]}`;
  - события: `route`, `prompt`, `arrive`, `route_failed` (поля — в Task 6).
- **Формат строки траектории** не меняется. `LocalizerOutput` и `TrajPoint` получают поля `psiRad`, `speedMps` только для ведения по маршруту, в строку траектории они не пишутся.
- **Лицензии:** новых зависимостей в приложении нет (TextToSpeech и org.json входят в Android). На ПК — pyosmium из группы `osm`, как в M2b.
- **OSM:** ODbL. Граф не коммитится, атрибуция показывается.
- **Android:** без Google Play Services, `minSdk 29`, без разрешения INTERNET.
- **FR-20:** изображения не сохраняются.
- **Команды Gradle** — из `/Users/vvnovg/navigator/android` с `JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"`.
- **Команды Python** — из `/Users/vvnovg/navigator/research/vpr_bench`: `uv sync --extra dev --extra osm`, затем `uv run pytest -q`.
- **Коммиты** заканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Текущие счётчики тестов:** `:core` — 173, `:replay` — 35, Python — 205 passed / 4 deselected.

---

## Структура файлов

```
research/vpr_bench/
├── src/vpr_bench/roadpack.py       Task 1  v2: скорость, названия, запреты; читает v1 и v2; FLAG_ROUNDABOUT
├── src/vpr_bench/osmgraph.py       Task 2  названия, maxspeed, круговое движение, отношения restriction, read_osm
├── src/vpr_bench/naveval.py        Task 8  NavResult, evaluate_nav, render_nav_report
├── src/vpr_bench/m3cli.py          Task 8  vpr-m3 nav-eval
├── pyproject.toml                  Task 8  [project.scripts] vpr-m3
├── tests/data/roadpack_v2_fixture/ Task 1  общий с Kotlin эталон v2 (синтетический)
├── tests/test_roadpack.py          Task 1  (дополнить)
├── tests/test_osmgraph.py          Task 2  (дополнить)
└── tests/test_naveval.py           Task 8

android/core/src/main/kotlin/io/visnav/core/
├── RoadPack.kt          Task 3  v2: speedKmh, nameIdx, names, restrictions, roundabout(); RoadClass.defaultSpeedKmh
├── RoadIndex.kt         Task 3  incident[node]
├── Router.kt            Task 4  RouteStep, Route, RouterConfig, Router
├── Maneuvers.kt         Task 5  ManeuverType, Maneuver, buildManeuvers
├── Instructions.kt      Task 5  distanceText, ordinal, promptText
├── RouteFollower.kt     Task 6  NavConfig, PromptStage, NavEvent, RouteFollower
├── NavFormat.kt         Task 6  заголовок и события .nav.jsonl
└── Localizer.kt         Task 6  LocalizerOutput.psiRad, speedMps
android/replay/src/main/kotlin/io/visnav/replay/
├── NavReplay.kt         Task 7  runNavigation(points, roads, destLat, destLon)
├── Replayer.kt          Task 7  TrajPoint.psiRad, speedMps
└── Main.kt              Task 7  --dest LAT,LON, --nav-out FILE
android/app/src/main/kotlin/io/visnav/app/
├── NavSession.kt        Task 9  маршрут, TTS, журнал .nav.jsonl
├── Bundle.kt            Task 9  route.json
├── M1Controller.kt      Task 9  подключение NavSession
└── M1Screen.kt          Task 9  строка следующего манёвра

docs/research/m3a-route.md        Task 10
docs/SPEC.md                      Task 10  решение по маршрутизатору, деление M3
```

---

### Task 1: `roadpack` v2 на Python

**Files:**
- Modify: `research/vpr_bench/src/vpr_bench/roadpack.py`
- Modify: `research/vpr_bench/tests/test_roadpack.py`
- Create: `research/vpr_bench/tests/data/roadpack_v2_fixture/roadpack.bin`, `roadpack.json` (генерируются в Step 3)

**Interfaces:**
- Produces:
  - `FLAG_ROUNDABOUT = 8`;
  - `DEFAULT_SPEED_KMH: dict[int, int]`;
  - `KIND_NO = 1`, `KIND_ONLY = 2`;
  - `RoadGraph` получает необязательные поля в конце: `edge_speed: np.ndarray | None = None` (uint8), `edge_name: np.ndarray | None = None` (int32), `names: tuple[str, ...] = ()`, `restrictions: np.ndarray | None = None` (int32, форма (k, 4): from_edge, via_node, to_edge, kind);
  - `write_roadpack(out_dir, g, meta, version=2)`. При `version=1` пишется формат v1, и требуется, чтобы у графа не было названий и запретов;
  - `read_roadpack(dir_)` читает v1 и v2. Для v1 скорость берётся по классу, названий и запретов нет.

- [ ] **Step 1: Падающие тесты.** В `tests/test_roadpack.py`:
  - в существующем `test_committed_fixture_matches_writer` вызывать `write_roadpack(..., version=1)`;
  - дописать:

```python
from vpr_bench.roadpack import DEFAULT_SPEED_KMH, FLAG_ROUNDABOUT, KIND_NO, KIND_ONLY

FIXTURE_V2_DIR = Path(__file__).parent / "data" / "roadpack_v2_fixture"


def fixture_graph_v2() -> RoadGraph:
    """fixture_graph() + скорость, названия (кириллица), круговое движение на ребре 1 и два запрета."""
    g = fixture_graph()
    return RoadGraph(
        g.node_lats, g.node_lons, g.edge_way, g.edge_from, g.edge_to,
        np.array([0, FLAG_ONEWAY | FLAG_TUNNEL | FLAG_ROUNDABOUT], dtype=np.uint8), g.edge_class,
        edge_speed=np.array([20, 48], dtype=np.uint8), edge_name=np.array([0, -1], dtype=np.int32),
        names=("Тверская улица",),
        restrictions=np.array([[0, 1, 1, KIND_NO], [1, 1, 0, KIND_ONLY]], dtype=np.int32),
    )


def test_v2_roundtrip_and_fixture(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META)
    g, meta = read_roadpack(tmp_path)
    assert meta["format"] == "VNRD/2"
    assert list(g.edge_speed) == [20, 48] and list(g.edge_name) == [0, -1] and g.names == ("Тверская улица",)
    assert g.restrictions.tolist() == [[0, 1, 1, KIND_NO], [1, 1, 0, KIND_ONLY]]
    assert (tmp_path / "roadpack.bin").read_bytes() == (FIXTURE_V2_DIR / "roadpack.bin").read_bytes()


def test_v2_defaults_without_attributes(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)  # v2 по умолчанию
    g, _ = read_roadpack(tmp_path)
    assert list(g.edge_speed) == [DEFAULT_SPEED_KMH[7], DEFAULT_SPEED_KMH[3]]
    assert list(g.edge_name) == [-1, -1] and g.names == () and len(g.restrictions) == 0


def test_v1_file_reads_with_defaults():
    g, meta = read_roadpack(FIXTURE_DIR)
    assert meta["format"] == "VNRD/1"
    assert list(g.edge_speed) == [DEFAULT_SPEED_KMH[7], DEFAULT_SPEED_KMH[3]] and g.names == ()


def test_v1_writer_rejects_attributes(tmp_path):
    with pytest.raises(ValueError, match="v1"):
        write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META, version=1)


def test_v2_rejects_trailing_bytes(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META)
    p = tmp_path / "roadpack.bin"
    p.write_bytes(p.read_bytes() + b"\x00")
    with pytest.raises(ValueError, match="trailing"):
        read_roadpack(tmp_path)


def test_bad_restriction_rejected():
    g = fixture_graph()
    with pytest.raises(ValueError, match="restriction"):
        RoadGraph(g.node_lats, g.node_lons, g.edge_way, g.edge_from, g.edge_to, g.edge_flags, g.edge_class,
                  restrictions=np.array([[0, 1, 5, KIND_NO]], dtype=np.int32))
```

Run: `uv run pytest tests/test_roadpack.py -q`
Expected: FAIL (`ImportError: DEFAULT_SPEED_KMH`).

- [ ] **Step 2: Реализация** — заменить содержимое `roadpack.py`:

```python
"""roadpack — дорожный граф OSM для телефона. v1 — план M2b; v2 (скорость, названия, запреты) — план M3a."""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"VNRD"
HEADER = struct.Struct("<4sHHII")
FLAG_ONEWAY = 1
FLAG_TUNNEL = 2
FLAG_BRIDGE = 4
FLAG_ROUNDABOUT = 8
KIND_NO = 1
KIND_ONLY = 2
# Скорость для оценки времени в пути по классу дороги, км/ч (общая с Kotlin RoadClass.defaultSpeedKmh).
DEFAULT_SPEED_KMH = {1: 90, 2: 70, 3: 60, 4: 50, 5: 40, 6: 30, 7: 20, 8: 10, 9: 10}


def _default_speeds(cls: np.ndarray) -> np.ndarray:
    return np.array([DEFAULT_SPEED_KMH.get(int(c), 20) for c in cls], dtype=np.uint8)


@dataclass(frozen=True)
class RoadGraph:
    node_lats: np.ndarray   # float64[n]
    node_lons: np.ndarray   # float64[n]
    edge_way: np.ndarray    # int64[m]  — OSM way id
    edge_from: np.ndarray   # int32[m]
    edge_to: np.ndarray     # int32[m]
    edge_flags: np.ndarray  # uint8[m]  — FLAG_*
    edge_class: np.ndarray  # uint8[m]  — код класса дороги
    edge_speed: np.ndarray | None = None    # uint8[m], км/ч; None — по классу
    edge_name: np.ndarray | None = None     # int32[m], индекс в names или -1
    names: tuple[str, ...] = ()
    restrictions: np.ndarray | None = None  # int32[k, 4]: from_edge, via_node, to_edge, kind

    def __post_init__(self) -> None:
        n, m = len(self.node_lats), len(self.edge_from)
        if len(self.node_lons) != n:
            raise ValueError("node_lats and node_lons must have equal length")
        if not (len(self.edge_to) == len(self.edge_way) == len(self.edge_flags) == len(self.edge_class) == m):
            raise ValueError("edge arrays must have equal length")
        if m and (min(self.edge_from.min(), self.edge_to.min()) < 0
                  or max(self.edge_from.max(), self.edge_to.max()) >= n):
            raise ValueError("edge node index out of range")
        if self.edge_speed is None:
            object.__setattr__(self, "edge_speed", _default_speeds(self.edge_class))
        if self.edge_name is None:
            object.__setattr__(self, "edge_name", np.full(m, -1, dtype=np.int32))
        if self.restrictions is None:
            object.__setattr__(self, "restrictions", np.zeros((0, 4), dtype=np.int32))
        if len(self.edge_speed) != m or len(self.edge_name) != m:
            raise ValueError("edge_speed and edge_name must have one entry per edge")
        if m and (self.edge_name.min() < -1 or self.edge_name.max() >= len(self.names)):
            raise ValueError("edge name index out of range")
        r = self.restrictions
        if r.ndim != 2 or r.shape[1] != 4:
            raise ValueError("restrictions must have shape (k, 4)")
        if len(r) and (r[:, [0, 2]].min() < 0 or r[:, [0, 2]].max() >= m or r[:, 1].min() < 0
                       or r[:, 1].max() >= n or not set(r[:, 3].tolist()) <= {KIND_NO, KIND_ONLY}):
            raise ValueError("bad restriction (edge/node index or kind)")


def write_roadpack(out_dir: Path, g: RoadGraph, meta: dict, version: int = 2) -> Path:
    if version not in (1, 2):
        raise ValueError(f"unsupported roadpack version={version}")
    if version == 1 and (g.names or len(g.restrictions)):
        raise ValueError("roadpack v1 cannot store names or restrictions")
    out_dir.mkdir(parents=True, exist_ok=True)
    n, m = len(g.node_lats), len(g.edge_from)
    with (out_dir / "roadpack.bin").open("wb") as f:
        f.write(HEADER.pack(MAGIC, version, 0, n, m))
        f.write(np.asarray(g.node_lats, dtype="<f8").tobytes())
        f.write(np.asarray(g.node_lons, dtype="<f8").tobytes())
        f.write(np.asarray(g.edge_way, dtype="<i8").tobytes())
        f.write(np.asarray(g.edge_from, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_to, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_flags, dtype="u1").tobytes())
        f.write(np.asarray(g.edge_class, dtype="u1").tobytes())
        if version == 2:
            f.write(np.asarray(g.edge_speed, dtype="u1").tobytes())
            f.write(np.asarray(g.edge_name, dtype="<i4").tobytes())
            f.write(struct.pack("<I", len(g.names)))
            for s in g.names:
                b = s.encode("utf-8")
                f.write(struct.pack("<H", len(b)) + b)
            f.write(struct.pack("<I", len(g.restrictions)))
            for fr, via, to, kind in g.restrictions.tolist():
                f.write(struct.pack("<iiiB", fr, via, to, kind))
    full = {**meta, "format": f"VNRD/{version}", "node_count": n, "edge_count": m}
    (out_dir / "roadpack.json").write_text(json.dumps(full, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return out_dir / "roadpack.bin"


def read_roadpack(dir_: Path) -> tuple[RoadGraph, dict]:
    raw = (dir_ / "roadpack.bin").read_bytes()
    if len(raw) < HEADER.size:
        raise ValueError("roadpack too short")
    magic, version, _, n, m = HEADER.unpack_from(raw, 0)
    if magic != MAGIC:
        raise ValueError("not a roadpack (bad magic)")
    if version not in (1, 2):
        raise ValueError(f"unsupported roadpack version={version}")
    base = HEADER.size + 16 * n + 18 * m
    if (version == 1 and len(raw) != base) or (version == 2 and len(raw) < base + 5 * m + 8):
        raise ValueError(f"roadpack size {len(raw)} does not match header (base {base})")
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
    speed = name = restr = None
    names: tuple[str, ...] = ()
    if version == 2:
        speed, name = take("u1", m, 1), take("<i4", m, 4)
        (count,) = struct.unpack_from("<I", raw, off)
        off += 4
        out = []
        for _ in range(count):
            (ln,) = struct.unpack_from("<H", raw, off)
            out.append(raw[off + 2: off + 2 + ln].decode("utf-8"))
            off += 2 + ln
        names = tuple(out)
        (k,) = struct.unpack_from("<I", raw, off)
        off += 4
        rows = [struct.unpack_from("<iiiB", raw, off + 13 * i) for i in range(k)]
        off += 13 * k
        restr = np.array(rows, dtype=np.int32).reshape(k, 4)
        if off != len(raw):
            raise ValueError(f"roadpack has {len(raw) - off} trailing bytes")
    meta = json.loads((dir_ / "roadpack.json").read_text(encoding="utf-8"))
    g = RoadGraph(lats, lons, way, fr.astype(np.int32), to.astype(np.int32), flags, cls,
                  edge_speed=speed, edge_name=name, names=names, restrictions=restr)
    return g, meta
```

- [ ] **Step 3: Эталон v2.** Он синтетический, поэтому его можно коммитить:

```bash
cd /Users/vvnovg/navigator/research/vpr_bench
uv run python -c "
import sys; sys.path.insert(0, 'tests')
from test_roadpack import fixture_graph_v2, FIXTURE_META, FIXTURE_V2_DIR
from vpr_bench.roadpack import write_roadpack
write_roadpack(FIXTURE_V2_DIR, fixture_graph_v2(), FIXTURE_META)
"
```

- [ ] **Step 4: Тесты зелёные.** Run: `uv run pytest -q` → не меньше 211 passed, 4 deselected.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/roadpack.py research/vpr_bench/tests/test_roadpack.py research/vpr_bench/tests/data/roadpack_v2_fixture
git commit -m "feat(vpr-bench): roadpack v2 — speeds, street names, roundabouts, turn restrictions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Названия, скорость, круговое движение и запреты поворотов из OSM

**Files:**
- Modify: `research/vpr_bench/src/vpr_bench/osmgraph.py`
- Modify: `research/vpr_bench/src/vpr_bench/m2cli.py`: `_pack_roads` вызывает `read_osm`
- Test: `research/vpr_bench/tests/test_osmgraph.py` (дополнить)

**Interfaces:**
- Consumes: `RoadGraph` v2, `DEFAULT_SPEED_KMH`, `FLAG_ROUNDABOUT`, `KIND_NO`, `KIND_ONLY` (Task 1).
- Produces:
  - `@dataclass(frozen=True) RawRestriction(from_way: int, via_node: int, to_way: int, kind: int)`;
  - `speed_kmh(tags, cls) -> int`;
  - `street_name(tags) -> str | None` — `name`, иначе `ref`;
  - `build_graph(ways, keep=None, restrictions=())` — заполняет скорость, названия, флаг кругового движения и запреты;
  - `read_osm(path, bbox=None) -> tuple[list[RawWay], list[RawRestriction]]`;
  - `read_ways(path, bbox)` остаётся и возвращает `read_osm(...)[0]`.

Правила:
- `maxspeed`:
  - число (`"60"`, `"60 km/h"`);
  - `"RU:urban"` = 60, `"RU:rural"` = 90, `"RU:motorway"` = 110, `"RU:living_street"` = 20;
  - `"<n> mph"` — переводится в км/ч;
  - остальное — нет значения.

  Скорость = `max(5, round(min(maxspeed, 110) * 0.8))`. Без `maxspeed` берётся `DEFAULT_SPEED_KMH[cls]`.
- Круговое движение: `junction` ∈ {roundabout, circular} → `FLAG_ROUNDABOUT`.
- Запрет:
  - отношение с `type=restriction` и значением `restriction` (или `restriction:motorcar`), начинающимся с `no_` (вид 1) или `only_` (вид 2);
  - участники: роль `from` (way), `via` (node), `to` (way). Запреты с `via`-линией пропускаются;
  - разрешение в индексы: `via` — индекс узла графа; `from_edge` — каждое ребро линии `from_way`, у которого `from` или `to` равно `via`; `to_edge` — так же для `to_way`;
  - запрет, части которого не попали в граф, отбрасывается.

- [ ] **Step 1: Падающие тесты** (в `tests/test_osmgraph.py`):

```python
from vpr_bench.osmgraph import RawRestriction, speed_kmh, street_name
from vpr_bench.roadpack import DEFAULT_SPEED_KMH, FLAG_ROUNDABOUT, KIND_NO, KIND_ONLY


def test_speed_and_name():
    assert speed_kmh({"maxspeed": "60"}, 3) == 48
    assert speed_kmh({"maxspeed": "RU:urban"}, 7) == 48
    assert speed_kmh({"maxspeed": "130"}, 1) == 88   # ограничено 110 → 88
    assert speed_kmh({"maxspeed": "30 mph"}, 7) == 39
    assert speed_kmh({"maxspeed": "signals"}, 4) == DEFAULT_SPEED_KMH[4]
    assert speed_kmh({}, 9) == DEFAULT_SPEED_KMH[9]
    assert street_name({"name": "Тверская улица", "ref": "M1"}) == "Тверская улица"
    assert street_name({"ref": "M1"}) == "M1" and street_name({}) is None


def test_build_graph_names_speed_roundabout_restrictions():
    a, b, c, d = (1, 55.75, 37.60), (2, 55.751, 37.60), (3, 55.752, 37.60), (4, 55.751, 37.602)
    ways = [
        _way(10, {"name": "Тверская улица", "maxspeed": "60"}, a, b, c),
        _way(11, {"name": "Тверская улица"}, b, d),
        _way(12, {"junction": "roundabout"}, d, c),
    ]
    rs = [RawRestriction(10, 2, 11, KIND_NO), RawRestriction(11, 2, 10, KIND_ONLY), RawRestriction(10, 99, 11, KIND_NO)]
    g = build_graph(ways, restrictions=rs)
    assert g.names == ("Тверская улица",)
    assert list(g.edge_name) == [0, 0, 0, -1]
    assert list(g.edge_speed) == [48, 48, DEFAULT_SPEED_KMH[7], DEFAULT_SPEED_KMH[7]]
    assert g.edge_flags[3] & FLAG_ROUNDABOUT and g.edge_flags[3] & FLAG_ONEWAY
    # way 10 касается узла b двумя рёбрами (0: a→b, 1: b→c); way 11 — одним (2: b→d); запрет с узлом 99 отброшен
    assert sorted(map(tuple, g.restrictions.tolist())) == [
        (0, 1, 2, KIND_NO), (1, 1, 2, KIND_NO), (2, 1, 0, KIND_ONLY), (2, 1, 1, KIND_ONLY)]


OSM_RESTRICTION_XML = OSM_XML.replace("</osm>", """  <relation id="50" version="1">
    <member type="way" ref="10" role="from"/><member type="node" ref="2" role="via"/>
    <member type="way" ref="11" role="to"/>
    <tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/>
  </relation>
</osm>
""")


def test_read_osm_restrictions(tmp_path):
    pytest.importorskip("osmium")
    from vpr_bench.osmgraph import read_osm
    p = tmp_path / "t.osm"
    p.write_text(OSM_RESTRICTION_XML)
    ways, rs = read_osm(p)
    assert sorted(w.id for w in ways) == [10, 11, 13]
    assert rs == [RawRestriction(10, 2, 11, KIND_NO)]


def test_pack_roads_writes_v2(tmp_path):
    pytest.importorskip("osmium")
    (tmp_path / "t.osm").write_text(OSM_RESTRICTION_XML)
    (tmp_path / "t.gpx").write_text(GPX)
    out = tmp_path / "roads"
    assert main(["pack-roads", "--pbf", str(tmp_path / "t.osm"), "--gpx", str(tmp_path / "t.gpx"),
                 "--out", str(out)]) == 0
    g, meta = read_roadpack(out)
    assert meta["format"] == "VNRD/2" and len(g.restrictions) >= 1
```

Run: `uv run pytest tests/test_osmgraph.py -q`
Expected: FAIL (`ImportError: RawRestriction`).

- [ ] **Step 2: Реализация** в `osmgraph.py`.

1. Импорты: `DEFAULT_SPEED_KMH`, `FLAG_ROUNDABOUT`, `KIND_NO`, `KIND_ONLY` из `vpr_bench.roadpack`; `import re`.
2. Новые определения:

```python
@dataclass(frozen=True)
class RawRestriction:
    from_way: int
    via_node: int  # OSM id узла
    to_way: int
    kind: int      # KIND_NO | KIND_ONLY


_RU_SPEED = {"RU:urban": 60, "RU:rural": 90, "RU:motorway": 110, "RU:living_street": 20}


def _parse_maxspeed(v: str | None) -> float | None:
    if not v:
        return None
    if v in _RU_SPEED:
        return float(_RU_SPEED[v])
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(km/h|kmh|mph)?\s*", v)
    if not m:
        return None
    x = float(m.group(1))
    return x * 1.609344 if m.group(2) == "mph" else x


def speed_kmh(tags: dict[str, str], cls: int) -> int:
    """Скорость для оценки времени в пути: 0,8 от maxspeed (не больше 110), иначе по классу дороги."""
    ms = _parse_maxspeed(tags.get("maxspeed"))
    if ms is None:
        return DEFAULT_SPEED_KMH[cls]
    return max(5, round(min(ms, 110.0) * 0.8))


def street_name(tags: dict[str, str]) -> str | None:
    return tags.get("name") or tags.get("ref") or None
```

3. В `_flags`: `if tags.get("junction") in ("roundabout", "circular"): f |= FLAG_ROUNDABOUT`.
4. В `build_graph(ways, keep=None, restrictions=())`:
   - для каждой линии считать `speed = speed_kmh(w.tags, cls)`;
   - `name_idx`: индекс `street_name(w.tags)` в списке `names`, добавляемом по первому появлению, или −1;
   - копить `e_speed` и `e_name` вместе с остальными массивами рёбер;
   - после цикла разрешить запреты:

```python
    edges_of_way: dict[int, list[int]] = {}
    for k, wid in enumerate(e_way):
        edges_of_way.setdefault(wid, []).append(k)
    rows: list[tuple[int, int, int, int]] = []
    for r in restrictions:
        via = index.get(r.via_node)
        if via is None:
            continue
        def touching(way_id: int) -> list[int]:
            return [k for k in edges_of_way.get(way_id, []) if e_from[k] == via or e_to[k] == via]
        for fe in touching(r.from_way):
            for te in touching(r.to_way):
                rows.append((fe, via, te, r.kind))
```

   Вернуть `RoadGraph(..., edge_speed=np.array(e_speed, dtype=np.uint8), edge_name=np.array(e_name, dtype=np.int32), names=tuple(names), restrictions=np.array(rows, dtype=np.int32).reshape(len(rows), 4))`.
5. `read_osm(path, bbox=None)`:
   - тот же обработчик, что в `read_ways`, плюс метод:

```python
        def relation(self, r) -> None:
            tags = {t.k: t.v for t in r.tags}
            if tags.get("type") != "restriction":
                return
            value = tags.get("restriction") or tags.get("restriction:motorcar") or ""
            kind = KIND_NO if value.startswith("no_") else KIND_ONLY if value.startswith("only_") else 0
            if not kind:
                return
            roles: dict[str, list[tuple[str, int]]] = {}
            for m in r.members:
                roles.setdefault(m.role, []).append((m.type, m.ref))
            fr, via, to = roles.get("from", []), roles.get("via", []), roles.get("to", [])
            if len(fr) == 1 and len(via) == 1 and len(to) == 1 and fr[0][0] == "w" and via[0][0] == "n" \
                    and to[0][0] == "w":
                rs.append(RawRestriction(fr[0][1], via[0][1], to[0][1], kind))
```

   - вернуть `(out, rs)`;
   - `read_ways(path, bbox=None)` возвращает `read_osm(path, bbox)[0]`.
6. В `m2cli._pack_roads`: `ways, rs = read_osm(args.pbf, corridor.bbox())` и `graph = build_graph(ways, keep=corridor.contains, restrictions=rs)`. Писать v2 (по умолчанию).

- [ ] **Step 3: Тесты зелёные.** Run: `uv run pytest -q` → не меньше 215 passed, 4 deselected.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/osmgraph.py research/vpr_bench/src/vpr_bench/m2cli.py research/vpr_bench/tests/test_osmgraph.py
git commit -m "feat(vpr-bench): street names, speeds, roundabouts and turn restrictions from OSM

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Чтение `roadpack` v2 в `:core` и смежность по узлам

**Files:**
- Modify: `android/core/src/main/kotlin/io/visnav/core/RoadPack.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/RoadIndex.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/RoadPackTest.kt`, `RoadIndexTest.kt` (дополнить)

**Interfaces:**
- Produces:
  - `data class TurnRestriction(val fromEdge: Int, val via: Int, val toEdge: Int, val only: Boolean)`;
  - `RoadPack` получает в конце конструктора параметры со значениями по умолчанию, так что существующие вызовы компилируются:
    - `speedKmh: ByteArray = ByteArray(from.size) { RoadClass.defaultSpeedKmh(cls[it].toInt() and 0xFF).toByte() }`;
    - `nameIdx: IntArray = IntArray(from.size) { -1 }`;
    - `names: List<String> = emptyList()`;
    - `restrictions: List<TurnRestriction> = emptyList()`;
  - методы `RoadPack`: `name(edge): String?`, `speedMps(edge): Double` (0 → по классу), `roundabout(edge): Boolean`;
  - константа `RoadPack.FLAG_ROUNDABOUT = 8`;
  - `RoadClass.defaultSpeedKmh(cls: Int): Int` — таблица из Global Constraints, неизвестный класс → 20;
  - `RoadPack.parse` читает v1 и v2;
  - `RoadIndex.incident: Array<IntArray>` — рёбра, касающиеся узла, независимо от направления.

- [ ] **Step 1: Падающие тесты.** В `RoadPackTest.kt` добавить:

```kotlin
    private val fixtureV2 = File("../../research/vpr_bench/tests/data/roadpack_v2_fixture")

    @Test fun parsesV2Fixture() {
        val pack = RoadPack.parse(ByteBuffer.wrap(File(fixtureV2, "roadpack.bin").readBytes()))
        assertEquals("Тверская улица", pack.name(0)); assertEquals(null, pack.name(1))
        assertEquals(20 / 3.6, pack.speedMps(0), 1e-9); assertEquals(48 / 3.6, pack.speedMps(1), 1e-9)
        assertTrue(pack.roundabout(1)); assertFalse(pack.roundabout(0))
        assertEquals(listOf(TurnRestriction(0, 1, 1, false), TurnRestriction(1, 1, 0, true)), pack.restrictions)
    }

    @Test fun v1FixtureGetsDefaults() {
        val pack = RoadPack.parse(ByteBuffer.wrap(File(fixture, "roadpack.bin").readBytes()))
        assertEquals(RoadClass.defaultSpeedKmh(RoadClass.RESIDENTIAL) / 3.6, pack.speedMps(0), 1e-9)
        assertEquals(null, pack.name(0)); assertTrue(pack.restrictions.isEmpty())
    }

    @Test fun rejectsV2TrailingBytes() {
        val raw = File(fixtureV2, "roadpack.bin").readBytes()
        assertFailsWith<IllegalArgumentException> { RoadPack.parse(ByteBuffer.wrap(raw + byteArrayOf(0))) }
    }

    @Test fun defaultSpeedTable() {
        assertEquals(listOf(90, 70, 60, 50, 40, 30, 20, 10, 10), (1..9).map { RoadClass.defaultSpeedKmh(it) })
    }
```

В `RoadIndexTest.kt`:

```kotlin
    @Test fun incidentListsEdgesTouchingNode() {
        val pack = roadPackOf(enu, listOf(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0, 100.0 to 100.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1, flags = RoadPack.FLAG_ONEWAY), EdgeSpec(3, 1, 2)))
        val idx = RoadIndex(pack, enu)
        assertEquals(setOf(0, 1, 2), idx.incident[1].toSet()); assertEquals(setOf(1), idx.incident[2].toSet())
    }
```

Run: `./gradlew :core:test` → FAIL при компиляции (`Unresolved reference: TurnRestriction`).

- [ ] **Step 2: Реализация.**

`RoadPack.kt`:
- в начало файла добавить `data class TurnRestriction(val fromEdge: Int, val via: Int, val toEdge: Int, val only: Boolean)`;
- расширить конструктор параметрами из Interfaces и добавить проверки в `init`:

```kotlin
        require(speedKmh.size == m && nameIdx.size == m) { "speed/name arrays size mismatch" }
        for (k in 0 until m) require(nameIdx[k] in -1 until names.size) { "edge $k: name index out of range" }
        for (r in restrictions) {
            require(r.fromEdge in 0 until m && r.toEdge in 0 until m && r.via in 0 until nodeCount) {
                "bad restriction $r"
            }
        }
```

- методы:

```kotlin
    fun roundabout(edge: Int): Boolean = flags[edge].toInt() and FLAG_ROUNDABOUT != 0
    fun name(edge: Int): String? = nameIdx[edge].let { if (it < 0) null else names[it] }
    fun speedMps(edge: Int): Double {
        val v = speedKmh[edge].toInt() and 0xFF
        return (if (v == 0) RoadClass.defaultSpeedKmh(roadClass(edge)) else v) / 3.6
    }
```

- в `companion`: `const val FLAG_ROUNDABOUT = 8`;
- `parse`:
  - принимать `version` 1 или 2;
  - для v1 требовать точный размер `16 + 16n + 18m`;
  - для v2 требовать `total >= 16 + 16n + 18m + 5m + 8`;
  - после массивов v1 для v2 читать:

```kotlin
            var speed: ByteArray? = null; var nameIdx: IntArray? = null
            var names: List<String> = emptyList(); var restrictions: List<TurnRestriction> = emptyList()
            if (version == 2) {
                speed = ByteArray(m).also { b.get(it) }
                nameIdx = IntArray(m).also { b.asIntBuffer().get(it) }; b.position(b.position() + 4 * m)
                val nc = b.int
                require(nc >= 0) { "bad name count" }
                names = List(nc) {
                    val len = b.short.toInt() and 0xFFFF
                    require(b.remaining() >= len) { "roadpack truncated in names" }
                    ByteArray(len).also { b.get(it) }.toString(Charsets.UTF_8)
                }
                val rc = b.int
                require(rc >= 0 && b.remaining().toLong() == 13L * rc) {
                    "roadpack restriction table size mismatch (${b.remaining()} trailing bytes for $rc entries)"
                }
                restrictions = List(rc) { TurnRestriction(b.int, b.int, b.int, (b.get().toInt() and 0xFF) == 2) }
            }
            return RoadPack(lats, lons, way, from, to, flags, cls,
                speed ?: ByteArray(m) { RoadClass.defaultSpeedKmh(cls[it].toInt() and 0xFF).toByte() },
                nameIdx ?: IntArray(m) { -1 }, names, restrictions)
```

  `BufferUnderflowException` при обрыве оборачивать в `IllegalArgumentException("roadpack truncated")`. Для v1 сохранить проверку точного размера до чтения.
- `RoadClass`:

```kotlin
    /** Скорость для оценки времени в пути по классу, км/ч (общая с vpr_bench.roadpack.DEFAULT_SPEED_KMH). */
    fun defaultSpeedKmh(cls: Int): Int = when (cls) {
        MOTORWAY -> 90; TRUNK -> 70; PRIMARY -> 60; SECONDARY -> 50; TERTIARY -> 40
        UNCLASSIFIED -> 30; RESIDENTIAL -> 20; LIVING_STREET, SERVICE -> 10; else -> 20
    }
```

`RoadIndex.kt`:
- поле `val incident: Array<IntArray>`;
- в `init`: список рёбер для каждого узла, ребро `k` добавляется к узлам `from[k]` и `to[k]`; петля добавляется один раз.

- [ ] **Step 3: Тесты зелёные.** Run: `./gradlew :core:test :replay:test` → `:core` не меньше 178 тестов, `:replay` — 35.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): roadpack v2 reader — speeds, names, roundabouts, turn restrictions; node incidence

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Маршрутизатор `Router`

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/Router.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/RouterTest.kt`

**Interfaces:**
- Consumes: `RoadIndex` (`project`, `near`, `length`, `bearing`, `degree`, `incident`, `nodeE/N`, `enu`), `RoadPack` (`oneway`, `speedMps`, `restrictions`, `way`), `wrapAngle`.
- Produces:
  - `data class RouteStep(val edge: Int, val forward: Boolean)`;
  - `class Route(val steps: List<RouteStep>, val points: List<DoubleArray>, val cumM: DoubleArray, val durationS: Double)`, свойство `lengthM`:
    - `points[0]` — проекция старта;
    - `points[i]` (1 ≤ i < steps.size) — узел между шагами `i−1` и `i`;
    - последняя точка — проекция финиша;
    - `cumM` — накопленная длина по точкам;
  - `data class RouterConfig(snapRadiusM = 60.0, destSnapRadiusM = 150.0, goalSlackM = 25.0, maxCandidateWays = 4, turnPenaltyS = 5.0, wrongHeadingPenaltyS = 60.0, maxSpeedMps = 130 / 3.6)`;
  - `class Router(index, config = RouterConfig())` с методом `route(fromE, fromN, headingRad: Double?, toE, toN): Route?`.

Модель:
- **Состояние** — ребро и направление проезда; его стоимость — время до выходного узла ребра.
- **Кандидаты старта и финиша** — ближайшая проекция каждой дороги (way) в радиусе, не больше `maxCandidateWays` дорог. Для двустороннего ребра оба направления, для одностороннего — только прямое.
- **Кандидаты финиша дополнительно отбираются по расстоянию:** берутся только те, что не дальше ближайшего + `goalSlackM`. Иначе маршрут мог бы закончиться на соседней улице в 70 м от цели, если до неё быстрее доехать.
- **Стоимость старта:**
  - `(len − pos) / speed`, где `pos` — расстояние от входа по ходу движения;
  - плюс `wrongHeadingPenaltyS`, если курс задан и расходится с направлением проезда больше чем на 90°.
- **Переход** из выходного узла `v` в ребро `k2`:
  - направление `k2` — прямое, если `from[k2] == v`; обратное — если `to[k2] == v` и ребро не одностороннее;
  - разворот на том же ребре запрещён, если у узла степень больше 1;
  - запреты: если есть `no` с `(k, v, k2)` — нельзя; если есть хотя бы один `only` с `(k, v, ·)` — можно только в его `toEdge`;
  - стоимость: `len[k2] / speed[k2]` плюс `turnPenaltyS`, если поворот больше 45°.
- **Финиш:** в состояние финишного кандидата можно войти; итог = стоимость на входе + `posFinish / speed`. Частный случай — старт и финиш на одном ребре в одном направлении при `posFinish ≥ posStart`.
- **A\*:** эвристика — расстояние от выходного узла до финиша, делённое на `maxSpeedMps`. Остановка, когда минимум `f` в очереди не меньше лучшего найденного итога.

- [ ] **Step 1: Падающие тесты** — `RouterTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTest {
    private val enu = Enu(55.75, 37.60)

    /** Узлы в метрах; рёбра (from, to, way, flags, cls, speedKmh); запреты по индексам рёбер. */
    private fun pack(
        nodes: List<Pair<Double, Double>>, edges: List<EdgeSpec>, speeds: List<Int>? = null,
        restrictions: List<TurnRestriction> = emptyList(),
    ): RoadPack {
        val base = roadPackOf(enu, nodes, edges)
        val sp = speeds?.let { s -> ByteArray(s.size) { s[it].toByte() } }
            ?: ByteArray(edges.size) { RoadClass.defaultSpeedKmh(edges[it].cls).toByte() }
        return RoadPack(base.lats, base.lons, base.way, base.from, base.to, base.flags, base.cls, sp,
            IntArray(edges.size) { -1 }, emptyList(), restrictions)
    }

    private fun ways(r: Route, p: RoadPack) = r.steps.map { p.way[it.edge] }.distinct()

    @Test fun prefersFasterRoadByTime() {
        // A(0,0) → B(1000,0) напрямую по двору 20 км/ч или через (500,400) по проспекту 60 км/ч (≈1280 м).
        val p = pack(listOf(0.0 to 0.0, 1000.0 to 0.0, 500.0 to 400.0),
            listOf(EdgeSpec(0, 1, 1), EdgeSpec(0, 2, 2, cls = RoadClass.PRIMARY), EdgeSpec(2, 1, 2, cls = RoadClass.PRIMARY)),
            speeds = listOf(20, 60, 60))
        val r = assertNotNull(Router(RoadIndex(p, enu)).route(0.0, 0.0, null, 1000.0, 0.0))
        assertEquals(listOf(2L), ways(r, p))
        assertTrue(r.durationS < 1000 / (20 / 3.6))
    }

    @Test fun respectsOnewayAndGoesAround() {
        // Прямая 0→1 односторонняя на запад (1→0); ехать на восток можно только через верхнюю петлю.
        val p = pack(listOf(0.0 to 0.0, 500.0 to 0.0, 0.0 to 200.0, 500.0 to 200.0),
            listOf(EdgeSpec(1, 0, 1, flags = RoadPack.FLAG_ONEWAY), EdgeSpec(0, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(3, 1, 4)))
        val r = assertNotNull(Router(RoadIndex(p, enu)).route(0.0, 0.0, null, 500.0, 0.0))
        assertEquals(listOf(2L, 3L, 4L), ways(r, p))
    }

    @Test fun noTurnRestrictionForcesAlternative() {
        // Крест в узле 1: запрещён поворот с ребра 0 (с запада) в ребро 2 (на север); есть объезд через восток.
        val nodes = listOf(-300.0 to 0.0, 0.0 to 0.0, 0.0 to 300.0, 300.0 to 0.0, 300.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 3, 1), EdgeSpec(1, 2, 2), EdgeSpec(3, 4, 3), EdgeSpec(4, 2, 4))
        val free = assertNotNull(Router(RoadIndex(pack(nodes, edges), enu)).route(-300.0, 0.0, null, 0.0, 300.0))
        assertEquals(listOf(1L, 2L), ways(free, pack(nodes, edges)))
        val restricted = pack(nodes, edges, restrictions = listOf(TurnRestriction(0, 1, 2, only = false)))
        val r = assertNotNull(Router(RoadIndex(restricted, enu)).route(-300.0, 0.0, null, 0.0, 300.0))
        assertEquals(listOf(1L, 3L, 4L), ways(r, restricted))
    }

    @Test fun onlyRestrictionAllowsOnlyItsTarget() {
        val nodes = listOf(-300.0 to 0.0, 0.0 to 0.0, 0.0 to 300.0, 300.0 to 0.0)
        // Ребро 1 одностороннее, чтобы нельзя было развернуться в тупике и вернуться к повороту.
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 3, 1, flags = RoadPack.FLAG_ONEWAY), EdgeSpec(1, 2, 2))
        val only = pack(nodes, edges, restrictions = listOf(TurnRestriction(0, 1, 1, only = true)))
        assertNull(Router(RoadIndex(only, enu)).route(-300.0, 0.0, null, 0.0, 300.0))
    }

    @Test fun startHeadingAvoidsDrivingAgainstIt() {
        // Двусторонняя прямая по n=0 (проспекты, 60 км/ч) и квартал через n=100; машина смотрит на восток,
        // цель — узел (300, 0) в 150 м позади. Объезд квартала: 650 м ≈ 54 с; «против курса»: 9 с + штраф 60 с.
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 0.0, 600.0 to 100.0, 300.0 to 100.0)
        val p = RoadClass.PRIMARY
        val edges = listOf(EdgeSpec(0, 1, 1, cls = p), EdgeSpec(1, 2, 1, cls = p), EdgeSpec(2, 3, 2, cls = p),
            EdgeSpec(3, 4, 3, cls = p), EdgeSpec(4, 1, 4, cls = p))
        val router = Router(RoadIndex(pack(nodes, edges), enu))
        val noHeading = assertNotNull(router.route(450.0, 0.0, null, 300.0, 0.0))
        assertTrue(!noHeading.steps.first().forward); assertEquals(150.0, noHeading.lengthM, 1e-6)
        val east = assertNotNull(router.route(450.0, 0.0, Math.PI / 2, 300.0, 0.0))
        assertTrue(east.steps.first().forward, "starts eastward")
        assertEquals(650.0, east.lengthM, 1e-6)
    }

    @Test fun pointsAndLengthAreConsistent() {
        val p = pack(listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0), listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2)))
        val r = assertNotNull(Router(RoadIndex(p, enu)).route(20.0, 3.0, null, 100.0, 70.0))
        assertEquals(3, r.points.size); assertEquals(80.0 + 70.0, r.lengthM, 1e-6)
        assertEquals(100.0, r.points[1][0], 1e-6); assertEquals(0.0, r.points[1][1], 1e-6)
    }

    @Test fun unreachableOrOffGraphReturnsNull() {
        val p = pack(listOf(0.0 to 0.0, 100.0 to 0.0), listOf(EdgeSpec(0, 1, 1)))
        val router = Router(RoadIndex(p, enu))
        assertNull(router.route(50.0, 0.0, null, 5000.0, 5000.0))
        assertNull(router.route(5000.0, 5000.0, null, 50.0, 0.0))
    }
}
```

Run: `./gradlew :core:test --tests 'io.visnav.core.RouterTest'` → FAIL при компиляции (`Unresolved reference: Router`).

- [ ] **Step 2: Реализация** — `Router.kt`:

```kotlin
package io.visnav.core

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Шаг маршрута: ребро и направление проезда (forward — from → to). */
data class RouteStep(val edge: Int, val forward: Boolean)

/**
 * Маршрут: points[0] — проекция старта, points[i] (1 ≤ i < steps.size) — узел между шагами i−1 и i, последняя —
 * проекция финиша; cumM — накопленная длина до каждой точки; durationS — оценка времени в пути.
 */
class Route(val steps: List<RouteStep>, val points: List<DoubleArray>, val cumM: DoubleArray, val durationS: Double) {
    val lengthM: Double get() = cumM.last()
}

data class RouterConfig(
    val snapRadiusM: Double = 60.0,
    val destSnapRadiusM: Double = 150.0,
    /** Финиш — только на дорогах не дальше ближайшей к цели + goalSlackM. */
    val goalSlackM: Double = 25.0,
    val maxCandidateWays: Int = 4,
    val turnPenaltyS: Double = 5.0,
    val wrongHeadingPenaltyS: Double = 60.0,
    val maxSpeedMps: Double = 130 / 3.6,
)

/**
 * A* по направленным рёбрам графа коридора: односторонние улицы, запреты поворотов (no_* / only_*), стоимость —
 * время в пути. Разворот на том же ребре — только в тупике.
 */
class Router(private val index: RoadIndex, private val config: RouterConfig = RouterConfig()) {
    private val pack = index.pack
    private val byFromVia: Map<Long, List<TurnRestriction>> =
        pack.restrictions.groupBy { (it.fromEdge.toLong() shl 32) or it.via.toLong() }

    private class Cand(val proj: RoadProjection, val forward: Boolean, val pos: Double)

    private fun state(edge: Int, forward: Boolean) = edge * 2 + if (forward) 0 else 1
    private fun exitNode(edge: Int, forward: Boolean) = if (forward) pack.to[edge] else pack.from[edge]
    private fun travelBearing(edge: Int, forward: Boolean) =
        if (forward) index.bearing[edge] else wrapAngle(index.bearing[edge] + PI)

    private fun candidates(e: Double, n: Double, radius: Double): List<Cand> =
        index.near(e, n, radius).distinctBy { pack.way[it.edge] }.take(config.maxCandidateWays).flatMap { p ->
            val len = index.length[p.edge]
            listOfNotNull(
                Cand(p, true, p.t * len),
                if (pack.oneway(p.edge)) null else Cand(p, false, (1 - p.t) * len),
            )
        }

    private fun allowed(fromEdge: Int, via: Int, toEdge: Int): Boolean {
        val rs = byFromVia[(fromEdge.toLong() shl 32) or via.toLong()] ?: return true
        if (rs.any { !it.only && it.toEdge == toEdge }) return false
        val only = rs.filter { it.only }
        return only.isEmpty() || only.any { it.toEdge == toEdge }
    }

    fun route(fromE: Double, fromN: Double, headingRad: Double?, toE: Double, toN: Double): Route? {
        val starts = candidates(fromE, fromN, config.snapRadiusM)
        val goalsAll = candidates(toE, toN, config.destSnapRadiusM)
        if (starts.isEmpty() || goalsAll.isEmpty()) return null
        val nearest = goalsAll.minOf { it.proj.distM }
        val goals = goalsAll.filter { it.proj.distM <= nearest + config.goalSlackM }
        val goalOf = HashMap<Int, MutableList<Cand>>()
        for (g in goals) goalOf.getOrPut(state(g.proj.edge, g.forward)) { ArrayList() }.add(g)

        val nStates = pack.edgeCount * 2
        val gCost = DoubleArray(nStates) { Double.POSITIVE_INFINITY }
        val prev = IntArray(nStates) { -2 }      // -1 — стартовое состояние
        val startPos = DoubleArray(nStates)
        fun h(s: Int): Double {
            val v = exitNode(s / 2, s % 2 == 0)
            return hypot(index.nodeE[v] - toE, index.nodeN[v] - toN) / config.maxSpeedMps
        }
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        var bestTotal = Double.POSITIVE_INFINITY
        var bestGoalState = -1
        var bestGoalPos = 0.0
        var bestGoalPrev = -1
        var directStart: Cand? = null

        for (c in starts) {
            val e = c.proj.edge
            val len = index.length[e]
            val speed = pack.speedMps(e)
            var cost = (len - c.pos) / speed
            if (headingRad != null && abs(wrapAngle(headingRad - travelBearing(e, c.forward))) > PI / 2) {
                cost += config.wrongHeadingPenaltyS
            }
            val s = state(e, c.forward)
            // Финиш на том же ребре впереди по ходу движения.
            goalOf[s]?.filter { it.pos >= c.pos }?.forEach { g ->
                val total = cost - (len - g.pos) / speed
                if (total < bestTotal) { bestTotal = total; bestGoalState = s; bestGoalPos = g.pos; directStart = c }
            }
            if (cost < gCost[s]) {
                gCost[s] = cost; prev[s] = -1; startPos[s] = c.pos
                pq.add(cost + h(s) to s)
            }
        }
        while (pq.isNotEmpty()) {
            val (f, s) = pq.poll()
            if (f >= bestTotal) break
            val g0 = gCost[s]
            if (f - h(s) > g0 + 1e-9) continue
            val edge = s / 2
            val v = exitNode(edge, s % 2 == 0)
            for (k2 in index.incident[v]) {
                val fwd2 = pack.from[k2] == v
                if (!fwd2 && (pack.to[k2] != v || pack.oneway(k2))) continue
                if (k2 == edge && index.degree[v] > 1) continue
                if (!allowed(edge, v, k2)) continue
                val turn = abs(wrapAngle(travelBearing(k2, fwd2) - travelBearing(edge, s % 2 == 0)))
                val entry = g0 + if (turn > PI / 4) config.turnPenaltyS else 0.0
                val s2 = state(k2, fwd2)
                val speed2 = pack.speedMps(k2)
                goalOf[s2]?.forEach { g ->
                    val total = entry + g.pos / speed2
                    if (total < bestTotal) {
                        // Предшественник финиша хранится отдельно: prev[s2] может позже смениться на путь,
                        // который проходит ребро целиком и к этому финишу не относится.
                        bestTotal = total; bestGoalState = s2; bestGoalPos = g.pos; bestGoalPrev = s; directStart = null
                    }
                }
                val g2 = entry + index.length[k2] / speed2
                if (g2 < gCost[s2]) {
                    gCost[s2] = g2; prev[s2] = s
                    pq.add(g2 + h(s2) to s2)
                }
            }
        }
        if (bestGoalState < 0) return null
        return build(bestGoalState, bestGoalPos, bestGoalPrev, directStart, prev, startPos, bestTotal)
    }

    private fun build(
        goal: Int, goalPos: Double, goalPrev: Int, direct: Cand?, prev: IntArray, startPos: DoubleArray, total: Double,
    ): Route {
        val chain = ArrayList<Int>()
        if (direct == null) {
            // Состояния раскрываются только после того, как их стоимость окончательна (A*), поэтому цепочка prev
            // от предшественника финиша — та самая, по которой считался bestTotal.
            var s = goalPrev
            while (s >= 0) { chain.add(s); s = prev[s] }
            chain.reverse()
        }
        chain.add(goal)
        val steps = chain.map { RouteStep(it / 2, it % 2 == 0) }
        val first = steps.first()
        val firstPos = direct?.pos ?: startPos[chain.first()]
        fun pointAt(step: RouteStep, pos: Double): DoubleArray {
            val a = if (step.forward) pack.from[step.edge] else pack.to[step.edge]
            val b = if (step.forward) pack.to[step.edge] else pack.from[step.edge]
            val len = index.length[step.edge]
            val f = if (len == 0.0) 0.0 else pos / len
            return doubleArrayOf(
                index.nodeE[a] + f * (index.nodeE[b] - index.nodeE[a]),
                index.nodeN[a] + f * (index.nodeN[b] - index.nodeN[a]),
            )
        }
        val points = ArrayList<DoubleArray>()
        points.add(pointAt(first, firstPos))
        for (i in 0 until steps.size - 1) {
            val v = exitNode(steps[i].edge, steps[i].forward)
            points.add(doubleArrayOf(index.nodeE[v], index.nodeN[v]))
        }
        points.add(pointAt(steps.last(), goalPos))
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
        }
        return Route(steps, points, cum, total)
    }
}
```

Если тест показывает дефект в этой логике (например, в учёте финиша на ребре, куда A* не заходит повторно), исправить реализацию в рамках модели, описанной выше, и указать это в отчёте. Тесты не ослаблять.

- [ ] **Step 3: Тесты зелёные.** Run: `./gradlew :core:test` → `:core` не меньше 185 тестов.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): offline A* router over the corridor road graph with turn restrictions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Манёвры и русские подсказки

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/Maneuvers.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/Instructions.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/ManeuversTest.kt`, `InstructionsTest.kt`

**Interfaces:**
- Consumes: `Route`, `RouteStep`, `RoadIndex`, `RoadPack` (`name`, `roundabout`).
- Produces:
  - `enum class ManeuverType { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }`;
  - `data class Maneuver(val type: ManeuverType, val atM: Double, val e: Double, val n: Double, val street: String?, val exit: Int = 0)`;
  - `fun buildManeuvers(route: Route, index: RoadIndex): List<Maneuver>`;
  - `object Instructions` с методами `distanceText(m: Double): String`, `ordinal(n: Int): String`, `prompt(m: Maneuver, distM: Double?): String`.

Правила `buildManeuvers`:
- первым идёт `DEPART` на 0 м с улицей первого шага, последним — `ARRIVE` в конце маршрута;
- в узле между шагами `i−1` и `i` (точка `points[i]`, расстояние `cumM[i]`):
  - угол `a = wrapAngle(bearingOut − bearingIn)` в градусах, положительный — вправо;
  - узел со степенью 2 — не манёвр;
  - вход на кольцо (ребро `i` круговое, `i−1` — нет) — `ROUNDABOUT`. Номер съезда — число узлов кольца, пройденных до выхода включительно, где есть ребро, ведущее не по кольцу. Внутри кольца манёвров нет;
  - иначе при степени ≥ 3 тип по |a|:
    - < 20° — `CONTINUE`, только если сменилось название улицы, иначе манёвра нет;
    - < 45° — `SLIGHT`;
    - < 135° — поворот;
    - < 170° — `SHARP`;
    - иначе — `UTURN`.

    Сторона определяется знаком `a`;
- если манёвр — `CONTINUE` или поворот в ту же сторону (`LEFT`/`SHARP_LEFT` или `RIGHT`/`SHARP_RIGHT`) и он ближе 30 м после предыдущего поворота, он сливается с предыдущим: остаётся первый, улица берётся у нового. Так поворот через двойную проезжую часть («налево» на перемычку и сразу «прямо» на улицу) даёт одну подсказку. В `DEPART` и `ROUNDABOUT` ничего не сливается;
- `street` — название ребра шага, начинающегося в узле манёвра.

Тексты `Instructions.prompt(m, d)`:
- действие:

  | Тип | Текст |
  |---|---|
  | `LEFT` | «поверните налево» |
  | `RIGHT` | «поверните направо» |
  | `SLIGHT_LEFT` | «держитесь левее» |
  | `SLIGHT_RIGHT` | «держитесь правее» |
  | `SHARP_LEFT` | «резко поверните налево» |
  | `SHARP_RIGHT` | «резко поверните направо» |
  | `UTURN` | «развернитесь» |
  | `CONTINUE` | «продолжайте прямо» |
  | `ROUNDABOUT` | «на круговом движении — {ordinal} съезд» |
  | `DEPART` | «начните движение» |

- к действию добавляется « — {улица}», если улица известна (кроме `DEPART` и `ARRIVE`);
- с дистанцией: «Через {distanceText(d)} {действие}{улица}»;
- без дистанции: действие с заглавной буквы плюс улица;
- `ARRIVE`: с дистанцией «Через {d} — пункт назначения», без дистанции «Вы прибыли»;
- `distanceText`:
  - < 100 м — округление до 10 м («80 м»), но не меньше 10 м;
  - < 1000 м — округление до 50 м («350 м»);
  - иначе одна цифра после запятой и « км» («1,2 км»);
- `ordinal(1..9)`: «первый», «второй», «третий», «четвёртый», «пятый», «шестой», «седьмой», «восьмой», «девятый»; иначе «{n}-й».

- [ ] **Step 1: Падающие тесты.**

`InstructionsTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InstructionsTest {
    private fun m(t: ManeuverType, street: String? = null, exit: Int = 0) = Maneuver(t, 0.0, 0.0, 0.0, street, exit)

    @Test fun distances() {
        assertEquals("80 м", Instructions.distanceText(78.0))
        assertEquals("10 м", Instructions.distanceText(3.0))
        assertEquals("350 м", Instructions.distanceText(362.0))
        assertEquals("1,2 км", Instructions.distanceText(1234.0))
    }

    @Test fun prompts() {
        assertEquals("Через 300 м поверните направо — Тверская улица",
            Instructions.prompt(m(ManeuverType.RIGHT, "Тверская улица"), 310.0))
        assertEquals("Держитесь левее", Instructions.prompt(m(ManeuverType.SLIGHT_LEFT), null))
        assertEquals("Через 150 м на круговом движении — второй съезд",
            Instructions.prompt(m(ManeuverType.ROUNDABOUT, exit = 2), 150.0))
        assertEquals("Через 200 м — пункт назначения", Instructions.prompt(m(ManeuverType.ARRIVE), 200.0))
        assertEquals("Вы прибыли", Instructions.prompt(m(ManeuverType.ARRIVE), null))
        assertEquals("12-й", Instructions.ordinal(12))
    }
}
```

`ManeuversTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ManeuversTest {
    private val enu = Enu(55.75, 37.60)

    private fun named(nodes: List<Pair<Double, Double>>, edges: List<EdgeSpec>, names: List<String?>): RoadPack {
        val base = roadPackOf(enu, nodes, edges)
        val table = names.filterNotNull().distinct()
        return RoadPack(base.lats, base.lons, base.way, base.from, base.to, base.flags, base.cls,
            nameIdx = IntArray(edges.size) { i -> names[i]?.let { table.indexOf(it) } ?: -1 }, names = table)
    }

    private fun types(ms: List<Maneuver>) = ms.map { it.type }

    @Test fun rightTurnAtJunctionIgnoresBends() {
        // Запад→восток с изломом 10° в узле степени 2, затем перекрёсток (степень 3): направо на юг.
        val nodes = listOf(0.0 to 0.0, 200.0 to 0.0, 400.0 to 35.0, 400.0 to -300.0, 400.0 to 300.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 1), EdgeSpec(2, 3, 2), EdgeSpec(2, 4, 3))
        val p = named(nodes, edges, listOf("Улица А", "Улица А", "Улица Б", "Улица В"))
        val idx = RoadIndex(p, enu)
        val r = assertNotNull(Router(idx).route(0.0, 0.0, null, 400.0, -300.0))
        val ms = buildManeuvers(r, idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.RIGHT, ManeuverType.ARRIVE), types(ms))
        assertEquals("Улица Б", ms[1].street)
        assertEquals(r.cumM[2], ms[1].atM, 1e-6)
    }

    @Test fun slightAndSharpByAngle() {
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 600.0 to 150.0, 300.0 to 300.0, 600.0 to 0.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(1, 3, 3), EdgeSpec(1, 4, 4))
        val p = named(nodes, edges, listOf("А", "Б", "В", "Г"))
        val idx = RoadIndex(p, enu)
        val slight = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 150.0)), idx)
        assertEquals(ManeuverType.SLIGHT_LEFT, slight[1].type)       // ≈27° влево
        val straight = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 600.0, 0.0)), idx)
        assertEquals(ManeuverType.CONTINUE, straight[1].type)        // прямо, сменилась улица
        assertEquals("Г", straight[1].street)
    }

    @Test fun roundaboutExitNumber() {
        // Квадратное кольцо против часовой стрелки: S(0,-50) → E(50,0) → N(0,50) → W(-50,0) → S.
        // Въезд с юга в S, съезды из E, N, W; маршрут выходит на север из N → второй съезд.
        val nodes = listOf(0.0 to -50.0, 50.0 to 0.0, 0.0 to 50.0, -50.0 to 0.0,
            0.0 to -300.0, 300.0 to 0.0, 0.0 to 300.0, -300.0 to 0.0)
        val ring = RoadPack.FLAG_ONEWAY or RoadPack.FLAG_ROUNDABOUT
        val edges = listOf(
            EdgeSpec(0, 1, 10, ring), EdgeSpec(1, 2, 10, ring), EdgeSpec(2, 3, 10, ring), EdgeSpec(3, 0, 10, ring),
            EdgeSpec(4, 0, 1), EdgeSpec(1, 5, 2), EdgeSpec(2, 6, 3), EdgeSpec(3, 7, 4),
        )
        val p = named(nodes, edges, listOf(null, null, null, null, "Юг", "Восток", "Север", "Запад"))
        val idx = RoadIndex(p, enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, -300.0, null, 0.0, 300.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ROUNDABOUT, ManeuverType.ARRIVE), types(ms))
        assertEquals(2, ms[1].exit); assertEquals("Север", ms[1].street)
    }

    @Test fun dualCarriagewayLeftTurnsMerge() {
        // Налево на перемычку (20 м) и сразу прямо на улицу Б — одна подсказка «налево — Б».
        val nodes = listOf(0.0 to 0.0, 300.0 to 0.0, 300.0 to 20.0, 300.0 to 300.0, 600.0 to 0.0, 300.0 to -300.0,
            600.0 to 20.0)
        val edges = listOf(EdgeSpec(0, 1, 1), EdgeSpec(1, 2, 2), EdgeSpec(2, 3, 3), EdgeSpec(1, 4, 4),
            EdgeSpec(1, 5, 5), EdgeSpec(2, 6, 6))
        val p = named(nodes, edges, listOf("А", "Перемычка", "Б", "Г", "Д", "Е"))
        val idx = RoadIndex(p, enu)
        val ms = buildManeuvers(assertNotNull(Router(idx).route(0.0, 0.0, null, 300.0, 300.0)), idx)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.LEFT, ManeuverType.ARRIVE), types(ms))
        assertEquals("Б", ms[1].street)
    }
}
```

Run: `./gradlew :core:test` → FAIL при компиляции.

- [ ] **Step 2: Реализация.**

`Instructions.kt`:

```kotlin
package io.visnav.core

import kotlin.math.roundToInt

/** Русские тексты подсказок: без склонения названий улиц («… — Тверская улица»). */
object Instructions {
    private val ORDINALS = listOf("первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой", "восьмой", "девятый")

    fun ordinal(n: Int): String = if (n in 1..9) ORDINALS[n - 1] else "$n-й"

    fun distanceText(m: Double): String = when {
        m < 100 -> "${maxOf(10, (m / 10).roundToInt() * 10)} м"
        m < 1000 -> "${(m / 50).roundToInt() * 50} м"
        else -> "${"%.1f".format(java.util.Locale.ROOT, m / 1000).replace('.', ',')} км"
    }

    private fun action(m: Maneuver): String = when (m.type) {
        ManeuverType.LEFT -> "поверните налево"
        ManeuverType.RIGHT -> "поверните направо"
        ManeuverType.SLIGHT_LEFT -> "держитесь левее"
        ManeuverType.SLIGHT_RIGHT -> "держитесь правее"
        ManeuverType.SHARP_LEFT -> "резко поверните налево"
        ManeuverType.SHARP_RIGHT -> "резко поверните направо"
        ManeuverType.UTURN -> "развернитесь"
        ManeuverType.CONTINUE -> "продолжайте прямо"
        ManeuverType.ROUNDABOUT -> "на круговом движении — ${ordinal(m.exit)} съезд"
        ManeuverType.DEPART -> "начните движение"
        ManeuverType.ARRIVE -> "пункт назначения"
    }

    fun prompt(m: Maneuver, distM: Double?): String {
        if (m.type == ManeuverType.ARRIVE) {
            return if (distM == null) "Вы прибыли" else "Через ${distanceText(distM)} — пункт назначения"
        }
        val street = if (m.street != null && m.type != ManeuverType.DEPART) " — ${m.street}" else ""
        val act = action(m)
        return if (distM == null) act.replaceFirstChar { it.uppercase() } + street
        else "Через ${distanceText(distM)} $act$street"
    }
}
```

`Maneuvers.kt`:

```kotlin
package io.visnav.core

import kotlin.math.PI
import kotlin.math.abs

enum class ManeuverType { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

/** Манёвр на маршруте: atM — расстояние от начала маршрута, (e, n) — точка, street — улица после манёвра. */
data class Maneuver(
    val type: ManeuverType, val atM: Double, val e: Double, val n: Double, val street: String?, val exit: Int = 0,
)

private const val MERGE_M = 30.0
private val TURNS = setOf(
    ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_RIGHT,
    ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.UTURN,
)

private fun bearingOf(index: RoadIndex, s: RouteStep): Double =
    if (s.forward) index.bearing[s.edge] else wrapAngle(index.bearing[s.edge] + PI)

private fun exitNode(index: RoadIndex, s: RouteStep): Int =
    if (s.forward) index.pack.to[s.edge] else index.pack.from[s.edge]

/** Манёвры маршрута: DEPART, повороты на перекрёстках (узлы степени ≥ 3), круговое движение, ARRIVE. */
fun buildManeuvers(route: Route, index: RoadIndex): List<Maneuver> {
    val pack = index.pack
    val steps = route.steps
    val out = ArrayList<Maneuver>()
    out.add(Maneuver(ManeuverType.DEPART, 0.0, route.points[0][0], route.points[0][1], pack.name(steps[0].edge)))
    var i = 1
    while (i < steps.size) {
        val prev = steps[i - 1]; val cur = steps[i]
        val v = exitNode(index, prev)
        val at = route.cumM[i]; val pe = route.points[i][0]; val pn = route.points[i][1]
        if (pack.roundabout(cur.edge) && !pack.roundabout(prev.edge)) {
            var exits = 0
            var j = i
            while (j < steps.size && pack.roundabout(steps[j].edge)) {
                val node = exitNode(index, steps[j])
                if (index.incident[node].any { !pack.roundabout(it) }) exits++
                j++
            }
            val street = if (j < steps.size) pack.name(steps[j].edge) else null
            out.add(Maneuver(ManeuverType.ROUNDABOUT, at, pe, pn, street, maxOf(1, exits)))
            i = j + 1
            continue
        }
        if (index.degree[v] >= 3) {
            val a = Math.toDegrees(wrapAngle(bearingOf(index, cur) - bearingOf(index, prev)))
            val mag = abs(a)
            val right = a > 0
            val type = when {
                mag < 20 -> if (pack.name(cur.edge) != pack.name(prev.edge)) ManeuverType.CONTINUE else null
                mag < 45 -> if (right) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
                mag < 135 -> if (right) ManeuverType.RIGHT else ManeuverType.LEFT
                mag < 170 -> if (right) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
                else -> ManeuverType.UTURN
            }
            if (type != null) {
                val m = Maneuver(type, at, pe, pn, pack.name(cur.edge))
                val last = out.last()
                val mergeable = last.type in TURNS && (type == ManeuverType.CONTINUE || sameSideTurn(last.type, type))
                if (mergeable && at - last.atM < MERGE_M) {
                    out[out.size - 1] = last.copy(street = m.street)
                } else {
                    out.add(m)
                }
            }
        }
        i++
    }
    val end = route.points.last()
    out.add(Maneuver(ManeuverType.ARRIVE, route.lengthM, end[0], end[1], null))
    return out
}

private fun sameSideTurn(a: ManeuverType, b: ManeuverType): Boolean {
    val left = setOf(ManeuverType.LEFT, ManeuverType.SHARP_LEFT)
    val right = setOf(ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT)
    return (a in left && b in left) || (a in right && b in right)
}
```

- [ ] **Step 3: Тесты зелёные.** Run: `./gradlew :core:test` → `:core` не меньше 191 теста.

Если геометрия теста даёт другой угол или другую степень узла, чем указано в комментарии теста, поправить геометрию теста, а не правило, и указать это в отчёте.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): maneuvers from routes and Russian prompt texts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Ведение по маршруту `RouteFollower` и формат `.nav.jsonl`

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/RouteFollower.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/NavFormat.kt`
- Modify: `android/core/src/main/kotlin/io/visnav/core/Localizer.kt`: `LocalizerOutput.psiRad`, `speedMps`
- Test: `android/core/src/test/kotlin/io/visnav/core/RouteFollowerTest.kt`, `NavFormatTest.kt`

**Interfaces:**
- Consumes: `Router`, `Route`, `buildManeuvers`, `Maneuver`, `Instructions`, `RoadIndex`, `Enu`.
- Produces:
  - `LocalizerOutput` получает последние поля `psiRad: Double = Double.NaN` и `speedMps: Double = Double.NaN`. `Localizer.onFrame` передаёт `ekf.x[2]` и `ekf.x[3]`;
  - `data class NavConfig(offRouteM = 40.0, offRouteSigmaK = 2.5, offRouteHoldMs = 4000L, rerouteCooldownMs = 10_000L, arriveM = 25.0, backtrackM = 15.0, minSpeedMps = 5.0)`;
  - `enum class PromptStage { FAR, NEAR, NOW }`;
  - `sealed class NavEvent` с полем `tMs`. Варианты:
    - `RouteReady(tMs, route: Route, maneuvers: List<Maneuver>, reroute: Boolean)`;
    - `Prompt(tMs, maneuver: Int, stage: PromptStage, text: String, distM: Double)`;
    - `Arrived(tMs)`;
    - `RouteFailed(tMs)`;
  - `class RouteFollower(index: RoadIndex, destE: Double, destN: Double, config: NavConfig = NavConfig(), routerConfig: RouterConfig = RouterConfig())`:
    - метод `update(tMs: Long, e: Double, n: Double, sigmaM: Double, psiRad: Double, speedMps: Double): List<NavEvent>`;
    - свойства для экрана: `route: Route?`, `maneuvers: List<Maneuver>`, `progressM: Double`, `nextManeuver: Int?`, `distanceToNextM: Double?`, `arrived: Boolean`;
  - `object NavFormat` с методами `header(sessionStartedMs, roadsCreatedAt: String?, destLat, destLon, outages: List<LongArray>, jams: List<LongArray>, spoofs: List<LongArray>): String` и `event(ev: NavEvent, enu: Enu): String`.

Поведение `update`:
1. После прибытия событий нет.
2. Нет маршрута:
   - попытка построить его от `(e, n)` с курсом `psiRad`, если он конечен и `speedMps ≥ 2`;
   - удача — `RouteReady(reroute = false)`, сброс прогресса и озвученных стадий;
   - неудача — `RouteFailed` не чаще раза в `rerouteCooldownMs`.
3. Прогресс:
   - на каждом отрезке маршрута, перекрывающем окно `[progress − backtrackM, progress + max(200, 5·v)]`, берётся проекция позиции; из них выбирается ближайшая;
   - прогресс обновляется, если новая проекция не меньше `progress − backtrackM`;
   - `d` — расстояние до этой проекции.
4. Съезд:
   - условие: `d > max(offRouteM, offRouteSigmaK·σ)` непрерывно в течение `offRouteHoldMs`, и с прошлой перестройки прошло не меньше `rerouteCooldownMs`;
   - тогда маршрут перестраивается от текущей позиции, выдаётся `RouteReady(reroute = true)`, прогресс и озвученные стадии сбрасываются;
   - если перестроить не удалось — `RouteFailed`.
5. Прибытие: `lengthM − progress ≤ arriveM` → `Prompt(последний манёвр, NOW, «Вы прибыли», 0)`, затем `Arrived`.
6. Подсказки:
   - следующий манёвр — первый, кроме `DEPART`, у которого `atM > progress + 1`; `dist = atM − progress`;
   - `v = max(speedMps, minSpeedMps)`;
   - пороги: `far = clamp(30v, 300, 1000)`, `near = clamp(8v, 60, 200)`, `now = clamp(2,5v, 15, 50)`;
   - при `dist ≤ now`, если `NOW` ещё не звучала: `Prompt(NOW, Instructions.prompt(m, null))`, затем `FAR` и `NEAR` для этого манёвра помечаются как озвученные;
   - иначе при `dist ≤ near`, если `NEAR` не звучала: `Prompt(NEAR, Instructions.prompt(m, dist))`, затем `FAR` помечается;
   - иначе при `near + 50 < dist ≤ far`, если `FAR` не звучала: `Prompt(FAR, …)`;
   - для `ARRIVE` стадия `NOW` не выдаётся: прибытие обрабатывает п. 5.

Формат событий `NavFormat.event`. Числа выводятся через `toString`, нефинитные — как `null`; строки — через `JsonPrimitive`.
- `route`:

  ```
  {"t_ms":…,"ev":"route","reroute":…,"length_m":…,"duration_s":…,"polyline":[[lat,lon],…],
   "maneuvers":[{"type":"right","at_m":…,"lat":…,"lon":…,"street":…|null,"exit":0},…]}
  ```

  `type` — имя `ManeuverType` в нижнем регистре.
- `prompt`: `{"t_ms":…,"ev":"prompt","maneuver":i,"stage":"far|near|now","dist_m":…,"text":"…"}`
- `arrive`: `{"t_ms":…,"ev":"arrive"}`
- `route_failed`: `{"t_ms":…,"ev":"route_failed"}`
- Заголовок: `{"type":"nav","session_started_ms":…,"roads":…|null,"dest":[lat,lon],"outages":[[s,e],…],"jams":[…],"spoofs":[…]}`. Внутренние списки окон — `[s,e]`.

- [ ] **Step 1: Падающие тесты** — `RouteFollowerTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteFollowerTest {
    private val enu = Enu(55.75, 37.60)

    /**
     * Восток по n=0 до перекрёстка (1000,0) (way 1); там направо на юг до (1000,−600) (way 2) или прямо на восток
     * до (1600,0) (way 3); от (1600,0) на юг (way 4) и по n=−600 обратно на запад к (1000,−600) (way 5) — квартал
     * замкнут, поэтому после пропущенного поворота есть путь к цели.
     */
    private fun graph(): RoadIndex {
        val nodes = ArrayList<Pair<Double, Double>>()
        fun add(e: Double, n: Double): Int { nodes += e to n; return nodes.size - 1 }
        val edges = ArrayList<EdgeSpec>()
        fun chain(ids: List<Int>, way: Long) { for (i in 0 until ids.size - 1) edges += EdgeSpec(ids[i], ids[i + 1], way) }
        val west = (0..10).map { add(100.0 * it, 0.0) }
        val south = listOf(west.last()) + (1..6).map { add(1000.0, -100.0 * it) }
        val east = listOf(west.last()) + (11..16).map { add(100.0 * it, 0.0) }
        val eastSouth = listOf(east.last()) + (1..6).map { add(1600.0, -100.0 * it) }
        val bottom = listOf(south.last()) + (11..15).map { add(100.0 * it, -600.0) } + listOf(eastSouth.last())
        chain(west, 1); chain(south, 2); chain(east, 3); chain(eastSouth, 4); chain(bottom, 5)
        return RoadIndex(roadPackOf(enu, nodes, edges), enu)
    }

    /** Прогон по точкам пути со скоростью 15 м/с, шаг 0,5 с. */
    private fun drive(f: RouteFollower, path: List<Pair<Double, Double>>, sigma: Double = 5.0): List<NavEvent> {
        val out = ArrayList<NavEvent>()
        var t = 0L
        for (k in 1 until path.size) {
            val (e0, n0) = path[k - 1]; val (e1, n1) = path[k]
            val len = kotlin.math.hypot(e1 - e0, n1 - n0)
            val psi = kotlin.math.atan2(e1 - e0, n1 - n0)
            var s = 0.0
            while (s < len) {
                out += f.update(t, e0 + (e1 - e0) * s / len, n0 + (n1 - n0) * s / len, sigma, psi, 15.0)
                s += 7.5; t += 500
            }
        }
        out += f.update(t, path.last().first, path.last().second, sigma, 0.0, 15.0)
        return out
    }

    @Test fun promptsInOrderOnceEachThenArrive() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val ev = drive(f, listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to -500.0))
        val route = ev.filterIsInstance<NavEvent.RouteReady>()
        assertEquals(1, route.size); assertTrue(!route[0].reroute)
        val turn = route[0].maneuvers.indexOfFirst { it.type == ManeuverType.RIGHT }
        val prompts = ev.filterIsInstance<NavEvent.Prompt>().filter { it.maneuver == turn }
        assertEquals(listOf(PromptStage.FAR, PromptStage.NEAR, PromptStage.NOW), prompts.map { it.stage })
        assertTrue(prompts[0].distM in 300.0..450.0, "far at ${prompts[0].distM}")
        assertTrue(prompts[1].distM in 60.0..120.0, "near at ${prompts[1].distM}")
        assertTrue(prompts[2].distM <= 37.5)
        assertEquals("Поверните направо", prompts[2].text)
        assertEquals(1, ev.count { it is NavEvent.Arrived })
        assertTrue(ev.filterIsInstance<NavEvent.Prompt>().any { it.text == "Вы прибыли" })
    }

    @Test fun missedTurnReroutes() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val ev = drive(f, listOf(0.0 to 0.0, 1000.0 to 0.0, 1300.0 to 0.0))
        val rr = ev.filterIsInstance<NavEvent.RouteReady>()
        assertTrue(rr.size >= 2, "routes: ${rr.size}"); assertTrue(rr[1].reroute)
        assertTrue(rr[1].route.lengthM > 0)
    }

    @Test fun largeSigmaPreventsFalseReroute() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        // Едем параллельно маршруту в 35 м к северу с σ 20 м: порог max(40, 50) = 50 м — съезда нет.
        val ev = drive(f, listOf(0.0 to 35.0, 900.0 to 35.0), sigma = 20.0)
        assertEquals(1, ev.count { it is NavEvent.RouteReady })
    }

    @Test fun noGraphNearbyGivesRouteFailedRateLimited() {
        val f = RouteFollower(graph(), 1000.0, -500.0)
        val ev = (0 until 40).flatMap { f.update(it * 500L, 5000.0, 5000.0, 5.0, 0.0, 10.0) }
        assertEquals(2, ev.count { it is NavEvent.RouteFailed })   // 0 с и 10 с за 20 с
    }
}
```

`NavFormatTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavFormatTest {
    private val enu = Enu(55.75, 37.60)

    @Test fun headerAndEvents() {
        assertEquals(
            "{\"type\":\"nav\",\"session_started_ms\":5,\"roads\":\"r1\",\"dest\":[55.7,37.6]," +
                "\"outages\":[[1,2]],\"jams\":[],\"spoofs\":[]}",
            NavFormat.header(5L, "r1", 55.7, 37.6, listOf(longArrayOf(1, 2)), emptyList(), emptyList()),
        )
        val p = NavFormat.event(NavEvent.Prompt(7L, 1, PromptStage.NEAR, "Через 80 м поверните направо", 81.0), enu)
        assertEquals("{\"t_ms\":7,\"ev\":\"prompt\",\"maneuver\":1,\"stage\":\"near\",\"dist_m\":81.0," +
            "\"text\":\"Через 80 м поверните направо\"}", p)
        assertEquals("{\"t_ms\":9,\"ev\":\"arrive\"}", NavFormat.event(NavEvent.Arrived(9L), enu))
        val route = Route(listOf(RouteStep(0, true)), listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(100.0, 0.0)),
            doubleArrayOf(0.0, 100.0), 18.0)
        val m = listOf(Maneuver(ManeuverType.DEPART, 0.0, 0.0, 0.0, "Улица"), Maneuver(ManeuverType.ARRIVE, 100.0, 100.0, 0.0, null))
        val line = NavFormat.event(NavEvent.RouteReady(3L, route, m, false), enu)
        assertTrue(line.startsWith("{\"t_ms\":3,\"ev\":\"route\",\"reroute\":false,\"length_m\":100.0,\"duration_s\":18.0,\"polyline\":[["))
        assertTrue(line.contains("{\"type\":\"depart\",\"at_m\":0.0,") && line.contains("\"street\":\"Улица\",\"exit\":0}"))
        assertTrue(line.contains("{\"type\":\"arrive\",\"at_m\":100.0,") && line.contains("\"street\":null,\"exit\":0}"))
    }
}
```

Run: `./gradlew :core:test` → FAIL при компиляции.

- [ ] **Step 2: Реализация.**

`Localizer.kt`:
- в `LocalizerOutput` после `road` добавить `val psiRad: Double = Double.NaN, val speedMps: Double = Double.NaN`;
- в `onFrame` в `return LocalizerOutput(...)` последними аргументами передать `ekf.x[2], ekf.x[3]`.

`RouteFollower.kt`:

```kotlin
package io.visnav.core

import kotlin.math.hypot
import kotlin.math.max

data class NavConfig(
    val offRouteM: Double = 40.0,
    val offRouteSigmaK: Double = 2.5,
    val offRouteHoldMs: Long = 4000L,
    val rerouteCooldownMs: Long = 10_000L,
    val arriveM: Double = 25.0,
    val backtrackM: Double = 15.0,
    val minSpeedMps: Double = 5.0,
)

enum class PromptStage { FAR, NEAR, NOW }

sealed class NavEvent {
    abstract val tMs: Long
    data class RouteReady(override val tMs: Long, val route: Route, val maneuvers: List<Maneuver>, val reroute: Boolean) : NavEvent()
    data class Prompt(override val tMs: Long, val maneuver: Int, val stage: PromptStage, val text: String, val distM: Double) : NavEvent()
    data class Arrived(override val tMs: Long) : NavEvent()
    data class RouteFailed(override val tMs: Long) : NavEvent()
}

/**
 * Ведение по маршруту: прогресс, подсказки на трёх дистанциях, съезд с учётом σ позиции, перестроение, прибытие.
 * Координаты — плоские (index.enu); вызывать на каждом выводе Localizer по порядку времени.
 */
class RouteFollower(
    private val index: RoadIndex, private val destE: Double, private val destN: Double,
    private val config: NavConfig = NavConfig(), routerConfig: RouterConfig = RouterConfig(),
) {
    private val router = Router(index, routerConfig)
    var route: Route? = null; private set
    var maneuvers: List<Maneuver> = emptyList(); private set
    var progressM = 0.0; private set
    var arrived = false; private set
    private val spoken = HashSet<Long>()
    private var offSince: Long? = null
    private var lastRouteAttempt = Long.MIN_VALUE / 2

    val nextManeuver: Int?
        get() = maneuvers.indices.firstOrNull { maneuvers[it].type != ManeuverType.DEPART && maneuvers[it].atM > progressM + 1 }
    val distanceToNextM: Double? get() = nextManeuver?.let { maneuvers[it].atM - progressM }

    private fun key(i: Int, s: PromptStage) = i.toLong() * 4 + s.ordinal

    private fun plan(tMs: Long, e: Double, n: Double, psi: Double?, reroute: Boolean): NavEvent {
        lastRouteAttempt = tMs
        val r = router.route(e, n, psi, destE, destN) ?: return NavEvent.RouteFailed(tMs)
        route = r; maneuvers = buildManeuvers(r, index); progressM = 0.0; spoken.clear(); offSince = null
        return NavEvent.RouteReady(tMs, r, maneuvers, reroute)
    }

    fun update(tMs: Long, e: Double, n: Double, sigmaM: Double, psiRad: Double, speedMps: Double): List<NavEvent> {
        if (arrived) return emptyList()
        val psi = if (psiRad.isFinite() && speedMps.isFinite() && speedMps >= 2.0) psiRad else null
        val r = route
        if (r == null) {
            if (tMs - lastRouteAttempt < config.rerouteCooldownMs) return emptyList()
            return listOf(plan(tMs, e, n, psi, reroute = false))
        }
        val v = max(if (speedMps.isFinite()) speedMps else 0.0, config.minSpeedMps)
        // Прогресс: ближайшая проекция в окне вокруг текущего прогресса.
        val lo = progressM - config.backtrackM
        val hi = progressM + max(200.0, 5 * v)
        var bestD = Double.POSITIVE_INFINITY
        var bestAt = progressM
        for (i in 1 until r.points.size) {
            if (r.cumM[i] < lo || r.cumM[i - 1] > hi) continue
            val a = r.points[i - 1]; val b = r.points[i]
            val de = b[0] - a[0]; val dn = b[1] - a[1]
            val len2 = de * de + dn * dn
            val t = if (len2 == 0.0) 0.0 else (((e - a[0]) * de + (n - a[1]) * dn) / len2).coerceIn(0.0, 1.0)
            val d = hypot(e - (a[0] + t * de), n - (a[1] + t * dn))
            if (d < bestD) { bestD = d; bestAt = r.cumM[i - 1] + t * (r.cumM[i] - r.cumM[i - 1]) }
        }
        if (bestAt >= lo) progressM = max(progressM - config.backtrackM, bestAt).coerceAtLeast(0.0)
        // Съезд с маршрута.
        if (bestD > max(config.offRouteM, config.offRouteSigmaK * sigmaM)) {
            val since = offSince ?: tMs.also { offSince = it }
            if (tMs - since >= config.offRouteHoldMs && tMs - lastRouteAttempt >= config.rerouteCooldownMs) {
                route = null
                return listOf(plan(tMs, e, n, psi, reroute = true))
            }
        } else {
            offSince = null
        }
        val out = ArrayList<NavEvent>()
        if (r.lengthM - progressM <= config.arriveM) {
            arrived = true
            out += NavEvent.Prompt(tMs, maneuvers.lastIndex, PromptStage.NOW, Instructions.prompt(maneuvers.last(), null), 0.0)
            out += NavEvent.Arrived(tMs)
            return out
        }
        val i = nextManeuver ?: return out
        val m = maneuvers[i]
        val dist = m.atM - progressM
        val far = (30 * v).coerceIn(300.0, 1000.0)
        val near = (8 * v).coerceIn(60.0, 200.0)
        val now = (2.5 * v).coerceIn(15.0, 50.0)
        when {
            dist <= now && m.type != ManeuverType.ARRIVE && spoken.add(key(i, PromptStage.NOW)) -> {
                spoken.add(key(i, PromptStage.NEAR)); spoken.add(key(i, PromptStage.FAR))
                out += NavEvent.Prompt(tMs, i, PromptStage.NOW, Instructions.prompt(m, null), dist)
            }
            dist <= near && spoken.add(key(i, PromptStage.NEAR)) -> {
                spoken.add(key(i, PromptStage.FAR))
                out += NavEvent.Prompt(tMs, i, PromptStage.NEAR, Instructions.prompt(m, dist), dist)
            }
            dist <= far && dist > near + 50 && spoken.add(key(i, PromptStage.FAR)) ->
                out += NavEvent.Prompt(tMs, i, PromptStage.FAR, Instructions.prompt(m, dist), dist)
        }
        return out
    }
}
```

`NavFormat.kt`:

```kotlin
package io.visnav.core

import kotlinx.serialization.json.JsonPrimitive

/** Журнал ведения по маршруту (.nav.jsonl): заголовок и по строке на событие NavEvent. */
object NavFormat {
    private fun num(v: Double): String = if (v.isFinite()) v.toString() else "null"
    private fun str(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()
    private fun windows(w: List<LongArray>) = w.joinToString(",") { "[${it[0]},${it[1]}]" }

    fun header(
        sessionStartedMs: Long, roadsCreatedAt: String?, destLat: Double, destLon: Double,
        outages: List<LongArray>, jams: List<LongArray>, spoofs: List<LongArray>,
    ): String = "{\"type\":\"nav\",\"session_started_ms\":$sessionStartedMs,\"roads\":${str(roadsCreatedAt)}," +
        "\"dest\":[${num(destLat)},${num(destLon)}],\"outages\":[${windows(outages)}],\"jams\":[${windows(jams)}]," +
        "\"spoofs\":[${windows(spoofs)}]}"

    fun event(ev: NavEvent, enu: Enu): String = when (ev) {
        is NavEvent.RouteReady -> {
            val poly = ev.route.points.joinToString(",") { val ll = enu.toLatLon(it[0], it[1]); "[${num(ll[0])},${num(ll[1])}]" }
            val ms = ev.maneuvers.joinToString(",") { m ->
                val ll = enu.toLatLon(m.e, m.n)
                "{\"type\":\"${m.type.name.lowercase()}\",\"at_m\":${num(m.atM)},\"lat\":${num(ll[0])}," +
                    "\"lon\":${num(ll[1])},\"street\":${str(m.street)},\"exit\":${m.exit}}"
            }
            "{\"t_ms\":${ev.tMs},\"ev\":\"route\",\"reroute\":${ev.reroute},\"length_m\":${num(ev.route.lengthM)}," +
                "\"duration_s\":${num(ev.route.durationS)},\"polyline\":[$poly],\"maneuvers\":[$ms]}"
        }
        is NavEvent.Prompt -> "{\"t_ms\":${ev.tMs},\"ev\":\"prompt\",\"maneuver\":${ev.maneuver}," +
            "\"stage\":\"${ev.stage.name.lowercase()}\",\"dist_m\":${num(ev.distM)},\"text\":${str(ev.text)}}"
        is NavEvent.Arrived -> "{\"t_ms\":${ev.tMs},\"ev\":\"arrive\"}"
        is NavEvent.RouteFailed -> "{\"t_ms\":${ev.tMs},\"ev\":\"route_failed\"}"
    }
}
```

- [ ] **Step 3: Тесты зелёные.**

Run: `./gradlew :core:test :replay:test :app:assembleDebug` → `:core` не меньше 196 тестов, `:replay` — 35.

Пороги тестов не ослаблять. Если граф в `RouteFollowerTest` даёт другие расстояния срабатывания, проверить сначала формулы порогов, затем геометрию теста. Причину указать в отчёте.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): route follower — progress, staged prompts, off-route reroute, arrival; nav log format

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Ведение по маршруту в replay

**Files:**
- Create: `android/replay/src/main/kotlin/io/visnav/replay/NavReplay.kt`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Replayer.kt`: `TrajPoint.psiRad`, `speedMps`
- Modify: `android/replay/src/main/kotlin/io/visnav/replay/Main.kt`
- Test: `android/replay/src/test/kotlin/io/visnav/replay/NavReplayTest.kt`, `MainTest.kt` (дополнить)

**Interfaces:**
- Consumes: `RouteFollower`, `NavFormat`, `NavEvent`, `RoadIndex`, `RoadPack`, `Enu`; `loadRoads` (M2b).
- Produces:
  - `TrajPoint` получает последние поля `psiRad: Double = Double.NaN`, `speedMps: Double = Double.NaN`. `Replayer` передаёт `o.psiRad`, `o.speedMps`;
  - `fun runNavigation(points: List<TrajPoint>, roads: RoadPack, destLat: Double, destLon: Double): Pair<Enu, List<NavEvent>>`:
    - `Enu` — по первой точке траектории;
    - пустой список точек → `IllegalArgumentException`;
  - `fun writeNav(file: File, header: String, enu: Enu, events: List<NavEvent>)`;
  - `internal fun parseDest(spec: String): Pair<Double, Double>?` — `"LAT,LON"`, обе части конечные, широта в [−90, 90], долгота в [−180, 180];
  - CLI:
    - `--dest LAT,LON`, `--nav-out FILE` — без `--roads` ошибка (код 2), `--nav-out` без `--dest` — ошибка (код 2);
    - в файл пишется заголовок `NavFormat.header(session.startedMs, roadsMeta.createdAt, destLat, destLon, outages, jams, spoofs)` и события;
    - печать: число подсказок, перестроений, было ли прибытие.

- [ ] **Step 1: Падающие тесты** — `NavReplayTest.kt`:

```kotlin
package io.visnav.replay

import io.visnav.core.NavEvent
import io.visnav.core.RoadPack
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavReplayTest {
    /** Синтетическая сессия едет на восток по n = 0 от 0 до 1200 м; дорога way 1 вдоль неё от −200 до 1500 м. */
    private fun roads(): RoadPack {
        val es = (-2..15).map { it * 100.0 }
        val ll = es.map { SyntheticSession.enu.toLatLon(it, 0.0) }
        val m = es.size - 1
        return RoadPack(DoubleArray(es.size) { ll[it][0] }, DoubleArray(es.size) { ll[it][1] }, LongArray(m) { 1L },
            IntArray(m) { it }, IntArray(m) { it + 1 }, ByteArray(m), ByteArray(m) { 7 })
    }

    @Test fun replayNavigatesToDestinationThroughOutage() {
        val s = SyntheticSession.session(createTempDirectory().toFile())
        val outage = Outage(SyntheticSession.T0 + 30_000, SyntheticSession.T0 + 90_000)
        val points = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false), roads()).run(s, listOf(outage))
        val dest = SyntheticSession.enu.toLatLon(1100.0, 0.0)
        val (_, events) = runNavigation(points, roads(), dest[0], dest[1])
        assertEquals(1, events.count { it is NavEvent.RouteReady })
        assertEquals(0, events.count { it is NavEvent.RouteReady && it.reroute })
        assertEquals(1, events.count { it is NavEvent.Arrived })
        val arrive = events.filterIsInstance<NavEvent.Prompt>().filter { it.text.contains("пункт назначения") }
        assertTrue(arrive.isNotEmpty())
    }

    @Test fun writesHeaderAndEvents() {
        val s = SyntheticSession.session(createTempDirectory().toFile())
        val points = Replayer(SyntheticSession.pack(), ReplayConfig(visual = false), roads()).run(s, emptyList())
        val dest = SyntheticSession.enu.toLatLon(1100.0, 0.0)
        val (enu, events) = runNavigation(points, roads(), dest[0], dest[1])
        val f = File(createTempDirectory().toFile(), "n.jsonl")
        writeNav(f, "{\"type\":\"nav\"}", enu, events)
        val lines = f.readLines()
        assertEquals("{\"type\":\"nav\"}", lines[0])
        assertTrue(lines[1].contains("\"ev\":\"route\"")); assertTrue(lines.last().contains("\"ev\":\"arrive\""))
    }
}
```

В `MainTest.kt`:

```kotlin
    @Test fun parseDestAcceptsAndRejects() {
        assertEquals(55.75 to 37.6, parseDest("55.75,37.6"))
        assertEquals(null, parseDest("55.75")); assertEquals(null, parseDest("95,37")); assertEquals(null, parseDest("a,b"))
        assertEquals(null, parseDest("NaN,37"))
    }
```

Run: `./gradlew :replay:test` → FAIL при компиляции.

- [ ] **Step 2: Реализация.** `NavReplay.kt`:

```kotlin
package io.visnav.replay

import io.visnav.core.Enu
import io.visnav.core.NavEvent
import io.visnav.core.NavFormat
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.RouteFollower
import java.io.File

/** Ведение по маршруту по готовой траектории replay: тот же RouteFollower, что на телефоне. */
fun runNavigation(points: List<TrajPoint>, roads: RoadPack, destLat: Double, destLon: Double): Pair<Enu, List<NavEvent>> {
    require(points.isNotEmpty()) { "no trajectory points to navigate" }
    val enu = Enu(points[0].lat, points[0].lon)
    val dest = enu.toEn(destLat, destLon)
    val follower = RouteFollower(RoadIndex(roads, enu), dest[0], dest[1])
    val events = ArrayList<NavEvent>()
    for (p in points) {
        if (!p.lat.isFinite() || !p.lon.isFinite()) continue
        val en = enu.toEn(p.lat, p.lon)
        events += follower.update(p.tMs, en[0], en[1], p.sigmaM, p.psiRad, p.speedMps)
    }
    return enu to events
}

fun writeNav(file: File, header: String, enu: Enu, events: List<NavEvent>) {
    file.parentFile?.mkdirs()
    file.bufferedWriter().use { w ->
        w.write(header); w.newLine()
        for (e in events) { w.write(NavFormat.event(e, enu)); w.newLine() }
    }
}
```

`Replayer.kt`:
- в `TrajPoint` последние поля `val psiRad: Double = Double.NaN, val speedMps: Double = Double.NaN`;
- при построении передать `o.psiRad, o.speedMps`.

`Main.kt`:
- `parseDest`:

```kotlin
/** "LAT,LON" → пара; null при неверной форме или координатах вне диапазона. */
internal fun parseDest(spec: String): Pair<Double, Double>? {
    val parts = spec.split(",")
    if (parts.size != 2) return null
    val lat = finiteOrNull(parts[0].trim()) ?: return null
    val lon = finiteOrNull(parts[1].trim()) ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}
```

- разбор `--dest` и `--nav-out` с проверками из Interfaces;
- после `TrajectoryWriter.write`:

```kotlin
    if (dest != null && navOut != null) {
        val (enu, events) = runNavigation(points, roads!!.first, dest.first, dest.second)
        val header = NavFormat.header(data.header.startedMs, roads.second.createdAt, dest.first, dest.second,
            outages.map { longArrayOf(it.startMs, it.endMs) }, jams.map { longArrayOf(it.startMs, it.endMs) },
            spoofs.map { longArrayOf(it.startMs, it.endMs) })
        writeNav(File(navOut), header, enu, events)
        println("nav: ${events.count { it is NavEvent.Prompt }} prompts, " +
            "${events.count { it is NavEvent.RouteReady && it.reroute }} reroutes, " +
            "arrived=${events.any { it is NavEvent.Arrived }} -> $navOut")
    }
```

- в `USAGE` дописать `[--dest LAT,LON --nav-out FILE]`.

- [ ] **Step 3: Тесты зелёные.** Run: `./gradlew :core:test :replay:test :app:assembleDebug` → `:replay` не меньше 38 тестов.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/replay/src
git commit -m "feat(replay): --dest/--nav-out — route following and prompts on recorded drives

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `vpr-m3 nav-eval`

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/naveval.py`
- Create: `research/vpr_bench/src/vpr_bench/m3cli.py`
- Modify: `research/vpr_bench/pyproject.toml`: `vpr-m3 = "vpr_bench.m3cli:main"` в `[project.scripts]`
- Test: `research/vpr_bench/tests/test_naveval.py`

**Interfaces:**
- Consumes: `fieldlog.read_log`, `fieldlog.gps_track`, `query.clean_track`, `geo.haversine_m`, `geo.M_PER_DEG_LAT`.
- Produces:
  - `read_nav(path) -> tuple[dict, list[dict]]` — заголовок и события; первая строка должна иметь `"type":"nav"`;
  - `@dataclass(frozen=True) ManeuverCheck(route_idx, maneuver, type, reached_t, in_window, prompt_lead_s)`, где `reached_t` — секунды от начала сессии (`session_started_ms`);
  - `@dataclass(frozen=True) NavResult(checks, n_routes, n_reroutes, false_reroutes, arrived, end_dist_m)`;
  - `evaluate_nav(header, events, log_frames, reach_m=20.0, lead_s=3.0, on_route_m=15.0) -> NavResult`;
  - `render_nav_report(r) -> str`;
  - CLI `vpr-m3 nav-eval --nav FILE --log S.jsonl --out R.md`. Код 2, если `session_started_ms` заголовка не равен `started_ms` журнала.

Определения:
- **Эталон** — `clean_track(gps_track(log), max_speed_mps=70, max_hdop=None)`.
- **Срок жизни маршрута** — от его события `route` до следующего `route` или до конца.
- **Достижение манёвра** — для каждого манёвра маршрута, кроме `depart` и `arrive`: первый момент эталона в сроке жизни маршрута не раньше `t` маршрута, когда до точки манёвра ≤ `reach_m`. Если такого нет — манёвр «не пройден», в метрику не входит.
- **Опережение** `prompt_lead_s` — `reached_t − t` самой ранней подсказки `near` или `now` этого манёвра в этом маршруте, выданной не позже `reached_t`; если такой нет — `None`.
- **Вовремя** — `prompt_lead_s ≥ lead_s`.
- **`in_window`** — `reached_t` попадает в окна `outages`, `jams` или `spoofs` из заголовка (в мс).
- **Ложное перестроение** — событие `route` с `reroute = true`, в момент которого эталонная позиция (интерполированная) ближе `on_route_m` к ломаной предыдущего маршрута.
- **Прибытие** — есть событие `arrive`. `end_dist_m` — расстояние от последней точки эталона до `dest`.
- **Вердикт:**
  - доля вовремя ≥ 95 % по всем пройденным манёврам и отдельно по манёврам в окнах: ✅/❌, без данных — «⚠️ нет данных»;
  - ложных перестроений 0: ✅/❌;
  - прибытие: ✅/❌.

- [ ] **Step 1: Падающие тесты** — `tests/test_naveval.py`:

```python
import json

from vpr_bench.fieldlog import FieldFrame
from vpr_bench.geo import offset_m
from vpr_bench.m3cli import main
from vpr_bench.naveval import evaluate_nav, read_nav, render_nav_report

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6


def _ll(e, n):
    return list(offset_m(LAT0, LON0, e, n))


def _frames(path):
    """path: [(e, n)] по секунде."""
    out = []
    for s, (e, n) in enumerate(path):
        lat, lon = offset_m(LAT0, LON0, e, n)
        out.append(FieldFrame(T0 + 1000 * s, "gps", (lat, lon, 4.0, T0 + 1000 * s), None,
                              {"pre": 0.0, "inf": 0.0, "search": 0.0}))
    return out


EAST_THEN_SOUTH = [(10.0 * s, 0.0) for s in range(101)] + [(1000.0, -10.0 * s) for s in range(1, 51)]


def _route(t_s, reroute=False, turn_at=(1000.0, 0.0), poly=((0, 0), (1000, 0), (1000, -500))):
    return {"t_ms": T0 + int(t_s * 1000), "ev": "route", "reroute": reroute, "length_m": 1500.0, "duration_s": 100.0,
            "polyline": [_ll(*p) for p in poly],
            "maneuvers": [{"type": "depart", "at_m": 0.0, "lat": LAT0, "lon": LON0, "street": None, "exit": 0},
                          {"type": "right", "at_m": 1000.0, "lat": _ll(*turn_at)[0], "lon": _ll(*turn_at)[1],
                           "street": "Б", "exit": 0},
                          {"type": "arrive", "at_m": 1500.0, "lat": _ll(1000, -500)[0], "lon": _ll(1000, -500)[1],
                           "street": None, "exit": 0}]}


def _prompt(t_s, i, stage):
    return {"t_ms": T0 + int(t_s * 1000), "ev": "prompt", "maneuver": i, "stage": stage, "dist_m": 0.0, "text": "x"}


def _header(**kw):
    h = {"type": "nav", "session_started_ms": T0, "roads": "r", "dest": _ll(1000, -500), "outages": [], "jams": [],
         "spoofs": []}
    h.update(kw)
    return h


def test_prompt_in_time_and_arrival():
    ev = [_route(0), _prompt(90, 1, "near"), _prompt(97, 1, "now"), {"t_ms": T0 + 150_000, "ev": "arrive"}]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    # Точка в 20 м от манёвра — около 98-й секунды (на границе допуска может оказаться 99-я).
    assert len(r.checks) == 1 and 97.5 <= r.checks[0].reached_t <= 99.5
    assert 7.5 <= r.checks[0].prompt_lead_s <= 9.5
    assert r.arrived and r.end_dist_m < 1.0
    rep = render_nav_report(r)
    assert "✅" in [l for l in rep.splitlines() if "вовремя" in l][0]


def test_late_prompt_fails():
    ev = [_route(0), _prompt(97, 1, "now")]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    assert r.checks[0].prompt_lead_s < 3.0
    assert "❌" in [l for l in render_nav_report(r).splitlines() if "вовремя" in l][0]


def test_window_split():
    ev = [_route(0), _prompt(90, 1, "near")]
    r = evaluate_nav(_header(outages=[[T0 + 95_000, T0 + 120_000]]), ev, _frames(EAST_THEN_SOUTH))
    assert r.checks[0].in_window


def test_false_and_true_reroutes():
    # Перестроение в 50 с, когда по GPS машина на маршруте (n = 0) — ложное.
    ev = [_route(0), _route(50, reroute=True)]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    assert r.n_reroutes == 1 and r.false_reroutes == 1
    # Та же поездка, но маршрут вёл на север; перестроение после съезда — не ложное.
    north = _route(0, turn_at=(1000.0, 0.0), poly=((0, 0), (1000, 0), (1000, 500)))
    r2 = evaluate_nav(_header(), [north, _route(130, reroute=True)], _frames(EAST_THEN_SOUTH))
    assert r2.false_reroutes == 0


def test_unreached_maneuver_excluded():
    other = _route(0, turn_at=(3000.0, 0.0))
    r = evaluate_nav(_header(), [other], _frames(EAST_THEN_SOUTH))
    assert r.checks == []
    assert "⚠️ нет данных" in render_nav_report(r)


def test_cli_session_mismatch_and_ok(tmp_path):
    nav = tmp_path / "n.jsonl"
    nav.write_text("\n".join(json.dumps(x) for x in [_header(), _route(0), _prompt(90, 1, "near")]) + "\n")
    log = tmp_path / "s.jsonl"
    lines = [json.dumps({"type": "session", "started_ms": T0})]
    for f in _frames(EAST_THEN_SOUTH):
        g = {"lat": f.gps[0], "lon": f.gps[1], "acc_m": f.gps[2], "t_ms": f.gps[3]}
        lines.append(json.dumps({"t_ms": f.t_ms, "mode": f.mode, "gps": g, "fix": None, "lat_ms": f.lat_ms}))
    log.write_text("\n".join(lines) + "\n")
    out = tmp_path / "r.md"
    assert main(["nav-eval", "--nav", str(nav), "--log", str(log), "--out", str(out)]) == 0
    assert "Подсказки о манёврах" in out.read_text()
    bad = tmp_path / "b.jsonl"
    bad.write_text(json.dumps(_header(session_started_ms=T0 + 1)) + "\n")
    assert main(["nav-eval", "--nav", str(bad), "--log", str(log), "--out", str(out)]) == 2
    _, ev = read_nav(nav)
    assert ev[0]["ev"] == "route"
```

Run: `uv run pytest tests/test_naveval.py -q` → FAIL (`ModuleNotFoundError`).

- [ ] **Step 2: Реализация.**

`naveval.py`:

```python
"""Оценка ведения по маршруту (M3a): подсказки вовремя, ложные перестроения, прибытие — по GPS-треку журнала."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import M_PER_DEG_LAT, haversine_m, interpolate_track
from vpr_bench.query import clean_track

TARGET_PCT = 95.0


@dataclass(frozen=True)
class ManeuverCheck:
    route_idx: int
    maneuver: int
    type: str
    reached_t: float
    in_window: bool
    prompt_lead_s: float | None


@dataclass(frozen=True)
class NavResult:
    checks: list[ManeuverCheck]
    n_routes: int
    n_reroutes: int
    false_reroutes: int
    arrived: bool
    end_dist_m: float


def read_nav(path: Path) -> tuple[dict, list[dict]]:
    lines = [l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    if not lines:
        raise ValueError(f"{path}: empty nav log")
    header = json.loads(lines[0])
    if header.get("type") != "nav":
        raise ValueError(f"{path}: not a nav log")
    return header, [json.loads(l) for l in lines[1:]]


def _dist_to_polyline(lat: float, lon: float, poly: list[list[float]]) -> float:
    kx = M_PER_DEG_LAT * math.cos(math.radians(lat))
    best = math.inf
    pts = [((p[1] - lon) * kx, (p[0] - lat) * M_PER_DEG_LAT) for p in poly]
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        dx, dy = bx - ax, by - ay
        l2 = dx * dx + dy * dy
        t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
        best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    if len(pts) == 1:
        best = math.hypot(*pts[0])
    return best


def evaluate_nav(header: dict, events: list[dict], log_frames: list[FieldFrame], reach_m: float = 20.0,
                 lead_s: float = 3.0, on_route_m: float = 15.0) -> NavResult:
    track, _ = clean_track(gps_track(log_frames), max_speed_mps=70.0, max_hdop=None)
    windows = [tuple(w) for k in ("outages", "jams", "spoofs") for w in header.get(k, [])]
    t0 = (header.get("session_started_ms") or 0) / 1000.0
    routes = [(i, e) for i, e in enumerate(events) if e.get("ev") == "route"]
    checks: list[ManeuverCheck] = []
    false_rr = 0
    for ri, (pos, rt) in enumerate(routes):
        t_start = rt["t_ms"] / 1000.0
        t_end = routes[ri + 1][1]["t_ms"] / 1000.0 if ri + 1 < len(routes) else math.inf
        if rt.get("reroute") and ri > 0:
            here = interpolate_track(track, t_start)
            if here is not None and _dist_to_polyline(here[0], here[1], routes[ri - 1][1]["polyline"]) <= on_route_m:
                false_rr += 1
        prompts = [e for e in events[pos + 1:] if e.get("ev") == "prompt" and e["t_ms"] / 1000.0 < t_end]
        for mi, m in enumerate(rt["maneuvers"]):
            if m["type"] in ("depart", "arrive"):
                continue
            reached = next((p.t for p in track if t_start <= p.t < t_end
                            and haversine_m(p.lat, p.lon, m["lat"], m["lon"]) <= reach_m), None)
            if reached is None:
                continue
            ok = [p["t_ms"] / 1000.0 for p in prompts if p["maneuver"] == mi and p["stage"] in ("near", "now")
                  and p["t_ms"] / 1000.0 <= reached]
            lead = reached - min(ok) if ok else None
            in_win = any(s <= reached * 1000.0 < e for s, e in windows)
            checks.append(ManeuverCheck(ri, mi, m["type"], reached - t0, in_win, lead))
    dest = header.get("dest")
    end = track[-1] if track else None
    end_dist = haversine_m(end.lat, end.lon, dest[0], dest[1]) if end and dest else math.nan
    return NavResult(checks, len(routes), sum(1 for _, r in routes if r.get("reroute")), false_rr,
                     any(e.get("ev") == "arrive" for e in events), end_dist)


def _share(cs: list[ManeuverCheck], lead_s: float) -> float:
    if not cs:
        return math.nan
    return 100.0 * sum(1 for c in cs if c.prompt_lead_s is not None and c.prompt_lead_s >= lead_s) / len(cs)


def _verdict(v: float) -> str:
    return "⚠️ нет данных" if math.isnan(v) else ("✅" if v >= TARGET_PCT else "❌")


def render_nav_report(r: NavResult, lead_s: float = 3.0) -> str:
    all_pct = _share(r.checks, lead_s)
    win = [c for c in r.checks if c.in_window]
    win_pct = _share(win, lead_s)
    fmt = lambda v: "—" if math.isnan(v) else f"{v:.1f} %"  # noqa: E731
    lines = [
        "# Подсказки о манёврах (M3a)", "",
        f"- Маршрутов: {r.n_routes}, перестроений: {r.n_reroutes}, из них ложных: {r.false_reroutes} — "
        f"{'✅' if r.false_reroutes == 0 else '❌'}",
        f"- Пройдено манёвров: {len(r.checks)}; подсказка вовремя (≥ {lead_s:.0f} с): {fmt(all_pct)} — "
        f"{_verdict(all_pct)} (цель ≥ {TARGET_PCT:.0f} %)",
        f"- В пропаданиях и подмешанных окнах: {len(win)} манёвров, вовремя: {fmt(win_pct)} — {_verdict(win_pct)}",
        f"- Прибытие: {'✅' if r.arrived else '❌'}"
        + (f" (конец трека в {r.end_dist_m:.0f} м от цели)" if not math.isnan(r.end_dist_m) else ""),
    ]
    late = [c for c in r.checks if c.prompt_lead_s is None or c.prompt_lead_s < lead_s]
    if late:
        lines += ["", "## Поздние или пропущенные подсказки", "", "| маршрут | манёвр | тип | t, с | опережение, с |",
                  "|---|---|---|---|---|"]
        lines += [f"| {c.route_idx} | {c.maneuver} | {c.type} | {c.reached_t:.0f} | "
                  f"{'—' if c.prompt_lead_s is None else f'{c.prompt_lead_s:.1f}'} |" for c in late]
    lines += ["", "Дорожные данные © участники OpenStreetMap, ODbL 1.0.", ""]
    return "\n".join(lines)
```

`m3cli.py`:

```python
"""CLI этапа M3: vpr-m3 nav-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from vpr_bench.fieldlog import read_log
from vpr_bench.naveval import evaluate_nav, read_nav, render_nav_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m3")
    sub = parser.add_subparsers(dest="command", required=True)
    n = sub.add_parser("nav-eval", help="оценить подсказки, перестроения и прибытие по журналу ведения (.nav.jsonl)")
    n.add_argument("--nav", required=True, type=Path)
    n.add_argument("--log", required=True, type=Path, help="журнал кадров сессии (.jsonl) — источник GPS")
    n.add_argument("--out", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    header, events = read_nav(args.nav)
    log_header, frames = read_log(args.log)
    if log_header.get("started_ms") != header.get("session_started_ms"):
        print("error: nav log and frame log are from different sessions", file=sys.stderr)
        return 2
    res = evaluate_nav(header, events, frames)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(render_nav_report(res), encoding="utf-8")
    print(f"maneuvers={len(res.checks)} reroutes={res.n_reroutes} false={res.false_reroutes} -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

`pyproject.toml`: добавить `vpr-m3 = "vpr_bench.m3cli:main"`, затем выполнить `uv sync --extra dev --extra osm`.

- [ ] **Step 3: Тесты зелёные.** Run: `uv run pytest -q` → не меньше 221 passed, 4 deselected.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/pyproject.toml research/vpr_bench/uv.lock research/vpr_bench/src/vpr_bench/naveval.py research/vpr_bench/src/vpr_bench/m3cli.py research/vpr_bench/tests/test_naveval.py
git commit -m "feat(vpr-bench): vpr-m3 nav-eval — prompt timing, false reroutes, arrival

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Маршрут и голосовые подсказки в приложении

**Files:**
- Create: `android/app/src/main/kotlin/io/visnav/app/NavSession.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/Bundle.kt`: `route.json`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Screen.kt`

**Interfaces:**
- Consumes: `RouteFollower`, `NavFormat`, `NavEvent`, `RoadIndex`, `Enu`, `LocalizerOutput.psiRad/speedMps`, `Instructions`.
- Produces:
  - `data class Destination(val lat: Double, val lon: Double, val name: String?)`;
  - `LoadedBundle.destination: Destination? = null` — из `refpack/route.json` вида `{"dest_lat":…,"dest_lon":…,"dest_name":"…"}` через `org.json.JSONObject`. Без графа дорог `route.json` игнорируется, и в статусе говорится, что без графа маршрута нет. Неверный файл — понятная ошибка «route.json: …»;
  - `class NavSession(context, roads: RoadPack, dest: Destination, navLog: File, sessionStartedMs: Long, roadsCreatedAt: String)`:
    - `fun onOutput(o: LocalizerOutput): NavUi?` — вызывается на потоке кадров;
    - `fun close()`;
  - `data class NavUi(val nextText: String?, val distM: Double?, val routeKm: Double?, val routeMin: Double?, val arrived: Boolean, val rerouted: Boolean)`;
  - `UiState` получает `nav: NavUi? = null`.

Поведение `NavSession`:
- `Enu` и `RouteFollower` создаются по первому выводу с конечной позицией (как в `runNavigation`);
- события пишутся в `.nav.jsonl` (заголовок `NavFormat.header(sessionStartedMs, roadsCreatedAt, dest.lat, dest.lon, emptyList(), emptyList(), emptyList())`);
- `Prompt` озвучивается через `TextToSpeech` (ru-RU, `QUEUE_ADD`). Если синтез речи не поддерживается или не инициализировался, подсказки только пишутся в журнал и показываются на экране; в статусе один раз сообщается «Голосовые подсказки недоступны»;
- `TextToSpeech` создаётся в конструкторе (на главном потоке) и освобождается в `close()` через `stop()` и `shutdown()`;
- `NavUi.nextText` — `Instructions.prompt(следующий манёвр, distanceToNextM)` из текущего состояния `RouteFollower`, `distM` — `distanceToNextM`;
- `rerouted = true` на кадре, где был `RouteReady(reroute = true)`.

- [ ] **Step 1: `Bundle.kt`.** Чтение `route.json` после графа дорог:

```kotlin
data class Destination(val lat: Double, val lon: Double, val name: String?)

        val routeJson = File(dir, "route.json")
        val destination = if (roadsBin.isFile && routeJson.isFile) {
            try {
                val o = org.json.JSONObject(routeJson.readText())
                val lat = o.getDouble("dest_lat"); val lon = o.getDouble("dest_lon")
                check(lat in -90.0..90.0 && lon in -180.0..180.0) { "координаты вне диапазона" }
                Destination(lat, lon, o.optString("dest_name").ifEmpty { null })
            } catch (e: Exception) {
                throw IllegalStateException("route.json: ${e.message}", e)
            }
        } else null
```

Создание `LoadedBundle` дополняется полем `destination`.

- [ ] **Step 2: `NavSession.kt`:**

```kotlin
package io.visnav.app

import android.content.Context
import android.speech.tts.TextToSpeech
import io.visnav.core.Enu
import io.visnav.core.Instructions
import io.visnav.core.LocalizerOutput
import io.visnav.core.NavEvent
import io.visnav.core.NavFormat
import io.visnav.core.RoadIndex
import io.visnav.core.RoadPack
import io.visnav.core.RouteFollower
import java.io.Closeable
import java.io.File
import java.util.Locale

data class NavUi(
    val nextText: String?, val distM: Double?, val routeKm: Double?, val routeMin: Double?,
    val arrived: Boolean, val rerouted: Boolean,
)

/** Ведение по маршруту на телефоне: RouteFollower по выводу Localizer, журнал .nav.jsonl и голос (TextToSpeech). */
class NavSession(
    context: Context, private val roads: RoadPack, private val dest: Destination, navLog: File,
    sessionStartedMs: Long, roadsCreatedAt: String, private val onTtsUnavailable: () -> Unit,
) : Closeable {
    private val log = navLog.bufferedWriter()
    @Volatile private var ttsReady = false
    private var ttsReported = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }
    private var enu: Enu? = null
    private var follower: RouteFollower? = null

    init {
        log.write(NavFormat.header(sessionStartedMs, roadsCreatedAt, dest.lat, dest.lon, emptyList(), emptyList(), emptyList()))
        log.newLine()
    }

    /** Вызывается на потоке кадров по порядку времени. */
    fun onOutput(o: LocalizerOutput): NavUi? {
        if (!o.lat.isFinite() || !o.lon.isFinite()) return null
        val e0 = enu ?: Enu(o.lat, o.lon).also { enu = it }
        val f = follower ?: run {
            val d = e0.toEn(dest.lat, dest.lon)
            RouteFollower(RoadIndex(roads, e0), d[0], d[1]).also { follower = it }
        }
        val en = e0.toEn(o.lat, o.lon)
        val events = f.update(o.tMs, en[0], en[1], o.sigmaM, o.psiRad, o.speedMps)
        var rerouted = false
        for (ev in events) {
            log.write(NavFormat.event(ev, e0)); log.newLine()
            when (ev) {
                is NavEvent.Prompt -> speak(ev.text)
                is NavEvent.RouteReady -> if (ev.reroute) { rerouted = true; speak("Маршрут перестроен") }
                else -> Unit
            }
        }
        val r = f.route
        val next = f.nextManeuver
        return NavUi(
            nextText = next?.let { Instructions.prompt(f.maneuvers[it], f.distanceToNextM) },
            distM = f.distanceToNextM, routeKm = r?.lengthM?.div(1000), routeMin = r?.durationS?.div(60),
            arrived = f.arrived, rerouted = rerouted,
        )
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            if (!ttsReported) { ttsReported = true; onTtsUnavailable() }
            return
        }
        val r = tts.setLanguage(Locale("ru", "RU"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (!ttsReported) { ttsReported = true; onTtsUnavailable() }
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "nav-${System.nanoTime()}")
    }

    override fun close() {
        try { log.close() } finally { tts.stop(); tts.shutdown() }
    }
}
```

- [ ] **Step 3: `M1Controller.kt`.**
  - `UiState`: `val nav: NavUi? = null`; сбрасывается в тех же местах, что `road` (старт, стоп, ошибка запуска, `failSession`).
  - В `start()`, если `b.roads != null && b.destination != null`, создать `NavSession(context, b.roads, b.destination, File(logDir, "$base.nav.jsonl"), startedMs, b.roadsMeta!!.createdAt) { _state.update { it.copy(status = it.status + " · Голосовые подсказки недоступны") } }`. Конструктор нужно вызвать на главном потоке: `start()` уже вызывается с UI. Если `start()` не на главном потоке, создавать сессию через `Handler(Looper.getMainLooper())`. Поле `navSession` хранить как остальные журналы. Закрывать вместе с журналом fusion на всех путях (`stop`, `failSession`, `close`, ошибка старта).
  - В обработчике кадра, рядом с обновлением `road`: `val nav = try { navSession?.onOutput(out) } catch (e: Exception) { null }` — ошибка учитывается один раз, как у фильтра; затем `_state.update { it.copy(..., nav = nav ?: it.nav) }`.
  - Если `route.json` есть, а графа нет, в статусе после загрузки показать: «route.json без графа дорог — маршрут не строится». Определяется через `File(dataDir, "route.json").isFile && b.roads == null`.

- [ ] **Step 4: `M1Screen.kt`.** Перед блоком режима:

```kotlin
            s.nav?.let { nav ->
                Text(if (nav.arrived) "Вы прибыли" else nav.nextText ?: "Маршрут строится…",
                    style = MaterialTheme.typography.titleMedium)
                if (nav.routeKm != null && nav.routeMin != null && !nav.arrived) {
                    Text("Маршрут: ${"%.1f".format(nav.routeKm)} км, ~${nav.routeMin.roundToInt()} мин")
                }
                if (nav.rerouted) Text("Маршрут перестроен")
            }
```

  Импорты: `androidx.compose.material3.MaterialTheme` (если ещё нет) и `kotlin.math.roundToInt`.

- [ ] **Step 5: Сборка.** Run: `./gradlew :core:test :replay:test :app:assembleDebug :app:lintDebug` → `BUILD SUCCESSFUL`, 0 ошибок lint, новых предупреждений нет (кроме предупреждений lint о `Locale("ru", "RU")`, если они появятся: их перечислить в отчёте).

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/app/src
git commit -m "feat(app): offline route to route.json destination with Russian voice prompts and nav log

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Протокол M3a и решение в SPEC

**Files:**
- Create: `docs/research/m3a-route.md`
- Modify: `docs/SPEC.md`

- [ ] **Step 1: `docs/SPEC.md`.**
  - В FR-16 после текста: «**Решение владельца (2026-10-03):** маршрут строится собственным маршрутизатором (A*) на графе коридора `roadpack` с запретами поворотов; GraphHopper не используется. Маршрут и перестроение ограничены загруженным коридором.»
  - В таблице модулей (§6) у `:routing` заменить «GraphHopper (Android)» на «собственный A* на графе `roadpack` (`:core` Router)».
  - В плане этапов у M3 дописать: «Делится на M3a (маршрут, подсказки, перестроение), M3b (экран с картой MapLibre), M3c (пакет коридора для поездки). Приложение пока без доступа в интернет: данные кладутся через adb.»

- [ ] **Step 2: `docs/research/m3a-route.md`:**

````markdown
# M3a: офлайн-маршрут и подсказки

## Что нужно
- Граф дорог v2 (`vpr-m2 pack-roads`, план M2b; теперь с названиями улиц, скоростями, круговым движением и запретами поворотов). Пересобрать граф для старых поездок.
- Записанная поездка M2a/M2c и комплект эталонов.

## 1. Replay: маршрут к концу поездки
Точка назначения — последняя точка GPS поездки или любая точка в коридоре. Пропадания — как в M2a/M2c.
```bash
cd /Users/vvnovg/navigator/android
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
S=/Users/vvnovg/navigator/research/vpr_bench/data/m1/logs/session-<ms>-gps
R=/Users/vvnovg/navigator/research/vpr_bench/data/m1/bundle
G=/Users/vvnovg/navigator/research/vpr_bench/data/m2b/roads
./gradlew -q :replay:run --args="--session $S --refpack $R --roads $G --out $S.m3a.jsonl --outage 300:120 --dest <LAT>,<LON> --nav-out $S.nav.jsonl"
cd /Users/vvnovg/navigator/research/vpr_bench
uv run vpr-m3 nav-eval --nav $S.nav.jsonl --log $S.jsonl --out data/m3a/nav.md
```

`nav-eval` проверяет каждый пройденный по GPS манёвр:
- была ли подсказка «близко» или «сейчас» не позже чем за 3 с до него;
- отдельно — для манёвров внутри пропаданий и подмешанных окон (сценарий «тоннель»);
- не было ли ложных перестроений, когда машина по GPS ехала по маршруту;
- распознано ли прибытие.

Если водитель ехал не по построенному маршруту, перестроения ожидаемы. Их нужно отличать от ложных по таблице.

## 2. Телефон
Положить рядом с базой эталонов граф дорог и точку назначения:
```bash
echo '{"dest_lat": 55.7558, "dest_lon": 37.6173, "dest_name": "Работа"}' > route.json
adb push route.json /sdcard/Android/data/io.visnav.app/files/refpack/
```
- При старте записи маршрут строится от первой позиции.
- Подсказки звучат голосом (русский синтез речи Android; если его нет — только текст на экране) и пишутся в `session-…​.nav.jsonl`.
- Журнал оценивается так же: `vpr-m3 nav-eval --nav <сессия>.nav.jsonl --log <сессия>.jsonl --out …`.

## Как это устроено
- **Маршрут** — A* по направленным рёбрам графа коридора: время в пути по скорости дороги, односторонние улицы, запреты поворотов OSM, штраф 5 с за поворот. Курс на старте не даёт начать «против шерсти». Разворот — только в тупике.
- **Манёвры** — на перекрёстках (узлы степени ≥ 3) по углу:
  - < 20° — «прямо», только если сменилась улица;
  - < 45° — «держитесь»;
  - < 135° — «поверните»;
  - < 170° — «резко»;
  - иначе — разворот.

  Круговое движение — с номером съезда. Два поворота в одну сторону ближе 30 м (двойная проезжая часть) — одна подсказка.
- **Подсказки** — три стадии, от скорости v (не меньше 5 м/с):
  - «далеко» — `30·v` (300–1000 м);
  - «близко» — `8·v` (60–200 м);
  - «сейчас» — `2,5·v` (15–50 м).
- **Съезд с маршрута** — дальше `max(40 м, 2,5σ)` дольше 4 с. Перестроение — не чаще раза в 10 с. При визуальной навигации и счислении пути σ больше, поэтому съезд замечается позже, зато ложных перестроений меньше.

## Известные ограничения
- Маршрут и перестроение возможны только внутри загруженного коридора (±300 м вокруг маршрута поездки). Съезд за пределы коридора даёт «маршрут не найден».
- Названия улиц не склоняются: «Через 300 м поверните направо — Тверская улица».
- Полосы движения (FR-17, «с указанием полосы») не поддерживаются: в графе нет данных о полосах.
- Карты на экране нет (M3b).

## Итоги (заполнить)
- Поездки, граф, точка назначения: …
- Таблица nav-eval: …
- Ложные перестроения и их причины: …

## Критерий M3a (предложение плана; владелец может скорректировать)
- [ ] ≥ 95 % пройденных манёвров — подсказка «близко» или «сейчас» за ≥ 3 с — факт: …
- [ ] То же внутри пропаданий GPS — факт: …
- [ ] Ложных перестроений нет — факт: …
- [ ] Прибытие распознано — факт: …
````

- [ ] **Step 3: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m3a-route.md docs/SPEC.md
git commit -m "docs: M3a routing protocol; SPEC — own corridor router, M3 split

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
