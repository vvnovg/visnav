from vpr_bench.cli import build_parser, main, parse_queries


def test_parser_bench_args():
    args = build_parser().parse_args(
        ["bench", "--refs", "r.csv", "--queries", "S1=q.csv", "--models", "a,b", "--out", "out"]
    )
    assert args.command == "bench"
    assert args.models == "a,b"
    assert args.queries == ["S1=q.csv"]


def test_parser_bench_pool_and_decision_session():
    args = build_parser().parse_args(
        [
            "bench", "--refs", "r.csv",
            "--queries", "S1=q1.csv", "--queries", "S2=q2.csv",
            "--pool", "S1,S2",
            "--decision-session", "S1+S2",
            "--models", "a", "--out", "out",
        ]
    )
    assert args.queries == ["S1=q1.csv", "S2=q2.csv"]
    assert args.pool == ["S1,S2"]
    assert args.decision_session == "S1+S2"


def test_parse_queries_name_equals_path():
    from pathlib import Path

    result = parse_queries(["S1=q1.csv", "S2=q2.csv"])
    assert result == {"S1": Path("q1.csv"), "S2": Path("q2.csv")}


def test_parse_queries_bare_path_uses_parent_dir_name():
    from pathlib import Path

    result = parse_queries(["some/session_a/queries.csv"])
    assert result == {"session_a": Path("some/session_a/queries.csv")}


def test_parse_queries_duplicate_name_raises():
    import pytest

    with pytest.raises(ValueError, match="S1"):
        parse_queries(["S1=q1.csv", "S1=q2.csv"])


def test_bench_duplicate_query_session_exits_2_and_no_output_dir(tmp_path, capsys):
    out_dir = tmp_path / "out"
    code = main(
        [
            "bench",
            "--refs", "r.csv",
            "--queries", "S1=q1.csv",
            "--queries", "S1=q2.csv",
            "--models", "cosplace-r50",
            "--out", str(out_dir),
        ]
    )
    assert code == 2
    assert "S1" in capsys.readouterr().err
    assert not out_dir.exists()


def test_bench_unknown_model_exits_2_and_no_output_dir(tmp_path, capsys):
    out_dir = tmp_path / "out"
    code = main(
        [
            "bench",
            "--refs", "r.csv",
            "--queries", "S1=q.csv",
            "--models", "nope-model",
            "--out", str(out_dir),
        ]
    )
    assert code == 2
    assert "nope-model" in capsys.readouterr().err
    assert not out_dir.exists()


def test_bench_empty_models_exits_2(tmp_path, capsys):
    out_dir = tmp_path / "out"
    code = main(
        [
            "bench",
            "--refs", "r.csv",
            "--queries", "S1=q.csv",
            "--models", "",
            "--out", str(out_dir),
        ]
    )
    assert code == 2
    assert not out_dir.exists()


def test_fetch_refs_requires_token(monkeypatch, tmp_path, capsys):
    monkeypatch.delenv("MAPILLARY_TOKEN", raising=False)
    code = main(["fetch-refs", "--bbox", "37.6,55.74,37.62,55.76", "--out", str(tmp_path)])
    assert code == 2
    assert "MAPILLARY_TOKEN" in capsys.readouterr().err


def test_fetch_refs_requires_bbox_or_gpx(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv("MAPILLARY_TOKEN", "tok")
    code = main(["fetch-refs", "--out", str(tmp_path)])
    assert code == 2
    err = capsys.readouterr().err
    assert "--bbox" in err and "--gpx" in err


def test_extract_queries_naive_video_start_exits_2(tmp_path, capsys):
    code = main(
        [
            "extract-queries",
            "--video", "v.mp4",
            "--gpx", "t.gpx",
            "--video-start", "2026-09-20T10:00:03.250",
            "--out", str(tmp_path / "out"),
        ]
    )
    assert code == 2
    err = capsys.readouterr().err
    assert "--video-start must include a timezone offset" in err
    assert "2026-09-20T10:00:03.250+03:00" in err


def test_extract_queries_non_positive_every_exits_2(tmp_path, capsys):
    code = main(
        [
            "extract-queries",
            "--video", "v.mp4",
            "--gpx", "t.gpx",
            "--video-start", "2026-09-20T10:00:03.250+03:00",
            "--every", "0",
            "--out", str(tmp_path / "out"),
        ]
    )
    assert code == 2
    assert "--every must be > 0" in capsys.readouterr().err


def test_parser_fetch_refs_defaults():
    args = build_parser().parse_args(["fetch-refs", "--bbox", "0,0,1,1", "--out", "out"])
    assert args.width == 640
    assert args.height == 480
    assert args.workers == 8
    assert args.buffer_m == 600.0
    assert args.gpx == []
