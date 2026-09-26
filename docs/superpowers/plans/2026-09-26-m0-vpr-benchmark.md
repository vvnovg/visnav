# M0: стенд сравнения VPR-моделей — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Python-стенд, который собирает базу эталонных снимков из Mapillary и кадры своих поездок с GPS-разметкой. На этих данных он сравнивает VPR-модели по Recall@K и выдаёт отчёт, по которому выбирается модель для M1.

**Architecture:** Небольшой пакет `vpr_bench` из модулей с одной ответственностью каждый:
- геоутилиты;
- CSV-формат мест;
- клиент Mapillary;
- нарезка панорам;
- сборщик базы эталонов;
- извлечение кадров-запросов из видео и GPX;
- обёртки моделей через `torch.hub`;
- гео-индекс (полный перебор в numpy);
- метрики;
- отчёт;
- CLI.

Всё тестируется без сети и без скачивания моделей: используются фейковые сессии и крошечные сети. Реальные модели проверяются отдельными тестами с маркером `slow`.

**Tech Stack:** Python 3.11+, uv, numpy, opencv-python-headless, requests, torch/torchvision, pytest.

## Global Constraints

Из `docs/SPEC.md`:
- Критерий выхода M0: **Recall@5 ≥ 85 %** на собственном датасете. Совпадение засчитывается, если эталон находится в пределах **25 м** от истинной позиции.
- Целевые параметры модели для устройства (в M0 фиксируются, но не оптимизируются):
  - размер ≤ 30 МБ после INT8;
  - инференс ≤ 60 мс;
  - дескриптор 512–4096 значений.
- Виды из панорамы: шаг **30–45°**, поле зрения как у камеры автомобиля. По умолчанию 8 видов × 90°.
- Поиск ведётся внутри окна неопределённости (гео-префильтр). В бенчмарке это режим `prior-500m`.
- Источник снимков в M0 — **только Mapillary** (CC BY-SA 4.0). Google Street View не используется.
- Токены и ключи не коммитятся и не пишутся в логи. Токен Mapillary передаётся через переменную окружения `MAPILLARY_TOKEN`.
- Данные (`research/vpr_bench/data/`) в git не попадают.

---

## Структура файлов

```
navigator/
├── .gitignore
├── docs/
│   ├── SPEC.md                                  (есть)
│   └── research/
│       ├── legal-imagery.md                     Task 11
│       ├── drive-protocol.md                    Task 12
│       └── m0-results.md                        Task 13
└── research/vpr_bench/
    ├── pyproject.toml
    ├── src/vpr_bench/
    │   ├── __init__.py
    │   ├── geo.py          расстояния, азимут, смещение, BBox, интерполяция трека
    │   ├── dataset.py      Place + чтение/запись CSV
    │   ├── mapillary.py    клиент Mapillary Graph API v4
    │   ├── panorama.py     equirectangular → перспективные виды
    │   ├── db_builder.py   сборка базы эталонов
    │   ├── query.py        GPX + видео → кадры-запросы с разметкой
    │   ├── models.py       реестр моделей, VprModel.embed
    │   ├── index.py        GeoIndex: поиск с гео-префильтром
    │   ├── evaluate.py     Recall@K, ошибки позиции
    │   ├── report.py       Markdown-отчёт
    │   ├── pipeline.py     прогон моделей по датасету
    │   └── cli.py          vpr-bench fetch-refs | extract-queries | bench
    └── tests/
        ├── test_geo.py
        ├── test_dataset.py
        ├── test_mapillary.py
        ├── test_panorama.py
        ├── test_db_builder.py
        ├── test_query.py
        ├── test_models.py
        ├── test_index.py
        ├── test_evaluate.py
        ├── test_report.py
        ├── test_pipeline.py
        └── test_cli.py
```

Все команды ниже выполняются из `/Users/vvnovg/navigator/research/vpr_bench`, если не указано другое.

---

### Task 1: Каркас проекта и геоутилиты

**Files:**
- Create: `/Users/vvnovg/navigator/.gitignore`
- Create: `research/vpr_bench/pyproject.toml`
- Create: `research/vpr_bench/src/vpr_bench/__init__.py`
- Create: `research/vpr_bench/src/vpr_bench/geo.py`
- Test: `research/vpr_bench/tests/test_geo.py`

**Interfaces:**
- Produces:
  - `haversine_m(lat1, lon1, lat2, lon2) -> float`
  - `haversine_m_vec(lat, lon, lats: np.ndarray, lons: np.ndarray) -> np.ndarray`
  - `bearing_deg(lat1, lon1, lat2, lon2) -> float` (0 = север, по часовой)
  - `offset_m(lat, lon, east_m, north_m) -> tuple[float, float]`
  - `BBox(min_lon, min_lat, max_lon, max_lat)` с методами `.parse(str)`, `.tiles(step_deg) -> list[BBox]`, `.as_param() -> str`
  - `TrackPoint(t, lat, lon)`
  - `interpolate_track(track: list[TrackPoint], t: float) -> tuple[float, float] | None`

- [ ] **Step 1: Инициализировать git-репозиторий и `.gitignore`**

```bash
cd /Users/vvnovg/navigator && git init
```

`/Users/vvnovg/navigator/.gitignore`:
```gitignore
.venv/
__pycache__/
*.pyc
.pytest_cache/
research/vpr_bench/data/
.env
```

- [ ] **Step 2: Создать `pyproject.toml` и пакет**

`research/vpr_bench/pyproject.toml`:
```toml
[project]
name = "vpr-bench"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "numpy>=1.26",
    "opencv-python-headless>=4.9",
    "requests>=2.31",
    "torch>=2.2",
    "torchvision>=0.17",
]

[project.optional-dependencies]
dev = ["pytest>=8"]

[project.scripts]
vpr-bench = "vpr_bench.cli:main"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/vpr_bench"]

[tool.pytest.ini_options]
testpaths = ["tests"]
markers = ["slow: скачивает модели или ходит в сеть"]
addopts = "-m 'not slow'"
```

`research/vpr_bench/src/vpr_bench/__init__.py`:
```python
"""Стенд сравнения моделей визуального распознавания места (VPR) для этапа M0."""
```

Run: `cd /Users/vvnovg/navigator/research/vpr_bench && uv sync --extra dev`
Expected: создан `.venv`, зависимости установлены без ошибок.

- [ ] **Step 3: Написать падающие тесты**

`research/vpr_bench/tests/test_geo.py`:
```python
import math

import numpy as np
import pytest

from vpr_bench.geo import (
    BBox,
    TrackPoint,
    bearing_deg,
    haversine_m,
    haversine_m_vec,
    interpolate_track,
    offset_m,
)


def test_haversine_one_degree_latitude():
    assert haversine_m(0, 0, 1, 0) == pytest.approx(111_195, rel=1e-3)


def test_haversine_vec_matches_scalar():
    lats = np.array([55.75, 55.76, 59.93])
    lons = np.array([37.62, 37.63, 30.31])
    got = haversine_m_vec(55.75, 37.62, lats, lons)
    want = [haversine_m(55.75, 37.62, a, b) for a, b in zip(lats, lons)]
    assert got == pytest.approx(want, rel=1e-9)


def test_bearing_north_and_east():
    assert bearing_deg(0, 0, 1, 0) == pytest.approx(0.0, abs=1e-6)
    assert bearing_deg(0, 0, 0, 1) == pytest.approx(90.0, abs=1e-6)


def test_offset_distance_matches():
    lat, lon = offset_m(55.75, 37.62, east_m=100, north_m=200)
    assert haversine_m(55.75, 37.62, lat, lon) == pytest.approx(math.hypot(100, 200), rel=1e-3)


def test_bbox_tiles_cover_area():
    tiles = BBox(0.0, 0.0, 0.01, 0.01).tiles(0.005)
    assert len(tiles) == 4
    assert tiles[-1] == BBox(0.005, 0.005, 0.01, 0.01)


def test_bbox_parse_and_param_roundtrip():
    b = BBox.parse("37.60,55.74,37.62,55.76")
    assert b == BBox(37.60, 55.74, 37.62, 55.76)
    assert b.as_param() == "37.6,55.74,37.62,55.76"


def test_bbox_parse_rejects_inverted():
    with pytest.raises(ValueError):
        BBox.parse("37.62,55.74,37.60,55.76")


def test_interpolate_midpoint_and_out_of_range():
    track = [TrackPoint(0.0, 55.0, 37.0), TrackPoint(10.0, 56.0, 38.0)]
    assert interpolate_track(track, 5.0) == pytest.approx((55.5, 37.5))
    assert interpolate_track(track, 0.0) == pytest.approx((55.0, 37.0))
    assert interpolate_track(track, -1.0) is None
    assert interpolate_track(track, 11.0) is None
    assert interpolate_track([], 0.0) is None
```

- [ ] **Step 4: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_geo.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.geo'`

- [ ] **Step 5: Реализовать `geo.py`**

`research/vpr_bench/src/vpr_bench/geo.py`:
```python
"""Геодезические утилиты: расстояния, азимуты, тайлинг bbox, интерполяция трека."""
from __future__ import annotations

import bisect
import math
from dataclasses import dataclass

import numpy as np

EARTH_RADIUS_M = 6_371_000.0
M_PER_DEG_LAT = 111_320.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def haversine_m_vec(lat: float, lon: float, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    p1 = np.radians(lat)
    p2 = np.radians(np.asarray(lats, dtype=np.float64))
    dp = p2 - p1
    dl = np.radians(np.asarray(lons, dtype=np.float64) - lon)
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.clip(a, 0.0, 1.0)))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.degrees(math.atan2(x, y)) % 360.0


def offset_m(lat: float, lon: float, east_m: float, north_m: float) -> tuple[float, float]:
    dlat = north_m / M_PER_DEG_LAT
    dlon = east_m / (M_PER_DEG_LAT * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


@dataclass(frozen=True)
class BBox:
    min_lon: float
    min_lat: float
    max_lon: float
    max_lat: float

    @classmethod
    def parse(cls, s: str) -> BBox:
        parts = [float(p) for p in s.split(",")]
        if len(parts) != 4:
            raise ValueError(f"bbox must be 'min_lon,min_lat,max_lon,max_lat', got {s!r}")
        b = cls(*parts)
        if b.min_lon >= b.max_lon or b.min_lat >= b.max_lat:
            raise ValueError(f"bbox min must be < max, got {s!r}")
        return b

    def tiles(self, step_deg: float) -> list[BBox]:
        out: list[BBox] = []
        lat = self.min_lat
        while lat < self.max_lat - 1e-12:
            top = min(lat + step_deg, self.max_lat)
            lon = self.min_lon
            while lon < self.max_lon - 1e-12:
                right = min(lon + step_deg, self.max_lon)
                out.append(BBox(lon, lat, right, top))
                lon = right
            lat = top
        return out

    def as_param(self) -> str:
        return f"{self.min_lon},{self.min_lat},{self.max_lon},{self.max_lat}"


@dataclass(frozen=True)
class TrackPoint:
    t: float  # unix-время, секунды
    lat: float
    lon: float


def interpolate_track(track: list[TrackPoint], t: float) -> tuple[float, float] | None:
    """Линейная интерполяция позиции; None вне временного диапазона трека."""
    if not track or t < track[0].t or t > track[-1].t:
        return None
    times = [p.t for p in track]
    i = bisect.bisect_left(times, t)
    if times[i] == t:
        return track[i].lat, track[i].lon
    a, b = track[i - 1], track[i]
    w = (t - a.t) / (b.t - a.t)
    return a.lat + w * (b.lat - a.lat), a.lon + w * (b.lon - a.lon)
```

- [ ] **Step 6: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_geo.py -v`
Expected: 8 passed

- [ ] **Step 7: Commit**

```bash
cd /Users/vvnovg/navigator
git add .gitignore docs/SPEC.md docs/superpowers/plans research/vpr_bench/pyproject.toml research/vpr_bench/uv.lock research/vpr_bench/src research/vpr_bench/tests
git commit -m "feat(vpr-bench): scaffold project and geo utilities"
```

---

### Task 2: Формат мест (CSV)

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/dataset.py`
- Test: `research/vpr_bench/tests/test_dataset.py`

**Interfaces:**
- Produces:
  - `Place(path: str, lat: float, lon: float, heading: float)`. Поле `path` задаётся относительно каталога, в котором лежит CSV.
  - `write_places(csv_path: Path, places: Iterable[Place]) -> None`
  - `read_places(csv_path: Path) -> list[Place]`

- [ ] **Step 1: Написать падающий тест**

`research/vpr_bench/tests/test_dataset.py`:
```python
from vpr_bench.dataset import Place, read_places, write_places


def test_roundtrip(tmp_path):
    places = [
        Place("images/a.jpg", 55.75, 37.62, 10.0),
        Place("images/b.jpg", 55.76, 37.63, 359.5),
    ]
    csv_path = tmp_path / "sub" / "refs.csv"
    write_places(csv_path, places)
    assert read_places(csv_path) == places


def test_empty_file_roundtrip(tmp_path):
    csv_path = tmp_path / "empty.csv"
    write_places(csv_path, [])
    assert read_places(csv_path) == []
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `uv run pytest tests/test_dataset.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.dataset'`

- [ ] **Step 3: Реализовать `dataset.py`**

`research/vpr_bench/src/vpr_bench/dataset.py`:
```python
"""Place — геопривязанный снимок (эталон или запрос) и его CSV-представление."""
from __future__ import annotations

import csv
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

FIELDS = ["path", "lat", "lon", "heading"]


@dataclass(frozen=True)
class Place:
    path: str  # относительно каталога CSV-файла
    lat: float
    lon: float
    heading: float  # градусы, 0 = север, по часовой


def write_places(csv_path: Path, places: Iterable[Place]) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(FIELDS)
        for p in places:
            w.writerow([p.path, repr(p.lat), repr(p.lon), repr(p.heading)])


def read_places(csv_path: Path) -> list[Place]:
    with csv_path.open(newline="") as f:
        return [
            Place(row["path"], float(row["lat"]), float(row["lon"]), float(row["heading"]))
            for row in csv.DictReader(f)
        ]
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_dataset.py -v`
Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/dataset.py research/vpr_bench/tests/test_dataset.py
git commit -m "feat(vpr-bench): add Place CSV format"
```

---

### Task 3: Клиент Mapillary

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/mapillary.py`
- Test: `research/vpr_bench/tests/test_mapillary.py`

**Interfaces:**
- Consumes: `BBox` (Task 1).
- Produces:
  - `RefImage(id: str, lat: float, lon: float, heading: float, captured_at: int, is_pano: bool, url: str)`
  - `parse_image(item: dict) -> RefImage | None`
  - `MapillaryClient(token: str, session=None, tile_step_deg: float = 0.005, timeout_s: float = 30.0)` с методами:
    - `.list_images(bbox: BBox) -> list[RefImage]`
    - `.download(img: RefImage, dest: Path) -> Path`

Справка по API: `GET https://graph.mapillary.com/images`. Параметры:
- `access_token`;
- `fields`;
- `bbox=minLon,minLat,maxLon,maxLat`;
- `limit` (≤ 2000).

Для больших bbox API возвращает не все снимки, поэтому область режется на тайлы по 0.005°.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_mapillary.py`:
```python
from vpr_bench.geo import BBox
from vpr_bench.mapillary import MapillaryClient, RefImage, parse_image


class FakeResponse:
    def __init__(self, json_data=None, content=b""):
        self._json = json_data
        self.content = content

    def raise_for_status(self):
        pass

    def json(self):
        return self._json


class FakeSession:
    def __init__(self, pages):
        self.pages = list(pages)
        self.calls = []

    def get(self, url, params=None, timeout=None):
        self.calls.append((url, params))
        return self.pages.pop(0)


def _item(id_, lat, lon, pano=False):
    return {
        "id": id_,
        "computed_geometry": {"type": "Point", "coordinates": [lon, lat]},
        "computed_compass_angle": 370.0,
        "captured_at": 1_700_000_000_000,
        "is_pano": pano,
        "thumb_2048_url": f"https://img/{id_}.jpg",
    }


def test_parse_image_normalizes_heading_and_swaps_coords():
    img = parse_image(_item("1", 55.75, 37.62, pano=True))
    assert img == RefImage("1", 55.75, 37.62, 10.0, 1_700_000_000_000, True, "https://img/1.jpg")


def test_parse_image_skips_items_without_geometry_or_url():
    assert parse_image({"id": "1", "thumb_2048_url": "u"}) is None
    item = _item("2", 1, 1)
    del item["thumb_2048_url"]
    assert parse_image(item) is None


def test_list_images_tiles_bbox_and_dedupes():
    session = FakeSession([
        FakeResponse({"data": [_item("1", 0.001, 0.001), _item("2", 0.002, 0.002)]}),
        FakeResponse({"data": [_item("2", 0.002, 0.002)]}),
    ])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=0.005)
    images = client.list_images(BBox(0.0, 0.0, 0.01, 0.005))
    assert sorted(i.id for i in images) == ["1", "2"]
    assert len(session.calls) == 2
    first_params = session.calls[0][1]
    assert first_params["bbox"] == "0.0,0.0,0.005,0.005"
    assert first_params["access_token"] == "TOKEN"


def test_download_writes_bytes(tmp_path):
    session = FakeSession([FakeResponse(content=b"JPEGDATA")])
    client = MapillaryClient("TOKEN", session=session)
    img = parse_image(_item("7", 1, 1))
    dest = client.download(img, tmp_path / "raw" / "7.jpg")
    assert dest.read_bytes() == b"JPEGDATA"
    assert session.calls[0][0] == "https://img/7.jpg"
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_mapillary.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.mapillary'`

- [ ] **Step 3: Реализовать `mapillary.py`**

`research/vpr_bench/src/vpr_bench/mapillary.py`:
```python
"""Клиент Mapillary Graph API v4: список снимков в bbox и их загрузка."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import requests

from vpr_bench.geo import BBox

GRAPH_URL = "https://graph.mapillary.com/images"
FIELDS = "id,computed_geometry,computed_compass_angle,captured_at,is_pano,thumb_2048_url"
PAGE_LIMIT = 2000


@dataclass(frozen=True)
class RefImage:
    id: str
    lat: float
    lon: float
    heading: float
    captured_at: int  # unix-время, миллисекунды
    is_pano: bool
    url: str


def parse_image(item: dict) -> RefImage | None:
    geom = item.get("computed_geometry")
    url = item.get("thumb_2048_url")
    if not geom or not url:
        return None
    lon, lat = geom["coordinates"]
    return RefImage(
        id=str(item["id"]),
        lat=float(lat),
        lon=float(lon),
        heading=float(item.get("computed_compass_angle") or 0.0) % 360.0,
        captured_at=int(item.get("captured_at") or 0),
        is_pano=bool(item.get("is_pano", False)),
        url=url,
    )


class MapillaryClient:
    def __init__(self, token: str, session=None, tile_step_deg: float = 0.005, timeout_s: float = 30.0):
        self._token = token
        self._session = session or requests.Session()
        self.tile_step_deg = tile_step_deg
        self._timeout_s = timeout_s

    def list_images(self, bbox: BBox) -> list[RefImage]:
        seen: dict[str, RefImage] = {}
        for tile in bbox.tiles(self.tile_step_deg):
            resp = self._session.get(
                GRAPH_URL,
                params={
                    "access_token": self._token,
                    "fields": FIELDS,
                    "bbox": tile.as_param(),
                    "limit": PAGE_LIMIT,
                },
                timeout=self._timeout_s,
            )
            resp.raise_for_status()
            for item in resp.json().get("data", []):
                img = parse_image(item)
                if img is not None:
                    seen.setdefault(img.id, img)
        return list(seen.values())

    def download(self, img: RefImage, dest: Path) -> Path:
        resp = self._session.get(img.url, timeout=self._timeout_s)
        resp.raise_for_status()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(resp.content)
        return dest
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_mapillary.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/mapillary.py research/vpr_bench/tests/test_mapillary.py
git commit -m "feat(vpr-bench): add Mapillary API client"
```

---

### Task 4: Нарезка панорам на перспективные виды

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/panorama.py`
- Test: `research/vpr_bench/tests/test_panorama.py`

**Interfaces:**
- Produces:
  - `equirect_to_perspective(pano: np.ndarray, yaw_deg: float, fov_deg: float, out_w: int, out_h: int, pitch_deg: float = 0.0) -> np.ndarray`. `yaw_deg` отсчитывается от центра панорамы по часовой; `pitch_deg > 0` — взгляд вверх.
  - `perspective_views(pano, n_views: int, fov_deg: float, out_w: int, out_h: int) -> list[tuple[float, np.ndarray]]`. Возвращает пары `(yaw_deg, изображение)`.

Соглашение: центральный столбец equirectangular-панорамы соответствует курсу снимка (`RefImage.heading`), столбцы правее центра — повороту по часовой. Абсолютный курс вида = `(heading + yaw) % 360`.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_panorama.py`:
```python
import numpy as np
import pytest

from vpr_bench.panorama import equirect_to_perspective, perspective_views

W, H = 360, 180


def _column_pano():
    # значение пикселя = номер столбца
    return np.tile(np.arange(W, dtype=np.float32), (H, 1))


def _row_pano():
    # значение пикселя = номер строки
    return np.tile(np.arange(H, dtype=np.float32)[:, None], (1, W))


def test_center_of_yaw0_view_samples_pano_center_column():
    view = equirect_to_perspective(_column_pano(), yaw_deg=0, fov_deg=90, out_w=101, out_h=101)
    assert view.shape == (101, 101)
    assert view[50, 50] == pytest.approx(179.5, abs=0.6)


def test_yaw90_view_looks_right_of_center():
    view = equirect_to_perspective(_column_pano(), yaw_deg=90, fov_deg=90, out_w=101, out_h=101)
    assert view[50, 50] == pytest.approx(269.5, abs=0.6)


def test_zero_pitch_center_samples_horizon_row():
    view = equirect_to_perspective(_row_pano(), yaw_deg=0, fov_deg=90, out_w=101, out_h=101)
    assert view[50, 50] == pytest.approx(89.5, abs=0.6)


def test_positive_pitch_looks_up():
    view = equirect_to_perspective(_row_pano(), yaw_deg=0, fov_deg=90, out_w=101, out_h=101, pitch_deg=30)
    assert view[50, 50] < 89.5 - 20


def test_perspective_views_yaws_and_shapes():
    pano = np.zeros((H, W, 3), dtype=np.uint8)
    views = perspective_views(pano, n_views=8, fov_deg=90, out_w=64, out_h=48)
    assert [yaw for yaw, _ in views] == [0, 45, 90, 135, 180, 225, 270, 315]
    assert all(img.shape == (48, 64, 3) for _, img in views)
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_panorama.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.panorama'`

- [ ] **Step 3: Реализовать `panorama.py`**

`research/vpr_bench/src/vpr_bench/panorama.py`:
```python
"""Проекция equirectangular-панорамы 360° в перспективные виды с заданным курсом и FOV."""
from __future__ import annotations

import math

import cv2
import numpy as np


def equirect_to_perspective(
    pano: np.ndarray,
    yaw_deg: float,
    fov_deg: float,
    out_w: int,
    out_h: int,
    pitch_deg: float = 0.0,
) -> np.ndarray:
    h, w = pano.shape[:2]
    f = 0.5 * out_w / math.tan(math.radians(fov_deg) / 2)
    xs = np.arange(out_w, dtype=np.float64) - (out_w - 1) / 2
    ys = np.arange(out_h, dtype=np.float64) - (out_h - 1) / 2
    x, y = np.meshgrid(xs, ys)
    z = np.full_like(x, f)

    # Камера: x вправо, y вниз, z вперёд. Сначала наклон (вокруг x), затем курс (вокруг y).
    p, yw = math.radians(pitch_deg), math.radians(yaw_deg)
    y2 = y * math.cos(p) - z * math.sin(p)
    z2 = y * math.sin(p) + z * math.cos(p)
    x3 = x * math.cos(yw) + z2 * math.sin(yw)
    z3 = -x * math.sin(yw) + z2 * math.cos(yw)

    lon = np.arctan2(x3, z3)  # 0 = центр панорамы
    lat = np.arctan2(-y2, np.hypot(x3, z3))  # вверх положительно

    map_x = ((lon / (2 * math.pi) + 0.5) * w - 0.5).astype(np.float32)
    map_y = ((0.5 - lat / math.pi) * h - 0.5).astype(np.float32)
    return cv2.remap(pano, map_x, map_y, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)


def perspective_views(
    pano: np.ndarray, n_views: int, fov_deg: float, out_w: int, out_h: int
) -> list[tuple[float, np.ndarray]]:
    step = 360.0 / n_views
    return [
        (i * step, equirect_to_perspective(pano, i * step, fov_deg, out_w, out_h))
        for i in range(n_views)
    ]
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_panorama.py -v`
Expected: 5 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/panorama.py research/vpr_bench/tests/test_panorama.py
git commit -m "feat(vpr-bench): add equirectangular to perspective projection"
```

---

### Task 5: Сборщик базы эталонов

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/db_builder.py`
- Test: `research/vpr_bench/tests/test_db_builder.py`

**Interfaces:**
- Consumes: `BBox` (Task 1); `Place`, `write_places` (Task 2); `RefImage` (Task 3); `perspective_views` (Task 4).
- Produces:
  - `ImageSource` (Protocol) с методами `list_images(bbox) -> list[RefImage]` и `download(img, dest) -> Path`. Этому протоколу удовлетворяет `MapillaryClient`.
  - `ViewConfig(n_views=8, fov_deg=90.0, width=640, height=480)`
  - `build_reference_db(source: ImageSource, bbox: BBox, out_dir: Path, views: ViewConfig = ViewConfig()) -> list[Place]`. Пишет:
    - `out_dir/raw/<id>.jpg` — исходники (повторно не скачиваются);
    - `out_dir/images/...` — эталоны;
    - `out_dir/refs.csv`.

- [ ] **Step 1: Написать падающий тест**

`research/vpr_bench/tests/test_db_builder.py`:
```python
import cv2
import numpy as np

from vpr_bench.dataset import read_places
from vpr_bench.db_builder import ViewConfig, build_reference_db
from vpr_bench.geo import BBox
from vpr_bench.mapillary import RefImage


class FakeSource:
    def __init__(self, images):
        self.images = images
        self.downloads = 0

    def list_images(self, bbox):
        return self.images

    def download(self, img, dest):
        self.downloads += 1
        shape = (100, 200, 3) if img.is_pano else (48, 64, 3)
        dest.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(dest), np.full(shape, 128, np.uint8))
        return dest


def test_builds_views_for_pano_and_copies_perspective(tmp_path):
    source = FakeSource([
        RefImage("p1", 55.75, 37.62, 10.0, 0, True, "u1"),
        RefImage("f1", 55.76, 37.63, 200.0, 0, False, "u2"),
    ])
    views = ViewConfig(n_views=8, fov_deg=90.0, width=64, height=48)
    places = build_reference_db(source, BBox(37.6, 55.7, 37.7, 55.8), tmp_path, views)

    assert len(places) == 9
    pano_places = [p for p in places if "p1_" in p.path]
    assert [p.heading for p in pano_places] == [10, 55, 100, 145, 190, 235, 280, 325]
    assert all((tmp_path / p.path).exists() for p in places)
    assert read_places(tmp_path / "refs.csv") == places


def test_skips_download_when_raw_exists(tmp_path):
    source = FakeSource([RefImage("f1", 55.76, 37.63, 0.0, 0, False, "u")])
    build_reference_db(source, BBox(0, 0, 1, 1), tmp_path, ViewConfig(width=64, height=48))
    build_reference_db(source, BBox(0, 0, 1, 1), tmp_path, ViewConfig(width=64, height=48))
    assert source.downloads == 1
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `uv run pytest tests/test_db_builder.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.db_builder'`

- [ ] **Step 3: Реализовать `db_builder.py`**

`research/vpr_bench/src/vpr_bench/db_builder.py`:
```python
"""Сборка базы эталонов: загрузка снимков, нарезка панорам на виды, запись refs.csv."""
from __future__ import annotations

import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import cv2

from vpr_bench.dataset import Place, write_places
from vpr_bench.geo import BBox
from vpr_bench.mapillary import RefImage
from vpr_bench.panorama import perspective_views


class ImageSource(Protocol):
    def list_images(self, bbox: BBox) -> list[RefImage]: ...

    def download(self, img: RefImage, dest: Path) -> Path: ...


@dataclass(frozen=True)
class ViewConfig:
    n_views: int = 8
    fov_deg: float = 90.0
    width: int = 640
    height: int = 480


def build_reference_db(
    source: ImageSource, bbox: BBox, out_dir: Path, views: ViewConfig = ViewConfig()
) -> list[Place]:
    raw_dir = out_dir / "raw"
    (out_dir / "images").mkdir(parents=True, exist_ok=True)
    places: list[Place] = []
    for img in source.list_images(bbox):
        raw_path = raw_dir / f"{img.id}.jpg"
        if not raw_path.exists():
            source.download(img, raw_path)
        if img.is_pano:
            frame = cv2.imread(str(raw_path))
            if frame is None:
                continue
            for yaw, view in perspective_views(frame, views.n_views, views.fov_deg, views.width, views.height):
                rel = f"images/{img.id}_{int(round(yaw)):03d}.jpg"
                cv2.imwrite(str(out_dir / rel), view)
                places.append(Place(rel, img.lat, img.lon, (img.heading + yaw) % 360.0))
        else:
            rel = f"images/{img.id}.jpg"
            shutil.copyfile(raw_path, out_dir / rel)
            places.append(Place(rel, img.lat, img.lon, img.heading))
    write_places(out_dir / "refs.csv", places)
    return places
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_db_builder.py -v`
Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/db_builder.py research/vpr_bench/tests/test_db_builder.py
git commit -m "feat(vpr-bench): build reference DB from image source"
```

---

### Task 6: Кадры-запросы из видео и GPX

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/query.py`
- Test: `research/vpr_bench/tests/test_query.py`

**Interfaces:**
- Consumes: `TrackPoint`, `interpolate_track`, `haversine_m`, `bearing_deg` (Task 1); `Place`, `write_places` (Task 2).
- Produces:
  - `parse_gpx(path: Path) -> list[TrackPoint]` (отсортирован по времени)
  - `pose_at(track, t: float) -> tuple[float, float, float, float] | None`. Возвращает `(lat, lon, heading, speed_mps)`; курс и скорость считаются по окну ±1 с.
  - `extract_query_frames(video: Path, track: list[TrackPoint], video_start_epoch: float, every_s: float, out_dir: Path, min_speed_mps: float = 2.0) -> list[Place]`. Пишет `out_dir/images/q_XXXXXX.jpg` и `out_dir/queries.csv`. Кадры на стоянке (скорость ниже `min_speed_mps`) отбрасываются.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_query.py`:
```python
from datetime import datetime, timezone

import cv2
import numpy as np
import pytest

from vpr_bench.dataset import read_places
from vpr_bench.query import extract_query_frames, parse_gpx, pose_at

GPX = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="55.7510" lon="37.6000"><time>2026-09-20T10:00:10Z</time></trkpt>
<trkpt lat="55.7500" lon="37.6000"><time>2026-09-20T10:00:00Z</time></trkpt>
<trkpt lat="55.7520" lon="37.6000"><time>2026-09-20T10:00:20Z</time></trkpt>
</trkseg></trk></gpx>
"""
T0 = datetime(2026, 9, 20, 10, 0, 0, tzinfo=timezone.utc).timestamp()


@pytest.fixture
def track(tmp_path):
    p = tmp_path / "track.gpx"
    p.write_text(GPX)
    return parse_gpx(p)


def _write_video(path, n_frames, fps):
    w = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), fps, (64, 48))
    for i in range(n_frames):
        w.write(np.full((48, 64, 3), i % 255, np.uint8))
    w.release()


def test_parse_gpx_sorts_by_time(track):
    assert [p.t - T0 for p in track] == [0.0, 10.0, 20.0]
    assert track[0].lat == 55.75


def test_pose_at_heading_north_and_speed(track):
    lat, lon, heading, speed = pose_at(track, T0 + 5)
    assert lat == pytest.approx(55.7505)
    assert heading == pytest.approx(0.0, abs=1e-6)
    assert speed == pytest.approx(11.1, rel=0.01)
    assert pose_at(track, T0) is None  # нет окна t-1


def test_extract_query_frames(tmp_path, track):
    video = tmp_path / "drive.avi"
    _write_video(video, n_frames=200, fps=10)
    places = extract_query_frames(video, track, T0, every_s=2.0, out_dir=tmp_path / "q")
    assert len(places) == 9  # t = 2..18 с; t = 0 отброшен
    assert places[0].lat == pytest.approx(55.7502)
    assert all((tmp_path / "q" / p.path).exists() for p in places)
    assert read_places(tmp_path / "q" / "queries.csv") == places


def test_extract_rejects_missing_video(tmp_path, track):
    with pytest.raises(ValueError):
        extract_query_frames(tmp_path / "nope.avi", track, T0, 1.0, tmp_path / "q")
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_query.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.query'`

- [ ] **Step 3: Реализовать `query.py`**

`research/vpr_bench/src/vpr_bench/query.py`:
```python
"""Кадры-запросы: видео поездки + GPX-трек → изображения с истинной позицией и курсом."""
from __future__ import annotations

import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

import cv2

from vpr_bench.dataset import Place, write_places
from vpr_bench.geo import TrackPoint, bearing_deg, haversine_m, interpolate_track


def parse_gpx(path: Path) -> list[TrackPoint]:
    root = ET.parse(path).getroot()
    points: list[TrackPoint] = []
    for el in root.iter():
        if not el.tag.endswith("trkpt"):
            continue
        time_el = next((c for c in el if c.tag.endswith("time")), None)
        if time_el is None or not time_el.text:
            continue
        t = datetime.fromisoformat(time_el.text.strip().replace("Z", "+00:00")).timestamp()
        points.append(TrackPoint(t, float(el.get("lat")), float(el.get("lon"))))
    points.sort(key=lambda p: p.t)
    return points


def pose_at(track: list[TrackPoint], t: float) -> tuple[float, float, float, float] | None:
    here = interpolate_track(track, t)
    before = interpolate_track(track, t - 1.0)
    after = interpolate_track(track, t + 1.0)
    if here is None or before is None or after is None:
        return None
    speed = haversine_m(*before, *after) / 2.0
    heading = bearing_deg(*before, *after)
    return here[0], here[1], heading, speed


def extract_query_frames(
    video: Path,
    track: list[TrackPoint],
    video_start_epoch: float,
    every_s: float,
    out_dir: Path,
    min_speed_mps: float = 2.0,
) -> list[Place]:
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise ValueError(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(every_s * fps))
    (out_dir / "images").mkdir(parents=True, exist_ok=True)

    places: list[Place] = []
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % step == 0:
            pose = pose_at(track, video_start_epoch + idx / fps)
            if pose is not None and pose[3] >= min_speed_mps:
                rel = f"images/q_{idx:06d}.jpg"
                cv2.imwrite(str(out_dir / rel), frame)
                places.append(Place(rel, pose[0], pose[1], pose[2]))
        idx += 1
    cap.release()
    write_places(out_dir / "queries.csv", places)
    return places
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_query.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/query.py research/vpr_bench/tests/test_query.py
git commit -m "feat(vpr-bench): extract labelled query frames from video and GPX"
```

---

### Task 7: Реестр VPR-моделей

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/models.py`
- Test: `research/vpr_bench/tests/test_models.py`

**Interfaces:**
- Produces:
  - `ModelSpec(repo: str, entry: str, kwargs: dict, image_size: tuple[int, int])`. `image_size` задаётся как `(h, w)`.
  - `MODEL_SPECS: dict[str, ModelSpec]` с ключами `cosplace-r50`, `eigenplaces-r50`, `salad-dinov2`, `boq-dinov2`.
  - `VprModel(name: str, net: torch.nn.Module, image_size: tuple[int, int], device: str = "cpu")` с методами:
    - `.embed(images_bgr: list[np.ndarray]) -> np.ndarray` — результат `(N, D)` float32, L2-нормирован;
    - `.size_mb() -> float`.
  - `load_model(name: str, device: str = "cpu") -> VprModel`
  - `pick_device() -> str` — `"cuda"`, `"mps"` или `"cpu"`.

Замечание: MixVPR из спецификации не публикуется через `torch.hub`. Если после первого прогона понадобится, его добавляют отдельной записью в реестр с загрузкой чекпойнта вручную. В M0 он не входит.

Все четыре модели грузятся через `torch.hub.load(..., trust_repo=True)`, то есть выполняется код из этих GitHub-репозиториев. Перед первым запуском нужно просмотреть их `hubconf.py`.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_models.py`:
```python
import numpy as np
import pytest
import torch

from vpr_bench.models import MODEL_SPECS, VprModel, load_model


def _pool_net():
    return torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())


class TupleNet(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.inner = _pool_net()

    def forward(self, x):
        return self.inner(x), "attention-maps"


def _images(n):
    return [np.random.default_rng(i).integers(0, 255, (60, 80, 3), dtype=np.uint8) for i in range(n)]


def test_embed_returns_normalized_descriptors():
    model = VprModel("fake", _pool_net(), (32, 48))
    desc = model.embed(_images(2))
    assert desc.shape == (2, 3)
    assert desc.dtype == np.float32
    assert np.linalg.norm(desc, axis=1) == pytest.approx([1.0, 1.0], abs=1e-5)


def test_preprocess_resizes_to_model_size():
    model = VprModel("fake", _pool_net(), (32, 48))
    assert tuple(model.preprocess(_images(1)).shape) == (1, 3, 32, 48)


def test_embed_unwraps_tuple_output():
    desc = VprModel("fake", TupleNet(), (32, 32)).embed(_images(1))
    assert desc.shape == (1, 3)


def test_size_mb_counts_parameters():
    net = torch.nn.Linear(1000, 1000)  # ≈ 1 001 000 float32 = 4.004 МБ
    assert VprModel("lin", net, (1, 1)).size_mb() == pytest.approx(4.004, rel=1e-3)


def test_registry_has_expected_models():
    assert set(MODEL_SPECS) == {"cosplace-r50", "eigenplaces-r50", "salad-dinov2", "boq-dinov2"}


def test_load_unknown_model_raises():
    with pytest.raises(KeyError):
        load_model("nope")


@pytest.mark.slow
@pytest.mark.parametrize("name", sorted(MODEL_SPECS))
def test_real_model_embeds(name):
    model = load_model(name)
    desc = model.embed(_images(1))
    assert desc.shape[0] == 1 and desc.shape[1] >= 256
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_models.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.models'`

- [ ] **Step 3: Реализовать `models.py`**

`research/vpr_bench/src/vpr_bench/models.py`:
```python
"""Реестр VPR-моделей (torch.hub) и единый интерфейс получения глобальных дескрипторов."""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np
import torch

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


@dataclass(frozen=True)
class ModelSpec:
    repo: str
    entry: str
    kwargs: dict
    image_size: tuple[int, int]  # (h, w)


MODEL_SPECS: dict[str, ModelSpec] = {
    "cosplace-r50": ModelSpec(
        "gmberton/cosplace", "get_trained_model", {"backbone": "ResNet50", "fc_output_dim": 2048}, (480, 640)
    ),
    "eigenplaces-r50": ModelSpec(
        "gmberton/eigenplaces", "get_trained_model", {"backbone": "ResNet50", "fc_output_dim": 2048}, (480, 640)
    ),
    "salad-dinov2": ModelSpec("serizba/salad", "dinov2_salad", {}, (322, 322)),
    "boq-dinov2": ModelSpec(
        "amaralibey/bag-of-queries", "get_trained_boq", {"backbone_name": "dinov2", "output_dim": 12288}, (322, 322)
    ),
}


class VprModel:
    def __init__(self, name: str, net: torch.nn.Module, image_size: tuple[int, int], device: str = "cpu"):
        self.name = name
        self.net = net.eval().to(device)
        self.image_size = image_size
        self.device = device

    def preprocess(self, images_bgr: list[np.ndarray]) -> torch.Tensor:
        h, w = self.image_size
        batch = []
        for img in images_bgr:
            resized = cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA)
            rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
            batch.append((rgb - IMAGENET_MEAN) / IMAGENET_STD)
        arr = np.ascontiguousarray(np.stack(batch).transpose(0, 3, 1, 2))
        return torch.from_numpy(arr).to(self.device)

    @torch.inference_mode()
    def embed(self, images_bgr: list[np.ndarray]) -> np.ndarray:
        out = self.net(self.preprocess(images_bgr))
        if isinstance(out, (tuple, list)):
            out = out[0]
        out = torch.nn.functional.normalize(out.float().flatten(1), dim=1)
        return out.cpu().numpy().astype(np.float32)

    def size_mb(self) -> float:
        return sum(p.numel() * p.element_size() for p in self.net.parameters()) / 1e6


def pick_device() -> str:
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def load_model(name: str, device: str = "cpu") -> VprModel:
    if name not in MODEL_SPECS:
        raise KeyError(f"unknown model {name!r}; known: {sorted(MODEL_SPECS)}")
    spec = MODEL_SPECS[name]
    net = torch.hub.load(spec.repo, spec.entry, trust_repo=True, **spec.kwargs)
    return VprModel(name, net, spec.image_size, device)
```

- [ ] **Step 4: Убедиться, что быстрые тесты проходят**

Run: `uv run pytest tests/test_models.py -v`
Expected: 6 passed, 4 deselected

- [ ] **Step 5: Проверить реальные модели (нужна сеть, скачивается ≈ 1.5 ГБ)**

Run: `uv run pytest tests/test_models.py -m slow -v`
Expected: 4 passed.

Если какой-то репозиторий поменял сигнатуру hub-функции, нужно открыть его `hubconf.py`, поправить `kwargs` в `MODEL_SPECS` и зафиксировать это в сообщении коммита.

- [ ] **Step 6: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/models.py research/vpr_bench/tests/test_models.py
git commit -m "feat(vpr-bench): add VPR model registry via torch.hub"
```

---

### Task 8: Гео-индекс

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/index.py`
- Test: `research/vpr_bench/tests/test_index.py`

**Interfaces:**
- Consumes: `haversine_m_vec` (Task 1).
- Produces:
  - `GeoIndex(descriptors: np.ndarray, lats: np.ndarray, lons: np.ndarray)` с атрибутами `.descriptors`, `.lats`, `.lons`.
  - `GeoIndex.search(query: np.ndarray, k: int, center: tuple[float, float] | None = None, radius_m: float | None = None) -> np.ndarray`. Возвращает индексы эталонов по убыванию косинусной близости. Если заданы `center` и `radius_m`, поиск идёт только среди эталонов в этом радиусе.

Используется полный перебор в numpy: для базы в десятки тысяч эталонов этого достаточно. FAISS или HNSW понадобятся в M1 на устройстве.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_index.py`:
```python
import numpy as np
import pytest

from vpr_bench.index import GeoIndex

LATS = np.array([55.7500, 55.7509, 55.7518])  # шаг ≈ 100 м
LONS = np.array([37.6, 37.6, 37.6])


def _index():
    return GeoIndex(np.eye(3, dtype=np.float32), LATS, LONS)


def test_search_orders_by_similarity():
    q = np.array([0.0, 0.6, 0.8], dtype=np.float32)
    assert _index().search(q, k=2).tolist() == [2, 1]


def test_search_respects_geo_prefilter():
    q = np.array([0.0, 0.0, 1.0], dtype=np.float32)
    got = _index().search(q, k=3, center=(LATS[0], LONS[0]), radius_m=50)
    assert got.tolist() == [0]


def test_search_empty_when_nothing_in_radius():
    got = _index().search(np.ones(3, np.float32), k=3, center=(0.0, 0.0), radius_m=10)
    assert got.size == 0


def test_mismatched_lengths_raise():
    with pytest.raises(ValueError):
        GeoIndex(np.eye(3, dtype=np.float32), LATS[:2], LONS)
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_index.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.index'`

- [ ] **Step 3: Реализовать `index.py`**

`research/vpr_bench/src/vpr_bench/index.py`:
```python
"""Поиск ближайших эталонов по дескриптору с опциональным гео-префильтром."""
from __future__ import annotations

import numpy as np

from vpr_bench.geo import haversine_m_vec


class GeoIndex:
    def __init__(self, descriptors: np.ndarray, lats: np.ndarray, lons: np.ndarray):
        self.descriptors = np.asarray(descriptors, dtype=np.float32)
        self.lats = np.asarray(lats, dtype=np.float64)
        self.lons = np.asarray(lons, dtype=np.float64)
        if not (len(self.descriptors) == len(self.lats) == len(self.lons)):
            raise ValueError("descriptors, lats and lons must have equal length")

    def search(
        self,
        query: np.ndarray,
        k: int,
        center: tuple[float, float] | None = None,
        radius_m: float | None = None,
    ) -> np.ndarray:
        candidates = np.arange(len(self.descriptors))
        if center is not None and radius_m is not None:
            dist = haversine_m_vec(center[0], center[1], self.lats, self.lons)
            candidates = candidates[dist <= radius_m]
        if candidates.size == 0:
            return candidates
        sims = self.descriptors[candidates] @ np.asarray(query, dtype=np.float32)
        k = min(k, candidates.size)
        top = np.argpartition(-sims, k - 1)[:k]
        top = top[np.argsort(-sims[top])]
        return candidates[top]
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_index.py -v`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/index.py research/vpr_bench/tests/test_index.py
git commit -m "feat(vpr-bench): add brute-force geo-filtered descriptor index"
```

---

### Task 9: Метрики

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/evaluate.py`
- Test: `research/vpr_bench/tests/test_evaluate.py`

**Interfaces:**
- Consumes: `GeoIndex` (Task 8); `haversine_m_vec`, `offset_m` (Task 1).
- Produces:
  - `EvalResult(model: str, setting: str, n_queries: int, recall: dict[int, float], median_err_m: float, p95_err_m: float)`
  - `evaluate(index, q_desc, q_lats, q_lons, model, setting, ks=(1, 5, 10), threshold_m=25.0, prior_radius_m=None, prior_noise_m=0.0, seed=0) -> EvalResult`

Что считается:
- **Recall@K** — доля запросов, у которых среди top-K есть эталон ближе `threshold_m`.
- **Ошибка** — расстояние от истинной позиции до top-1 эталона. Если в радиусе не нашлось ни одного эталона, ошибка равна `inf`.
- **Режим с априорной оценкой.** Центр окна поиска = истинная позиция + гауссов шум с σ = `prior_noise_m`. Так имитируется неопределённость фильтра.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_evaluate.py`:
```python
import numpy as np
import pytest

from vpr_bench.evaluate import evaluate
from vpr_bench.index import GeoIndex

LATS = np.array([55.7500, 55.7509, 55.7518])
LONS = np.array([37.6, 37.6, 37.6])


def _setup():
    index = GeoIndex(np.eye(3, dtype=np.float32), LATS, LONS)
    q1 = np.array([0.0, 0.6, 0.8], dtype=np.float32)  # похож на эталон 2, стоит у эталона 1
    q_desc = np.stack([np.array([1.0, 0, 0], np.float32), q1])
    return index, q_desc, LATS[:2], LONS[:2]


def test_global_recall_and_errors():
    index, q_desc, q_lats, q_lons = _setup()
    r = evaluate(index, q_desc, q_lats, q_lons, "m", "global", ks=(1, 2))
    assert r.n_queries == 2
    assert r.recall == {1: 0.5, 2: 1.0}
    assert r.median_err_m == pytest.approx(50.0, rel=0.02)


def test_prior_window_removes_wrong_candidate():
    index, q_desc, q_lats, q_lons = _setup()
    r = evaluate(index, q_desc, q_lats, q_lons, "m", "prior", ks=(1,), prior_radius_m=50, prior_noise_m=0.0)
    assert r.recall == {1: 1.0}
    assert r.p95_err_m == pytest.approx(0.0, abs=1e-6)


def test_empty_queries_raise():
    index, *_ = _setup()
    with pytest.raises(ValueError):
        evaluate(index, np.zeros((0, 3), np.float32), np.array([]), np.array([]), "m", "global")
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_evaluate.py -v`
Expected: FAIL с `ModuleNotFoundError: No module named 'vpr_bench.evaluate'`

- [ ] **Step 3: Реализовать `evaluate.py`**

`research/vpr_bench/src/vpr_bench/evaluate.py`:
```python
"""Метрики VPR: Recall@K с порогом расстояния и ошибка позиции top-1."""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from vpr_bench.geo import haversine_m_vec, offset_m
from vpr_bench.index import GeoIndex


@dataclass(frozen=True)
class EvalResult:
    model: str
    setting: str
    n_queries: int
    recall: dict[int, float]
    median_err_m: float
    p95_err_m: float


def evaluate(
    index: GeoIndex,
    q_desc: np.ndarray,
    q_lats: np.ndarray,
    q_lons: np.ndarray,
    model: str,
    setting: str,
    ks: tuple[int, ...] = (1, 5, 10),
    threshold_m: float = 25.0,
    prior_radius_m: float | None = None,
    prior_noise_m: float = 0.0,
    seed: int = 0,
) -> EvalResult:
    n = len(q_desc)
    if n == 0:
        raise ValueError("no queries to evaluate")
    rng = np.random.default_rng(seed)
    kmax = max(ks)
    hits = {k: 0 for k in ks}
    errors: list[float] = []
    for i in range(n):
        lat, lon = float(q_lats[i]), float(q_lons[i])
        center = None
        if prior_radius_m is not None:
            east, north = rng.normal(0.0, prior_noise_m, 2) if prior_noise_m > 0 else (0.0, 0.0)
            center = offset_m(lat, lon, east, north)
        top = index.search(q_desc[i], kmax, center=center, radius_m=prior_radius_m)
        if top.size == 0:
            errors.append(math.inf)
            continue
        dists = haversine_m_vec(lat, lon, index.lats[top], index.lons[top])
        errors.append(float(dists[0]))
        for k in ks:
            if np.any(dists[:k] <= threshold_m):
                hits[k] += 1
    errs = np.array(errors)
    return EvalResult(
        model=model,
        setting=setting,
        n_queries=n,
        recall={k: hits[k] / n for k in ks},
        median_err_m=float(np.median(errs)),
        p95_err_m=float(np.percentile(errs, 95)),
    )
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `uv run pytest tests/test_evaluate.py -v`
Expected: 3 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/evaluate.py research/vpr_bench/tests/test_evaluate.py
git commit -m "feat(vpr-bench): add Recall@K and localization error metrics"
```

---

### Task 10: Прогон бенчмарка, отчёт и CLI

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/report.py`
- Create: `research/vpr_bench/src/vpr_bench/pipeline.py`
- Create: `research/vpr_bench/src/vpr_bench/cli.py`
- Test: `research/vpr_bench/tests/test_report.py`
- Test: `research/vpr_bench/tests/test_pipeline.py`
- Test: `research/vpr_bench/tests/test_cli.py`

**Interfaces:**
- Consumes: всё из Tasks 1–9.
- Produces:
  - `render_report(results: list[EvalResult], meta: dict[str, dict], target_recall5: float = 0.85) -> str`. `meta[name]` содержит ключи `dim`, `size_mb`, `ms_per_image`.
  - `SETTINGS: list[tuple[str, float | None, float]]`. Каждый элемент — `(setting, prior_radius_m, prior_noise_m)`. Значение: `[("global", None, 0.0), ("prior-500m", 500.0, 100.0)]`.
  - `embed_places(model: VprModel, places: list[Place], root: Path, batch_size: int = 16) -> tuple[np.ndarray, float]`. Возвращает дескрипторы и среднее время на кадр в мс; время включает чтение файла.
  - `run_benchmark(model_names, refs_csv: Path, queries_csv: Path, loader=load_model, device="cpu") -> tuple[list[EvalResult], dict[str, dict]]`
  - `cli.main(argv: list[str] | None = None) -> int` с подкомандами `fetch-refs`, `extract-queries`, `bench`.

- [ ] **Step 1: Написать падающие тесты**

`research/vpr_bench/tests/test_report.py`:
```python
from vpr_bench.evaluate import EvalResult
from vpr_bench.report import render_report


def test_report_marks_target():
    results = [
        EvalResult("good", "prior-500m", 100, {1: 0.8, 5: 0.91, 10: 0.95}, 4.2, 18.0),
        EvalResult("bad", "prior-500m", 100, {1: 0.5, 5: 0.7, 10: 0.8}, 30.0, 400.0),
    ]
    meta = {
        "good": {"dim": 2048, "size_mb": 100.5, "ms_per_image": 42.0},
        "bad": {"dim": 512, "size_mb": 20.0, "ms_per_image": 10.0},
    }
    md = render_report(results, meta)
    assert "| good | prior-500m | 100 | 80.0 | 91.0 | 95.0 | 4.2 | 18.0 | 2048 | 100.5 | 42.0 | ✅ |" in md
    assert "| bad |" in md and "❌" in md
```

`research/vpr_bench/tests/test_pipeline.py`:
```python
import cv2
import numpy as np
import torch

from vpr_bench.dataset import Place, write_places
from vpr_bench.models import VprModel
from vpr_bench.pipeline import SETTINGS, run_benchmark

COLORS = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
LATS = [55.7500, 55.7509, 55.7518]


def _make_set(root, name):
    places = []
    for i, color in enumerate(COLORS):
        rel = f"images/{i}.jpg"
        (root / "images").mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(root / rel), np.full((48, 64, 3), color, np.uint8))
        places.append(Place(rel, LATS[i], 37.6, 0.0))
    write_places(root / name, places)
    return root / name


def _fake_loader(name, device):
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    return VprModel(name, net, (32, 32), device)


def test_run_benchmark_perfect_match(tmp_path):
    refs = _make_set(tmp_path / "refs", "refs.csv")
    queries = _make_set(tmp_path / "queries", "queries.csv")
    results, meta = run_benchmark(["fake"], refs, queries, loader=_fake_loader)
    assert [r.setting for r in results] == [s[0] for s in SETTINGS]
    assert all(r.recall[1] == 1.0 for r in results)
    assert meta["fake"]["dim"] == 3
    assert meta["fake"]["ms_per_image"] > 0
```

`research/vpr_bench/tests/test_cli.py`:
```python
from vpr_bench.cli import build_parser, main


def test_parser_bench_args():
    args = build_parser().parse_args(
        ["bench", "--refs", "r.csv", "--queries", "q.csv", "--models", "a,b", "--out", "out"]
    )
    assert args.command == "bench"
    assert args.models == "a,b"


def test_fetch_refs_requires_token(monkeypatch, tmp_path, capsys):
    monkeypatch.delenv("MAPILLARY_TOKEN", raising=False)
    code = main(["fetch-refs", "--bbox", "37.6,55.74,37.62,55.76", "--out", str(tmp_path)])
    assert code == 2
    assert "MAPILLARY_TOKEN" in capsys.readouterr().err
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `uv run pytest tests/test_report.py tests/test_pipeline.py tests/test_cli.py -v`
Expected: FAIL с `ModuleNotFoundError` для `vpr_bench.report`, `vpr_bench.pipeline` и `vpr_bench.cli`

- [ ] **Step 3: Реализовать `report.py`**

`research/vpr_bench/src/vpr_bench/report.py`:
```python
"""Markdown-отчёт по результатам бенчмарка."""
from __future__ import annotations

from vpr_bench.evaluate import EvalResult

HEADER = (
    "| Модель | Режим | N | R@1, % | R@5, % | R@10, % | Медиана, м | P95, м "
    "| Размерность | Размер, МБ | мс/кадр | R@5 ≥ цели |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def _pct(recall: dict[int, float], k: int) -> str:
    return f"{recall[k] * 100:.1f}" if k in recall else "—"


def render_report(results: list[EvalResult], meta: dict[str, dict], target_recall5: float = 0.85) -> str:
    lines = [
        "# Результаты бенчмарка VPR",
        "",
        f"Порог совпадения: 25 м. Цель M0: R@5 ≥ {target_recall5 * 100:.0f} %.",
        "",
        HEADER,
    ]
    for r in results:
        m = meta[r.model]
        ok = "✅" if r.recall.get(5, 0.0) >= target_recall5 else "❌"
        lines.append(
            f"| {r.model} | {r.setting} | {r.n_queries} | {_pct(r.recall, 1)} | {_pct(r.recall, 5)} "
            f"| {_pct(r.recall, 10)} | {r.median_err_m:.1f} | {r.p95_err_m:.1f} | {m['dim']} "
            f"| {m['size_mb']:.1f} | {m['ms_per_image']:.1f} | {ok} |"
        )
    return "\n".join(lines) + "\n"
```

- [ ] **Step 4: Реализовать `pipeline.py`**

`research/vpr_bench/src/vpr_bench/pipeline.py`:
```python
"""Прогон набора моделей по базе эталонов и кадрам-запросам."""
from __future__ import annotations

import time
from collections.abc import Callable
from pathlib import Path

import cv2
import numpy as np

from vpr_bench.dataset import Place, read_places
from vpr_bench.evaluate import EvalResult, evaluate
from vpr_bench.index import GeoIndex
from vpr_bench.models import VprModel, load_model

# (режим, радиус окна поиска, σ шума центра окна)
SETTINGS: list[tuple[str, float | None, float]] = [
    ("global", None, 0.0),
    ("prior-500m", 500.0, 100.0),
]


def embed_places(model: VprModel, places: list[Place], root: Path, batch_size: int = 16) -> tuple[np.ndarray, float]:
    chunks = []
    start = time.perf_counter()
    for i in range(0, len(places), batch_size):
        images = []
        for p in places[i : i + batch_size]:
            img = cv2.imread(str(root / p.path))
            if img is None:
                raise FileNotFoundError(root / p.path)
            images.append(img)
        chunks.append(model.embed(images))
    ms_per_image = (time.perf_counter() - start) * 1000 / max(1, len(places))
    return np.concatenate(chunks), ms_per_image


def run_benchmark(
    model_names: list[str],
    refs_csv: Path,
    queries_csv: Path,
    loader: Callable[[str, str], VprModel] = load_model,
    device: str = "cpu",
) -> tuple[list[EvalResult], dict[str, dict]]:
    refs = read_places(refs_csv)
    queries = read_places(queries_csv)
    q_lats = np.array([p.lat for p in queries])
    q_lons = np.array([p.lon for p in queries])
    results: list[EvalResult] = []
    meta: dict[str, dict] = {}
    for name in model_names:
        model = loader(name, device)
        ref_desc, _ = embed_places(model, refs, refs_csv.parent)
        q_desc, ms = embed_places(model, queries, queries_csv.parent)
        index = GeoIndex(ref_desc, [p.lat for p in refs], [p.lon for p in refs])
        meta[name] = {"dim": int(ref_desc.shape[1]), "size_mb": model.size_mb(), "ms_per_image": ms}
        for setting, radius, noise in SETTINGS:
            results.append(
                evaluate(index, q_desc, q_lats, q_lons, name, setting,
                         prior_radius_m=radius, prior_noise_m=noise)
            )
    return results, meta
```

- [ ] **Step 5: Реализовать `cli.py`**

`research/vpr_bench/src/vpr_bench/cli.py`:
```python
"""CLI: vpr-bench fetch-refs | extract-queries | bench."""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict
from datetime import datetime
from pathlib import Path

from vpr_bench.db_builder import ViewConfig, build_reference_db
from vpr_bench.geo import BBox
from vpr_bench.mapillary import MapillaryClient
from vpr_bench.models import pick_device
from vpr_bench.pipeline import run_benchmark
from vpr_bench.query import extract_query_frames, parse_gpx
from vpr_bench.report import render_report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-bench")
    sub = parser.add_subparsers(dest="command", required=True)

    f = sub.add_parser("fetch-refs", help="скачать эталоны Mapillary в bbox")
    f.add_argument("--bbox", required=True, help="min_lon,min_lat,max_lon,max_lat")
    f.add_argument("--out", required=True, type=Path)
    f.add_argument("--views", type=int, default=8)
    f.add_argument("--fov", type=float, default=90.0)

    q = sub.add_parser("extract-queries", help="нарезать видео поездки в кадры-запросы")
    q.add_argument("--video", required=True, type=Path)
    q.add_argument("--gpx", required=True, type=Path)
    q.add_argument("--video-start", required=True, help="ISO-время первого кадра, напр. 2026-09-20T10:00:03+03:00")
    q.add_argument("--every", type=float, default=1.0, help="шаг между кадрами, с")
    q.add_argument("--out", required=True, type=Path)

    b = sub.add_parser("bench", help="сравнить модели")
    b.add_argument("--refs", required=True, type=Path)
    b.add_argument("--queries", required=True, type=Path)
    b.add_argument("--models", required=True, help="через запятую, напр. eigenplaces-r50,salad-dinov2")
    b.add_argument("--out", required=True, type=Path)
    b.add_argument("--device", default=None)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "fetch-refs":
        token = os.environ.get("MAPILLARY_TOKEN")
        if not token:
            print("error: set MAPILLARY_TOKEN environment variable", file=sys.stderr)
            return 2
        places = build_reference_db(
            MapillaryClient(token), BBox.parse(args.bbox), args.out,
            ViewConfig(n_views=args.views, fov_deg=args.fov),
        )
        print(f"{len(places)} reference images -> {args.out / 'refs.csv'}")
        return 0

    if args.command == "extract-queries":
        track = parse_gpx(args.gpx)
        start = datetime.fromisoformat(args.video_start).timestamp()
        places = extract_query_frames(args.video, track, start, args.every, args.out)
        print(f"{len(places)} query frames -> {args.out / 'queries.csv'}")
        return 0

    results, meta = run_benchmark(
        [m.strip() for m in args.models.split(",") if m.strip()],
        args.refs, args.queries, device=args.device or pick_device(),
    )
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.md").write_text(render_report(results, meta))
    (args.out / "results.json").write_text(
        json.dumps({"results": [asdict(r) for r in results], "meta": meta}, ensure_ascii=False, indent=2)
    )
    print(f"report -> {args.out / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 6: Убедиться, что все тесты проходят**

Run: `uv run pytest -v`
Expected: все быстрые тесты зелёные (42 passed, 4 deselected)

- [ ] **Step 7: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/report.py research/vpr_bench/src/vpr_bench/pipeline.py research/vpr_bench/src/vpr_bench/cli.py research/vpr_bench/tests/test_report.py research/vpr_bench/tests/test_pipeline.py research/vpr_bench/tests/test_cli.py
git commit -m "feat(vpr-bench): add benchmark pipeline, markdown report and CLI"
```

---

### Task 11: Юридическая проверка источников снимков

Неинженерная задача. Результат — документ-запрос для юриста и зафиксированное решение. Блокирует решение о выходе из M0, но не блокирует Tasks 1–10.

**Files:**
- Create: `docs/research/legal-imagery.md`

- [ ] **Step 1: Создать документ с вопросами**

`docs/research/legal-imagery.md`:
```markdown
# Правовой статус источников уличных снимков для VisNav

Статус: запрос направлен юристу / ответ получен (нужное оставить)

## Сценарий использования
Приложение заранее загружает на Android-устройство снимки (или вычисленные из них
дескрипторы) вдоль маршрута пользователя и хранит их до поездки. Во время поездки
кадры камеры автомобиля сравниваются с этой базой, чтобы определить положение
при отсутствии GPS. Приложение коммерческое, распространяется через Google Play.

## Вопросы по Google Maps Platform (Street View Static API, Map Tiles API)
1. Разрешено ли предварительно загружать и хранить снимки Street View на устройстве дольше 30 дней?
2. Разрешено ли вычислять из снимков и хранить производные данные (векторы признаков)?
3. Разрешено ли использовать контент для навигации в реальном времени в приложении,
   где карта отрисовывается не Google (MapLibre + OSM)?
4. Существует ли отдельная лицензия или соглашение с Google, которое снимает эти ограничения, и через кого её запрашивать?

## Вопросы по Mapillary (CC BY-SA 4.0)
5. Распространяется ли share-alike на дескрипторы, вычисленные из снимков, и на само приложение?
6. Какой формат атрибуции обязателен в интерфейсе приложения?
7. Есть ли ограничения на коммерческое использование API v4 (лимиты, условия Meta)?

## Вопросы по KartaView
8. Те же вопросы 5–7.

## Вопросы по собственному сбору (v2)
9. Требования к согласию пользователя на отправку кадров и к обезличиванию (лица, номера) в целевых юрисдикциях.

## Решение
| Источник | Разрешено для сценария | Условия | Дата, кто подтвердил |
|---|---|---|---|
| Google Street View | | | |
| Mapillary | | | |
| KartaView | | | |
```

- [ ] **Step 2: Commit и передача заказчику**

```bash
cd /Users/vvnovg/navigator
git add docs/research/legal-imagery.md
git commit -m "docs: add imagery licensing questions for legal review"
```

Таблицу «Решение» заполняет заказчик по ответу юриста.

---

### Task 12: Протокол записи поездок

**Files:**
- Create: `docs/research/drive-protocol.md`

- [ ] **Step 1: Создать протокол**

`docs/research/drive-protocol.md`:
```markdown
# Протокол записи поездок для датасета M0

## Оборудование
- Смартфон на держателе у лобового стекла, камера смотрит вперёд, капот занимает ≤ 20 % кадра.
  Либо видеорегистратор, дающий файл MP4.
- GPS-трек пишется на том же смартфоне приложением GPSLogger for Android
  (формат GPX, интервал 1 с, «только GPS»). Для M0 достаточно обычного GPS
  при открытом небе: точность ~3–5 м заметно лучше порога 25 м. RTK — в M2.

## Синхронизация времени видео и трека
1. Включить запись видео.
2. Первые 3 секунды снимать экран телефона с часами, показывающими секунды
   (например, приложение GPS Status или time.is).
3. По первому кадру определить время начала видео и записать его в ISO-формате
   с часовым поясом в `sessions.csv`.

## Маршруты (минимум для M0)
| Сессия | Условия | Длина | Требование |
|---|---|---|---|
| S1 | день, город, центр | ≥ 15 км | улицы, где в Mapillary есть снимки (проверить на mapillary.com/app) |
| S2 | день, спальный район | ≥ 10 км | то же |
| S3 | вечер / ночь | ≥ 10 км | повтор части S1 |
| S4 | дождь или мокрая дорога | ≥ 10 км | повтор части S1 или S2 |

Итого ≥ 45 км. Из них после нарезки с шагом 1 с и отбрасывания стоянок должно получиться ≥ 2000 кадров-запросов.

## Раскладка файлов
research/vpr_bench/data/drives/<session>/video.mp4
research/vpr_bench/data/drives/<session>/track.gpx
research/vpr_bench/data/drives/sessions.csv   # session,video_start_iso,conditions,notes

## Проверка сессии
uv run vpr-bench extract-queries --video data/drives/S1/video.mp4 --gpx data/drives/S1/track.gpx \
  --video-start <из sessions.csv> --every 1 --out data/queries/S1
Выборочно открыть 5 кадров из data/queries/S1/images и сверить место на карте
по координатам из queries.csv. Расхождение > 20 м означает ошибку синхронизации: поправить video_start.
```

- [ ] **Step 2: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/drive-protocol.md
git commit -m "docs: add drive recording protocol for M0 dataset"
```

---

### Task 13: Прогон на реальных данных и решение о выходе из M0

Предусловия:
- Tasks 1–10 и 12 выполнены.
- Токен Mapillary получен владельцем проекта на mapillary.com/dashboard/developers (Client Token) и выставлен в окружении.
- Записаны сессии S1–S4.

**Files:**
- Create: `docs/research/m0-results.md`

- [ ] **Step 1: Собрать базу эталонов по bbox, покрывающему маршруты**

bbox строится по крайним точкам всех GPX-треков с запасом 0.005°.

```bash
cd /Users/vvnovg/navigator/research/vpr_bench
export MAPILLARY_TOKEN=...   # вводит владелец токена, в файлы не сохранять
uv run vpr-bench fetch-refs --bbox <min_lon,min_lat,max_lon,max_lat> --out data/refs
```

Expected: строка `N reference images -> data/refs/refs.csv`, где N > 0.

- [ ] **Step 2: Нарезать запросы для каждой сессии и объединить их**

```bash
for s in S1 S2 S3 S4; do
  uv run vpr-bench extract-queries --video data/drives/$s/video.mp4 --gpx data/drives/$s/track.gpx \
    --video-start "$(awk -F, -v s=$s '$1==s{print $2}' data/drives/sessions.csv)" --every 1 --out data/queries/$s
done
```

Для каждой сессии затем нужен отдельный прогон бенчмарка: так видна разница между днём, ночью и дождём.

- [ ] **Step 3: Запустить бенчмарк всех моделей по каждой сессии**

```bash
for s in S1 S2 S3 S4; do
  uv run vpr-bench bench --refs data/refs/refs.csv --queries data/queries/$s/queries.csv \
    --models cosplace-r50,eigenplaces-r50,salad-dinov2,boq-dinov2 --out data/results/$s
done
```

Expected: в каждом `data/results/<S>/` лежат `report.md` и `results.json`.

- [ ] **Step 4: Свести результаты в `docs/research/m0-results.md`**

Структура документа:
```markdown
# Итоги M0

## Данные
- База эталонов: N снимков Mapillary, bbox ..., даты съёмки от ... до ...
- Запросы: сессии S1–S4, всего M кадров

## Результаты
(вставить таблицы из data/results/S*/report.md)

## Выбор модели
Модель: ... Причины: R@5 в режиме prior-500m = ...; размер ...; время ...

## Критерий выхода M0
- [ ] R@5 ≥ 85 % (prior-500m, дневные сессии S1+S2) — факт: ...
- [ ] Источник снимков юридически подтверждён (docs/research/legal-imagery.md)

## Что переносится в M1
- квантизация выбранной модели в INT8 и замер на устройстве (цель ≤ 30 МБ, ≤ 60 мс)
- ...
```

Если R@5 < 85 %, в документ записываются кандидаты на улучшение:
- дообучение на своих данных;
- добавление видов с шагом 30° (`--views 12`);
- повторное ранжирование по локальным признакам (запланировано в M1).

Решение о переходе к M1 принимает заказчик.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m0-results.md
git commit -m "docs: record M0 benchmark results and model decision"
```
