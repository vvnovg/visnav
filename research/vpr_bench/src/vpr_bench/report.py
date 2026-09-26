"""Markdown-отчёт по результатам бенчмарка."""
from __future__ import annotations

from vpr_bench.evaluate import EvalResult

HEADER = (
    "| Сессия | Модель | Режим | N | Покрытие, % | R@1, % | R@5, % | R@10, % | R@20, % | Медиана, м | P95, м "
    "| Размерность | Размер, МБ | мс/кадр (ПК, с I/O) | Решение |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def _pct(recall: dict[int, float], k: int) -> str:
    return f"{recall[k] * 100:.1f}" if k in recall else "—"


def render_report(
    results: list[EvalResult],
    meta: dict[str, dict],
    target_recall5: float = 0.85,
    threshold_m: float = 25.0,
    decision_setting: str = "prior-500m",
    decision_session: str | None = None,
) -> str:
    goal_line = (
        f"Порог совпадения: {threshold_m:g} м. Recall считается по кадрам, для которых есть эталон "
        f"в пределах порога. Цель M0: R@5 ≥ {target_recall5 * 100:.0f} % в режиме {decision_setting}"
    )
    if decision_session is not None:
        goal_line += f", сессия {decision_session}."
    else:
        goal_line += "."
    lines = [
        "# Результаты бенчмарка VPR",
        "",
        goal_line,
        "",
        HEADER,
    ]
    for r in results:
        m = meta[r.model]
        is_decision_row = r.setting == decision_setting and (
            decision_session is None or r.session == decision_session
        )
        if is_decision_row:
            decision = "✅" if r.recall.get(5, 0.0) >= target_recall5 else "❌"
        else:
            decision = "—"
        lines.append(
            f"| {r.session} | {r.model} | {r.setting} | {r.n_queries} | {r.coverage * 100:.1f} "
            f"| {_pct(r.recall, 1)} | {_pct(r.recall, 5)} | {_pct(r.recall, 10)} | {_pct(r.recall, 20)} "
            f"| {r.median_err_m:.1f} | {r.p95_err_m:.1f} | {m['dim']} | {m['size_mb']:.1f} "
            f"| {m['ms_per_image']:.1f} | {decision} |"
        )
    return "\n".join(lines) + "\n"
