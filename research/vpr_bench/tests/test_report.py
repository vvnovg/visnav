from vpr_bench.evaluate import EvalResult
from vpr_bench.report import render_report


def test_report_marks_decision_row_and_shows_coverage():
    results = [
        EvalResult("good", "prior-500m", 100, 95, 0.95, {1: 0.8, 5: 0.91, 10: 0.95, 20: 0.97}, 4.2, 18.0),
        EvalResult("good", "global", 100, 100, 1.0, {1: 0.5, 5: 0.6, 10: 0.7, 20: 0.75}, 30.0, 400.0),
    ]
    meta = {
        "good": {"dim": 2048, "size_mb": 100.5, "ms_per_image": 42.0},
    }
    md = render_report(results, meta)
    assert (
        "Порог совпадения: 25 м. Recall считается по кадрам, для которых есть эталон в пределах порога. "
        "Цель M0: R@5 ≥ 85 % в режиме prior-500m." in md
    )
    assert "Покрытие, %" in md
    assert "R@20, %" in md
    assert "Решение" in md
    assert "мс/кадр (ПК, с I/O)" in md
    assert (
        "|  | good | prior-500m | 100 | 95.0 | 80.0 | 91.0 | 95.0 | 97.0 | 4.2 | 18.0 | 2048 | 100.5 "
        "| 42.0 | ✅ |"
        in md
    )
    assert (
        "|  | good | global | 100 | 100.0 | 50.0 | 60.0 | 70.0 | 75.0 | 30.0 | 400.0 | 2048 | 100.5 "
        "| 42.0 | — |"
        in md
    )


def test_report_marks_failing_decision_row():
    results = [
        EvalResult("bad", "prior-500m", 100, 90, 0.9, {1: 0.3, 5: 0.7, 10: 0.8, 20: 0.85}, 30.0, 400.0),
    ]
    meta = {"bad": {"dim": 512, "size_mb": 20.0, "ms_per_image": 10.0}}
    md = render_report(results, meta)
    assert "| bad |" in md and "❌" in md


def test_report_session_column_first_and_decision_session_filter():
    results = [
        EvalResult(
            "good", "prior-500m", 50, 48, 0.96, {1: 0.8, 5: 0.9, 10: 0.95, 20: 0.97}, 4.0, 18.0, session="S1"
        ),
        EvalResult(
            "good", "prior-500m", 50, 47, 0.94, {1: 0.5, 5: 0.6, 10: 0.7, 20: 0.8}, 10.0, 30.0, session="S2"
        ),
        EvalResult(
            "good", "prior-500m", 100, 95, 0.95, {1: 0.7, 5: 0.86, 10: 0.9, 20: 0.95}, 6.0, 20.0,
            session="S1+S2",
        ),
    ]
    meta = {"good": {"dim": 2048, "size_mb": 100.5, "ms_per_image": 42.0}}
    md = render_report(results, meta, decision_session="S1+S2")

    # Session column is first.
    header_line = next(line for line in md.splitlines() if line.startswith("| "))
    assert header_line.split("|")[1].strip() == "Сессия"

    assert "сессия S1+S2" in md

    lines = md.splitlines()
    row_s1 = next(line for line in lines if "| S1 |" in line)
    row_s2 = next(line for line in lines if "| S2 |" in line)
    row_pooled = next(line for line in lines if "| S1+S2 |" in line)
    # Only the pooled S1+S2 row (matching setting AND session) gets a decision mark.
    assert row_s1.rstrip("|").rstrip().endswith("—")
    assert row_s2.rstrip("|").rstrip().endswith("—")
    assert row_pooled.rstrip("|").rstrip().endswith("✅")
