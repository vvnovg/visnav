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
MIN_RUN_MIN = 59.5  # допуск на автостоп ровно в 60 мин и скачки часов; в тексте вердикта — «≥ 60 мин»
DELAY_STEPS_MS = (300, 500, 800, 1500)
DELAY_MARGIN_MS = 100.0
MAX_LATE_SHARE = 0.001  # цель: не больше 0.1 % опоздавших событий
MIN_SOURCE_N = 300  # источник с меньшим числом событий в выборе буфера не участвует
MIN_FRAME_N = 100  # кадры участвуют уже с этого числа — «мало кадров, оценка грубая»
STRICT_LATE_SOURCES = ("frame", "gnss_fix")  # фактическая доля опоздавших > 0.1 % — ❌
MAX_SYS_GAP_MS = 15_000  # разрыв между сэмплами sys длиннее — выпадает из долей времени
MIN_THERMAL_COVERAGE = 0.95
CHARGE_JUMP_FRAC = 0.005  # рост счётчика больше 0.5 % ёмкости или такое же падение быстрее 60 с — «счётчик скачет»
CHARGE_DROP_WINDOW_MS = 60_000
MAX_DURATION_RATIO = 1.5
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
    mean_current_ma: float | None  # модуль среднего по времени тока вне зарядки
    notes: tuple[str, ...] = ()


@dataclass(frozen=True)
class LateWindow:
    n: int
    p50: float | None
    p99: float | None
    max: float | None


@dataclass(frozen=True)
class SourceLateness:
    windows: tuple[LateWindow, ...]

    @property
    def n(self) -> int:
        return sum(w.n for w in self.windows)

    @property
    def p50_mean(self) -> float | None:
        """Среднее p50 по окнам, взвешенное по n."""
        pairs = [(w.n, w.p50) for w in self.windows if w.p50 is not None and w.n > 0]
        total = sum(n for n, _ in pairs)
        return sum(n * v for n, v in pairs) / total if total > 0 else None

    @property
    def p99_max(self) -> float | None:
        vals = [w.p99 for w in self.windows if w.p99 is not None]
        return max(vals) if vals else None

    @property
    def max(self) -> float | None:
        vals = [w.max for w in self.windows if w.max is not None]
        return max(vals) if vals else None

    def share_ub(self, threshold_ms: float) -> float | None:
        """Верхняя оценка доли событий с опозданием > threshold_ms (по сводке окон p50/p99/max)."""
        total = self.n
        if total <= 0:
            return None
        ub = 0
        for w in self.windows:
            if w.n <= 0 or (w.max is not None and w.max <= threshold_ms):
                continue
            if w.p99 is not None and w.p99 <= threshold_ms:
                ub += max(1, w.n // 100)
            elif w.p50 is not None and w.p50 <= threshold_ms:
                ub += w.n // 2
            else:
                ub += w.n
        return ub / total


@dataclass(frozen=True)
class PerfResult:
    header: dict
    duration_min: float
    stages: dict[str, StageStats]
    buffer_ratio: float | None  # reorder_delay_ms / p50(e2e)
    e2e_minus_buffer: StageStats | None  # e2e_ms − reorder_delay_ms: обработка и ожидание слива
    nfr4_ok: bool | None
    drain: DrainResult
    baseline_header: dict | None
    baseline_drain: DrainResult | None
    excess_pct_per_h: float | None
    nfr6_ok: bool | None
    nfr6_rough: bool
    run_warnings: list[str]
    first_thermal2_min: float | None
    thermal_share: dict[int | None, float]
    thermal_coverage: float
    interval_share: dict[int, float]
    interval_min: int | None
    interval_max: int | None
    gaps_ms: list[float]
    nfr7: str
    lateness: dict[str, SourceLateness]
    late_count: int
    late_src: dict[str, int] | None  # накопительные счётчики опоздавших по источникам; None — старый журнал
    dropped_count: int
    late_share: float | None
    recommended_delay_ms: int | None
    recommendation: str  # "ok" | "over" | "few"
    table_delay_ms: int  # ступень, для которой в таблице показана share_ub

    def late_src_share(self, src: str) -> float | None:
        """Фактическая доля опоздавших событий источника: late_src / Σn."""
        st = self.lateness.get(src)
        if self.late_src is None or st is None or st.n <= 0:
            return None
        return self.late_src.get(src, 0) / st.n


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


def _num(v) -> float | None:
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) else None


def _vals(recs: list[dict], key: str) -> list[float]:
    return [v for v in (_num(r.get(key)) for r in recs) if v is not None]


def _stage(values: list[float]) -> StageStats:
    if not values:
        return StageStats(0, None, None, None)
    a = np.asarray(values, dtype=float)
    return StageStats(len(values), float(np.percentile(a, 50)), float(np.percentile(a, 95)), float(a.max()))


def _span(log: PerfLog) -> tuple[float, float]:
    ts = [r["t_ms"] for r in (*log.frames, *log.sys, *log.late)]
    start = _num(log.header.get("session_started_ms"))
    if start is not None:
        ts.append(start)
    if not ts:
        return 0.0, 0.0
    return float(min(ts)), float(max(ts))


def _mean_current_ma(sys: list[dict]) -> float | None:
    """Модуль среднего по времени тока, мА; сэмплы на зарядке и разрывы > 15 с не считаются."""
    num = den = 0.0
    for a, dt in _intervals(sys)[0]:
        cur = _num(a.get("current_ua"))
        if cur is None or a.get("plugged") is True:
            continue
        num += cur * dt
        den += dt
    if den > 0:
        return abs(num / den) / 1000.0
    plain = [c for c in (_num(r.get("current_ua")) for r in sys if r.get("plugged") is not True) if c is not None]
    return abs(float(np.mean(plain))) / 1000.0 if plain else None


def _charge_jump(a: dict, b: dict, cap_uah: float) -> bool:
    """Соседние сэмплы: рост больше 0.5 % ёмкости или падение больше 0.5 % быстрее чем за 60 с."""
    delta = b["charge_uah"] - a["charge_uah"]
    limit = CHARGE_JUMP_FRAC * cap_uah
    return delta > limit or (-delta > limit and b["t_ms"] - a["t_ms"] < CHARGE_DROP_WINDOW_MS)


def _drain(log: PerfLog) -> DrainResult:
    mean_ma = _mean_current_ma(log.sys)
    if any(r.get("plugged") is True for r in log.sys):
        return DrainResult(None, None, True, mean_ma)
    notes: list[str] = []
    cap = _num(log.header.get("battery_capacity_mah"))
    charged = [r for r in log.sys if _num(r.get("charge_uah")) is not None]
    result: tuple[float, str] | None = None
    if cap is not None and cap > 0 and len(charged) >= 2:
        cap_uah = cap * 1000.0
        if any(_charge_jump(a, b, cap_uah) for a, b in zip(charged, charged[1:])):
            notes.append("счётчик скачет")
        else:
            a, b = charged[0], charged[-1]
            hours = (b["t_ms"] - a["t_ms"]) / 3.6e6
            if hours > 0:
                result = ((a["charge_uah"] - b["charge_uah"]) / cap_uah / hours * 100.0, "charge")
    if result is None:
        pcts = [r for r in log.sys if _num(r.get("batt_pct")) is not None]
        if len(pcts) >= 2:
            a, b = pcts[0], pcts[-1]
            hours = (b["t_ms"] - a["t_ms"]) / 3.6e6
            if hours > 0:
                result = ((a["batt_pct"] - b["batt_pct"]) / hours, "pct")
    if result is None:
        return DrainResult(None, None, False, mean_ma, tuple(notes))
    if result[0] < 0:
        notes.append("заряд вырос — расход отрицательный, проверьте журнал")
    return DrainResult(result[0], result[1], False, mean_ma, tuple(notes))


def _intervals(sys: list[dict]) -> tuple[list[tuple[dict, float]], list[float]]:
    """Пары (сэмпл, длительность до следующего) без разрывов и сами разрывы > MAX_SYS_GAP_MS."""
    spans, gaps = [], []
    for a, b in zip(sys, sys[1:]):
        dt = float(b["t_ms"] - a["t_ms"])
        if dt > MAX_SYS_GAP_MS:
            gaps.append(dt)
        else:
            spans.append((a, dt))
    return spans, gaps


def _time_shares(spans: list[tuple[dict, float]], key: str) -> dict:
    """Доля времени по значению поля; разрывы не входят."""
    dur: dict = {}
    for rec, dt in spans:
        v = rec.get(key)
        dur[v] = dur.get(v, 0.0) + dt
    total = sum(dur.values())
    return {k: v / total for k, v in dur.items()} if total > 0 else {}


def _lateness(late: list[dict]) -> dict[str, SourceLateness]:
    acc: dict[str, list[LateWindow]] = {}
    for rec in late:
        for src, st in (rec.get("sources") or {}).items():
            if isinstance(st, dict):
                n = _num(st.get("n"))
                acc.setdefault(src, []).append(LateWindow(int(n) if n is not None else 0, _num(st.get("p50")),
                                                          _num(st.get("p99")), _num(st.get("max"))))
    order = [s for s in LATE_SOURCES if s in acc] + sorted(s for s in acc if s not in LATE_SOURCES)
    return {src: SourceLateness(tuple(acc[src])) for src in order}


def _eligible(src: str, st: SourceLateness) -> bool:
    return st.n >= MIN_SOURCE_N or (src == "frame" and st.n >= MIN_FRAME_N)


def _late_src(late: list[dict]) -> dict[str, int] | None:
    """Максимум накопительных счётчиков late_src по записям; None — поля нет ни в одной записи (старый журнал)."""
    if not any(isinstance(r.get("late_src"), dict) for r in late):
        return None
    out: dict[str, int] = {}
    for r in late:
        for src, v in (r.get("late_src") or {}).items():
            n = _num(v)
            if n is not None:
                out[src] = max(out.get(src, 0), int(n))
    return out


def _recommend(lateness: dict[str, SourceLateness]) -> tuple[int | None, str]:
    """Наименьшая ступень D, при которой share_ub(D − 100) ≤ 0.1 % для всех источников с n ≥ 300 (кадры — с 100)."""
    eligible = [s for src, s in lateness.items() if _eligible(src, s)]
    if not eligible:
        return None, "few"
    for d in DELAY_STEPS_MS:
        if all(s.share_ub(d - DELAY_MARGIN_MS) <= MAX_LATE_SHARE for s in eligible):
            return d, "ok"
    return None, "over"


def _nfr7(duration_min: float, first3_min: float | None, coverage: float, gaps: list[float]) -> str:
    fails = []
    if duration_min < MIN_RUN_MIN:
        fails.append("короче 60 мин")
    if first3_min is not None:
        fails.append(f"thermal ≥ 3 на {first3_min:.1f} мин")
    if fails:
        return f"нет ({', '.join(fails)})"
    # вердикт — по покрытию thermal; разрывы только для сведения
    gap_note = (f"разрывов sys > {MAX_SYS_GAP_MS / 1000:.0f} с: {len(gaps)}, самый долгий {max(gaps) / 1000:.0f} с"
                if gaps else None)
    if coverage < MIN_THERMAL_COVERAGE:
        parts = [f"thermal известен {coverage * 100:.0f} % времени, нужно ≥ {MIN_THERMAL_COVERAGE * 100:.0f} %"]
        return f"неполные данные ({'; '.join(parts + ([gap_note] if gap_note else []))})"
    return f"да ({gap_note})" if gap_note else "да"


def evaluate(full: PerfLog, baseline: PerfLog | None = None) -> PerfResult:
    start, end = _span(full)
    duration_ms = end - start
    duration_min = duration_ms / 60_000.0
    stages = {name: _stage(_vals(full.frames, key)) for name, key in STAGES}
    e2e = stages["e2e"]
    delay = _num(full.header.get("reorder_delay_ms"))
    buffer_ratio = delay / e2e.p50 if delay is not None and e2e.p50 is not None and e2e.p50 > 0 else None
    nfr4_ok = None if e2e.p95 is None else e2e.p95 <= MAX_E2E_P95_MS
    e2e_minus_buffer = (_stage([v - delay for v in _vals(full.frames, "e2e_ms")])
                        if delay is not None and e2e.n > 0 else None)

    drain = _drain(full)
    base_drain = _drain(baseline) if baseline is not None else None
    excess = (drain.pct_per_h - base_drain.pct_per_h
              if base_drain is not None and drain.pct_per_h is not None and base_drain.pct_per_h is not None
              else None)
    nfr6_ok = None if excess is None else excess <= MAX_EXCESS_PCT_PER_H
    nfr6_rough = excess is not None and "pct" in (drain.method, base_drain.method)
    run_warnings = []
    if baseline is not None:
        if full.header.get("device") != baseline.header.get("device"):
            run_warnings.append(f"разные устройства: {full.header.get('device')!r} и {baseline.header.get('device')!r}")
        b0, b1 = _span(baseline)
        lo, hi = sorted((duration_ms, b1 - b0))
        if lo <= 0 or hi / lo > MAX_DURATION_RATIO:
            run_warnings.append(f"длительности прогонов различаются больше чем в {MAX_DURATION_RATIO:g} раза: "
                                f"{duration_min:.1f} и {(b1 - b0) / 60_000.0:.1f} мин")

    def first_at_least(level: int) -> float | None:
        t = next((r["t_ms"] for r in full.sys if _num(r.get("thermal")) is not None and r["thermal"] >= level), None)
        return None if t is None else (t - start) / 60_000.0

    spans, gaps = _intervals(full.sys)
    known_ms = sum(dt for rec, dt in spans if _num(rec.get("thermal")) is not None)
    coverage = known_ms / duration_ms if duration_ms > 0 else 0.0
    intervals = [int(v) for v in _vals(full.sys, "interval_ms")]

    lateness = _lateness(full.late)
    recommended, status = _recommend(lateness)
    total_n = sum(s.n for s in lateness.values())
    late_count = max((int(_num(r.get("late")) or 0) for r in full.late), default=0)  # счётчики накопительные
    dropped_count = max((int(_num(r.get("dropped")) or 0) for r in full.late), default=0)

    return PerfResult(
        header=full.header, duration_min=duration_min, stages=stages, buffer_ratio=buffer_ratio,
        e2e_minus_buffer=e2e_minus_buffer, nfr4_ok=nfr4_ok,
        drain=drain, baseline_header=baseline.header if baseline is not None else None, baseline_drain=base_drain,
        excess_pct_per_h=excess, nfr6_ok=nfr6_ok, nfr6_rough=nfr6_rough, run_warnings=run_warnings,
        first_thermal2_min=first_at_least(2),
        thermal_share=_time_shares(spans, "thermal"), thermal_coverage=coverage,
        interval_share=_time_shares(spans, "interval_ms"),
        interval_min=min(intervals) if intervals else None, interval_max=max(intervals) if intervals else None,
        gaps_ms=gaps, nfr7=_nfr7(duration_min, first_at_least(3), coverage, gaps),
        lateness=lateness, late_count=late_count, late_src=_late_src(full.late), dropped_count=dropped_count,
        late_share=late_count / total_n if total_n > 0 else None,
        recommended_delay_ms=recommended, recommendation=status,
        table_delay_ms=recommended if recommended is not None else DELAY_STEPS_MS[-1],
    )


def _mark(ok: bool | None) -> str:
    return {True: "✅", False: "❌", None: "⚠️ нет данных"}[ok]


def _f(x: float | None, nd: int = 1) -> str:
    return "—" if x is None else f"{x:.{nd}f}"


def _with_unit(x: float | None, unit: str) -> str:
    """Значение с единицей и пробелом перед отметкой; пусто, если значения нет (отметка скажет «нет данных»)."""
    return "" if x is None else f"{x:.1f} {unit} "


def _current_text(ma: float | None) -> str:
    return "—" if ma is None else f"{ma:.0f} мА (разряд)"


def _drain_text(d: DrainResult | None) -> str:
    if d is None:
        return "нет прогона"
    if d.plugged:
        return "на зарядке, расход не считается"
    notes = "".join(f"; {n}" for n in d.notes)
    if d.pct_per_h is None:
        return "нет данных" + notes
    return f"{d.pct_per_h:.1f} %/ч" + (" (по процентам, грубо)" if d.method == "pct" else "") + notes


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
    ]
    if r.stages["e2e"].n == 0:
        lines += ["Кадров с e2e_ms нет — нет данных.", ""]
    lines += ["| этап | n | p50, мс | p95, мс | max, мс |", "|---|---|---|---|---|"]
    for name, st in r.stages.items():
        lines.append(f"| {name} | {st.n} | {_f(st.p50)} | {_f(st.p95)} | {_f(st.max)} |")
        if name == "e2e" and r.e2e_minus_buffer is not None:
            mb = r.e2e_minus_buffer
            lines.append(f"| e2e − буфер | {mb.n} | {_f(mb.p50)} | {_f(mb.p95)} | {_f(mb.max)} |")
    ratio = "—" if r.buffer_ratio is None else f"×{r.buffer_ratio:.1f}"
    lines += [
        "",
        f"- Задержка буфера относительно p50(e2e) (reorder_delay_ms / p50): {ratio}",
        f"- p95 e2e ≤ {MAX_E2E_P95_MS:.0f} мс — {_with_unit(r.stages['e2e'].p95, 'мс')}{_mark(r.nfr4_ok)}",
        "",
        "e2e ≥ задержки буфера по построению: при буфере ≥ 300 мс NFR-4 (≤ 300 мс) не выполняется; "
        "строка “e2e − буфер” показывает собственную задержку обработки.",
        "",
        "## NFR-6: батарея", "",
        f"- Расход full: {_drain_text(r.drain)}",
        f"- Расход baseline: {_drain_text(r.baseline_drain)}",
        f"- Превышение full над baseline ≤ {MAX_EXCESS_PCT_PER_H:.0f} %/ч — "
        f"{_with_unit(r.excess_pct_per_h, '%/ч')}{'(грубо) ' if r.nfr6_rough else ''}{_mark(r.nfr6_ok)}",
        f"- Средний ток full (по времени, без зарядки): {_current_text(r.drain.mean_current_ma)}",
    ]
    if r.baseline_drain is not None:
        lines.append(f"- Средний ток baseline (по времени, без зарядки): "
                     f"{_current_text(r.baseline_drain.mean_current_ma)}")
    lines += [f"- ⚠️ {w}" for w in r.run_warnings]
    first2 = "не было" if r.first_thermal2_min is None else f"{r.first_thermal2_min:.1f} мин"
    gaps = ("нет" if not r.gaps_ms
            else f"{len(r.gaps_ms)}, самый долгий {max(r.gaps_ms) / 1000:.0f} с (в доли времени не входят)")
    lines += [
        "",
        "## NFR-7: нагрев", "",
        f"- Время до первого thermal ≥ 2: {first2}",
        f"- thermal известен {r.thermal_coverage * 100:.1f} % времени прогона",
        f"- Разрывы между сэмплами sys > {MAX_SYS_GAP_MS / 1000:.0f} с: {gaps}",
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
        f"| источник | n | p50 (среднее), мс | p99 (макс. по окнам), мс | max, мс | доля > {r.table_delay_ms - 100:.0f} мс "
        f"(верх. оценка) | опоздало (факт) | доля опоздавших (факт) |",
        "|---|---|---|---|---|---|---|---|",
    ]
    rough_frames = False
    for src, st in r.lateness.items():
        share = st.share_ub(r.table_delay_ms - DELAY_MARGIN_MS)
        if not _eligible(src, st):
            few = " (мало данных)"
        elif src == "frame" and st.n < MIN_SOURCE_N:
            few, rough_frames = " (мало кадров, оценка грубая)", True
        else:
            few = ""
        fact = r.late_src_share(src)
        if fact is None:
            fact_cols = "— | —"
        else:
            mark = (" ✅" if fact <= MAX_LATE_SHARE else " ❌") if src in STRICT_LATE_SOURCES else ""
            fact_cols = f"{r.late_src.get(src, 0)} | {fact * 100:.2f} %{mark}"
        lines.append(f"| {src}{few} | {st.n} | {_f(st.p50_mean)} | {_f(st.p99_max)} | {_f(st.max)} | "
                     f"{'—' if share is None else f'{share * 100:.2f} %'} | {fact_cols} |")
    if rough_frames:
        lines += ["", f"Кадров меньше {MIN_SOURCE_N}: мало кадров, оценка грубая."]
    if r.recommendation == "few":
        rec = f"нет данных (ни у одного источника нет {MIN_SOURCE_N} событий)"
    elif r.recommendation == "over":
        rec = "> 1500, оставить 1500 и разобраться"
    else:
        rec = f"{r.recommended_delay_ms} мс"
    late_share = "—" if r.late_share is None else f"{r.late_share * 100:.2f} %"
    lines += [
        "",
        f"Источники с n < {MIN_SOURCE_N} в выборе буфера не участвуют (кадры — с n ≥ {MIN_FRAME_N}). "
        f"Фактическая доля опоздавших для frame и gnss_fix должна быть ≤ {MAX_LATE_SHARE * 100:.1f} %.",
        "",
        f"- Опоздавших (late): {r.late_count}, отброшенных (dropped): {r.dropped_count} — счётчики за весь прогон",
        f"- При текущем буфере доля опоздавших (по всем источникам, в основном IMU): {late_share} "
        f"(цель ≤ {MAX_LATE_SHARE * 100:.1f} %)",
        f"- Рекомендация буфера: наименьшая ступень D, при которой доля событий с опозданием > D − "
        f"{DELAY_MARGIN_MS:.0f} мс ≤ {MAX_LATE_SHARE * 100:.1f} % по каждому источнику → {rec}",
        "",
    ]
    return "\n".join(lines)
