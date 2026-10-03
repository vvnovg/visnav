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
