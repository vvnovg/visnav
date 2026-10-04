import json
import sqlite3
import zipfile

import pytest

from vpr_bench.db_builder import Corridor
from vpr_bench.geo import TrackPoint
from vpr_bench.m3cli import main
from vpr_bench.mapkit import (ATTRIBUTION, FONTSTACKS, GLYPH_RANGES, check_mbtiles, clip_mbtiles, extract_glyphs,
                              tile_bbox)

LAYERS = ["transportation", "water", "building", "park", "place", "transportation_name", "landuse"]


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
    assert main(["pack-map", "--mbtiles", str(tmp_path / "in.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--log", str(tmp_path / "missing.jsonl"), "--out", str(tmp_path / "m3")]) == 2


def test_pack_map_bounds_are_corridor(tmp_path):
    near, far = _tiles_near_and_far()
    _mbtiles(tmp_path / "in.mbtiles", tiles=near + far)
    _fonts_zip(tmp_path / "f.zip")
    (tmp_path / "t.gpx").write_text(GPX)
    out = tmp_path / "map"
    assert main(["pack-map", "--mbtiles", str(tmp_path / "in.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--gpx", str(tmp_path / "t.gpx"), "--out", str(out)]) == 0
    b = json.loads((out / "map.json").read_text(encoding="utf-8"))["bounds"]
    box = Corridor([[TrackPoint(0.0, 55.75, 37.60), TrackPoint(1.0, 55.77, 37.60)]], 500.0).bbox()
    assert box.min_lon - 1e-6 <= b[0] and box.min_lat - 1e-6 <= b[1] and b[2] <= box.max_lon + 1e-6 and b[3] <= box.max_lat + 1e-6
    assert (b[2] - b[0]) < 0.1 * (37.7 - 37.5) and (b[3] - b[1]) < 0.5 * (55.8 - 55.7)
    db = sqlite3.connect(out / "corridor.mbtiles")
    assert db.execute("SELECT value FROM metadata WHERE name='bounds'").fetchone()[0].split(",")[0] == str(b[0])


def test_pack_map_errors_return_2(tmp_path):
    _mbtiles(tmp_path / "in.mbtiles", tiles=[(0, 0, 0)])
    _fonts_zip(tmp_path / "f.zip")
    (tmp_path / "t.gpx").write_text(GPX)
    (tmp_path / "bad.zip").write_bytes(b"not a zip")
    base = ["--gpx", str(tmp_path / "t.gpx")]
    assert main(["pack-map", "--mbtiles", str(tmp_path / "missing.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--out", str(tmp_path / "o1"), *base]) == 2
    assert main(["pack-map", "--mbtiles", str(tmp_path / "in.mbtiles"), "--fonts-zip", str(tmp_path / "bad.zip"),
                 "--out", str(tmp_path / "o2"), *base]) == 2
    assert not (tmp_path / "o2" / "corridor.mbtiles").exists()


def test_clip_compact_shared_blob_survives(tmp_path):
    near, far = _tiles_near_and_far()
    src = tmp_path / "src.mbtiles"
    _mbtiles(src, compact=True, tiles=[near[3]])
    db = sqlite3.connect(src)
    db.execute("INSERT INTO tiles_shallow VALUES (?, ?, ?, 0)", far[3])  # far tile shares blob 0
    db.commit()
    db.close()
    kept, removed = clip_mbtiles(src, tmp_path / "dst.mbtiles", _corridor())
    assert (kept, removed) == (1, 1)
    db = sqlite3.connect(tmp_path / "dst.mbtiles")
    assert db.execute("SELECT COUNT(*) FROM tiles_data").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0] == 1
