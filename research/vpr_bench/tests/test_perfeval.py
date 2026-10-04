import json

import pytest

from vpr_bench.m4cli import main
from vpr_bench.perfeval import evaluate, read_perf, render

T0 = 1_700_000_000_000
MIN = 60_000


def _ms(x):
    return None if x is None else round(float(x), 1)


def _header(profile="full", capacity=4500, delay=1500):
    # как PerfLog.header: компактный JSON, числа с точкой
    return {"type": "perf", "v": 1, "session_started_ms": T0, "device": "Pixel \"7\"", "profile": profile,
            "reorder_delay_ms": delay, "ort": "cpu", "battery_capacity_mah": capacity}


def _frame(t_ms, e2e, pre=10.0, inf=50.0, search=3.0, fuse=0.5, nav=1.0, interval=500):
    return {"type": "frame", "t_ms": t_ms, "pre": _ms(pre), "inf": _ms(inf), "search": _ms(search),
            "fuse_ms": _ms(fuse), "nav_ms": _ms(nav), "e2e_ms": _ms(e2e), "interval_ms": interval}


def _sys(t_ms, pct=90.0, charge=3_000_000, current=450_000, temp=31.2, plugged=False, thermal=0, headroom=0.5,
         interval=500):
    return {"type": "sys", "t_ms": t_ms, "batt_pct": pct, "charge_uah": charge, "current_ua": current,
            "batt_temp_c": temp, "plugged": plugged, "thermal": thermal, "headroom": headroom,
            "interval_ms": interval}


def _late(t_ms, sources, late=0, dropped=0):
    return {"type": "late", "t_ms": t_ms,
            "sources": {k: {"n": n, "p50": _ms(p50), "p99": _ms(p99), "max": _ms(mx)}
                        for k, (n, p50, p99, mx) in sources.items()},
            "late": late, "dropped": dropped}


def _write(tmp_path, records, header=None, name="run.perf.jsonl"):
    p = tmp_path / name
    lines = [json.dumps(header or _header(), separators=(",", ":"))]
    lines += [json.dumps(r, separators=(",", ":")) for r in records]
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


def _drain_run(minutes, drop_uah, step_s=60, start_charge=4_000_000):
    """Прогон `minutes` мин: заряд падает линейно на drop_uah, процент — на 10, сэмплы sys раз в step_s."""
    n = minutes * 60 // step_s
    recs = []
    for i in range(n + 1):
        t = T0 + i * step_s * 1000
        recs.append(_sys(t, charge=start_charge - drop_uah * i // n, pct=90.0 - 10.0 * i / n))
    return recs


def test_reads_kotlin_format_lines(tmp_path):
    p = tmp_path / "k.perf.jsonl"
    p.write_text(
        '{"type":"perf","v":1,"session_started_ms":123,"device":"Pixel \\"7\\"","profile":"full",'
        '"reorder_delay_ms":1500,"ort":"xnnpack","battery_capacity_mah":null}\n'
        '{"type":"frame","t_ms":1000,"pre":12.3,"inf":56.8,"search":3.0,"fuse_ms":0.3,"nav_ms":2.0,'
        '"e2e_ms":null,"interval_ms":500}\n'
        '{"type":"sys","t_ms":6,"batt_pct":null,"charge_uah":null,"current_ua":null,"batt_temp_c":null,'
        '"plugged":true,"thermal":null,"headroom":null,"interval_ms":500}\n'
        '{"type":"late","t_ms":7,"sources":{"frame":{"n":3,"p50":120.3,"p99":300.0,"max":410.5}},'
        '"late":2,"dropped":1}\n', encoding="utf-8")
    log = read_perf(p)
    assert log.header["device"] == 'Pixel "7"'
    assert log.header["battery_capacity_mah"] is None
    assert len(log.frames) == 1 and len(log.sys) == 1 and len(log.late) == 1
    assert log.frames[0]["e2e_ms"] is None
    render(evaluate(log))  # null-поля не ломают отчёт


def test_bad_header_is_value_error(tmp_path):
    p = tmp_path / "bad.perf.jsonl"
    p.write_text('{"type":"frame","t_ms":1}\n', encoding="utf-8")
    with pytest.raises(ValueError):
        read_perf(p)
    p.write_text("not json\n", encoding="utf-8")
    with pytest.raises(ValueError):
        read_perf(p)


def test_e2e_percentiles_from_100_frames(tmp_path):
    frames = [_frame(T0 + i * 500, e2e=i + 1) for i in range(100)]
    frames.append(_frame(T0 + 100 * 500, e2e=None))  # null отбрасывается
    res = evaluate(read_perf(_write(tmp_path, frames)))
    st = res.stages["e2e"]
    assert st.p50 == pytest.approx(50.5)
    assert st.p95 == pytest.approx(95.05)
    assert st.max == pytest.approx(100.0)
    assert res.buffer_share == pytest.approx(1500 / 50.5)
    assert res.nfr4_ok is True
    text = render(res)
    assert "NFR-4" in text and "| e2e | 100 | 50.5 |" in text


def test_e2e_over_300_fails_nfr4(tmp_path):
    frames = [_frame(T0 + i * 500, e2e=400) for i in range(20)]
    assert evaluate(read_perf(_write(tmp_path, frames))).nfr4_ok is False


def test_drain_full_vs_baseline(tmp_path):
    # ёмкость 4500 мА·ч = 4.5e6 мкА·ч; за 30 мин full теряет 900000 (40 %/ч), baseline 450000 (20 %/ч)
    full = read_perf(_write(tmp_path, _drain_run(30, 900_000), name="f.perf.jsonl"))
    base = read_perf(_write(tmp_path, _drain_run(30, 450_000), header=_header("baseline"), name="b.perf.jsonl"))
    res = evaluate(full, base)
    assert res.drain.pct_per_h == pytest.approx(40.0)
    assert res.drain.method == "charge"
    assert res.baseline_drain.pct_per_h == pytest.approx(20.0)
    assert res.excess_pct_per_h == pytest.approx(20.0)
    assert res.nfr6_ok is False
    assert res.drain.mean_current_ma == pytest.approx(450.0)
    text = render(res)
    assert "40.0 %/ч" in text and "20.0 %/ч" in text


def test_drain_within_15_passes(tmp_path):
    full = read_perf(_write(tmp_path, _drain_run(30, 500_000), name="f.perf.jsonl"))
    base = read_perf(_write(tmp_path, _drain_run(30, 450_000), header=_header("baseline"), name="b.perf.jsonl"))
    assert evaluate(full, base).nfr6_ok is True


def test_plugged_refuses_drain(tmp_path):
    recs = _drain_run(30, 900_000)
    recs[5]["plugged"] = True
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.drain.pct_per_h is None
    assert res.drain.plugged is True
    assert res.nfr6_ok is None
    assert "на зарядке, расход не считается" in render(res)


def test_no_charge_falls_back_to_percent(tmp_path):
    recs = _drain_run(30, 900_000)
    for r in recs:
        r["charge_uah"] = None
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.drain.method == "pct"
    assert res.drain.pct_per_h == pytest.approx(20.0)  # 10 % за 30 мин
    assert "по процентам, грубо" in render(res)


def test_no_capacity_falls_back_to_percent(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _drain_run(30, 900_000), header=_header(capacity=None))))
    assert res.drain.method == "pct"


def test_first_thermal_2_at_20_minutes(tmp_path):
    recs = [_sys(T0 + m * MIN, thermal=2 if m >= 20 else 1) for m in range(0, 41)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.first_thermal2_min == pytest.approx(20.0)
    assert res.thermal_share[1] == pytest.approx(0.5)
    assert res.thermal_share[2] == pytest.approx(0.5)
    assert "20 мин" in render(res)


def test_interval_steps_share(tmp_path):
    recs = [_sys(T0 + m * MIN, interval=500 if m < 30 else 1000) for m in range(0, 41)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.interval_share[500] == pytest.approx(0.75)
    assert res.interval_share[1000] == pytest.approx(0.25)
    assert (res.interval_min, res.interval_max) == (500, 1000)


def test_nfr7_61_min_cool_is_yes(tmp_path):
    recs = [_sys(T0 + m * MIN, thermal=2) for m in range(0, 62)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.duration_min == pytest.approx(61.0)
    assert res.nfr7 == "да"


def test_nfr7_30_min_is_too_short(tmp_path):
    recs = [_sys(T0 + m * MIN, thermal=0) for m in range(0, 31)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.nfr7 == "нет (короче 60 мин)"
    assert "нет (короче 60 мин)" in render(res)


def test_nfr7_thermal_3_is_no(tmp_path):
    recs = [_sys(T0 + m * MIN, thermal=3 if m == 40 else 1) for m in range(0, 62)]
    assert evaluate(read_perf(_write(tmp_path, recs))).nfr7.startswith("нет (thermal ≥ 3")


def test_lateness_p99_420_recommends_800(tmp_path):
    recs = [
        _late(T0 + 5000, {"frame": (10, 100, 420, 450), "imu": (100, 5, 9, 12)}, late=1, dropped=0),
        _late(T0 + 10000, {"frame": (10, 110, 420, 430), "gnss_fix": (5, 50, 200, 210)}, late=2, dropped=3),
    ]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.lateness["frame"].p99 == pytest.approx(420.0)
    assert res.lateness["frame"].n == 20
    assert res.lateness["frame"].max == pytest.approx(450.0)
    assert "gnss_status" not in res.lateness  # источника нет — не выдумываем
    assert (res.late_total, res.dropped_total) == (3, 3)
    assert res.recommended_delay_ms == 800
    assert "800 мс" in render(res)


def test_lateness_weighted_by_n(tmp_path):
    recs = [_late(T0 + 5000, {"frame": (30, 1, 100, 100)}), _late(T0 + 10000, {"frame": (10, 1, 500, 500)})]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.lateness["frame"].p99 == pytest.approx(200.0)
    assert res.recommended_delay_ms == 300


def test_lateness_over_1500(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, [_late(T0 + 5000, {"imu": (10, 1, 1600, 1700)})])))
    assert res.recommended_delay_ms is None
    assert "> 1500, оставить 1500 и разобраться" in render(res)


def test_cli_writes_report(tmp_path):
    full = _write(tmp_path, [_frame(T0 + 500, 80.0)] + _drain_run(30, 500_000), name="f.perf.jsonl")
    base = _write(tmp_path, _drain_run(30, 450_000), header=_header("baseline"), name="b.perf.jsonl")
    out = tmp_path / "rep" / "report.md"
    assert main(["perf-eval", "--perf", str(full), "--baseline", str(base), "--out", str(out)]) == 0
    assert "NFR-6" in out.read_text(encoding="utf-8")


@pytest.mark.parametrize("full_profile,base_profile", [("baseline", "baseline"), ("full", "full")])
def test_cli_wrong_profiles_return_2(tmp_path, full_profile, base_profile):
    full = _write(tmp_path, [], header=_header(full_profile), name="f.perf.jsonl")
    base = _write(tmp_path, [], header=_header(base_profile), name="b.perf.jsonl")
    assert main(["perf-eval", "--perf", str(full), "--baseline", str(base), "--out", str(tmp_path / "r.md")]) == 2


def test_cli_bad_format_returns_2(tmp_path):
    p = tmp_path / "x.perf.jsonl"
    p.write_text('{"type":"perf","v":2}\n', encoding="utf-8")
    assert main(["perf-eval", "--perf", str(p), "--out", str(tmp_path / "r.md")]) == 2
    assert main(["perf-eval", "--perf", str(tmp_path / "missing.jsonl"), "--out", str(tmp_path / "r.md")]) == 2
