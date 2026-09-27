import json
from pathlib import Path

import numpy as np
import torch

from vpr_bench.dataset import write_places, Place
from vpr_bench.m1cli import build_parser, main
from vpr_bench.models import VprModel
from vpr_bench.refpack import write_refpack


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


class _FlippedModel(VprModel):
    """Deliberately returns the negated embedding, so cosine(torch, onnx) == -1 always."""

    def embed(self, images_bgr):
        return -super().embed(images_bgr)


def test_export_deletes_output_on_parity_failure(tmp_path, monkeypatch):
    net = torch.nn.Sequential(
        torch.nn.Conv2d(3, 4, 3, padding=1), torch.nn.ReLU(),
        torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten(),
    )
    fake_model = _FlippedModel("fake", net, (32, 32))
    monkeypatch.setattr("vpr_bench.m1cli.MODEL_SPECS", {"fake": object()})
    monkeypatch.setattr("vpr_bench.m1cli.load_model", lambda name: fake_model)
    out = tmp_path / "m.onnx"
    result = main(["export-onnx", "--model", "fake", "--out", str(out)])
    assert result == 1
    assert not out.exists()


def _write_bundle(root, created_at):
    write_refpack(
        root, [55.75], [37.6], [0.0], np.array([[1.0, 0.0]], dtype=np.float32),
        {"model": "m", "created_at": created_at},
    )
    return root


def _write_log(path, refpack_created_at):
    header = json.dumps({
        "v": 1, "type": "session", "model": "m", "refpack_created_at": refpack_created_at,
        "device": "d", "started_ms": 0, "mode": "gps",
    })
    frame = json.dumps({
        "v": 1, "type": "frame", "t_ms": 1, "mode": "gps", "gps": None, "prior": None,
        "top": [], "fix": None, "lat_ms": {"pre": 0.0, "inf": 0.0, "search": 0.0},
    })
    path.write_text(header + "\n" + frame + "\n")


def test_field_eval_refpack_mismatch_exits_2_without_force(tmp_path, capsys):
    bundle = _write_bundle(tmp_path / "bundle", "2026-01-01T00:00:00Z")
    log = tmp_path / "s.jsonl"
    _write_log(log, "2026-02-02T00:00:00Z")
    result = main(["field-eval", "--log", str(log), "--refpack", str(bundle), "--out", str(tmp_path / "out.md")])
    assert result == 2
    assert "refpack_created_at" in capsys.readouterr().err
    assert not (tmp_path / "out.md").exists()


def test_field_eval_refpack_mismatch_with_force_succeeds(tmp_path):
    bundle = _write_bundle(tmp_path / "bundle", "2026-01-01T00:00:00Z")
    log = tmp_path / "s.jsonl"
    _write_log(log, "2026-02-02T00:00:00Z")
    result = main([
        "field-eval", "--log", str(log), "--refpack", str(bundle), "--out", str(tmp_path / "out.md"), "--force",
    ])
    assert result == 0
    assert (tmp_path / "out.md").exists()


def test_field_eval_matching_created_at_succeeds(tmp_path):
    bundle = _write_bundle(tmp_path / "bundle", "2026-01-01T00:00:00Z")
    log = tmp_path / "s.jsonl"
    _write_log(log, "2026-01-01T00:00:00Z")
    result = main(["field-eval", "--log", str(log), "--refpack", str(bundle), "--out", str(tmp_path / "out.md")])
    assert result == 0
    assert (tmp_path / "out.md").exists()
