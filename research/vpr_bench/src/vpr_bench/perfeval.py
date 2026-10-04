"""Оценка производительности M4a по `.perf.jsonl`: NFR-4 (задержка), NFR-6 (батарея), NFR-7 (нагрев), буфер."""
from __future__ import annotations

import json
import math
import warnings
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAX_E2E_P95_MS = 300.0
MAX_EXCESS_PCT_PER_H = 15.0
MIN_RUN_MIN = 60.0
DELAY_STEPS_MS = (300, 500, 800, 1500)
DELAY_MARGIN_MS = 100.0
STAGES = (("e2e", "e2e_ms"), ("pre", "pre"), ("inf", "inf"), ("search", "search"), ("fuse", "fuse_ms"),
          ("nav", "nav_ms"))
LATE_SOURCES = ("frame", "gnss_fix", "gnss_status", "imu", "agc", "other")


@dataclass(frozen=True)
class PerfLog:
    header: dict
    frames: list[dict]
    sys: list[dict]
    late: list[dict]


@dataclass(frozen=True)
class StageStats:
    n: int
    p50: float | None
    p95: float | None
    max: float | None


@dataclass(frozen=True)
class DrainResult:
    pct_per_h: float | None
    method: str | None  # "charge" | "pct" | None
    plugged: bool
    mean_current_ma: float | None


@dataclass(frozen=True)
class SourceLateness:
    n: int
    p50: float | None  # среднее по окнам, взвешенное по n
    p99: float | None  # среднее по окнам, взвешенное по n
    max: float | None


@dataclass(frozen=True)
class PerfResult:
    header: dict
    duration_min: float
    stages: dict[str, StageStats]
    buffer_share: float | None
    nfr4_ok: bool | None
    drain: DrainResult
    baseline_header: dict | None
    baseline_drain: DrainResult | None
    excess_pct_per_h: float | None
    nfr6_ok: bool | None
    first_thermal2_min: float | None
    thermal_share: dict[int | None, float]
    interval_share: dict[int, float]
    interval_min: int | None
    interval_max: int | None
    nfr7: str
    lateness: dict[str, SourceLateness]
    late_total: int
    dropped_total: int
    needed_delay_ms: float | None  # max(p99) + 100; None — нет данных об опозданиях
    recommended_delay_ms: int | None  # ступень сверху; None — нет данных или > 1500


def read_perf(path: Path) -> PerfLog:
    """Прочитать `.perf.jsonl` v1. Оборванная последняя строка пропускается с предупреждением."""
    lines = [(i, s) for i, s in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), start=1) if s.strip()]
    if not lines:
        raise ValueError(f"{path}: empty perf log")
    recs = []
    for k, (i, s) in enumerate(lines):
        try:
            rec = json.loads(s)
        except json.JSONDecodeError as e:
            if k == len(lines) - 1 and k > 0:
                warnings.warn(f"{path}: skipping truncated final line {i}: {e}")
                continue
            raise ValueError(f"{path}: malformed line {i}: {e}") from e
        if not isinstance(rec, dict) or "type" not in rec:
            raise ValueError(f"{path}: line {i} is not a perf record")
        recs.append(rec)
    header = recs[0]
    if header.get("type") != "perf" or header.get("v") != 1:
        raise ValueError(f"{path}: not a perf log v1 (first line must be type=perf, v=1)")
    by_type: dict[str, list[dict]] = {"frame": [], "sys": [], "late": []}
    for rec in recs[1:]:
        if rec["type"] in by_type:
            if not isinstance(rec.get("t_ms"), (int, float)):
                raise ValueError(f"{path}: {rec['type']} record without t_ms")
            by_type[rec["type"]].append(rec)
    return PerfLog(header, *(sorted(by_type[k], key=lambda r: r["t_ms"]) for k in ("frame", "sys", "late")))


def _vals(recs: list[dict], key: str) -> list[float]:
    return [float(r[key]) for r in recs if isinstance(r.get(key), (int, float)) and math.isfinite(r[key])]


def _stage(values: list[float]) -> StageStats:
    if not values:
        return StageStats(0, None, None, None)
    a = np.asarray(values, dtype=float)
    return StageStats(len(values), float(np.percentile(a, 50)), float(np.percentile(a, 95)), float(a.max()))


def _span(log: PerfLog) -> tuple[float, float]:
    ts = [r["t_ms"] for r in (*log.frames, *log.sys, *log.late)]
    start = log.header.get("session_started_ms")
    if isinstance(start, (int, float)):
        ts.append(start)
    if not ts:
        return 0.0, 0.0
    return float(min(ts)), float(max(ts))


def _drain(log: PerfLog) -> DrainResult:
    currents = _vals(log.sys, "current_ua")
    mean_ma = float(np.mean(currents)) / 1000.0 if currents else None
    if any(r.get("plugged") is True for r in log.sys):
        return DrainResult(None, None, True, mean_ma)
    cap = log.header.get("battery_capacity_mah")
    charged = [r for r in log.sys if isinstance(r.get("charge_uah"), (int, float))]
    if isinstance(cap, (int, float)) and cap > 0 and len(charged) >= 2:
        a, b = charged[0], charged[-1]
        hours = (b["t_ms"] - a["t_ms"]) / 3.6e6
        if hours > 0:
            return DrainResult((a["charge_uah"] - b["charge_uah"]) / (cap * 1000.0) / hours * 100.0, "charge",
                               False, mean_ma)
    pcts = [r for r in log.sys if isinstance(r.get("batt_pct"), (int, float))]
    if len(pcts) >= 2:
        a, b = pcts[0], pcts[-1]
        hours = (b["t_ms"] - a["t_ms"]) / 3.6e6
        if hours > 0:
            return DrainResult((a["batt_pct"] - b["batt_pct"]) / hours, "pct", False, mean_ma)
    return DrainResult(None, None, False, mean_ma)


def _time_shares(sys: list[dict], key: str) -> dict:
    """Доля времени по значению поля: сэмпл действует до следующего (последний — без длительности)."""
    dur: dict = {}
    for a, b in zip(sys, sys[1:]):
        dur[a.get(key)] = dur.get(a.get(key), 0.0) + (b["t_ms"] - a["t_ms"])
    total = sum(dur.values())
    return {k: v / total for k, v in dur.items()} if total > 0 else {}


def _weighted(windows: list[tuple[int, float | None]]) -> float | None:
    pairs = [(n, v) for n, v in windows if v is not None and n > 0]
    w = sum(n for n, _ in pairs)
    return sum(n * v for n, v in pairs) / w if w > 0 else None


def _lateness(late: list[dict]) -> dict[str, SourceLateness]:
    acc: dict[str, list[dict]] = {}
    for rec in late:
        for src, st in (rec.get("sources") or {}).items():
            if isinstance(st, dict):
                acc.setdefault(src, []).append(st)
    order = [s for s in LATE_SOURCES if s in acc] + sorted(s for s in acc if s not in LATE_SOURCES)
    out = {}
    for src in order:
        ws = acc[src]
        ns = [int(w.get("n") or 0) for w in ws]

        def num(w, k):
            v = w.get(k)
            return float(v) if isinstance(v, (int, float)) and math.isfinite(v) else None

        maxes = [m for m in (num(w, "max") for w in ws) if m is not None]
        out[src] = SourceLateness(sum(ns), _weighted([(n, num(w, "p50")) for n, w in zip(ns, ws)]),
                                  _weighted([(n, num(w, "p99")) for n, w in zip(ns, ws)]),
                                  max(maxes) if maxes else None)
    return out


def evaluate(full: PerfLog, baseline: PerfLog | None = None) -> PerfResult:
    start, end = _span(full)
    duration_min = (end - start) / 60_000.0
    stages = {name: _stage(_vals(full.frames, key)) for name, key in STAGES}
    e2e = stages["e2e"]
    delay = full.header.get("reorder_delay_ms")
    buffer_share = (delay / e2e.p50 if isinstance(delay, (int, float)) and e2e.p50 is not None and e2e.p50 > 0
                    else None)
    nfr4_ok = None if e2e.p95 is None else e2e.p95 <= MAX_E2E_P95_MS

    drain = _drain(full)
    base_drain = _drain(baseline) if baseline is not None else None
    excess = (drain.pct_per_h - base_drain.pct_per_h
              if base_drain is not None and drain.pct_per_h is not None and base_drain.pct_per_h is not None
              else None)
    nfr6_ok = None if excess is None else excess <= MAX_EXCESS_PCT_PER_H

    first2 = next((r["t_ms"] for r in full.sys if isinstance(r.get("thermal"), int) and r["thermal"] >= 2), None)
    first3 = next((r["t_ms"] for r in full.sys if isinstance(r.get("thermal"), int) and r["thermal"] >= 3), None)
    intervals = [int(v) for v in _vals(full.sys, "interval_ms")]
    reasons = []
    if duration_min < MIN_RUN_MIN:
        reasons.append("короче 60 мин")
    if first3 is not None:
        reasons.append(f"thermal ≥ 3 на {(first3 - start) / 60_000.0:.0f}-й мин")
    if reasons:
        nfr7 = f"нет ({', '.join(reasons)})"
    elif not any(isinstance(r.get("thermal"), int) for r in full.sys):
        nfr7 = "нет данных (thermal недоступен)"
    else:
        nfr7 = "да"

    lateness = _lateness(full.late)
    p99s = [s.p99 for s in lateness.values() if s.p99 is not None]
    needed = max(p99s) + DELAY_MARGIN_MS if p99s else None
    recommended = next((s for s in DELAY_STEPS_MS if needed is not None and needed <= s), None)

    return PerfResult(
        header=full.header, duration_min=duration_min, stages=stages, buffer_share=buffer_share, nfr4_ok=nfr4_ok,
        drain=drain, baseline_header=baseline.header if baseline is not None else None, baseline_drain=base_drain,
        excess_pct_per_h=excess, nfr6_ok=nfr6_ok,
        first_thermal2_min=None if first2 is None else (first2 - start) / 60_000.0,
        thermal_share=_time_shares(full.sys, "thermal"), interval_share=_time_shares(full.sys, "interval_ms"),
        interval_min=min(intervals) if intervals else None, interval_max=max(intervals) if intervals else None,
        nfr7=nfr7, lateness=lateness,
        late_total=sum(int(r.get("late") or 0) for r in full.late),
        dropped_total=sum(int(r.get("dropped") or 0) for r in full.late),
        needed_delay_ms=needed, recommended_delay_ms=recommended,
    )


def _mark(ok: bool | None) -> str:
    return {True: "✅", False: "❌", None: "⚠️ нет данных"}[ok]


def _f(x: float | None, nd: int = 1) -> str:
    return "—" if x is None else f"{x:.{nd}f}"


def _with_unit(x: float | None, unit: str) -> str:
    """Значение с единицей и пробелом перед отметкой; пусто, если значения нет (отметка скажет «нет данных»)."""
    return "" if x is None else f"{x:.1f} {unit} "


def _drain_text(d: DrainResult | None) -> str:
    if d is None:
        return "нет прогона"
    if d.plugged:
        return "на зарядке, расход не считается"
    if d.pct_per_h is None:
        return "нет данных"
    return f"{d.pct_per_h:.1f} %/ч" + (" (по процентам, грубо)" if d.method == "pct" else "")


def render(r: PerfResult) -> str:
    h = r.header
    cap = h.get("battery_capacity_mah")
    lines = [
        "# Производительность M4a", "",
        "## Прогон", "",
        f"- Устройство: {h.get('device', '—')}",
        f"- Профиль: {h.get('profile', '—')}",
        f"- Длительность: {r.duration_min:.1f} мин",
        f"- Буфер переупорядочивания: {h.get('reorder_delay_ms', '—')} мс",
        f"- ORT: {h.get('ort', '—')}",
        f"- Ёмкость батареи: {'—' if cap is None else f'{cap} мА·ч'}",
        "",
        "## NFR-4: задержка кадра", "",
        "| этап | n | p50, мс | p95, мс | max, мс |",
        "|---|---|---|---|---|",
    ]
    for name, st in r.stages.items():
        lines.append(f"| {name} | {st.n} | {_f(st.p50)} | {_f(st.p95)} | {_f(st.max)} |")
    lines += [
        "",
        f"- Доля буфера: reorder_delay_ms / p50(e2e) = {_f(r.buffer_share, 2)}",
        f"- p95 e2e ≤ {MAX_E2E_P95_MS:.0f} мс — {_with_unit(r.stages['e2e'].p95, 'мс')}{_mark(r.nfr4_ok)}",
        "",
        "## NFR-6: батарея", "",
        f"- Расход full: {_drain_text(r.drain)}",
        f"- Расход baseline: {_drain_text(r.baseline_drain)}",
        f"- Превышение full над baseline ≤ {MAX_EXCESS_PCT_PER_H:.0f} %/ч — "
        f"{_with_unit(r.excess_pct_per_h, '%/ч')}{_mark(r.nfr6_ok)}",
        f"- Средний ток full: {'—' if r.drain.mean_current_ma is None else f'{r.drain.mean_current_ma:.0f} мА'}",
    ]
    if r.baseline_drain is not None:
        ma = r.baseline_drain.mean_current_ma
        lines.append(f"- Средний ток baseline: {'—' if ma is None else f'{ma:.0f} мА'}")
    first2 = "не было" if r.first_thermal2_min is None else f"{r.first_thermal2_min:.0f} мин"
    lines += [
        "",
        "## NFR-7: нагрев", "",
        f"- Время до первого thermal ≥ 2: {first2}",
        "",
        "| thermal | доля времени |",
        "|---|---|",
    ]
    for lvl in sorted(r.thermal_share, key=lambda k: (k is None, k if k is not None else 0)):
        lines.append(f"| {'нет данных' if lvl is None else lvl} | {r.thermal_share[lvl] * 100:.1f} % |")
    lines += ["", "| интервал кадров, мс | доля времени |", "|---|---|"]
    for iv in sorted(r.interval_share):
        lines.append(f"| {iv} | {r.interval_share[iv] * 100:.1f} % |")
    lines += [
        "",
        f"- Интервал кадров: мин {r.interval_min if r.interval_min is not None else '—'} мс, "
        f"макс {r.interval_max if r.interval_max is not None else '—'} мс",
        f"- ≥ 60 мин и thermal < 3 всё время — {r.nfr7}",
        "",
        "## Опоздания событий", "",
        "| источник | p50, мс | p99, мс | max, мс | n |",
        "|---|---|---|---|---|",
    ]
    for src, st in r.lateness.items():
        lines.append(f"| {src} | {_f(st.p50)} | {_f(st.p99)} | {_f(st.max)} | {st.n} |")
    if r.needed_delay_ms is None:
        rec = "нет данных"
    elif r.recommended_delay_ms is None:
        rec = "> 1500, оставить 1500 и разобраться"
    else:
        rec = f"{r.recommended_delay_ms} мс"
    lines += [
        "",
        "p50 и p99 — средние по окнам, взвешенные по n.",
        "",
        f"- Опоздавших (late): {r.late_total}, отброшенных (dropped): {r.dropped_total}",
        f"- Рекомендация буфера: max(p99) + {DELAY_MARGIN_MS:.0f} = {_f(r.needed_delay_ms)} мс → {rec}",
        "",
    ]
    return "\n".join(lines)
