import json

import numpy as np
import pytest

from vpr_bench.fieldlog import FieldFrame, evaluate_field, gps_lag_warnings, gps_track, read_log, render_field_report
from vpr_bench.geo import offset_m

SAMPLE_LINE = (
    '{"v":1,"type":"frame","t_ms":1700000000123,"mode":"gps","gps":{"lat":55.75,"lon":37.6,"acc_m":4.5,'
    '"t_ms":1700000000000},"prior":{"lat":55.75,"lon":37.6,"radius_m":500.0},"top":[{"i":1,"sim":0.75,'
    '"lat":55.7509,"lon":37.6}],"fix":{"lat":55.7509,"lon":37.6,"sim":0.75},"lat_ms":{"pre":10.5,'
    '"inf":80.25,"search":2.0}}'
)
HEADER_LINE = json.dumps({
    "v": 1, "type": "session", "model": "m", "refpack_created_at": "2026-09-27T00:00:00Z",
    "device": "test", "started_ms": 1700000000000, "mode": "gps",
})

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6


def test_read_log_parses_sample(tmp_path):
    p = tmp_path / "s.jsonl"
    p.write_text(HEADER_LINE + "\n" + SAMPLE_LINE + "\n")
    header, frames = read_log(p)
    assert header["model"] == "m"
    assert frames == [FieldFrame(
        t_ms=1700000000123, mode="gps", gps=(55.75, 37.6, 4.5, 1700000000000),
        fix=(55.7509, 37.6, 0.75), lat_ms={"pre": 10.5, "inf": 80.25, "search": 2.0},
    )]


def _drive(n=30, fix_err_m=10.0, mode="gps", fix_every=1):
    """Едем на север 10 м/с, GPS раз в секунду, кадр раз в секунду; фиксация с ошибкой fix_err_m на восток."""
    frames = []
    for s in range(n):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        fix = None
        if s % fix_every == 0:
            flat, flon = offset_m(lat, lon, fix_err_m, 0.0)
            fix = (flat, flon, 0.8)
        frames.append(FieldFrame(T0 + 1000 * s, mode, (lat, lon, 5.0, T0 + 1000 * s), fix,
                                 {"pre": 5.0, "inf": 50.0, "search": 1.0}))
    return frames


def _refs_along(n=30):
    pts = [offset_m(LAT0, LON0, 0.0, 10.0 * s) for s in range(n)]
    return np.array([p[0] for p in pts]), np.array([p[1] for p in pts])


def test_gps_track_filters_by_accuracy():
    frames = _drive(5)
    bad = FieldFrame(T0 + 500, "gps", (0.0, 0.0, 99.0, T0 + 500), None, {"pre": 0, "inf": 0, "search": 0})
    track = gps_track(frames + [bad])
    assert len(track) == 5 and all(p.lat > 50 for p in track)


def test_all_within_threshold():
    lats, lons = _refs_along()
    [r] = evaluate_field(_drive(fix_err_m=10.0), lats, lons)
    assert r.mode == "gps" and r.n_frames == 30
    assert r.n_with_gt == 28  # первый и последний кадр без окна ±1 с
    assert r.coverage == 1.0 and r.frac_within == 1.0
    assert r.median_err_m == pytest.approx(10.0, abs=0.5)
    assert r.latency_ms["total"]["p50"] == pytest.approx(56.0)


def test_far_fixes_and_missing_fixes_fail():
    lats, lons = _refs_along()
    [r] = evaluate_field(_drive(fix_err_m=50.0), lats, lons)
    assert r.frac_within == 0.0
    [r2] = evaluate_field(_drive(fix_err_m=5.0, fix_every=2), lats, lons)
    assert 0.4 < r2.frac_within < 0.6
    assert r2.p95_err_m == float("inf")


def test_uncovered_frames_excluded():
    lats, lons = _refs_along(15)  # эталоны только на первой половине пути
    [r] = evaluate_field(_drive(fix_err_m=5.0), lats, lons)
    assert r.n_covered < r.n_with_gt
    assert r.coverage == pytest.approx(r.n_covered / r.n_with_gt)
    assert r.frac_within == 1.0


def test_modes_reported_separately_and_report_marks_gps_only():
    lats, lons = _refs_along()
    frames = _drive(mode="gps") + [
        FieldFrame(f.t_ms, "visual", f.gps, f.fix, f.lat_ms) for f in _drive(fix_err_m=50.0)
    ]
    results = evaluate_field(frames, lats, lons)
    assert [r.mode for r in results] == ["gps", "visual"]
    md = render_field_report({"model": "m", "device": "d"}, results)
    lines = [l for l in md.splitlines() if l.startswith("| gps") or l.startswith("| visual")]
    assert "✅" in lines[0] and "—" in lines[1]


def _drive_with_lag(n=10, lag_ms=200.0):
    frames = []
    for s in range(n):
        lat, lon = offset_m(LAT0, LON0, 0.0, 10.0 * s)
        frames.append(FieldFrame(
            T0 + 1000 * s, "gps", (lat, lon, 5.0, T0 + 1000 * s - int(lag_ms)), (lat, lon, 0.9),
            {"pre": 0.0, "inf": 0.0, "search": 0.0},
        ))
    return frames


def test_gps_lag_stats():
    lats, lons = _refs_along()
    [r] = evaluate_field(_drive_with_lag(lag_ms=200.0), lats, lons)
    assert r.gps_lag_ms == {"p50": 200.0, "p95": 200.0, "min": 200.0, "max": 200.0}
    assert gps_lag_warnings([r]) == []


def test_gps_lag_warning_outside_range():
    lats, lons = _refs_along()
    [r] = evaluate_field(_drive_with_lag(lag_ms=2000.0), lats, lons)
    warnings_ = gps_lag_warnings([r])
    assert len(warnings_) == 1 and "gps_lag_ms" in warnings_[0]
    md = render_field_report({"model": "m", "device": "d"}, [r])
    assert "⚠️" in md


def _drive_with_stop(n=30, stop_start=10, stop_len=5, fix_err_m=5.0):
    frames = []
    for s in range(n):
        if s < stop_start:
            dist = 10.0 * s
        elif s < stop_start + stop_len:
            dist = 10.0 * stop_start
        else:
            dist = 10.0 * stop_start + 10.0 * (s - (stop_start + stop_len))
        lat, lon = offset_m(LAT0, LON0, 0.0, dist)
        flat, flon = offset_m(lat, lon, fix_err_m, 0.0)
        frames.append(FieldFrame(
            T0 + 1000 * s, "gps", (lat, lon, 5.0, T0 + 1000 * s), (flat, flon, 0.8),
            {"pre": 5.0, "inf": 50.0, "search": 1.0},
        ))
    return frames


def test_stationary_frames_excluded_from_gt_and_counted():
    lats, lons = _refs_along()
    frames = _drive_with_stop()
    [r] = evaluate_field(frames, lats, lons)
    # Стоим в позиции s=10..15 включительно (stop_len=5 после s=10 — та же позиция ещё раз при s=15,
    # т.к. смещение после остановки считается от stop_start+stop_len); полностью внутри окна ±1 с
    # (оба соседа тоже стоят) — s=11..14.
    assert r.n_stationary == 4
    assert r.frac_within == 1.0  # остановленные кадры не портят критерий


def test_read_log_skips_truncated_final_line_with_warning(tmp_path):
    p = tmp_path / "s.jsonl"
    p.write_text(HEADER_LINE + "\n" + SAMPLE_LINE + "\n" + '{"v":1,"type":"frame","t_ms":170')
    with pytest.warns(UserWarning, match="truncated"):
        header, frames = read_log(p)
    assert header["model"] == "m"
    assert len(frames) == 1


def test_read_log_raises_on_malformed_middle_line(tmp_path):
    p = tmp_path / "s.jsonl"
    p.write_text(HEADER_LINE + "\n" + '{"broken' + "\n" + SAMPLE_LINE + "\n")
    with pytest.raises(ValueError, match="line 2"):
        read_log(p)
