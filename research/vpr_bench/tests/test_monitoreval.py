import json

import pytest

from vpr_bench.fieldlog import FieldFrame
from vpr_bench.geo import offset_m
from vpr_bench.m2cli import main
from vpr_bench.monitoreval import evaluate_monitor, render_monitor_report
from vpr_bench.replayeval import read_trajectory

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6
N = 300


def _gps(s, north_extra=0.0):
    lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s + north_extra)
    return lat, lon


def _frames(n=N, shifted=()):
    out = []
    for s in range(n):
        lat, lon = _gps(s, 3000.0 if s in shifted else 0.0)
        out.append(FieldFrame(T0 + 1000 * s, "gps", (lat, lon, 4.0, T0 + 1000 * s), None,
                              {"pre": 0.0, "inf": 0.0, "search": 0.0}))
    return out


def _write(tmp_path, spoofs=(), jams=(), mode=lambda s: "gnss", health=lambda s: "good",
           err_m=lambda s: 0.0, new_fields=True, n=N, null_pos=()):
    header = {"type": "replay", "visual": True, "outages": [], "monitor": True,
              "jams": [[T0 + a * 1000, T0 + b * 1000] for a, b in jams],
              "spoofs": [[T0 + w[0] * 1000, T0 + w[1] * 1000, *(w[2:5] if len(w) > 2 else (300.0, 0.0, 0))]
                         for w in spoofs],
              "session_started_ms": T0, "refpack_created_at": "c"}
    if not new_fields:
        header = {"type": "replay", "visual": True, "outages": [], "session_started_ms": T0,
                  "refpack_created_at": "c"}
    lines = [json.dumps(header)]
    for s in range(1, n - 1):
        lat, lon = offset_m(LAT0, LON0, err_m(s), 10.0 * s)
        row = {"t_ms": T0 + 1000 * s, "lat": lat, "lon": lon, "sigma_m": 5.0, "outage": False,
               "vis_sim": None, "vis_ok": None, "vis_state": None, "stationary": None}
        if s in null_pos:
            row["lat"] = row["lon"] = None
        if new_fields:
            row.update({"mode": mode(s), "health": health(s), "reasons": [], "injected": None})
        lines.append(json.dumps(row))
    p = tmp_path / "traj.jsonl"
    p.write_text("\n".join(lines) + "\n")
    return p


def _eval(path, frames=None):
    header, rows = read_trajectory(path)
    return evaluate_monitor(header, rows, frames or _frames()), rows


def test_spoof_detected_after_three_seconds(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 160)], health=lambda s: "untrusted" if 103 <= s < 160 else "good",
               err_m=lambda s: 5.0)
    r, _ = _eval(p)
    [w] = r.windows
    assert w.kind == "spoof" and w.latency_s == 3.0
    assert w.p95_err_m == pytest.approx(5.0, abs=0.1)
    rep = render_monitor_report(r)
    assert "✅" in rep and "❌" not in rep.split("Ложное")[0]


def test_spoof_not_detected(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 160)])
    r, _ = _eval(p)
    assert r.windows[0].latency_s is None
    assert "❌" in render_monitor_report(r)


def test_jam_latency_is_first_non_gnss_row(tmp_path):
    p = _write(tmp_path, jams=[(100, 160)], mode=lambda s: "fused" if s >= 102 else "gnss")
    r, _ = _eval(p)
    [w] = r.windows
    assert w.kind == "jam" and w.latency_s == 2.0
    line = [l for l in render_monitor_report(r).splitlines() if "глушении" in l][0]
    assert "2.0 с" in line and "✅" in line


@pytest.mark.parametrize("n_bad,ok", [(2, True), (3, False)])
def test_false_untrusted_rate(tmp_path, n_bad, ok):
    # Clean rows: s in [61, 298] minus nothing -> use first n_bad rows after warm-up.
    bad = set(range(100, 100 + n_bad))
    p = _write(tmp_path, health=lambda s: "untrusted" if s in bad else "good", n=263)
    header, rows = read_trajectory(p)
    r = evaluate_monitor(header, rows, _frames(263))
    assert r.false_untrusted_pct == pytest.approx(n_bad / 200 * 100, rel=0.05)
    assert ("✅" in render_monitor_report(r).split("Ложное")[1].splitlines()[0]) is ok


def test_real_spoof_recall(tmp_path):
    shifted = set(range(100, 110))
    p = _write(tmp_path, health=lambda s: "untrusted" if 101 <= s < 110 else "good")
    r, _ = _eval(p, _frames(shifted=shifted))
    assert r.n_real_spoof_s == 10
    assert r.real_spoof_recall_pct == pytest.approx(90.0)
    rep = render_monitor_report(r)
    assert "✅" in rep.split("реальн")[1]
    assert r.false_untrusted_pct <= 1.0
    assert "✅" in [l for l in rep.splitlines() if "Ложное" in l][0]


def _log(tmp_path, frames):
    lines = [json.dumps({"type": "session", "started_ms": T0})]
    for f in frames:
        g = {"lat": f.gps[0], "lon": f.gps[1], "acc_m": f.gps[2], "t_ms": f.gps[3]}
        lines.append(json.dumps({"t_ms": f.t_ms, "mode": f.mode, "gps": g, "fix": None, "lat_ms": f.lat_ms}))
    p = tmp_path / "s.jsonl"
    p.write_text("\n".join(lines) + "\n")
    return p


def test_old_format_trajectory_reads_and_cli_reports_no_data(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 160)], new_fields=False)
    header, rows = read_trajectory(p)
    assert rows[0].mode is None and rows[0].health is None and rows[0].reasons == ()
    out = tmp_path / "r.md"
    assert main(["monitor-eval", "--traj", str(p), "--log", str(_log(tmp_path, _frames())), "--out", str(out)]) == 0
    rep = out.read_text()
    assert "⚠️ нет данных" in rep and "❌" not in rep


def test_empty_clean_set_is_no_data(tmp_path):
    p = _write(tmp_path, n=50)
    r, _ = _eval(p)
    assert r.false_untrusted_pct != r.false_untrusted_pct
    assert "⚠️ нет данных" in [l for l in render_monitor_report(r).splitlines() if "Ложное" in l][0]


def test_empty_window_is_no_data_not_failure(tmp_path):
    p = _write(tmp_path, spoofs=[(400, 460)])
    r, _ = _eval(p)
    rep = render_monitor_report(r)
    assert "❌" not in rep and "| spoof | " in rep and "nan" not in rep
    assert "⚠️ нет данных" in [l for l in rep.splitlines() if "подмены ≤ 5" in l][0]


def test_null_position_row_still_counts_for_latency(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 160)], health=lambda s: "untrusted" if s >= 103 else "good",
               null_pos={103})
    header, rows = read_trajectory(p)
    assert all(r.t_ms != T0 + 103_000 for r in rows)  # replay-eval view unchanged
    header, rows = read_trajectory(p, keep_null_pos=True)
    r = evaluate_monitor(header, rows, _frames())
    assert r.windows[0].latency_s == 3.0


def test_five_element_spoofs_and_monitor_flag_shown(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 160)])
    r, _ = _eval(p)
    assert r.monitor is True
    assert "монитор: включён" in render_monitor_report(r)


def test_cli_session_mismatch_returns_2(tmp_path):
    traj = _write(tmp_path)
    log = tmp_path / "s.jsonl"
    log.write_text(json.dumps({"type": "session", "started_ms": T0 + 5}) + "\n")
    assert main(["monitor-eval", "--traj", str(traj), "--log", str(log), "--out", str(tmp_path / "r.md")]) == 2


def test_phone_fusion_log_accepted_by_monitor_eval_only(tmp_path):
    p = _write(tmp_path)
    lines = p.read_text().splitlines()
    lines[0] = json.dumps({"type": "fusion", "monitor": True, "session_started_ms": T0,
                           "refpack_created_at": "x"})
    fusion = tmp_path / "s.fusion.jsonl"
    fusion.write_text("\n".join(lines) + "\n")
    log = _log(tmp_path, _frames())
    out = tmp_path / "r.md"
    assert main(["monitor-eval", "--traj", str(fusion), "--log", str(log), "--out", str(out)]) == 0
    assert "монитор: включён" in out.read_text()
    with pytest.raises(ValueError):
        read_trajectory(fusion)


def test_tail_after_injected_windows_is_not_clean(tmp_path):
    # After the spoof ends (160) the fix jumps back: 10 s UNTRUSTED, 20 s non-GNSS. None of it is "clean".
    p = _write(tmp_path, spoofs=[(100, 160)],
               health=lambda s: "untrusted" if 160 <= s < 170 else "good",
               mode=lambda s: "fused" if 160 <= s < 180 else "gnss")
    r, _ = _eval(p)
    assert r.tail_s == 20.0
    assert r.false_untrusted_pct == 0.0 and r.non_gnss_clean_pct == 0.0
    assert "20 с" in render_monitor_report(r)


def test_tail_after_jam_and_outage_windows_is_not_clean(tmp_path):
    p = _write(tmp_path, jams=[(100, 130)], mode=lambda s: "fused" if 130 <= s < 150 else "gnss")
    r, _ = _eval(p)
    assert r.non_gnss_clean_pct == 0.0


def test_ramped_spoof_detected_before_100m_has_zero_latency(tmp_path):
    # 300 m offset, 60 s ramp -> 100 m reached 20 s after start; untrusted at +5 s.
    p = _write(tmp_path, spoofs=[(100, 200, 300.0, 0.0, 60_000)],
               health=lambda s: "untrusted" if 105 <= s < 200 else "good")
    r, _ = _eval(p)
    assert r.windows[0].latency_s == 0.0
    assert "✅" in [l for l in render_monitor_report(r).splitlines() if "подмены ≤ 5" in l][0]


def test_ramped_spoof_latency_measured_from_100m(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 200, 300.0, 0.0, 60_000)],
               health=lambda s: "untrusted" if 123 <= s < 200 else "good")
    r, _ = _eval(p)
    assert r.windows[0].latency_s == pytest.approx(3.0)


def test_spoof_under_100m_excluded_from_verdict(tmp_path):
    p = _write(tmp_path, spoofs=[(100, 160, 50.0, 0.0, 0)],
               health=lambda s: "untrusted" if s >= 110 else "good")
    r, _ = _eval(p)
    assert r.windows[0].latency_s == 10.0
    rep = render_monitor_report(r)
    assert "< 100 м" in rep
    assert "⚠️ нет данных" in [l for l in rep.splitlines() if "подмены ≤ 5" in l][0]
    assert "❌" not in rep.split("Ложное")[0]
