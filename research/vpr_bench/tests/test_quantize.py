import numpy as np
import onnx
import torch

from vpr_bench.onnx_export import OnnxEmbedder, export_onnx
from vpr_bench.quantize import cosine_parity, quantize_int8


def _net():
    torch.manual_seed(0)
    return torch.nn.Sequential(
        torch.nn.Conv2d(3, 16, 3, padding=1), torch.nn.BatchNorm2d(16), torch.nn.ReLU(),
        torch.nn.Conv2d(16, 16, 3, padding=1), torch.nn.ReLU(),
        torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten(),
    )


def _images(n, seed=0):
    rng = np.random.default_rng(seed)
    return [rng.integers(0, 255, (48, 64, 3), dtype=np.uint8) for _ in range(n)]


def test_quantized_model_close_to_fp32(tmp_path):
    net = _net().eval()
    fp32 = export_onnx(net, (32, 48), tmp_path / "fp32.onnx")
    int8 = quantize_int8(fp32, tmp_path / "int8.onnx", _images(16))
    assert int8.exists()
    emb = OnnxEmbedder(int8)
    assert emb.image_size == (32, 48)
    parity = cosine_parity(fp32, int8, _images(8, seed=1))
    assert set(parity) == {"mean", "min"}
    assert parity["mean"] > 0.95
    assert parity["min"] <= parity["mean"]


def test_cosine_parity_identical_models(tmp_path):
    net = _net().eval()
    fp32 = export_onnx(net, (32, 48), tmp_path / "fp32.onnx")
    parity = cosine_parity(fp32, fp32, _images(4))
    assert parity["min"] > 0.9999


def test_quantize_int8_has_quantize_nodes(tmp_path):
    net = _net().eval()
    fp32 = export_onnx(net, (32, 48), tmp_path / "fp32.onnx")
    int8 = quantize_int8(fp32, tmp_path / "int8.onnx", _images(16))
    model = onnx.load(str(int8))
    op_types = [node.op_type for node in model.graph.node]
    assert "QuantizeLinear" in op_types, f"Expected QuantizeLinear in {set(op_types)}"
    assert "DequantizeLinear" in op_types, f"Expected DequantizeLinear in {set(op_types)}"
