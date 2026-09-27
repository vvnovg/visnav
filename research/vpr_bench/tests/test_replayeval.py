import json
import math

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


def _traj(tmp_path, err_m, visual=True, outage=(30, 90), vis_state=lambda s: None, stationary=lambda s: None):
    header = {"type": "replay", "visual": visual, "outages": [[T0 + outage[0] * 1000, T0 + outage[1] * 1000]],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(1, 119):
        lat, lon = offset_m(LAT0, LON0, err_m(s), 10.0 * s)
        lines.append(json.dumps({"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0,
                                 "outage": outage[0] <= s < outage[1], "vis_sim": None, "vis_ok": None,
                                 "vis_state": vis_state(s), "stationary": stationary(s)}))
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
    # Owner decision: the NFR-5 verdict only counts outages >= 1000 m of distance (see
    # replayeval.VERDICT_MIN_OUTAGE_DIST_M), so this outage runs long enough (105 s at 10 m/s
    # = 1050 m) to qualify. Error grows 0.2 m per second of outage -> 2 % drift, same as before.
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 0.2 * max(0, s - 10), visual=False, outage=(10, 115)))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.distance_m == pytest.approx(1040.0, rel=0.01)  # 104 интервала по 10 м
    assert o.drift_pct == pytest.approx(2.0, rel=0.05)
    report = render_replay_report(r)
    assert "NFR-5" in report and "✅" in report
    assert "(ориентир.)" not in report  # 1040 m clears the 1000 m verdict threshold


def test_vis_state_counts_and_report_line(tmp_path):
    # 30 rows in the outage: split ok/gated/below/empty_window/no_desc so each is exactly 20 %.
    states = ["ok", "gated", "below", "empty_window", "no_desc"]

    def vis_state(s):
        return states[(s - 30) % 5] if 30 <= s < 90 else None

    header, rows = read_trajectory(_traj(tmp_path, lambda s: 4.0, outage=(30, 90), vis_state=vis_state))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.vis_counts == {"ok": 12, "gated": 12, "below": 12, "empty_window": 12, "no_desc": 12}
    assert r.vis_counts == o.vis_counts
    report = render_replay_report(r)
    assert "ok 20 %" in report and "gated 20 %" in report and "no_desc 20 %" in report


def test_vis_state_counts_absent_in_dr_mode(tmp_path):
    """visual=false: vis_counts stay empty (no vis_state line in the DR-mode report)."""
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 4.0, visual=False, outage=(10, 115)))
    r = evaluate_replay(header, rows, _frames())
    assert r.vis_counts == {}
    assert "визуальные фиксации" not in render_replay_report(r)


def test_zupt_false_and_missed_stationary(tmp_path):
    # Rows in the outage move at 10 m/s (see _frames/GT), so a moving row reporting stationary=True
    # is a false stop; _frames has no slow/stopped GT here, so we only exercise false-stationary,
    # and rows without the field (None) must not be counted at all.
    def stationary(s):
        if not (30 <= s < 90):
            return None
        return s < 60  # first half falsely reports stationary, second half correctly does not

    header, rows = read_trajectory(_traj(tmp_path, lambda s: 4.0, outage=(30, 90), stationary=stationary))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.false_stationary_pct == pytest.approx(50.0, abs=1.0)


def test_zupt_stats_are_nan_when_stationary_field_is_absent(tmp_path):
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 4.0, outage=(30, 90)))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert math.isnan(o.false_stationary_pct)
    assert math.isnan(o.missed_stationary_pct)


def test_p95_second_half_of_outage(tmp_path):
    # Error is 1 m in the first half of the outage, 10 m in the second half: P95 of the whole
    # outage would blend both, but the second-half-only column must reflect only the 10 m rows.
    def err_m(s):
        if not (30 <= s < 90):
            return 0.0
        return 1.0 if s < 60 else 10.0

    header, rows = read_trajectory(_traj(tmp_path, err_m, visual=False, outage=(30, 90)))
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.p95_second_half_m == pytest.approx(10.0, abs=0.5)


def test_weighted_drift_pct_combines_outages_by_distance(tmp_path):
    # Two long (>=1000 m) outages with different drift: the weighted figure must fall strictly
    # between the two, not just average them unweighted (distances differ 2x).
    def err_m(s):
        if 10 <= s < 115:  # 105 s ~ 1050 m, drift 1 %
            return 0.1 * (s - 10)
        if 200 <= s < 260:  # 60 s ~ 600 m... too short to qualify; use a second >=1000 m outage instead
            return 0.0
        return 0.0

    header = {"type": "replay", "visual": False,
              "outages": [[T0 + 10_000, T0 + 115_000], [T0 + 130_000, T0 + 245_000]],
              "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(1, 250):
        if 10 <= s < 115:
            err = 0.1 * (s - 10)  # -> 1 % drift over ~1050 m
        elif 130 <= s < 245:
            err = 0.4 * (s - 130)  # -> 4 % drift over ~1150 m
        else:
            err = 0.0
        lat, lon = offset_m(LAT0, LON0, err, 10.0 * s)
        lines.append(json.dumps({"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0,
                                 "outage": (10 <= s < 115) or (130 <= s < 245),
                                 "vis_sim": None, "vis_ok": None, "vis_state": None, "stationary": None}))
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")

    header_out, rows = read_trajectory(p)
    r = evaluate_replay(header_out, rows, _frames(n=250))
    worst = r.worst_drift_pct()
    weighted = r.weighted_drift_pct()
    assert worst == pytest.approx(4.0, rel=0.1)
    assert weighted < worst
    assert weighted > 1.0


def test_whole_trajectory_diagnostics_shown_with_no_outages(tmp_path):
    """No outages at all -> has_data() is False (nothing to grade for NFR-1/5), but the
    whole-trajectory vis_state and ZUPT shares must still be computed and always printed —
    they're useful diagnostics for the very first drive, before any outage is even replayed."""
    header = {"type": "replay", "visual": True, "outages": [], "session_started_ms": T0, "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    states = ["ok", "gated", "below", "empty_window", "no_desc"]
    for s in range(1, 119):
        lat, lon = offset_m(LAT0, LON0, 3.0, 10.0 * s)
        lines.append(json.dumps({"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0,
                                 "outage": False, "vis_sim": None, "vis_ok": None,
                                 "vis_state": states[s % 5], "stationary": s % 2 == 0}))
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")

    header_out, rows = read_trajectory(p)
    r = evaluate_replay(header_out, rows, _frames())
    assert not r.has_data()  # no outages -> nothing to grade
    assert sum(r.vis_counts.values()) == 118  # every row counted, not just in-outage ones
    assert not math.isnan(r.false_stationary_pct) or not math.isnan(r.missed_stationary_pct)

    report = render_replay_report(r)
    assert "нет данных для проверки" in report  # still no NFR verdict
    assert "визуальные фиксации:" in report  # but the whole-trajectory shares are printed anyway
    assert "ZUPT по всей траектории" in report


def test_read_trajectory_empty_file_raises(tmp_path):
    p = tmp_path / "empty.jsonl"
    p.write_text("")
    with pytest.raises(ValueError, match="empty"):
        read_trajectory(p)


def test_nfr5_short_outage_is_indicative_only(tmp_path):
    """An outage under 1000 m still appears in the table (marked "(ориентир.)") but is excluded
    from the pass/fail verdict — the pre-2026-09-27 200 m threshold would have graded it."""
    header, rows = read_trajectory(_traj(tmp_path, lambda s: 0.2 * max(0, s - 30), visual=False))  # 590 m, outage=(30, 90)
    r = evaluate_replay(header, rows, _frames())
    [o] = r.outages
    assert o.distance_m == pytest.approx(590.0, rel=0.01)
    report = render_replay_report(r)
    assert "нет пропаданий" in report
    assert "✅" not in report and "❌" not in report
    assert "(ориентир.)" in report


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
                             "outage": False, "vis_sim": None, "vis_ok": None}))
    # Row with null sigma_m should be kept but sigma_m should be NaN
    lines.append(json.dumps({"t_ms": T0 + 3000, "lat": 55.75, "lon": 37.6, "sigma_m": None,
                             "outage": False, "vis_sim": None, "vis_ok": None}))
    # Row with null lat should be skipped
    lines.append(json.dumps({"t_ms": T0 + 1000, "lat": None, "lon": 37.6, "sigma_m": 5.0,
                             "outage": False, "vis_sim": None, "vis_ok": None}))
    # Normal row should be kept
    lines.append(json.dumps({"t_ms": T0 + 4000, "lat": 55.751, "lon": 37.601, "sigma_m": 5.0,
                             "outage": False, "vis_sim": None, "vis_ok": None}))
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
                                 "outage": 30 <= s < 90, "vis_sim": None, "vis_ok": None}))
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
