import hashlib
import json

import pytest

from vpr_bench import m3cli
from vpr_bench.m3cli import main

# 3 точки вдоль меридиана, 1 км шаг (0.008993216° ≈ 1000 м); формат как у trip-plan
_LATS = (55.0, 55.008993216, 55.017986432)
_LON = 37.0
_TIMES = ("2000-01-01T00:00:00Z", "2000-01-01T00:01:40.123Z", "2000-01-01T00:03:20.500Z")


def _gpx(path, with_time=True):
    pts = []
    for lat, t in zip(_LATS, _TIMES):
        tm = f"<time>{t}</time>" if with_time else ""
        pts.append(f'<trkpt lat="{lat:.7f}" lon="{_LON:.7f}">{tm}</trkpt>\n')
    path.write_text('<?xml version="1.0" encoding="UTF-8"?>\n'
                    '<gpx version="1.1" creator="visnav trip-plan" xmlns="http://www.topografix.com/GPX/1/1">\n'
                    "<trk><trkseg>\n" + "".join(pts) + "</trkseg></trk>\n</gpx>\n", encoding="utf-8")
    return path


@pytest.fixture
def fakes(monkeypatch):
    calls = {"refs": [], "roads": [], "map": []}
    state = {"model": b"M1", "refs_rc": 0, "refs_raise": False, "tag": b"v1", "refs_bytes": 10}

    def refs(ns):
        calls["refs"].append(ns)
        if state["refs_raise"]:
            raise RuntimeError("boom")
        if state["refs_rc"]:
            return state["refs_rc"]
        model = ns.onnx.read_bytes()
        (ns.out / "refpack.bin").write_bytes(b"0" * state["refs_bytes"])
        (ns.out / "refpack.json").write_text(json.dumps({"onnx_sha256": hashlib.sha256(model).hexdigest()}))
        (ns.out / "model.onnx").write_bytes(model)
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


def _run(tmp_path, name="work", *extra, route=None, model=None):
    route = route or _gpx(tmp_path / "r.gpx")
    onnx = tmp_path / "m.onnx"
    onnx.write_bytes(model if model is not None else b"M1")
    return main(["pack-trip", "--route", str(route), "--name", name, "--refs", str(tmp_path / "refs.csv"),
                 "--onnx", str(onnx), "--pbf", str(tmp_path / "p.osm.pbf"),
                 "--mbtiles", str(tmp_path / "t.mbtiles"), "--fonts-zip", str(tmp_path / "f.zip"),
                 "--out", str(tmp_path / "root"), *extra])


def _no_temp(root):
    return not any(p.name.startswith(".") for p in (root / "trips").iterdir())


def test_layout_and_trip_json(tmp_path, fakes):
    assert _run(tmp_path) == 0
    root = tmp_path / "root"
    trip = root / "trips" / "work"
    assert (root / "model.onnx").read_bytes() == b"M1"
    for f in ("refpack.bin", "roadpack.bin", "map/map.json", "route.json", "trip.json"):
        assert (trip / f).exists(), f
    assert not (trip / "model.onnx").exists()
    route = json.loads((trip / "route.json").read_text(encoding="utf-8"))
    assert route == {"dest_lat": round(_LATS[-1], 7), "dest_lon": _LON, "dest_name": "work"}
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
    tmp = tmp_path / "root" / "trips" / ".work.tmp"  # сборка во временном каталоге, затем подмена
    r, d, m = calls["refs"][0], calls["roads"][0], calls["map"][0]
    for ns in (r, d, m):
        assert ns.gpx == [route]
    assert (r.buffer_m, r.out, r.refs, r.onnx) == (100.0, tmp, tmp_path / "refs.csv", tmp_path / "m.onnx")
    assert (d.buffer_m, d.out, d.pbf) == (200.0, tmp, tmp_path / "p.osm.pbf")
    assert d.bbox is None and d.log == []
    assert (m.buffer_m, m.out, m.mbtiles, m.fonts_zip) == (700.0, tmp / "map", tmp_path / "t.mbtiles",
                                                          tmp_path / "f.zip")
    assert m.log == []


def test_second_trip_same_model_ok_other_model_fails(tmp_path, fakes, capsys):
    calls, _ = fakes
    assert _run(tmp_path, "work") == 0
    assert _run(tmp_path, "home") == 0
    n_refs = len(calls["refs"])
    capsys.readouterr()
    assert _run(tmp_path, "gym", model=b"M2") == 2
    assert "model" in capsys.readouterr().err
    assert len(calls["refs"]) == n_refs  # проверка до шагов
    root = tmp_path / "root"
    assert not (root / "trips" / "gym").exists()
    assert _no_temp(root)
    assert (root / "model.onnx").read_bytes() == b"M1"
    assert (root / "trips" / "home" / "trip.json").exists()


def test_missing_root_model_checked_against_existing_trips(tmp_path, fakes, capsys):
    calls, _ = fakes
    assert _run(tmp_path, "work") == 0
    root = tmp_path / "root"
    (root / "model.onnx").unlink()
    n_refs = len(calls["refs"])
    assert _run(tmp_path, "home", model=b"M2") == 2
    assert "model" in capsys.readouterr().err
    assert len(calls["refs"]) == n_refs
    assert not (root / "trips" / "home").exists()
    assert _run(tmp_path, "home") == 0
    assert (root / "model.onnx").read_bytes() == b"M1"


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
    assert _no_temp(root)
    assert (root / "model.onnx").read_bytes() == b"M1"
    assert (root / "trips" / "home" / "trip.json").exists()


def test_force_with_failing_step_keeps_old_trip(tmp_path, fakes):
    _, state = fakes
    assert _run(tmp_path) == 0
    trip = tmp_path / "root" / "trips" / "work"
    before = (trip / "trip.json").read_bytes()
    state["refs_rc"] = 2
    assert _run(tmp_path, "work", "--force") == 2
    assert (trip / "trip.json").read_bytes() == before
    assert (trip / "roadpack.bin").read_bytes() == b"RDv1"
    assert _no_temp(tmp_path / "root")


def test_step_exception_cleans_up_and_propagates(tmp_path, fakes):
    _, state = fakes
    assert _run(tmp_path) == 0
    state["refs_raise"] = True
    with pytest.raises(RuntimeError, match="boom"):
        _run(tmp_path, "work", "--force")
    with pytest.raises(RuntimeError, match="boom"):
        _run(tmp_path, "home")
    root = tmp_path / "root"
    assert (root / "trips" / "work" / "trip.json").exists()
    assert not (root / "trips" / "home").exists()
    assert _no_temp(root)


def test_sizes_and_nfr8_warning(tmp_path, fakes, capsys):
    _, state = fakes
    state["refs_bytes"] = 1_200_000
    assert _run(tmp_path) == 0
    err = capsys.readouterr().err
    trip = tmp_path / "root" / "trips" / "work"
    meta = json.loads((trip / "trip.json").read_text(encoding="utf-8"))
    refs = sum((trip / f).stat().st_size for f in ("refpack.bin", "refpack.json")) / 1e6
    roads = sum((trip / f).stat().st_size for f in ("roadpack.bin", "roadpack.json")) / 1e6
    mp = (trip / "map" / "map.json").stat().st_size / 1e6
    assert meta["sizes_mb"] == {"refs": round(refs, 2), "roads": round(roads, 2), "map": round(mp, 2)}
    assert meta["total_mb"] == round(refs + roads + mp, 2)
    km = meta["route_km"]
    assert meta["mb_per_100km"] == pytest.approx((refs + roads + mp) / km * 100, abs=0.01)
    assert meta["mb_per_100km"] > 50
    assert "warning: over NFR-8 target" in err


def test_no_warning_under_target(tmp_path, fakes, capsys):
    assert _run(tmp_path) == 0
    assert "warning" not in capsys.readouterr().err


def test_route_without_points(tmp_path, fakes):
    route = _gpx(tmp_path / "r.gpx", with_time=False)
    assert _run(tmp_path, route=route) == 2
    calls, _ = fakes
    assert calls["refs"] == []
