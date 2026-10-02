"""Оценка монитора GNSS (M2c): задержки обнаружения, ложные тревоги, полнота на реальной подмене."""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from vpr_bench.fieldlog import FieldFrame, gps_track
from vpr_bench.geo import haversine_m, interpolate_track
from vpr_bench.query import clean_track, pose_at
from vpr_bench.replayeval import TrajRow

MAX_LATENCY_S = 5.0
MAX_FALSE_UNTRUSTED_PCT = 1.0
MAX_SPOOF_P95_M = 15.0
MIN_RECALL_PCT = 90.0


@dataclass(frozen=True)
class WindowResult:
    kind: str  # "spoof" | "jam"
    start_ms: int
    end_ms: int
    latency_s: float | None
    p95_err_m: float


@dataclass(frozen=True)
class MonitorResult:
    windows: list[WindowResult]
    false_untrusted_pct: float
    non_gnss_clean_pct: float
    real_spoof_recall_pct: float | None
    n_real_spoof_s: int
    monitor: bool | None = None  # флаг "monitor" из заголовка траектории (None — в старом формате его нет)
    has_monitor_fields: bool = True  # False — траектория M2a без mode/health


def _latency(rows: list[TrajRow], start_ms: int, end_ms: int, detected) -> float | None:
    for r in rows:
        if start_ms <= r.t_ms < end_ms and detected(r):
            return (r.t_ms - start_ms) / 1000.0
    return None


def evaluate_monitor(
    header: dict,
    rows: list[TrajRow],
    log_frames: list[FieldFrame],
    warmup_s: float = 60.0,
    min_speed_mps: float = 2.0,
) -> MonitorResult:
    raw = gps_track(log_frames)
    track, _ = clean_track(raw, max_speed_mps=70.0, max_hdop=None)
    has_fields = any(r.health is not None or r.mode is not None for r in rows)

    spoof_windows = [(int(s[0]), int(s[1])) for s in header.get("spoofs", [])]  # 4 или 5 элементов
    jam_windows = [(int(j[0]), int(j[1])) for j in header.get("jams", [])]
    outage_windows = [(int(o[0]), int(o[1])) for o in header.get("outages", [])]

    def p95(start_ms: int, end_ms: int) -> float:
        errs = []
        for r in rows:
            if start_ms <= r.t_ms < end_ms:
                gt = interpolate_track(track, r.t_ms / 1000.0)
                if gt is not None:
                    errs.append(haversine_m(gt[0], gt[1], r.lat, r.lon))
        return float(np.quantile(np.array(errs), 0.95, method="higher")) if errs else float("nan")

    windows: list[WindowResult] = []
    for s, e in spoof_windows:
        lat = _latency(rows, s, e, lambda r: r.health == "untrusted") if has_fields else None
        windows.append(WindowResult("spoof", s, e, lat, p95(s, e)))
    for s, e in jam_windows:
        lat = _latency(rows, s, e, lambda r: r.mode is not None and r.mode != "gnss") if has_fields else None
        windows.append(WindowResult("jam", s, e, lat, p95(s, e)))

    all_windows = spoof_windows + jam_windows + outage_windows
    t_start_ms = header.get("session_started_ms")
    if t_start_ms is None:
        t_start_ms = rows[0].t_ms if rows else 0
    clean = []
    for r in rows:
        if r.t_ms < t_start_ms + warmup_s * 1000.0:
            continue
        if any(s <= r.t_ms < e for s, e in all_windows):
            continue
        pose = pose_at(track, r.t_ms / 1000.0)
        if pose is None or pose[3] < min_speed_mps:
            continue
        clean.append(r)
    if has_fields and clean:
        false_pct = sum(r.health == "untrusted" for r in clean) / len(clean) * 100
        non_gnss_pct = sum(r.mode is not None and r.mode != "gnss" for r in clean) / len(clean) * 100
    else:
        false_pct = non_gnss_pct = float("nan")

    kept = {p.t for p in track}
    marks = sorted({int(p.t) for p in raw if p.t not in kept})
    recall: float | None = None
    if marks and has_fields:
        hit = 0
        for sec in marks:
            near = [r for r in rows if abs(r.t_ms / 1000.0 - sec) <= 1.0]
            if near:
                nearest = min(near, key=lambda r: abs(r.t_ms / 1000.0 - sec))
                hit += nearest.health == "untrusted"
        recall = hit / len(marks) * 100

    return MonitorResult(windows, false_pct, non_gnss_pct, recall, len(marks),
                         monitor=header.get("monitor"), has_monitor_fields=has_fields)


def _mark(ok: bool) -> str:
    return "✅" if ok else "❌"


def _window_verdict(kind: str, ws: list[WindowResult], has_fields: bool) -> str:
    if not ws or not has_fields:
        return "⚠️ нет данных"
    missed = sum(w.latency_s is None for w in ws)
    seen = [w.latency_s for w in ws if w.latency_s is not None]
    worst = f"худшая {max(seen):.1f} с" if seen else "нет обнаружений"
    ok = missed == 0 and max(seen) <= MAX_LATENCY_S
    return f"{worst}, не обнаружено {missed} из {len(ws)} {_mark(ok)}"


def render_monitor_report(r: MonitorResult) -> str:
    flag = {True: "включён", False: "выключен", None: "неизвестно (старый формат)"}[r.monitor]
    lines = ["# Монитор GNSS M2c", "", f"Режим: монитор: {flag}", ""]
    spoofs = [w for w in r.windows if w.kind == "spoof"]
    jams = [w for w in r.windows if w.kind == "jam"]
    lines.append(f"- Задержка обнаружения подмены ≤ {MAX_LATENCY_S:.0f} с — "
                 f"{_window_verdict('spoof', spoofs, r.has_monitor_fields)}")
    lines.append(f"- Задержка выхода из GNSS при глушении ≤ {MAX_LATENCY_S:.0f} с — "
                 f"{_window_verdict('jam', jams, r.has_monitor_fields)}")
    if math.isnan(r.false_untrusted_pct):
        lines.append(f"- Ложное UNTRUSTED ≤ {MAX_FALSE_UNTRUSTED_PCT:.0f} % чистого времени — ⚠️ нет данных")
    else:
        ok = r.false_untrusted_pct <= MAX_FALSE_UNTRUSTED_PCT
        lines.append(f"- Ложное UNTRUSTED ≤ {MAX_FALSE_UNTRUSTED_PCT:.0f} % чистого времени — "
                     f"{r.false_untrusted_pct:.2f} % {_mark(ok)}")
    p95s = [w.p95_err_m for w in spoofs if not math.isnan(w.p95_err_m)]
    if not p95s or not r.has_monitor_fields:
        lines.append(f"- P95 ошибки в окнах подмены ≤ {MAX_SPOOF_P95_M:.0f} м — ⚠️ нет данных")
    else:
        lines.append(f"- P95 ошибки в окнах подмены ≤ {MAX_SPOOF_P95_M:.0f} м — "
                     f"{max(p95s):.1f} м {_mark(max(p95s) <= MAX_SPOOF_P95_M)}")
    if r.real_spoof_recall_pct is None:
        lines.append(f"- Полнота на реальной подмене ≥ {MIN_RECALL_PCT:.0f} % — ⚠️ нет данных")
    else:
        lines.append(f"- Полнота на реальной подмене ≥ {MIN_RECALL_PCT:.0f} % — "
                     f"{r.real_spoof_recall_pct:.0f} % из {r.n_real_spoof_s} с "
                     f"{_mark(r.real_spoof_recall_pct >= MIN_RECALL_PCT)}")
    lines += ["", f"Доля чистого времени не в режиме GNSS: "
                  f"{'н/д' if math.isnan(r.non_gnss_clean_pct) else f'{r.non_gnss_clean_pct:.2f} %'}"]
    if r.windows:
        lines += ["", "| Окно | Начало, мс | Конец, мс | Задержка, с | P95 ошибки, м |", "|---|---|---|---|---|"]
        for w in r.windows:
            lat = "—" if w.latency_s is None else f"{w.latency_s:.1f}"
            lines.append(f"| {w.kind} | {w.start_ms} | {w.end_ms} | {lat} | {w.p95_err_m:.1f} |")
    return "\n".join(lines) + "\n"
