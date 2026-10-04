# M3b: навигационный экран с офлайн-картой MapLibre — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Главный экран приложения — навигационный (SPEC §7):
  - карта без интернета;
  - маршрут;
  - маркер машины с кругом неопределённости в цвете режима;
  - панель следующего манёвра со стрелкой, расстоянием и улицей;
  - строка режима с «±N м»;
  - остаток пути;
  - камера следует за машиной курсом вверх, с возвратом после жестов пользователя.
- Отладочный экран M1 остаётся на второй вкладке.

**Architecture:**
- **Карта** — MapLibre Native Android, вариант OpenGL ES (`org.maplibre.gl:android-sdk-opengl`, BSD-2).
- **Подготовка данных на ПК:**
  - planetiler строит векторные тайлы коридора по схеме OpenMapTiles в `.mbtiles` из той же выгрузки OSM, что и граф дорог;
  - `vpr-m3 pack-map` удаляет тайлы вне коридора (масштабы ≥ 10), проверяет файл и кладёт рядом глифы шрифта Noto Sans (латиница и кириллица) из релиза openmaptiles/fonts;
  - результат — каталог `map/`, который кладётся на телефон через adb.
- **Стиль и геометрия в `:core`** (строки JSON, покрытые тестами, без Android):
  - стиль собирается в коде: источник `mbtiles://…`, глифы `file://…`, слои дорог, воды, зданий и подписей, дневная и ночная палитры, плюс слои маршрута и маркера на GeoJSON;
  - геометрия маршрута, круга неопределённости и стрелки курса строится в GeoJSON;
  - политика камеры: масштаб по скорости, курс вверх, режимы «следовать» и «свободно».
- **Приложение:**
  - Compose-обёртка `MapView` с передачей жизненного цикла;
  - вкладки «Навигация» и «Отладка»;
  - без интернета: разрешения, которые приносит MapLibre, удаляются из манифеста, и это проверяет отдельная задача Gradle.
- **Атрибуция:** «© OpenMapTiles © участники OpenStreetMap» показывается поверх карты всегда.

**Tech Stack:** Kotlin 2.1, Android (Compose, MapLibre Native 13.6.1 OpenGL), Python 3.11+ (sqlite3, zipfile, numpy), planetiler 0.10.x (Java 21, запускает владелец), JUnit, pytest.

## Global Constraints

- **Решения владельца (2026-10-04):**
  - карта — MapLibre с офлайн-тайлами;
  - главный экран — навигационный, отладка — на второй вкладке.
- **Без интернета.** В итоговом (merged) манифесте приложения нет `android.permission.INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`: они удаляются через `tools:node="remove"`. Задача Gradle `verifyNoNetworkPermissions` падает, если они есть.
- **Зависимость:** `org.maplibre.gl:android-sdk-opengl:13.6.1` (BSD-2-Clause). Других новых зависимостей в приложении нет. Шрифты — Noto Sans (OFL) из openmaptiles/fonts v2.0; в репозиторий не коммитятся.
- **Данные карты на телефоне** — каталог `files/refpack/map/`:
  - `corridor.mbtiles` — векторные тайлы OpenMapTiles, `format=pbf`;
  - `fonts/<fontstack>/<range>.pbf` — для `Noto Sans Regular` и `Noto Sans Bold`, диапазоны `0-255`, `256-511`, `1024-1279`, `8192-8447`;
  - `map.json` — метаданные: `created_at`, `bounds [minlon,minlat,maxlon,maxlat]`, `minzoom`, `maxzoom`, `tiles`, `bytes`, `attribution`, `fontstacks`, `buffer_m`.
- **Атрибуция** на экране всегда, пока карта видна: «© OpenMapTiles © участники OpenStreetMap». Логотип и встроенная кнопка атрибуции MapLibre отключены: они ведут в интернет.
- **Цвета режимов** (SPEC §7, как в M2c):
  - `GNSS` и `FUSED` — `#2E7D32`;
  - `VISUAL` — `#1565C0`;
  - `DEAD_RECKONING` — `#EF6C00`.

  Круг неопределённости — заливка цветом режима с прозрачностью 0,2 и контур.
- **Камера:**
  - масштаб по скорости v: меньше 8 м/с — 17; меньше 15 — 16; меньше 25 — 15,5; иначе 15;
  - курс вверх при v ≥ 3 м/с, иначе держится последний;
  - наклон 0;
  - жест пользователя переводит камеру в режим «свободно»; обратно — по кнопке «В центр» или через 10 с без жестов;
  - при старте приложения без позиции камера показывает центр `bounds` карты.
- **NFR-8:** размер `corridor.mbtiles` отчётом сравнивается с целью ≤ 50 МБ на 100 км коридора (вместе с эталонами; отчёт показывает долю карты).
- **Формат строки траектории, `.nav.jsonl`, `roadpack`** не меняются.
- **Android:** без Google Play Services, `minSdk 29`, только arm64-v8a. MapLibre 13 требует `minSdk ≥ 23`, условие выполнено.
- **FR-20:** изображения не сохраняются.
- **Команды Gradle** — из `/Users/vvnovg/navigator/android` с `JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"`.
- **Python** — из `research/vpr_bench`: `uv sync --extra dev --extra osm`, затем `uv run pytest -q`.
- **Коммиты** заканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Текущие счётчики тестов:** `:core` — 242, `:replay` — 38, Python — 235 passed / 4 deselected.

---

## Структура файлов

```
research/vpr_bench/src/vpr_bench/mapkit.py      Task 1  check_mbtiles, clip_mbtiles, extract_glyphs, write_map_meta
research/vpr_bench/src/vpr_bench/m3cli.py       Task 1  vpr-m3 pack-map
research/vpr_bench/tests/test_mapkit.py         Task 1

android/core/src/main/kotlin/io/visnav/core/
├── MapStyle.kt        Task 2  MapPalette, MapStyle.build(...)
├── MapGeometry.kt     Task 3  circlePolygon, headingArrow, lineString, points (GeoJSON)
└── CameraPolicy.kt    Task 3  CameraTarget, CameraPolicy

android/app/
├── build.gradle.kts                      Task 4  MapLibre, verifyNoNetworkPermissions
├── src/main/AndroidManifest.xml          Task 4  tools:node="remove" для сетевых разрешений
└── src/main/kotlin/io/visnav/app/
    ├── MapData.kt                        Task 4  поиск map/ и сборка стиля
    ├── MapViewCompose.kt                 Task 4  MapView в Compose с жизненным циклом
    ├── NavSession.kt, M1Controller.kt    Task 5  NavUi/UiState: позиция, маршрут, манёвр
    ├── NavScreen.kt                      Task 5  навигационный экран
    ├── ManeuverIcon.kt                   Task 5  стрелки манёвров (Canvas)
    └── MainActivity.kt                   Task 5  вкладки «Навигация» и «Отладка»

docs/research/m3b-map.md                  Task 6
```

---

### Task 1: `vpr-m3 pack-map` — подготовка офлайн-карты на ПК

**Files:**
- Create: `research/vpr_bench/src/vpr_bench/mapkit.py`
- Modify: `research/vpr_bench/src/vpr_bench/m3cli.py`: подкоманда `pack-map`.
- Test: `research/vpr_bench/tests/test_mapkit.py`

**Interfaces:**
- Consumes: `db_builder.Corridor(tracks, buffer_m)` с методами `.bbox()` и `.intersects(BBox)`; `geo.BBox`; `query.parse_gpx`, `query.clean_track`; `fieldlog.read_log`, `fieldlog.gps_track`.
- Produces:
  - `REQUIRED_LAYERS = ("transportation", "water", "building", "place", "transportation_name")`;
  - `GLYPH_RANGES = ("0-255", "256-511", "1024-1279", "8192-8447")`;
  - `FONTSTACKS = ("Noto Sans Regular", "Noto Sans Bold")`;
  - `ATTRIBUTION = "© OpenMapTiles © участники OpenStreetMap"`;
  - `@dataclass(frozen=True) MbtilesInfo(format, minzoom, maxzoom, bounds, layers, tiles, bytes)`;
  - `check_mbtiles(path) -> MbtilesInfo`:
    - читает таблицу `metadata` (`format`, `minzoom`, `maxzoom`, `bounds`, `json` → `vector_layers[].id`);
    - число тайлов берёт из `tiles` (таблица или представление);
    - `ValueError`, если `format != "pbf"` или нет слоя из `REQUIRED_LAYERS`;
  - `tile_bbox(z, x, y_tms) -> BBox` — схема TMS, `y_xyz = 2^z − 1 − y_tms`;
  - `clip_mbtiles(src, dst, corridor, min_clip_zoom=10) -> tuple[int, int]` (оставлено, удалено):
    - копирует файл и удаляет тайлы с `zoom ≥ min_clip_zoom`, чей bbox не пересекает коридор;
    - поддерживает обе схемы: обычную (`tiles` — таблица) и сжатую planetiler (`tiles` — представление над `tiles_shallow` и `tiles_data`). Во второй схеме удаляет из `tiles_shallow`, затем из `tiles_data` — записи, на которые больше никто не ссылается;
    - в конце `VACUUM`;
  - `extract_glyphs(fonts_zip, out_dir) -> list[str]`:
    - достаёт из zip файлы `<fontstack>/<range>.pbf` для `FONTSTACKS × GLYPH_RANGES`;
    - внутри zip путь может иметь любой префикс каталога; файл ищется по окончанию пути `"/<fontstack>/<range>.pbf"` или `"<fontstack>/<range>.pbf"`;
    - `ValueError` с перечнем недостающих файлов;
  - `write_map_meta(out_dir, info, buffer_m)`;
  - CLI: `vpr-m3 pack-map --mbtiles FILE --fonts-zip FILE [--gpx F]... [--log S.jsonl]... [--buffer-m 500] --out DIR`:
    - результат: `DIR/corridor.mbtiles`, `DIR/fonts/...`, `DIR/map.json`;
    - печатает размер и размер на 100 км трека (длина трека по haversine);
    - код 2 при ошибке проверки или отсутствии треков.

- [ ] **Step 1: Падающие тесты** — `tests/test_mapkit.py`:

```python
import json
import sqlite3
import zipfile

import pytest

from vpr_bench.db_builder import Corridor
from vpr_bench.geo import TrackPoint
from vpr_bench.m3cli import main
from vpr_bench.mapkit import (ATTRIBUTION, FONTSTACKS, GLYPH_RANGES, check_mbtiles, clip_mbtiles, extract_glyphs,
                              tile_bbox)

LAYERS = ["transportation", "water", "building", "place", "transportation_name", "landuse"]


def _lonlat_to_tile(lon, lat, z):
    import math
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return x, n - 1 - y  # TMS


def _mbtiles(path, compact=False, fmt="pbf", layers=LAYERS, tiles=()):
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    meta = {"format": fmt, "minzoom": "0", "maxzoom": "14", "bounds": "37.5,55.7,37.7,55.8",
            "json": json.dumps({"vector_layers": [{"id": l} for l in layers]})}
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    if compact:
        db.execute("CREATE TABLE tiles_shallow (zoom_level INT, tile_column INT, tile_row INT, tile_data_id INT)")
        db.execute("CREATE TABLE tiles_data (tile_data_id INT PRIMARY KEY, tile_data BLOB)")
        db.execute("CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_shallow "
                   "JOIN tiles_data USING (tile_data_id)")
        for i, (z, x, y) in enumerate(tiles):
            db.execute("INSERT INTO tiles_data VALUES (?, ?)", (i, b"x" * 100))
            db.execute("INSERT INTO tiles_shallow VALUES (?, ?, ?, ?)", (z, x, y, i))
    else:
        db.execute("CREATE TABLE tiles (zoom_level INT, tile_column INT, tile_row INT, tile_data BLOB)")
        db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", [(z, x, y, b"x" * 100) for z, x, y in tiles])
    db.commit()
    db.close()


def _corridor():
    track = [TrackPoint(1000.0 + i, 55.75 + 0.0005 * i, 37.60) for i in range(40)]
    return Corridor([track], 500.0)


def _tiles_near_and_far():
    near = [(z, *_lonlat_to_tile(37.60, 55.755, z)) for z in (8, 10, 12, 14)]
    far = [(z, *_lonlat_to_tile(38.50, 56.50, z)) for z in (8, 10, 12, 14)]
    return near, far


def test_tile_bbox_contains_its_point():
    x, y = _lonlat_to_tile(37.6, 55.75, 14)
    b = tile_bbox(14, x, y)
    assert b.min_lon <= 37.6 <= b.max_lon and b.min_lat <= 55.75 <= b.max_lat


def test_check_mbtiles_ok_and_errors(tmp_path):
    ok = tmp_path / "ok.mbtiles"
    _mbtiles(ok, tiles=[(0, 0, 0)])
    info = check_mbtiles(ok)
    assert info.format == "pbf" and info.maxzoom == 14 and "transportation" in info.layers and info.tiles == 1
    bad = tmp_path / "bad.mbtiles"
    _mbtiles(bad, fmt="png")
    with pytest.raises(ValueError, match="format"):
        check_mbtiles(bad)
    nolayer = tmp_path / "nolayer.mbtiles"
    _mbtiles(nolayer, layers=["water"])
    with pytest.raises(ValueError, match="transportation"):
        check_mbtiles(nolayer)


@pytest.mark.parametrize("compact", [False, True])
def test_clip_keeps_corridor_tiles_and_low_zooms(tmp_path, compact):
    near, far = _tiles_near_and_far()
    src = tmp_path / "src.mbtiles"
    _mbtiles(src, compact=compact, tiles=near + far)
    kept, removed = clip_mbtiles(src, tmp_path / "dst.mbtiles", _corridor())
    assert removed == 3 and kept == 5     # дальние z10, z12, z14 удалены; z8 оставлен в обоих
    db = sqlite3.connect(tmp_path / "dst.mbtiles")
    rows = set(db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"))
    assert set(near) <= rows and far[0] in rows and not (set(far[1:]) & rows)
    if compact:
        assert db.execute("SELECT COUNT(*) FROM tiles_data").fetchone()[0] == 5


def _fonts_zip(path, missing=()):
    with zipfile.ZipFile(path, "w") as z:
        for fs in FONTSTACKS:
            for r in GLYPH_RANGES + ("512-767",):
                if (fs, r) not in missing:
                    z.writestr(f"noto-sans/{fs}/{r}.pbf", b"glyph")


def test_extract_glyphs(tmp_path):
    zp = tmp_path / "f.zip"
    _fonts_zip(zp)
    files = extract_glyphs(zp, tmp_path / "fonts")
    assert len(files) == len(FONTSTACKS) * len(GLYPH_RANGES)
    assert (tmp_path / "fonts" / "Noto Sans Regular" / "1024-1279.pbf").read_bytes() == b"glyph"
    assert not (tmp_path / "fonts" / "Noto Sans Regular" / "512-767.pbf").exists()
    zp2 = tmp_path / "g.zip"
    _fonts_zip(zp2, missing={("Noto Sans Bold", "1024-1279")})
    with pytest.raises(ValueError, match="Noto Sans Bold/1024-1279"):
        extract_glyphs(zp2, tmp_path / "fonts2")


GPX = """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
<trkpt lat="55.7500" lon="37.6000"><time>2026-10-04T10:00:00Z</time></trkpt>
<trkpt lat="55.7700" lon="37.6000"><time>2026-10-04T10:03:00Z</time></trkpt>
</trkseg></trk></gpx>"""


def test_pack_map_cli(tmp_path):
    near, far = _tiles_near_and_far()
    _mbtiles(tmp_path / "in.mbtiles", tiles=near + far)
    _fonts_zip(tmp_path / "f.zip")
    (tmp_path / "t.gpx").write_text(GPX)
    out = tmp_path / "map"
    assert main(["pack-map", "--mbtiles", str(tmp_path / "in.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--gpx", str(tmp_path / "t.gpx"), "--out", str(out)]) == 0
    meta = json.loads((out / "map.json").read_text(encoding="utf-8"))
    assert meta["attribution"] == ATTRIBUTION and meta["tiles"] == 5 and meta["buffer_m"] == 500.0
    assert meta["fontstacks"] == list(FONTSTACKS) and (out / "corridor.mbtiles").is_file()
    assert main(["pack-map", "--mbtiles", str(tmp_path / "in.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--out", str(tmp_path / "m2")]) == 2
```

Run: `uv run pytest tests/test_mapkit.py -q`
Expected: FAIL (`ModuleNotFoundError: vpr_bench.mapkit`).

- [ ] **Step 2: Реализация** — `mapkit.py`:

```python
"""Офлайн-карта коридора: проверка и обрезка .mbtiles (OpenMapTiles), глифы Noto Sans, метаданные map.json."""
from __future__ import annotations

import json
import math
import shutil
import sqlite3
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from vpr_bench.db_builder import Corridor
from vpr_bench.geo import BBox

REQUIRED_LAYERS = ("transportation", "water", "building", "place", "transportation_name")
GLYPH_RANGES = ("0-255", "256-511", "1024-1279", "8192-8447")
FONTSTACKS = ("Noto Sans Regular", "Noto Sans Bold")
ATTRIBUTION = "© OpenMapTiles © участники OpenStreetMap"


@dataclass(frozen=True)
class MbtilesInfo:
    format: str
    minzoom: int
    maxzoom: int
    bounds: tuple[float, float, float, float]
    layers: tuple[str, ...]
    tiles: int
    bytes: int


def check_mbtiles(path: Path) -> MbtilesInfo:
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        meta = dict(db.execute("SELECT name, value FROM metadata"))
        fmt = meta.get("format", "")
        if fmt != "pbf":
            raise ValueError(f"{path}: format={fmt!r}, expected 'pbf' (vector tiles)")
        layers = tuple(l["id"] for l in json.loads(meta.get("json", "{}")).get("vector_layers", []))
        missing = [l for l in REQUIRED_LAYERS if l not in layers]
        if missing:
            raise ValueError(f"{path}: missing OpenMapTiles layers: {', '.join(missing)}")
        bounds = tuple(float(v) for v in meta.get("bounds", "-180,-85,180,85").split(","))
        tiles = db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
    finally:
        db.close()
    return MbtilesInfo(fmt, int(meta.get("minzoom", 0)), int(meta.get("maxzoom", 14)), bounds, layers, tiles,
                       path.stat().st_size)


def tile_bbox(z: int, x: int, y_tms: int) -> BBox:
    n = 2 ** z
    y = n - 1 - y_tms

    def lat(yy: int) -> float:
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))

    return BBox(x / n * 360.0 - 180.0, lat(y + 1), (x + 1) / n * 360.0 - 180.0, lat(y))


def clip_mbtiles(src: Path, dst: Path, corridor: Corridor, min_clip_zoom: int = 10) -> tuple[int, int]:
    shutil.copyfile(src, dst)
    db = sqlite3.connect(dst)
    try:
        kind = db.execute("SELECT type FROM sqlite_master WHERE name = 'tiles'").fetchone()[0]
        table = "tiles" if kind == "table" else "tiles_shallow"
        drop = [(z, x, y) for z, x, y in db.execute(
            f"SELECT zoom_level, tile_column, tile_row FROM {table} WHERE zoom_level >= ?", (min_clip_zoom,))
            if not corridor.intersects(tile_bbox(z, x, y))]
        db.executemany(f"DELETE FROM {table} WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?", drop)
        if table == "tiles_shallow":
            db.execute("DELETE FROM tiles_data WHERE tile_data_id NOT IN (SELECT tile_data_id FROM tiles_shallow)")
        kept = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()
    return kept, len(drop)


def extract_glyphs(fonts_zip: Path, out_dir: Path) -> list[str]:
    wanted = {f"{fs}/{r}.pbf" for fs in FONTSTACKS for r in GLYPH_RANGES}
    found: dict[str, str] = {}
    with zipfile.ZipFile(fonts_zip) as z:
        for name in z.namelist():
            for w in wanted:
                if name == w or name.endswith("/" + w):
                    found[w] = name
        missing = sorted(wanted - found.keys())
        if missing:
            raise ValueError(f"{fonts_zip}: missing glyphs: {', '.join(m[:-4] for m in missing)}")
        for w, name in found.items():
            target = out_dir / w
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(name))
    return sorted(found)


def write_map_meta(out_dir: Path, info: MbtilesInfo, buffer_m: float) -> Path:
    meta = {
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "bounds": list(info.bounds), "minzoom": info.minzoom, "maxzoom": info.maxzoom, "tiles": info.tiles,
        "bytes": info.bytes, "attribution": ATTRIBUTION, "fontstacks": list(FONTSTACKS), "buffer_m": buffer_m,
    }
    p = out_dir / "map.json"
    p.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return p
```

`m3cli.py`: подкоманда `pack-map` с аргументами из Interfaces и функция `_pack_map(args)`:
1. Собрать треки:
   - из `--gpx` — через `parse_gpx`;
   - из `--log` — через `clean_track(gps_track(read_log(p)[1]), max_hdop=None)[0]`.

   Если треков нет — `print("error: need at least one --gpx or --log track", file=sys.stderr)`, `return 2`.
2. `check_mbtiles(args.mbtiles)`; при `ValueError` — сообщение в stderr и `return 2`.
3. `out.mkdir(parents=True, exist_ok=True)`.
4. `kept, removed = clip_mbtiles(args.mbtiles, out / "corridor.mbtiles", Corridor(tracks, args.buffer_m))`.
5. `extract_glyphs(args.fonts_zip, out / "fonts")`; при `ValueError` — `return 2`.
6. `info = check_mbtiles(out / "corridor.mbtiles")`, затем `write_map_meta(out, info, args.buffer_m)`.
7. `km` — сумма `haversine_m` по соседним точкам всех треков, делённая на 1000.
8. `print(f"tiles kept={kept} removed={removed}, {info.bytes / 1e6:.1f} MB, {info.bytes / 1e6 / max(km, 1e-9) * 100:.1f} MB per 100 km -> {out}")`, `return 0`.

`main()` в `m3cli` — диспетчер по `args.command`: `nav-eval` — как сейчас, `pack-map` — `_pack_map`.

- [ ] **Step 3: Тесты зелёные.** Run: `uv run pytest -q` → не меньше 243 passed, 4 deselected.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add research/vpr_bench/src/vpr_bench/mapkit.py research/vpr_bench/src/vpr_bench/m3cli.py research/vpr_bench/tests/test_mapkit.py
git commit -m "feat(vpr-bench): vpr-m3 pack-map — clip OpenMapTiles mbtiles to the corridor, extract Noto glyphs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Стиль карты `MapStyle` в `:core`

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/MapStyle.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/MapStyleTest.kt`

**Interfaces:**
- Produces:
  - `data class MapPalette(background, water, park, building, roadMinor, roadMajor, roadMotorway, casing, label, labelHalo, route)` — все поля строки `#RRGGBB`. Константы `MapPalette.DAY` и `MapPalette.NIGHT`;
  - `object MapStyle`:
    - `ROUTE_SOURCE = "route"`, `MANEUVER_SOURCE = "maneuvers"`, `ACCURACY_SOURCE = "accuracy"`, `MARKER_SOURCE = "marker"`, `HEADING_SOURCE = "heading"`;
    - `fun build(mbtilesPath: String, fontsDir: String, palette: MapPalette): String` — JSON стиля MapLibre версии 8.

Состав стиля (порядок слоёв снизу вверх):
1. Источники:
   - `"omt": {"type":"vector","url":"mbtiles://<mbtilesPath>"}`;
   - пять GeoJSON-источников с пустыми `FeatureCollection`.

   Поле `"glyphs": "file://<fontsDir>/{fontstack}/{range}.pbf"`. Спрайта нет: значки не используются.
2. `background` — цвет фона.
3. `water` (fill, source-layer `water`).
4. `park` (fill, source-layer `park`, прозрачность 0,6).
5. `building` (fill, source-layer `building`, minzoom 14).
6. Дороги (source-layer `transportation`, line):
   - `road-casing` — контур под всеми дорогами классов `motorway`, `trunk`, `primary`, `secondary`, `tertiary`, `minor`, `service`;
   - `road-minor` — `minor` и `service`, ширина 2–8 по масштабу 13–18;
   - `road-major` — `primary`, `secondary`, `tertiary`, ширина 3–12;
   - `road-motorway` — `motorway`, `trunk`, ширина 4–14;
   - тоннели (`brunnel = tunnel`) — пунктир `[2,1]`.
7. `route` — линия, цвет `route`, ширина 5–14; `route-casing` — под ней, белая, на 2 px шире.
8. `maneuvers` (circle, радиус 5, белый с обводкой цвета маршрута).
9. `accuracy` — fill с `["get","color"]` и прозрачностью 0,2; `accuracy-outline` — line, тот же цвет.
10. `heading` — fill, цвет `["get","color"]`.
11. `marker` — circle, радиус 8, заливка `["get","color"]`, обводка белая 2.
12. Подписи:
    - `road-label` — symbol, source-layer `transportation_name`, `symbol-placement: line`, `text-field: ["coalesce",["get","name:ru"],["get","name"]]`, `text-font: ["Noto Sans Regular"]`, размер 11–14;
    - `place-label` — symbol, source-layer `place`, `text-font: ["Noto Sans Bold"]`, фильтр по классам `city`, `town`, `suburb`, `neighbourhood`.

    Цвет и ореол подписей — из палитры.

Подписи идут после маркера, чтобы улицы читались. Маркер выше маршрута.

- [ ] **Step 1: Падающие тесты** — `MapStyleTest.kt`:

```kotlin
package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapStyleTest {
    private fun style(p: MapPalette = MapPalette.DAY): JsonObject = Json.parseToJsonElement(
        MapStyle.build("/sdcard/Android/data/io.visnav.app/files/refpack/map/corridor.mbtiles",
            "/sdcard/Android/data/io.visnav.app/files/refpack/map/fonts", p),
    ).jsonObject

    @Test fun sourcesAndGlyphs() {
        val s = style()
        assertEquals(8, s["version"]!!.jsonPrimitive.content.toInt())
        val src = s["sources"]!!.jsonObject
        assertEquals("mbtiles:///sdcard/Android/data/io.visnav.app/files/refpack/map/corridor.mbtiles",
            src["omt"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        for (id in listOf(MapStyle.ROUTE_SOURCE, MapStyle.MANEUVER_SOURCE, MapStyle.ACCURACY_SOURCE,
            MapStyle.MARKER_SOURCE, MapStyle.HEADING_SOURCE)) {
            assertEquals("geojson", src[id]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertEquals("file:///sdcard/Android/data/io.visnav.app/files/refpack/map/fonts/{fontstack}/{range}.pbf",
            s["glyphs"]!!.jsonPrimitive.content)
        assertTrue("sprite" !in s)
    }

    @Test fun layerOrderAndSourceLayers() {
        val layers = style()["layers"]!!.jsonArray.map { it.jsonObject }
        val ids = layers.map { it["id"]!!.jsonPrimitive.content }
        val order = listOf("background", "water", "park", "building", "road-casing", "road-minor", "road-major",
            "road-motorway", "route-casing", "route", "maneuvers", "accuracy", "accuracy-outline", "heading", "marker",
            "road-label", "place-label")
        assertEquals(order, ids.filter { it in order })
        val byId = layers.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("transportation", byId["road-major"]!!["source-layer"]!!.jsonPrimitive.content)
        assertEquals("transportation_name", byId["road-label"]!!["source-layer"]!!.jsonPrimitive.content)
        val font = byId["road-label"]!!["layout"]!!.jsonObject["text-font"] as JsonArray
        assertEquals("Noto Sans Regular", font[0].jsonPrimitive.content)
    }

    @Test fun nightPaletteChangesBackground() {
        fun bg(p: MapPalette) = style(p)["layers"]!!.jsonArray[0].jsonObject["paint"]!!.jsonObject["background-color"]!!
            .jsonPrimitive.content
        assertEquals(MapPalette.DAY.background, bg(MapPalette.DAY))
        assertEquals(MapPalette.NIGHT.background, bg(MapPalette.NIGHT))
        assertTrue(MapPalette.DAY.background != MapPalette.NIGHT.background)
    }

    @Test fun pathsWithQuotesAreEscaped() {
        val s = Json.parseToJsonElement(MapStyle.build("/a\"b.mbtiles", "/f", MapPalette.DAY)).jsonObject
        assertEquals("mbtiles:///a\"b.mbtiles", s["sources"]!!.jsonObject["omt"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }
}
```

Run: `./gradlew :core:test --tests 'io.visnav.core.MapStyleTest'` → FAIL при компиляции.

- [ ] **Step 2: Реализация.**
  - Стиль собирается через `kotlinx.serialization.json.buildJsonObject` / `buildJsonArray` (зависимость уже есть в `:core`), результат — `toString()`.
  - Палитры:
    - `DAY`: фон `#F2EFE9`, вода `#AAD3DF`, парк `#CDEBB0`, здания `#D9D0C9`, дороги — `#FFFFFF`, `#FCD6A4`, `#E892A2`, контур `#BBBBBB`, подписи `#333333` с ореолом `#FFFFFF`, маршрут `#1E88E5`;
    - `NIGHT`: фон `#1E2126`, вода `#2B3A4A`, парк `#24332A`, здания `#2E3135`, дороги — `#3B4048`, `#6B5B3E`, `#7A4A55`, контур `#15171A`, подписи `#D0D0D0` с ореолом `#1E2126`, маршрут `#64B5F6`.
  - Ширину по масштабу задавать выражением `["interpolate",["exponential",1.5],["zoom"],z0,w0,z1,w1]`.

- [ ] **Step 3: Тесты зелёные.** Run: `./gradlew :core:test` → `:core` не меньше 246 тестов.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): MapLibre style for offline OpenMapTiles mbtiles with route and marker layers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Геометрия для карты и политика камеры в `:core`

**Files:**
- Create: `android/core/src/main/kotlin/io/visnav/core/MapGeometry.kt`
- Create: `android/core/src/main/kotlin/io/visnav/core/CameraPolicy.kt`
- Test: `android/core/src/test/kotlin/io/visnav/core/MapGeometryTest.kt`, `CameraPolicyTest.kt`

**Interfaces:**
- Produces:
  - `object MapGeometry`:
    - `fun circlePolygon(lat: Double, lon: Double, radiusM: Double, color: String, n: Int = 48): String` — Feature с `Polygon`; кольцо из `n + 1` точек, первая равна последней; свойство `color`;
    - `fun headingArrow(lat: Double, lon: Double, psiRad: Double, lengthM: Double, color: String): String` — Feature с треугольником: вершина впереди на `lengthM`, основание шириной `0,6·lengthM` сзади на `0,3·lengthM`;
    - `fun point(lat: Double, lon: Double, color: String): String` — Feature с `Point`;
    - `fun lineString(latLon: List<DoubleArray>): String` — Feature с `LineString`;
    - `fun points(latLon: List<DoubleArray>): String` — FeatureCollection из точек;
    - `fun empty(): String` — пустая `FeatureCollection`.

    Координаты в GeoJSON — `[lon, lat]`. Смещения в метрах переводятся через `Enu(lat, lon)` в точке.
  - `data class CameraTarget(val lat: Double, val lon: Double, val zoom: Double, val bearingDeg: Double, val follow: Boolean)`;
  - `class CameraPolicy(freeHoldMs: Long = 10_000)` с методами:
    - `fun onUserGesture(tMs: Long)`;
    - `fun recenter()`;
    - `fun update(tMs: Long, lat: Double, lon: Double, speedMps: Double, psiRad: Double): CameraTarget?`. Возвращает null в режиме «свободно»: камеру не трогать. Режим «свободно» заканчивается после `freeHoldMs` без жестов;
    - `fun zoomFor(speedMps: Double): Double` — правило из Global Constraints;
    - курс: при скорости ≥ 3 и конечном ψ — `Math.toDegrees(psi)`, нормированный к [0, 360); иначе последний. Начальный курс — 0.

- [ ] **Step 1: Падающие тесты.**

`MapGeometryTest.kt`:

```kotlin
package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapGeometryTest {
    private fun ring(feature: String) = Json.parseToJsonElement(feature).jsonObject["geometry"]!!.jsonObject["coordinates"]!!
        .jsonArray[0].jsonArray.map { val p = it.jsonArray; p[0].jsonPrimitive.double to p[1].jsonPrimitive.double }

    @Test fun circleIsClosedAndHasRadius() {
        val r = ring(MapGeometry.circlePolygon(55.75, 37.6, 30.0, "#2E7D32", n = 36))
        assertEquals(37, r.size); assertEquals(r.first(), r.last())
        for ((lon, lat) in r.dropLast(1)) assertEquals(30.0, Geo.haversineM(55.75, 37.6, lat, lon), 0.3)
        val props = Json.parseToJsonElement(MapGeometry.circlePolygon(55.75, 37.6, 30.0, "#2E7D32")).jsonObject["properties"]!!
        assertEquals("#2E7D32", props.jsonObject["color"]!!.jsonPrimitive.content)
    }

    @Test fun headingArrowPointsAlongPsi() {
        val r = ring(MapGeometry.headingArrow(55.75, 37.6, Math.PI / 2, 20.0, "#1565C0"))   // на восток
        val tip = r.maxBy { it.first }
        assertEquals(20.0, Geo.haversineM(55.75, 37.6, tip.second, tip.first), 0.3)
        assertEquals(55.75, tip.second, 1e-6)
        assertEquals(r.first(), r.last())
    }

    @Test fun lineAndPointsAreLonLat() {
        val line = Json.parseToJsonElement(MapGeometry.lineString(listOf(doubleArrayOf(55.7, 37.5), doubleArrayOf(55.8, 37.6))))
            .jsonObject["geometry"]!!.jsonObject
        assertEquals("LineString", line["type"]!!.jsonPrimitive.content)
        assertEquals(37.5, line["coordinates"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.double)
        val pts = Json.parseToJsonElement(MapGeometry.points(listOf(doubleArrayOf(55.7, 37.5)))).jsonObject
        assertEquals("FeatureCollection", pts["type"]!!.jsonPrimitive.content)
        assertEquals(1, pts["features"]!!.jsonArray.size)
        assertTrue(MapGeometry.empty().contains("\"features\":[]"))
    }
}
```

`CameraPolicyTest.kt`:

```kotlin
package io.visnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CameraPolicyTest {
    @Test fun zoomBySpeed() {
        val c = CameraPolicy()
        assertEquals(17.0, c.zoomFor(5.0)); assertEquals(16.0, c.zoomFor(10.0))
        assertEquals(15.5, c.zoomFor(20.0)); assertEquals(15.0, c.zoomFor(30.0))
    }

    @Test fun headingUpOnlyWhenMoving() {
        val c = CameraPolicy()
        assertEquals(90.0, c.update(0, 55.75, 37.6, 10.0, Math.PI / 2)!!.bearingDeg, 1e-9)
        assertEquals(90.0, c.update(500, 55.75, 37.6, 1.0, Math.PI)!!.bearingDeg, 1e-9)   // стоим — держим курс
        assertEquals(270.0, c.update(1000, 55.75, 37.6, 10.0, -Math.PI / 2)!!.bearingDeg, 1e-9)
    }

    @Test fun gestureFreesCameraUntilRecenterOrTimeout() {
        val c = CameraPolicy(freeHoldMs = 10_000)
        c.onUserGesture(1_000)
        assertNull(c.update(2_000, 55.75, 37.6, 10.0, 0.0))
        assertNotNull(c.update(11_001, 55.75, 37.6, 10.0, 0.0))
        c.onUserGesture(12_000)
        c.recenter()
        assertNotNull(c.update(12_500, 55.75, 37.6, 10.0, 0.0))
    }
}
```

Run: `./gradlew :core:test` → FAIL при компиляции.

- [ ] **Step 2: Реализация.**
  - `MapGeometry` использует `Enu(lat, lon).toLatLon(e, n)` для смещений и собирает JSON через `buildJsonObject`.
  - Точки круга: угол `2π·k/n` по часовой стрелке от севера, `e = r·sin(a)`, `n = r·cos(a)`.
  - Точки стрелки:
    - вершина — `(sin ψ, cos ψ)·L`;
    - основание — `−0,3·L` вдоль курса и `±0,3·L` поперёк по нормали `(cos ψ, −sin ψ)`.
  - `CameraPolicy` хранит `freeSinceMs: Long?` и `lastBearing`.

- [ ] **Step 3: Тесты зелёные.** Run: `./gradlew :core:test` → `:core` не меньше 252 тестов.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/core/src
git commit -m "feat(core): GeoJSON geometry for route, marker and accuracy circle; follow-camera policy

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: MapLibre в приложении без сетевых разрешений

**Files:**
- Modify: `android/gradle/libs.versions.toml`: `maplibre = "13.6.1"` и библиотека `maplibre-android = { module = "org.maplibre.gl:android-sdk-opengl", version.ref = "maplibre" }`.
- Modify: `android/app/build.gradle.kts`: `implementation(libs.maplibre.android)` и задача `verifyNoNetworkPermissions`.
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/kotlin/io/visnav/app/MapData.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/MapViewCompose.kt`

**Interfaces:**
- Consumes: `MapStyle`, `MapPalette` (Task 2).
- Produces:
  - `class MapData(val dir: File, val styleJson: (night: Boolean) -> String, val bounds: DoubleArray, val attribution: String)`;
  - `object MapDataLoader` с методом `fun load(refpackDir: File): Result<MapData>`:
    - ищет `map/corridor.mbtiles`, `map/fonts/Noto Sans Regular/0-255.pbf` и `map/map.json`;
    - при отсутствии — `Result.failure` с текстом «нет карты: …»;
    - `map.json` читается через `org.json.JSONObject`;
  - `@Composable fun MapLibreMap(modifier: Modifier, styleJson: String, onMapReady: (MapLibreMap) -> Unit, onUserGesture: () -> Unit)`:
    - создаёт `MapView`;
    - пересылает события жизненного цикла `LocalLifecycleOwner`: onCreate/onStart/onResume/onPause/onStop/onDestroy, а также `onLowMemory`;
    - загружает стиль через `Style.Builder().fromJson(styleJson)`;
    - отключает логотип и кнопку атрибуции (`uiSettings.isLogoEnabled = false`, `isAttributionEnabled = false`) и наклон;
    - жесты пользователя (`addOnCameraMoveStartedListener` с `REASON_API_GESTURE`) передаёт в `onUserGesture`.

  Точные имена API сверить по исходникам SDK 13.6.1 в кэше Gradle (`~/.gradle/caches/.../android-sdk-opengl-13.6.1-sources.jar` или javadoc). Если имя отличается, использовать актуальное и указать в отчёте.

- [ ] **Step 1: Зависимость и манифест.**
  - `MapLibre.getInstance(applicationContext)` вызывается в `MainActivity.onCreate` до `setContent`.
  - В манифест добавить `xmlns:tools="http://schemas.android.com/tools"` и строки:

```xml
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
```

- [ ] **Step 2: Проверка манифеста** в `app/build.gradle.kts`:

```kotlin
val verifyNoNetworkPermissions by tasks.registering {
    description = "Fails if the merged debug manifest requests network permissions (offline app)."
    dependsOn("processDebugMainManifest")
    doLast {
        val manifest = layout.buildDirectory.file("intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml").get().asFile
        check(manifest.isFile) { "merged manifest not found: $manifest" }
        val text = manifest.readText()
        val banned = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE")
        val found = banned.filter { text.contains("\"$it\"") }
        check(found.isEmpty()) { "network permissions in merged manifest: $found" }
    }
}
tasks.named("check") { dependsOn(verifyNoNetworkPermissions) }
```

  Путь к итоговому манифесту зависит от версии AGP. Если файла нет по этому пути, найти его в `app/build/intermediates` (`find … -name AndroidManifest.xml -path '*merged*debug*'`), поправить путь и указать это в отчёте.

  Проверить, что задача падает без строк `tools:node="remove"`: временно убрать их и запустить. Потом вернуть.

- [ ] **Step 3: `MapData.kt` и `MapViewCompose.kt`.**
  - Стиль: `MapStyle.build(File(dir, "corridor.mbtiles").absolutePath, File(dir, "fonts").absolutePath, if (night) MapPalette.NIGHT else MapPalette.DAY)`.
  - Ночь — по `isSystemInDarkTheme()`.
  - В `MapLibreMap` при смене `styleJson` (день или ночь) стиль загружается заново.

- [ ] **Step 4: Сборка.**

  Run: `./gradlew :core:test :replay:test :app:assembleDebug :app:lintDebug :app:verifyNoNetworkPermissions`

  Expected: `BUILD SUCCESSFUL`, 0 ошибок lint. Указать в отчёте новые предупреждения и прирост размера APK.

- [ ] **Step 5: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/gradle/libs.versions.toml android/app
git commit -m "feat(app): MapLibre (OpenGL) map view with offline style; network permissions removed and verified

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Навигационный экран и вкладки

**Files:**
- Modify: `android/app/src/main/kotlin/io/visnav/app/NavSession.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/M1Controller.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/NavScreen.kt`
- Create: `android/app/src/main/kotlin/io/visnav/app/ManeuverIcon.kt`
- Modify: `android/app/src/main/kotlin/io/visnav/app/MainActivity.kt`

**Interfaces:**
- Consumes:
  - `MapLibreMap` и `MapDataLoader` (Task 4);
  - `MapGeometry`, `CameraPolicy`, `MapStyle` (Task 2–3);
  - `RouteFollower`, `ManeuverType`, `Instructions`.
- Produces:
  - `NavUi` получает поля:
    - `nextType: ManeuverType?`, `nextExit: Int`, `nextStreet: String?`, `nextDistM: Double?`;
    - `remainingM: Double?`;
    - `routeVersion: Int` — увеличивается при каждом новом маршруте;
    - `routeLatLon: List<DoubleArray>`, `maneuverLatLon: List<DoubleArray>` — передаются только когда `routeVersion` изменился, иначе пустые списки, а экран хранит последние;
  - `UiState` получает `pos: MapPos?`, где `data class MapPos(val lat: Double, val lon: Double, val sigmaM: Double, val psiRad: Double, val speedMps: Double, val mode: NavMode)`. Обновляется на каждом кадре из `LocalizerOutput` и сбрасывается в тех же местах, что и `nav`;
  - `@Composable fun NavScreen(controller: M1Controller, permissionsGranted: Boolean)`;
  - `@Composable fun ManeuverIcon(type: ManeuverType, exit: Int, modifier: Modifier, color: Color)`.

Экран (горизонтальная ориентация):
- **Слева панель шириной 36 %:**
  - иконка манёвра 96 dp;
  - расстояние крупно: `Instructions.distanceText`; «Сейчас», если меньше 30 м;
  - улица;
  - вместо иконки — «Маршрут строится…», «Маршрут не найден» или «Вы прибыли» по `NavUi`;
  - если маршрут перестроен — «Маршрут перестроен»;
  - строка режима цветом режима с «±N м» и причинами, как сейчас;
  - «Осталось N км · ~M мин»;
  - строка «Дорога: …», как сейчас;
  - кнопки «Старт/Стоп» и «В центр» (видна в режиме «свободно»).
- **Справа карта:**
  - `MapLibreMap`, если карта загружена, иначе текст «Нет карты: …» и путь для `adb push`;
  - поверх карты в углу — атрибуция `MapData.attribution` мелким текстом на полупрозрачном фоне;
  - при старте без позиции камера показывает центр `bounds` с масштабом 13.
- **Обновление слоёв на кадре** (GeoJSON-источники стиля):
  - `marker` — `MapGeometry.point` в цвете режима;
  - `accuracy` — `circlePolygon(σ)`;
  - `heading` — `headingArrow`, длина `max(12, 2σ)` м, только при скорости ≥ 3;
  - при смене `routeVersion` — `route` и `maneuvers`.
- **Камера:** `CameraPolicy.update(...)`; если вернулся не null — `animateCamera` или `easeCamera` за 400 мс к цели. Жест — `onUserGesture`.
- **Обновления карты** выполняются на главном потоке из `LaunchedEffect`/`snapshotFlow` по состоянию, не чаще одного раза на кадр состояния.

`ManeuverIcon` рисует на Canvas стрелку: прямо, «держитесь» под 30°, поворот 90°, резкий поворот 135° и разворот — дугой. Кольцо — окружность со стрелкой выхода и номером съезда; финиш — флажок. Влево — зеркально.

`MainActivity`:
- `TabRow` с вкладками «Навигация» и «Отладка»; на первой `NavScreen`, на второй существующий `M1Screen`;
- обе вкладки используют один контроллер;
- выбранная вкладка хранится в `rememberSaveable`;
- `android:label` в манифесте — «VisNav».

- [ ] **Step 1: Данные для экрана.**
  - В `NavSession.onOutput` заполнить новые поля `NavUi` из `RouteFollower`:
    - `nextManeuver` → тип, номер съезда, улица;
    - `distanceToNextM`;
    - `remainingM = route.lengthM − progressM`.
  - `routeLatLon` — точки `route.points`, переведённые через `enu.toLatLon`; `maneuverLatLon` — манёвры без `DEPART`. Оба поля передаются только в обновлении, где сменилась версия маршрута.
  - В `M1Controller` выставлять `pos` из `LocalizerOutput` (`lat`, `lon`, `sigmaM`, `psiRad`, `speedMps`, `mode`) и `nav` в том же `_state.update`.

- [ ] **Step 2: `ManeuverIcon.kt`, `NavScreen.kt`, вкладки в `MainActivity`** — по описанию выше.

- [ ] **Step 3: Сборка.**

  Run: `./gradlew :core:test :replay:test :app:assembleDebug :app:lintDebug :app:verifyNoNetworkPermissions` → `BUILD SUCCESSFUL`, 0 ошибок lint. Новые предупреждения перечислить.

- [ ] **Step 4: Commit**

```bash
cd /Users/vvnovg/navigator
git add android/app/src
git commit -m "feat(app): navigation screen — offline map, route, accuracy circle, maneuver panel; debug tab kept

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Протокол M3b

**Files:**
- Create: `docs/research/m3b-map.md`
- Modify: `docs/SPEC.md`: в разделе 10а добавить строки про MapLibre (OpenGL, BSD-2), шрифты Noto Sans (OFL) и схему OpenMapTiles (CC-BY 4.0: атрибуция «© OpenMapTiles»). planetiler (Apache 2.0) работает только на ПК.

- [ ] **Step 1: Документ** `docs/research/m3b-map.md`:

````markdown
# M3b: офлайн-карта и навигационный экран

## Что нужно (делает владелец)
- Java 21 для planetiler: `brew install openjdk@21`.
- planetiler: `curl -L -o data/osm/planetiler.jar https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar`.
- Шрифты: `curl -L -o data/osm/noto-sans.zip https://github.com/openmaptiles/fonts/releases/download/v2.0/noto-sans.zip` (~60 МБ, OFL/Apache).
- Выгрузка OSM — как в M2b (`data/osm/moscow.osm.pbf`).

## 1. Тайлы коридора
```bash
cd /Users/vvnovg/navigator/research/vpr_bench
J21="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home/bin/java"
# границы — bbox коридора с запасом; planetiler сам скачает воду и границы (--download)
$J21 -Xmx4g -jar data/osm/planetiler.jar --download --osm-path=data/osm/moscow.osm.pbf \
  --bounds=37.30,55.55,37.95,55.95 --maxzoom=14 --output=data/m3b/moscow.mbtiles
S=data/m1/logs/session-<ms>-gps
uv run vpr-m3 pack-map --mbtiles data/m3b/moscow.mbtiles --fonts-zip data/osm/noto-sans.zip \
  --log $S.jsonl --buffer-m 500 --out data/m3b/map
```
`pack-map`:
- проверяет тайлы (векторные, слои OpenMapTiles);
- удаляет тайлы масштаба 10 и выше вне коридора ±500 м;
- кладёт глифы Noto Sans (латиница и кириллица) и `map.json`;
- печатает размер на 100 км (цель NFR-8 — ≤ 50 МБ на 100 км вместе с эталонами).

## 2. Телефон
```bash
adb push data/m3b/map /sdcard/Android/data/io.visnav.app/files/refpack/
```
- Вкладка «Навигация»:
  - карта, маршрут, маркер с кругом неопределённости в цвете режима;
  - стрелка следующего манёвра с расстоянием и улицей;
  - остаток пути.
- Камера:
  - следует курсом вверх, масштаб зависит от скорости;
  - после жеста пользователя карта свободна 10 с или до кнопки «В центр».
- Вкладка «Отладка» — прежний экран M1.

У приложения нет доступа в интернет: сетевые разрешения MapLibre удалены из манифеста, это проверяет `./gradlew :app:verifyNoNetworkPermissions`.

## Что проверить в поездке
- Карта открывается без сети (режим полёта).
- Подписи улиц по-русски.
- Карта не тормозит на 2 Гц обновлений.
- Круг неопределённости меняет цвет при смене режима.
- Камера не дёргается на стоянке.
- Атрибуция видна.

## Итоги (заполнить)
- Размер карты коридора и на 100 км: …
- Плавность (кадров в секунду, если видно) и нагрев: …
- Замечания по читаемости днём и ночью: …
````

- [ ] **Step 2: Commit**

```bash
cd /Users/vvnovg/navigator
git add docs/research/m3b-map.md docs/SPEC.md
git commit -m "docs: M3b offline map protocol; SPEC licenses for MapLibre, Noto Sans, OpenMapTiles

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
