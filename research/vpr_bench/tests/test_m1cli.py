from pathlib import Path

from vpr_bench.dataset import write_places, Place
from vpr_bench.m1cli import build_parser, main


def test_parser_export():
    args = build_parser().parse_args(["export-onnx", "--model", "eigenplaces-r50", "--out", "m.onnx"])
    assert args.command == "export-onnx" and args.model == "eigenplaces-r50"


def test_export_unknown_model_exits_2(tmp_path, capsys):
    assert main(["export-onnx", "--model", "nope", "--out", str(tmp_path / "m.onnx")]) == 2
    assert "unknown model" in capsys.readouterr().err


def test_parser_quantize():
    args = build_parser().parse_args([
        "quantize-onnx", "--onnx", "fp32.onnx", "--calib", "refs.csv", "--n", "100", "--out", "int8.onnx"
    ])
    assert args.command == "quantize-onnx"
    assert args.onnx == Path("fp32.onnx")
    assert args.calib == Path("refs.csv")
    assert args.n == 100
    assert args.out == Path("int8.onnx")


def test_quantize_n_zero_exits_2(tmp_path, capsys):
    refs_csv = tmp_path / "refs.csv"
    write_places(refs_csv, [Place("dummy.jpg", 0.0, 0.0, 0.0)])
    result = main([
        "quantize-onnx", "--onnx", str(tmp_path / "fp32.onnx"), "--calib", str(refs_csv),
        "--n", "0", "--out", str(tmp_path / "int8.onnx")
    ])
    assert result == 2
    assert "non-empty" in capsys.readouterr().err


def test_quantize_missing_image_exits_2(tmp_path, capsys):
    refs_csv = tmp_path / "refs.csv"
    write_places(refs_csv, [Place("missing.jpg", 0.0, 0.0, 0.0)])
    result = main([
        "quantize-onnx", "--onnx", str(tmp_path / "fp32.onnx"), "--calib", str(refs_csv),
        "--n", "1", "--out", str(tmp_path / "int8.onnx")
    ])
    assert result == 2
    assert "could not be read" in capsys.readouterr().err
