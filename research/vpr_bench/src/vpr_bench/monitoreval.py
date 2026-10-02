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
DETECT_OFFSET_M = 100.0  # подмена < этого смещения не входит в вердикт по задержке


@dataclass(frozen=True)
class WindowResult:
    kind: str  # "spoof" | "jam"
    start_ms: int
    end_ms: int
    latency_s: float | None
    p95_err_m: float
    n_rows: int = 0  # строк траектории внутри окна; 0 — окно пустое (нет данных)
    small: bool = False  # смещение подмены < 100 м: задержка от начала окна, вне вердикта


@dataclass(frozen=True)
class MonitorResult:
    windows: list[WindowResult]
    false_untrusted_pct: float
    non_gnss_clean_pct: float
    real_spoof_recall_pct: float | None
    n_real_spoof_s: int
    monitor: bool | None = None  # флаг "monitor" из заголовка траектории (None — в старом формате его нет)
    has_monitor_fields: bool = True  # False — траектория M2a без mode/health
    tail_s: float = 20.0  # хвост, исключаемый из чистого времени после окон и меток подмены


def _first_detection_ms(rows: list[TrajRow], start_ms: int, end_ms: int, detected) -> int | None:
    for r in rows:
        if start_ms <= r.t_ms < end_ms and detected(r):
            return r.t_ms
    return None


def evaluate_monitor(
    header: dict,
    rows: list[TrajRow],
    log_frames: list[FieldFrame],
    warmup_s: float = 60.0,
    min_speed_mps: float = 2.0,
    tail_s: float = 20.0,
) -> MonitorResult:
    raw = gps_track(log_frames)
    track, _ = clean_track(raw, max_speed_mps=70.0, max_hdop=None)
    has_fields = any(r.health is not None or r.mode is not None for r in rows)

    spoofs = []  # (start_ms, end_ms, t100_ms, small); окно — [s, e, east, north, ramp_ms?]
    for w in header.get("spoofs", []):
        s_ms, e_ms = int(w[0]), int(w[1])
        offset = math.hypot(float(w[2]), float(w[3]))
        ramp_ms = float(w[4]) if len(w) > 4 else 0.0
        if offset >= DETECT_OFFSET_M:
            spoofs.append((s_ms, e_ms, s_ms + ramp_ms * min(1.0, DETECT_OFFSET_M / offset), False))
        else:
            spoofs.append((s_ms, e_ms, float(s_ms), True))
    spoof_windows = [(s, e) for s, e, _, _ in spoofs]
    jam_windows = [(int(j[0]), int(j[1])) for j in header.get("jams", [])]
    outage_windows = [(int(o[0]), int(o[1])) for o in header.get("outages", [])]

    def p95(start_ms: int, end_ms: int) -> float:
        errs = []
        for r in rows:
            if start_ms <= r.t_ms < end_ms and not math.isnan(r.lat):
                gt = interpolate_track(track, r.t_ms / 1000.0)
                if gt is not None:
                    errs.append(haversine_m(gt[0], gt[1], r.lat, r.lon))
        return float(np.quantile(np.array(errs), 0.95, method="higher")) if errs else float("nan")

    def n_in(start_ms: int, end_ms: int) -> int:
        return sum(start_ms <= r.t_ms < end_ms for r in rows)

    windows: list[WindowResult] = []
    for s, e, t100, small in spoofs:
        first = _first_detection_ms(rows, s, e, lambda r: r.health == "untrusted") if has_fields else None
        lat = None if first is None else max(0.0, (first - t100) / 1000.0)
        windows.append(WindowResult("spoof", s, e, lat, p95(s, e), n_in(s, e), small))
    for s, e in jam_windows:
        first = _first_detection_ms(rows, s, e, lambda r: r.mode is not None and r.mode != "gnss") \
            if has_fields else None
        lat = None if first is None else (first - s) / 1000.0
        windows.append(WindowResult("jam", s, e, lat, p95(s, e), n_in(s, e)))

    all_windows = spoof_windows + jam_windows + outage_windows
    t_start_ms = header.get("session_started_ms")
    if t_start_ms is None:
        t_start_ms = rows[0].t_ms if rows else 0
    kept = {p.t for p in track}
    marks = sorted({int(p.t) for p in raw if p.t not in kept})
    clean = []
    for r in rows:
        if r.t_ms < t_start_ms + warmup_s * 1000.0:
            continue
        if any(s <= r.t_ms < e + tail_s * 1000.0 for s, e in all_windows):
            continue
        t_s = r.t_ms / 1000.0
        if any(m - 1.0 <= t_s <= m + tail_s for m in marks):
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
                         monitor=header.get("monitor"), has_monitor_fields=has_fields, tail_s=tail_s)


def _mark(ok: bool) -> str:
    return "✅" if ok else "❌"


def _window_verdict(ws: list[WindowResult], has_fields: bool) -> str:
    ws = [w for w in ws if not w.small]
    empty = sum(w.n_rows == 0 for w in ws)
    ws = [w for w in ws if w.n_rows > 0]
    if not ws or not has_fields:
        return "⚠️ нет данных"
    missed = sum(w.latency_s is None for w in ws)
    seen = [w.latency_s for w in ws if w.latency_s is not None]
    worst = f"худшая {max(seen):.1f} с" if seen else "нет обнаружений"
    ok = missed == 0 and max(seen) <= MAX_LATENCY_S
    note = f", без данных {empty}" if empty else ""
    return f"{worst}, не обнаружено {missed} из {len(ws)}{note} {_mark(ok)}"


def render_monitor_report(r: MonitorResult) -> str:
    flag = {True: "включён", False: "выключен", None: "неизвестно (старый формат)"}[r.monitor]
    lines = ["# Монитор GNSS M2c", "", f"Режим: монитор: {flag}", ""]
    spoofs = [w for w in r.windows if w.kind == "spoof"]
    jams = [w for w in r.windows if w.kind == "jam"]
    lines.append(f"- Задержка обнаружения подмены ≤ {MAX_LATENCY_S:.0f} с — "
                 f"{_window_verdict(spoofs, r.has_monitor_fields)}")
    lines.append(f"- Задержка выхода из GNSS при глушении ≤ {MAX_LATENCY_S:.0f} с — "
                 f"{_window_verdict(jams, r.has_monitor_fields)}")
    if math.isnan(r.false_untrusted_pct):
        lines.append(f"- Ложное UNTRUSTED ≤ {MAX_FALSE_UNTRUSTED_PCT:.0f} % чистого времени — ⚠️ нет данных")
    else:
        ok = r.false_untrusted_pct <= MAX_FALSE_UNTRUSTED_PCT
        lines.append(f"- Ложное UNTRUSTED ≤ {MAX_FALSE_UNTRUSTED_PCT:.0f} % чистого времени — "
                     f"{r.false_untrusted_pct:.2f} % {_mark(ok)}")
    p95s = [w.p95_err_m for w in spoofs if not math.isnan(w.p95_err_m)]
    p95_missing = len(spoofs) - len(p95s)
    if not p95s or not r.has_monitor_fields:
        lines.append(f"- P95 ошибки в окнах подмены ≤ {MAX_SPOOF_P95_M:.0f} м — ⚠️ нет данных")
    else:
        lines.append(f"- P95 ошибки в окнах подмены ≤ {MAX_SPOOF_P95_M:.0f} м — "
                     f"{max(p95s):.1f} м{f', окон без данных {p95_missing}' if p95_missing else ''} "
                     f"{_mark(max(p95s) <= MAX_SPOOF_P95_M)}")
    if r.real_spoof_recall_pct is None:
        lines.append(f"- Полнота на реальной подмене ≥ {MIN_RECALL_PCT:.0f} % — ⚠️ нет данных")
    else:
        lines.append(f"- Полнота на реальной подмене ≥ {MIN_RECALL_PCT:.0f} % — "
                     f"{r.real_spoof_recall_pct:.0f} % из {r.n_real_spoof_s} с "
                     f"{_mark(r.real_spoof_recall_pct >= MIN_RECALL_PCT)}")
    tail = f"{r.tail_s:g}"
    lines += ["", f"Чистое время: после прогрева, в движении, вне окон (подмена, глушение, пропадание) "
                  f"и {tail} с после их конца, и вне [метка − 1 с, метка + {tail} с] реальной подмены "
                  f"(хвост `tail_s` — повторный захват монитора)."]
    lines += ["", f"Доля чистого времени не в режиме GNSS: "
                  f"{'н/д' if math.isnan(r.non_gnss_clean_pct) else f'{r.non_gnss_clean_pct:.2f} %'}"]
    if r.windows:
        lines += ["", "| Окно | Начало, мс | Конец, мс | Задержка, с | P95 ошибки, м |", "|---|---|---|---|---|"]
        for w in r.windows:
            lat = "—" if w.latency_s is None else f"{w.latency_s:.1f}"
            p = "—" if math.isnan(w.p95_err_m) else f"{w.p95_err_m:.1f}"
            kind = f"{w.kind} (< 100 м)" if w.small else w.kind
            lines.append(f"| {kind} | {w.start_ms} | {w.end_ms} | {lat} | {p} |")
    return "\n".join(lines) + "\n"
