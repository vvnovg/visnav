import hashlib
import json

import pytest

from vpr_bench import m3cli
from vpr_bench.m3cli import main

# 3 точки вдоль меридиана, 1 км шаг (0.008993216° ≈ 1000 м)
_LATS = (55.0, 55.008993216, 55.017986432)
_LON = 37.0


def _gpx(path, with_time=True):
    pts = []
    for i, lat in enumerate(_LATS):
        t = f"<time>2000-01-01T00:0{i}:00Z</time>" if with_time else ""
        pts.append(f'<trkpt lat="{lat}" lon="{_LON}">{t}</trkpt>')
    path.write_text('<?xml version="1.0"?><gpx version="1.1" creator="t"><trk><trkseg>'
                    + "".join(pts) + "</trkseg></trk></gpx>", encoding="utf-8")
    return path


@pytest.fixture
def fakes(monkeypatch):
    calls = {"refs": [], "roads": [], "map": []}
    state = {"model": b"M1", "refs_rc": 0, "tag": b"v1"}

    def refs(ns):
        calls["refs"].append(ns)
        if state["refs_rc"]:
            return state["refs_rc"]
        ns.out.mkdir(parents=True, exist_ok=True)
        (ns.out / "refpack.bin").write_bytes(b"0123456789")
        (ns.out / "refpack.json").write_bytes(b"{}" + state["tag"])
        (ns.out / "model.onnx").write_bytes(state["model"])
        return 0

    def roads(ns):
        calls["roads"].append(ns)
        (ns.out / "roadpack.bin").write_bytes(b"RD" + state["tag"])
        (ns.out / "roadpack.json").write_bytes(b"{}")
        return 0

    def map_(ns):
        calls["map"].append(ns)
        ns.out.mkdir(parents=True, exist_ok=True)
        (ns.out / "map.json").write_bytes(b"{}" + state["tag"])
        return 0

    monkeypatch.setattr(m3cli, "_step_refs", refs)
    monkeypatch.setattr(m3cli, "_step_roads", roads)
    monkeypatch.setattr(m3cli, "_step_map", map_)
    return calls, state


def _run(tmp_path, name="work", *extra, route=None):
    route = route or _gpx(tmp_path / "r.gpx")
    return main(["pack-trip", "--route", str(route), "--name", name, "--refs", str(tmp_path / "refs.csv"),
                 "--onnx", str(tmp_path / "m.onnx"), "--pbf", str(tmp_path / "p.osm.pbf"),
                 "--mbtiles", str(tmp_path / "t.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--out", str(tmp_path / "root"), *extra])


def test_layout_and_trip_json(tmp_path, fakes):
    assert _run(tmp_path) == 0
    root = tmp_path / "root"
    trip = root / "trips" / "work"
    assert (root / "model.onnx").read_bytes() == b"M1"
    for f in ("refpack.bin", "roadpack.bin", "map/map.json", "route.json", "trip.json"):
        assert (trip / f).exists(), f
    assert not (trip / "model.onnx").exists()
    route = json.loads((trip / "route.json").read_text(encoding="utf-8"))
    assert route == {"dest_lat": _LATS[-1], "dest_lon": _LON, "dest_name": "work"}
    meta = json.loads((trip / "trip.json").read_text(encoding="utf-8"))
    assert meta["name"] == "work"
    assert meta["route_km"] == pytest.approx(2.0, abs=0.01)
    assert meta["buffers_m"] == {"refs": 300.0, "roads": 300.0, "map": 500.0}
    assert meta["nfr8_target_mb_per_100km"] == 50
    assert meta["onnx_sha256"] == hashlib.sha256(b"M1").hexdigest()
    assert set(meta["sizes_mb"]) == {"refs", "roads", "map"}
    assert "total_mb" in meta and "mb_per_100km" in meta and "created_at" in meta and "model" in meta


def test_dest_name_option(tmp_path, fakes):
    assert _run(tmp_path, "work", "--dest-name", "Работа") == 0
    route = json.loads((tmp_path / "root" / "trips" / "work" / "route.json").read_text(encoding="utf-8"))
    assert route["dest_name"] == "Работа"


def test_sha256_matches_onnx_export(tmp_path):
    onnx_export = pytest.importorskip("vpr_bench.onnx_export")
    p = tmp_path / "m.onnx"
    p.write_bytes(b"M1" * 100000)
    assert m3cli._file_sha256(p) == onnx_export.onnx_sha256(p)


def test_steps_get_route_and_buffers(tmp_path, fakes):
    calls, _ = fakes
    route = _gpx(tmp_path / "r.gpx")
    assert _run(tmp_path, "work", "--refs-buffer-m", "100", "--roads-buffer-m", "200", "--map-buffer-m", "700",
                route=route) == 0
    trip = tmp_path / "root" / "trips" / "work"
    r, d, m = calls["refs"][0], calls["roads"][0], calls["map"][0]
    for ns in (r, d, m):
        assert ns.gpx == [route]
    assert (r.buffer_m, r.out, r.refs, r.onnx) == (100.0, trip, tmp_path / "refs.csv", tmp_path / "m.onnx")
    assert (d.buffer_m, d.out, d.pbf) == (200.0, trip, tmp_path / "p.osm.pbf")
    assert d.bbox is None and d.log == []
    assert (m.buffer_m, m.out, m.mbtiles, m.fonts_zip) == (700.0, trip / "map", tmp_path / "t.mbtiles",
                                                          tmp_path / "f.zip")
    assert m.log == []


def test_second_trip_same_model_ok_other_model_fails(tmp_path, fakes, capsys):
    _, state = fakes
    assert _run(tmp_path, "work") == 0
    assert _run(tmp_path, "home") == 0
    state["model"] = b"M2"
    capsys.readouterr()
    assert _run(tmp_path, "gym") == 2
    assert "model" in capsys.readouterr().err
    root = tmp_path / "root"
    assert not (root / "trips" / "gym").exists()
    assert (root / "model.onnx").read_bytes() == b"M1"
    assert (root / "trips" / "home" / "trip.json").exists()


def test_existing_trip_needs_force(tmp_path, fakes):
    _, state = fakes
    assert _run(tmp_path) == 0
    trip = tmp_path / "root" / "trips" / "work"
    (trip / "stale.txt").write_text("x")
    assert _run(tmp_path) == 2
    assert (trip / "stale.txt").exists()
    state["tag"] = b"v2"
    assert _run(tmp_path, "work", "--force") == 0
    assert not (trip / "stale.txt").exists()
    assert (trip / "roadpack.bin").read_bytes() == b"RDv2"
    assert (trip / "map" / "map.json").read_bytes() == b"{}v2"


@pytest.mark.parametrize("name", ["../x", "", "a b"])
def test_bad_name_rejected(tmp_path, fakes, name):
    assert _run(tmp_path, name) == 2
    assert not (tmp_path / "root" / "trips").exists() or not any((tmp_path / "root" / "trips").iterdir())


def test_step_failure_propagates(tmp_path, fakes):
    _, state = fakes
    assert _run(tmp_path, "home") == 0
    state["refs_rc"] = 2
    assert _run(tmp_path, "work") == 2
    root = tmp_path / "root"
    assert not (root / "trips" / "work").exists()
    assert (root / "model.onnx").read_bytes() == b"M1"
    assert (root / "trips" / "home" / "trip.json").exists()


def test_route_without_points(tmp_path, fakes):
    route = _gpx(tmp_path / "r.gpx", with_time=False)
    assert _run(tmp_path, route=route) == 2
    calls, _ = fakes
    assert calls["refs"] == []
