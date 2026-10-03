import json

from vpr_bench.fieldlog import FieldFrame
from vpr_bench.geo import offset_m
from vpr_bench.m3cli import main
from vpr_bench.naveval import evaluate_nav, read_nav, render_nav_report

T0 = 1_700_000_000_000
LAT0, LON0 = 55.75, 37.6


def _ll(e, n):
    return list(offset_m(LAT0, LON0, e, n))


def _frames(path):
    """path: [(e, n)] по секунде."""
    out = []
    for s, (e, n) in enumerate(path):
        lat, lon = offset_m(LAT0, LON0, e, n)
        out.append(FieldFrame(T0 + 1000 * s, "gps", (lat, lon, 4.0, T0 + 1000 * s), None,
                              {"pre": 0.0, "inf": 0.0, "search": 0.0}))
    return out


EAST_THEN_SOUTH = [(10.0 * s, 0.0) for s in range(101)] + [(1000.0, -10.0 * s) for s in range(1, 51)]


def _route(t_s, reroute=False, turn_at=(1000.0, 0.0), poly=((0, 0), (1000, 0), (1000, -500))):
    return {"t_ms": T0 + int(t_s * 1000), "ev": "route", "reroute": reroute, "length_m": 1500.0, "duration_s": 100.0,
            "polyline": [_ll(*p) for p in poly],
            "maneuvers": [{"type": "depart", "at_m": 0.0, "lat": LAT0, "lon": LON0, "street": None, "exit": 0},
                          {"type": "right", "at_m": 1000.0, "lat": _ll(*turn_at)[0], "lon": _ll(*turn_at)[1],
                           "street": "Б", "exit": 0},
                          {"type": "arrive", "at_m": 1500.0, "lat": _ll(1000, -500)[0], "lon": _ll(1000, -500)[1],
                           "street": None, "exit": 0}]}


def _prompt(t_s, i, stage, then=None):
    return {"t_ms": T0 + int(t_s * 1000), "ev": "prompt", "maneuver": i, "then": then, "stage": stage, "dist_m": 0.0,
            "text": "x"}


def _header(**kw):
    h = {"type": "nav", "session_started_ms": T0, "roads": "r", "dest": _ll(1000, -500), "outages": [], "jams": [],
         "spoofs": []}
    h.update(kw)
    return h


def test_prompt_in_time_and_arrival():
    ev = [_route(0), _prompt(90, 1, "near"), _prompt(97, 1, "now"), {"t_ms": T0 + 150_000, "ev": "arrive"}]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    # Точка в 20 м от манёвра — около 98-й секунды (на границе допуска может оказаться 99-я).
    assert len(r.checks) == 1 and 97.5 <= r.checks[0].reached_t <= 99.5
    assert 7.5 <= r.checks[0].prompt_lead_s <= 9.5
    assert r.arrived and r.end_dist_m < 1.0
    rep = render_nav_report(r)
    assert "✅" in [l for l in rep.splitlines() if "вовремя" in l][0]


def test_late_prompt_fails():
    ev = [_route(0), _prompt(97, 1, "now")]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    assert r.checks[0].prompt_lead_s < 3.0
    assert "❌" in [l for l in render_nav_report(r).splitlines() if "вовремя" in l][0]


def test_chained_then_prompt_counts_for_next_maneuver():
    # near для манёвра 0 со сцепкой «затем» на манёвр 1 — засчитывается манёвру 1.
    ev = [_route(0), _prompt(90, 0, "near", then=1)]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    assert r.checks[0].maneuver == 1 and r.checks[0].prompt_lead_s >= 3.0
    assert "✅" in [l for l in render_nav_report(r).splitlines() if "вовремя" in l][0]


def test_window_split():
    ev = [_route(0), _prompt(90, 1, "near")]
    r = evaluate_nav(_header(outages=[[T0 + 95_000, T0 + 120_000]]), ev, _frames(EAST_THEN_SOUTH))
    assert r.checks[0].in_window


def test_false_and_true_reroutes():
    # Перестроение в 50 с, когда по GPS машина на маршруте (n = 0) — ложное.
    ev = [_route(0), _route(50, reroute=True)]
    r = evaluate_nav(_header(), ev, _frames(EAST_THEN_SOUTH))
    assert r.n_reroutes == 1 and r.false_reroutes == 1
    # Та же поездка, но маршрут вёл на север; перестроение после съезда — не ложное.
    north = _route(0, turn_at=(1000.0, 0.0), poly=((0, 0), (1000, 0), (1000, 500)))
    r2 = evaluate_nav(_header(), [north, _route(130, reroute=True)], _frames(EAST_THEN_SOUTH))
    assert r2.false_reroutes == 0


def test_unreached_maneuver_excluded():
    other = _route(0, turn_at=(3000.0, 0.0))
    r = evaluate_nav(_header(), [other], _frames(EAST_THEN_SOUTH))
    assert r.checks == []
    assert "⚠️ нет данных" in render_nav_report(r)


def test_cli_session_mismatch_and_ok(tmp_path):
    nav = tmp_path / "n.jsonl"
    nav.write_text("\n".join(json.dumps(x) for x in [_header(), _route(0), _prompt(90, 1, "near")]) + "\n")
    log = tmp_path / "s.jsonl"
    lines = [json.dumps({"type": "session", "started_ms": T0})]
    for f in _frames(EAST_THEN_SOUTH):
        g = {"lat": f.gps[0], "lon": f.gps[1], "acc_m": f.gps[2], "t_ms": f.gps[3]}
        lines.append(json.dumps({"t_ms": f.t_ms, "mode": f.mode, "gps": g, "fix": None, "lat_ms": f.lat_ms}))
    log.write_text("\n".join(lines) + "\n")
    out = tmp_path / "r.md"
    assert main(["nav-eval", "--nav", str(nav), "--log", str(log), "--out", str(out)]) == 0
    assert "Подсказки о манёврах" in out.read_text()
    bad = tmp_path / "b.jsonl"
    bad.write_text(json.dumps(_header(session_started_ms=T0 + 1)) + "\n")
    assert main(["nav-eval", "--nav", str(bad), "--log", str(log), "--out", str(out)]) == 2
    _, ev = read_nav(nav)
    assert ev[0]["ev"] == "route"
