from vpr_bench.evaluate import EvalResult
from vpr_bench.report import render_report


def test_report_marks_target():
    results = [
        EvalResult("good", "prior-500m", 100, {1: 0.8, 5: 0.91, 10: 0.95}, 4.2, 18.0),
        EvalResult("bad", "prior-500m", 100, {1: 0.5, 5: 0.7, 10: 0.8}, 30.0, 400.0),
    ]
    meta = {
        "good": {"dim": 2048, "size_mb": 100.5, "ms_per_image": 42.0},
        "bad": {"dim": 512, "size_mb": 20.0, "ms_per_image": 10.0},
    }
    md = render_report(results, meta)
    assert "| good | prior-500m | 100 | 80.0 | 91.0 | 95.0 | 4.2 | 18.0 | 2048 | 100.5 | 42.0 | ✅ |" in md
    assert "| bad |" in md and "❌" in md
