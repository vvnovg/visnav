import json

import numpy as np
import pytest

from vpr_bench.m2cli import main
from vpr_bench.obdeval import evaluate_obd, read_sensors, render_obd_report

V_TRUE_KMH = 100.0
OBD_KMH = 103  # спидометр завышает на 3 %


def _write(path, events):
    lines = [json.dumps({"v": 1, "type": "sensors", "started_ms": 0})]
    lines += [json.dumps(e) for e in events]
    path.write_text("\n".join(lines) + "\n")
    return path


def _rtt(i):
    return 30.0 + (i % 10) * 10.0


def _synthetic(path):
    """60 с: OBD 5 Гц с разрывом 20–25 с, GPS 1 Гц на 100 км/ч; плюс строки, которые читатель пропускает."""
    events = []
    obd_t = [t for t in range(0, 60_001, 200) if not 20_000 < t < 25_000]
    for i, t in enumerate(obd_t):
        events.append({"t": float(t), "k": "obd", "kmh": OBD_KMH, "rtt_ms": _rtt(i)})
    for t in range(0, 60_001, 1000):
        events.append({"t": float(t), "k": "loc", "lat": 55.0, "lon": 37.0, "acc": 3.0,
                       "spd": V_TRUE_KMH / 3.6, "spd_acc": 0.3, "brg": 90.0, "brg_acc": 2.0})
        events.append({"t": float(t), "k": "g", "x": 0.0, "y": 0.0, "z": 0.0})
        events.append({"t": float(t), "k": "future_kind", "q": 1})
    events.sort(key=lambda e: e["t"])
    return _write(path, events), [_rtt(i) for i in range(len(obd_t))]


def test_synthetic_session(tmp_path):
    path, rtts = _synthetic(tmp_path / "s.sensors.jsonl")
    header, locs, obds = read_sensors(path)
    assert header["started_ms"] == 0
    assert len(locs) == 61 and len(obds) == len(rtts)
    r = evaluate_obd(locs, obds)
    assert r.n_obd == len(rtts)
    assert r.duration_s == pytest.approx(60.0)
    assert r.median_interval_ms == pytest.approx(200.0)
    assert r.n_gaps == 1 and r.gap_s == pytest.approx(5.0)
    assert r.no_obd_pct == pytest.approx(5.0 / 60.0 * 100.0)
    assert r.rtt_p50_ms == pytest.approx(np.percentile(rtts, 50))
    assert r.rtt_p95_ms == pytest.approx(np.percentile(rtts, 95))
    assert r.rtt_max_ms == pytest.approx(120.0)
    # фиксы на 21–24 с без свежего OBD
    assert r.n_ratio == 61 - 4
    assert r.k == pytest.approx(0.971, abs=1e-3)
    assert r.speedometer_pct == pytest.approx(3.0, abs=1e-6)
    assert r.resid_p50_mps == pytest.approx(0.0, abs=1e-9)
    text = render_obd_report(r)
    assert "0.971" in text and "+3.0 %" in text and "здоровье" in text.lower()


def test_ratio_interpolates_and_rejects_stale_slow_and_outliers():
    obds = [(0.0, 100, 40.0), (400.0, 110, 40.0),  # между ними — интерполяция
            (5_000.0, 100, 40.0),                     # фикс через 600 мс — несвежий
            (10_000.0, 100, 40.0), (10_200.0, 100, 40.0),
            (20_000.0, 0, 40.0), (20_200.0, 0, 40.0)]  # kmh = 0 — не входит
    locs = [(200.0, 105 / 3.6),
            (5_600.0, 100 / 3.6),
            (10_100.0, 120 / 3.6),   # отношение 1.2 — выброс
            (10_150.0, 4.0),         # медленнее 5 м/с
            (20_100.0, 10.0)]
    r = evaluate_obd(locs, obds)
    assert r.n_ratio == 1 and r.n_outliers == 1
    assert r.k == pytest.approx(1.0)


def test_no_obd(tmp_path):
    path = _write(tmp_path / "s.sensors.jsonl",
                  [{"t": 0.0, "k": "loc", "lat": 55.0, "lon": 37.0, "acc": 3.0,
                    "spd": 10.0, "spd_acc": 0.3, "brg": None, "brg_acc": None}])
    _, locs, obds = read_sensors(path)
    r = evaluate_obd(locs, obds)
    assert r.n_obd == 0 and r.k is None
    assert "нет данных OBD" in render_obd_report(r)


def test_cli_writes_report(tmp_path, capsys):
    path, _ = _synthetic(tmp_path / "s.sensors.jsonl")
    out = tmp_path / "r" / "obd.md"
    assert main(["obd-eval", "--sensors", str(path), "--out", str(out)]) == 0
    assert "0.971" in out.read_text()
    assert "k=0.971" in capsys.readouterr().out
