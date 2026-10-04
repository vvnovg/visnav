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
    assert res.buffer_ratio == pytest.approx(1500 / 50.5)
    assert res.nfr4_ok is True
    text = render(res)
    assert "NFR-4" in text and "| e2e | 100 | 50.5 |" in text
    assert "×29.7" in text


def test_e2e_over_300_fails_nfr4(tmp_path):
    frames = [_frame(T0 + i * 500, e2e=400) for i in range(20)]
    assert evaluate(read_perf(_write(tmp_path, frames))).nfr4_ok is False


def test_no_frames_is_nfr4_no_data(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(5))))
    assert res.nfr4_ok is None
    assert res.buffer_ratio is None
    assert "p95 e2e ≤ 300 мс — ⚠️ нет данных" in render(res)


def test_empty_file_is_value_error_and_cli_2(tmp_path):
    p = tmp_path / "e.perf.jsonl"
    p.write_text("\n", encoding="utf-8")
    with pytest.raises(ValueError):
        read_perf(p)
    assert main(["perf-eval", "--perf", str(p), "--out", str(tmp_path / "r.md")]) == 2


def test_truncated_last_line_skipped_middle_line_fails(tmp_path):
    good = [json.dumps(r, separators=(",", ":")) for r in (_header(), _sys(T0), _sys(T0 + 5000))]
    p = tmp_path / "t.perf.jsonl"
    p.write_text("\n".join(good) + '\n{"type":"sys","t_ms":17000', encoding="utf-8")
    with pytest.warns(UserWarning, match="truncated final line"):
        log = read_perf(p)
    assert len(log.sys) == 2
    p.write_text("\n".join([good[0], '{"type":"sys","t_ms":17000', good[1], good[2]]) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="malformed line 2"):
        read_perf(p)


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
    assert res.nfr6_rough is False
    assert res.run_warnings == []
    assert res.drain.mean_current_ma == pytest.approx(450.0)
    text = render(res)
    assert "40.0 %/ч" in text and "20.0 %/ч" in text and "450 мА (разряд)" in text


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


def test_partial_charge_uses_non_null_samples(tmp_path):
    recs = _drain_run(30, 900_000)
    for r in recs[10:21]:
        r["charge_uah"] = None
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.drain.method == "charge"
    assert res.drain.pct_per_h == pytest.approx(40.0)


def test_no_capacity_falls_back_to_percent(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _drain_run(30, 900_000), header=_header(capacity=None))))
    assert res.drain.method == "pct"


def test_jumping_charge_counter_falls_back_to_percent(tmp_path):
    recs = _drain_run(30, 900_000)
    recs[10]["charge_uah"] = recs[9]["charge_uah"] + 30_000  # > 0.5 % от 4.5e6
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.drain.method == "pct"
    assert "счётчик скачет" in res.drain.notes
    assert "счётчик скачет" in render(res)


def test_negative_drain_warns(tmp_path):
    recs = [_sys(T0 + m * MIN, charge=3_000_000 + 10_000 * m, pct=50.0 + m * 0.2) for m in range(31)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.drain.pct_per_h < 0
    assert any("отрицательный" in n for n in res.drain.notes)


def test_mixed_methods_rough_and_run_mismatch_warnings(tmp_path):
    full = read_perf(_write(tmp_path, _drain_run(30, 900_000), header=_header(capacity=None), name="f.perf.jsonl"))
    base_header = dict(_header("baseline"), device="Other")
    base = read_perf(_write(tmp_path, _drain_run(15, 225_000), header=base_header, name="b.perf.jsonl"))
    res = evaluate(full, base)
    assert res.nfr6_rough is True
    assert any("разные устройства" in w for w in res.run_warnings)
    assert any("длительности" in w for w in res.run_warnings)
    assert "(грубо)" in render(res).split("Превышение")[1].splitlines()[0]


def test_mean_current_time_weighted_without_charger(tmp_path):
    recs = [_sys(T0, current=-400_000), _sys(T0 + 10_000, current=-800_000),
            _sys(T0 + 15_000, current=-5_000_000, plugged=True), _sys(T0 + 20_000, current=0)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.drain.mean_current_ma == pytest.approx((400 * 10 + 800 * 5) / 15)


def _sys_run(minutes, step_s=5, thermal=lambda m: 1, interval=lambda m: 500, skip=lambda m: False):
    """Сэмплы sys каждые step_s с; thermal и interval — функции минуты от начала."""
    out = []
    for i in range(minutes * 60 // step_s + 1):
        m = i * step_s / 60.0
        if not skip(m):
            out.append(_sys(T0 + i * step_s * 1000, thermal=thermal(m), interval=interval(m)))
    return out


def test_first_thermal_2_at_20_minutes(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(40, thermal=lambda m: 2 if m >= 20 else 1))))
    assert res.first_thermal2_min == pytest.approx(20.0)
    assert res.thermal_share[1] == pytest.approx(0.5)
    assert res.thermal_share[2] == pytest.approx(0.5)
    assert "Время до первого thermal ≥ 2: 20.0 мин" in render(res)


def test_interval_steps_share(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(40, interval=lambda m: 500 if m < 30 else 1000))))
    assert res.interval_share[500] == pytest.approx(0.75)
    assert res.interval_share[1000] == pytest.approx(0.25)
    assert (res.interval_min, res.interval_max) == (500, 1000)


def test_nfr7_61_min_cool_is_yes(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(61, thermal=lambda m: 2))))
    assert res.duration_min == pytest.approx(61.0)
    assert res.thermal_coverage == pytest.approx(1.0)
    assert res.gaps_ms == []
    assert res.nfr7 == "да"


def test_nfr7_30_min_is_too_short(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(30, thermal=lambda m: 0))))
    assert res.nfr7 == "нет (короче 60 мин)"
    assert "нет (короче 60 мин)" in render(res)


def test_nfr7_thermal_3_is_no(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(61, thermal=lambda m: 3 if 40 <= m < 41 else 1))))
    assert res.nfr7.startswith("нет (thermal ≥ 3 на 40.0 мин")


def test_nfr7_single_thermal_rest_null_is_incomplete(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(61, thermal=lambda m: 0 if m == 0 else None))))
    assert res.nfr7.startswith("неполные данные (thermal известен 0 %")


def test_all_thermal_null(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _sys_run(61, thermal=lambda m: None))))
    assert res.first_thermal2_min is None
    assert res.thermal_share == {None: pytest.approx(1.0)}
    assert res.nfr7.startswith("неполные данные")
    assert "| нет данных | 100.0 % |" in render(res)


def test_ten_minute_gap_is_flagged_and_excluded(tmp_path):
    recs = _sys_run(61, thermal=lambda m: 2 if m < 20 else 1, skip=lambda m: 20 < m < 30)
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.gaps_ms == [600_000]
    assert res.nfr7.startswith("неполные данные")
    assert "разрывов sys > 15 с: 1, самый долгий 600 с" in res.nfr7
    assert res.thermal_share[2] == pytest.approx(20 / 51)  # разрыв в доли не входит
    assert "1, самый долгий 600 с" in render(res)


def _windows(k, src, n, p50, p99, mx, t0=0):
    return [_late(T0 + (t0 + i) * 5000, {src: (n, p50, p99, mx)}) for i in range(k)]


def test_lateness_p99_420_recommends_800(tmp_path):
    recs = _windows(20, "frame", 100, 100, 420, 450)
    recs[0]["sources"]["imu"] = {"n": 100, "p50": 5.0, "p99": 9.0, "max": 12.0}
    res = evaluate(read_perf(_write(tmp_path, recs)))
    fr = res.lateness["frame"]
    assert (fr.n, fr.p50_mean, fr.p99_max, fr.max) == (2000, 100.0, 420.0, 450.0)
    assert fr.share_ub(200) == pytest.approx(0.5)
    assert fr.share_ub(700) == 0.0
    assert "gnss_status" not in res.lateness  # источника нет — не выдумываем
    assert res.recommended_delay_ms == 800
    text = render(res)
    assert "→ 800 мс" in text and "| imu (мало данных) | 100 |" in text


def test_late_and_dropped_are_cumulative_counters(tmp_path):
    recs = [_late(T0 + 5000, {"frame": (4000, 10, 50, 60)}, late=1, dropped=0),
            _late(T0 + 10000, {"frame": (4000, 10, 50, 60)}, late=2, dropped=3),
            _late(T0 + 15000, {"frame": (2000, 10, 50, 60)}, late=10, dropped=3)]
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert (res.late_count, res.dropped_count) == (10, 3)
    assert res.late_share == pytest.approx(10 / 10_000)
    assert "Доля опоздавших при текущем буфере: 0.10 % (цель ≤ 0.1 %)" in render(res)


def test_rare_burst_is_not_300(tmp_path):
    recs = _windows(718, "gnss_fix", 5, 50, 100, 150) + _windows(2, "gnss_fix", 5, 50, 1300, 1400, t0=718)
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.lateness["gnss_fix"].n == 3600
    assert res.recommended_delay_ms != 300
    assert res.recommended_delay_ms == 1500


def test_quiet_log_recommends_300(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _windows(20, "frame", 100, 50, 120, 150))))
    assert res.recommended_delay_ms == 300
    assert res.recommendation == "ok"


def test_sparse_source_does_not_affect_choice(tmp_path):
    recs = _windows(20, "frame", 100, 50, 120, 150)
    recs[0]["sources"]["imu"] = {"n": 10, "p50": 1.0, "p99": 1600.0, "max": 1700.0}
    res = evaluate(read_perf(_write(tmp_path, recs)))
    assert res.recommended_delay_ms == 300


def test_lateness_over_1500(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _windows(20, "imu", 100, 10, 1600, 1700))))
    assert res.recommended_delay_ms is None
    assert res.recommendation == "over"
    assert "> 1500, оставить 1500 и разобраться" in render(res)


def test_only_sparse_sources_is_no_data(tmp_path):
    res = evaluate(read_perf(_write(tmp_path, _windows(3, "imu", 10, 10, 1600, 1700))))
    assert res.recommendation == "few"
    assert "нет данных (ни у одного источника нет 1000 событий)" in render(res)


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
