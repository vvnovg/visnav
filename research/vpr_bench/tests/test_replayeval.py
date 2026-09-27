import json

import pytest

from vpr_bench.fieldlog import FieldFrame
from vpr_bench.geo import offset_m
from vpr_bench.m2cli import main
from vpr_bench.replayeval import evaluate_replay, read_trajectory, render_replay_report

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6


def _frames(n=120):
    """Едем на север 10 м/с, GPS каждую секунду."""
    out = []
    for s in range(n):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        out.append(FieldFrame(T0 + 1000 * s, "gps", (lat, lon, 4.0, T0 + 1000 * s), None,
                              {"pre": 0.0, "inf": 0.0, "search": 0.0}))
    return out


def _traj(tmp_path, err_m, visual=True, outage=(30, 90)):
    header = {"type": "replay", "visual": visual, "outages": [[T0 + outage[0] * 1000, T0 + outage[1] * 1000]],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(1, 119):
        lat, lon = offset_m(LAT0, LON0, err_m(s), 10.0 * s)
        lines.append(json.dumps({"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0,
                                 "vis_sim": None, "vis_ok": None}))
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")
    return p


def test_nfr1_pass_with_small_errors(tmp_path):
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 4.0))
    r = evaluate_replay(header, rows, _frames())
    assert r.visual and r.n_points == 60
    assert r.p50_m == pytest.approx(4.0, abs=0.1)
    assert "✅" in render_replay_report(r)


def test_nfr1_fail_with_large_errors(tmp_path):
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 20.0))
    r = evaluate_replay(header, rows, _frames())
    assert r.p95_m > 15.0
    assert "❌" in render_replay_report(r)


def test_nfr5_drift_percent(tmp_path):
    # ошибка растёт линейно: 0.2 м на секунду пропадания → 2 % от 10 м/с
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 0.2 * max(0, s - 30), visual=False))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.distance_m == pytest.approx(590.0, rel=0.01)  # 59 интервалов по 10 м
    assert o.drift_pct == pytest.approx(2.0, rel=0.05)
    report = render_replay_report(r)
    assert "NFR-5" in report and "✅" in report


def test_cli_writes_report(tmp_path):
    traj = _traj(tmp_path, lambda s: 3.0)
    log = tmp_path / "s.jsonl"
    lines = [json.dumps({"v": 1, "type": "session", "model": "m", "refpack_created_at": "c", "device": "d",
                         "started_ms": T0, "mode": "gps"})]
    for f in _frames():
        lines.append(json.dumps({"v": 1, "type": "frame", "t_ms": f.t_ms, "mode": "gps",
                                 "gps": {"lat": f.gps[0], "lon": f.gps[1], "acc_m": 4.0, "t_ms": f.gps[3]},
                                 "prior": None, "top": [], "fix": None,
                                 "lat_ms": {"pre": 0.0, "inf": 0.0, "search": 0.0}}))
    log.write_text("\n".join(lines) + "\n")
    out = tmp_path / "r.md"
    assert main(["replay-eval", "--traj", str(traj), "--log", str(log), "--out", str(out)]) == 0
    assert "NFR-1" in out.read_text()


def test_read_trajectory_skips_null_lat_lon(tmp_path):
    """Skip rows with null lat or lon; treat null sigma_m as NaN; sort by t_ms."""
    header = {"type": "replay", "visual": True, "outages": [[T0 + 30000, T0 + 90000]],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    # Row with null lon should be skipped
    lines.append(json.dumps({"t_ms": T0 + 2000, "lat": 55.75, "lon": None, "sigma_m": 5.0,
                             "vis_sim": None, "vis_ok": None}))
    # Row with null sigma_m should be kept but sigma_m should be NaN
    lines.append(json.dumps({"t_ms": T0 + 3000, "lat": 55.75, "lon": 37.6, "sigma_m": None,
                             "vis_sim": None, "vis_ok": None}))
    # Row with null lat should be skipped
    lines.append(json.dumps({"t_ms": T0 + 1000, "lat": None, "lon": 37.6, "sigma_m": 5.0,
                             "vis_sim": None, "vis_ok": None}))
    # Normal row should be kept
    lines.append(json.dumps({"t_ms": T0 + 4000, "lat": 55.751, "lon": 37.601, "sigma_m": 5.0,
                             "vis_sim": None, "vis_ok": None}))
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")

    header_out, rows = read_trajectory(p)
    assert len(rows) == 2  # Only rows with non-null lat/lon
    assert rows[0].t_ms == T0 + 3000  # Sorted by t_ms
    assert rows[0].lat == 55.75 and rows[0].lon == 37.6
    assert rows[0].sigma_m != rows[0].sigma_m  # NaN
    assert rows[1].t_ms == T0 + 4000
    assert rows[1].sigma_m == 5.0


def test_visual_mode_no_data(tmp_path):
    """Visual mode with empty outages list → report contains 'нет данных' and no ❌/✅."""
    header = {"type": "replay", "visual": True, "outages": [],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")

    header_out, rows = read_trajectory(p)
    r = evaluate_replay(header_out, rows, _frames())
    report = render_replay_report(r)
    assert "нет данных" in report
    assert "❌" not in report
    assert "✅" not in report


def test_dr_mode_no_data(tmp_path):
    """DR mode with empty outages or all < 200 m → report contains 'нет данных' and no ❌/✅."""
    header = {"type": "replay", "visual": False, "outages": [],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")

    header_out, rows = read_trajectory(p)
    r = evaluate_replay(header_out, rows, _frames())
    report = render_replay_report(r)
    assert "нет данных" in report
    assert "❌" not in report
    assert "✅" not in report


def test_cli_mismatched_sessions(tmp_path):
    """CLI with mismatched session timestamps → exit code 2."""
    header = {"type": "replay", "visual": True, "outages": [[T0 + 30000, T0 + 90000]],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(1, 119):
        lat, lon = offset_m(LAT0, LON0, 3.0, 10.0 * s)
        lines.append(json.dumps({"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0,
                                 "vis_sim": None, "vis_ok": None}))
    traj = tmp_path / "traj.jsonl"
    traj.write_text("\n".join(lines) + "\n")

    log = tmp_path / "s.jsonl"
    log_lines = [json.dumps({"v": 1, "type": "session", "model": "m", "refpack_created_at": "c", "device": "d",
                             "started_ms": T0 + 1000000, "mode": "gps"})]  # Different session time
    for f in _frames():
        log_lines.append(json.dumps({"v": 1, "type": "frame", "t_ms": f.t_ms, "mode": "gps",
                                     "gps": {"lat": f.gps[0], "lon": f.gps[1], "acc_m": 4.0, "t_ms": f.gps[3]},
                                     "prior": None, "top": [], "fix": None,
                                     "lat_ms": {"pre": 0.0, "inf": 0.0, "search": 0.0}}))
    log.write_text("\n".join(log_lines) + "\n")
    out = tmp_path / "r.md"
    assert main(["replay-eval", "--traj", str(traj), "--log", str(log), "--out", str(out)]) == 2
