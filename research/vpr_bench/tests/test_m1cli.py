from vpr_bench.m1cli import build_parser, main


def test_parser_export():
    args = build_parser().parse_args(["export-onnx", "--model", "eigenplaces-r50", "--out", "m.onnx"])
    assert args.command == "export-onnx" and args.model == "eigenplaces-r50"


def test_export_unknown_model_exits_2(tmp_path, capsys):
    assert main(["export-onnx", "--model", "nope", "--out", str(tmp_path / "m.onnx")]) == 2
    assert "unknown model" in capsys.readouterr().err
