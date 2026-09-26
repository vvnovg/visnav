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
