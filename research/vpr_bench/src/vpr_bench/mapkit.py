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

REQUIRED_LAYERS = ("transportation", "water", "building", "park", "place", "transportation_name")
GLYPH_RANGES = ("0-255", "256-511", "1024-1279", "8192-8447", "8448-8703")  # 8448-8703: «№»
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
    db = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
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
    """Копия + VACUUM: на диске временно нужно около 3x размера исходника (src, dst и временная копия VACUUM).

    bounds в metadata результата заменяются bbox коридора (с буфером).
    """
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
        b = corridor.bbox()
        db.execute("DELETE FROM metadata WHERE name = 'bounds'")
        db.execute("INSERT INTO metadata VALUES ('bounds', ?)", (f"{b.min_lon},{b.min_lat},{b.max_lon},{b.max_lat}",))
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
