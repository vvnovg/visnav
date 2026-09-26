"""Markdown-отчёт по результатам бенчмарка."""
from __future__ import annotations

from vpr_bench.evaluate import EvalResult

HEADER = (
    "| Модель | Режим | N | R@1, % | R@5, % | R@10, % | Медиана, м | P95, м "
    "| Размерность | Размер, МБ | мс/кадр | R@5 ≥ цели |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def _pct(recall: dict[int, float], k: int) -> str:
    return f"{recall[k] * 100:.1f}" if k in recall else "—"


def render_report(results: list[EvalResult], meta: dict[str, dict], target_recall5: float = 0.85) -> str:
    lines = [
        "# Результаты бенчмарка VPR",
        "",
        f"Порог совпадения: 25 м. Цель M0: R@5 ≥ {target_recall5 * 100:.0f} %.",
        "",
        HEADER,
    ]
    for r in results:
        m = meta[r.model]
        ok = "✅" if r.recall.get(5, 0.0) >= target_recall5 else "❌"
        lines.append(
            f"| {r.model} | {r.setting} | {r.n_queries} | {_pct(r.recall, 1)} | {_pct(r.recall, 5)} "
            f"| {_pct(r.recall, 10)} | {r.median_err_m:.1f} | {r.p95_err_m:.1f} | {m['dim']} "
            f"| {m['size_mb']:.1f} | {m['ms_per_image']:.1f} | {ok} |"
        )
    return "\n".join(lines) + "\n"
