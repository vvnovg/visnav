from vpr_bench.cli import build_parser, main


def test_parser_bench_args():
    args = build_parser().parse_args(
        ["bench", "--refs", "r.csv", "--queries", "q.csv", "--models", "a,b", "--out", "out"]
    )
    assert args.command == "bench"
    assert args.models == "a,b"


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


def test_parser_fetch_refs_defaults():
    args = build_parser().parse_args(["fetch-refs", "--bbox", "0,0,1,1", "--out", "out"])
    assert args.width == 640
    assert args.height == 480
    assert args.workers == 8
    assert args.buffer_m == 600.0
    assert args.gpx == []
