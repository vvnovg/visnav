import numpy as np
import onnx
import pytest
import torch

from vpr_bench.models import VprModel, load_model
from vpr_bench.onnx_export import OnnxEmbedder, export_onnx, make_parity, to_model_input

SIZE = (32, 48)  # (h, w)


def _net():
    torch.manual_seed(0)
    return torch.nn.Sequential(
        torch.nn.Conv2d(3, 8, 3, padding=1), torch.nn.ReLU(),
        torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten(),
    )


def _images(n):
    rng = np.random.default_rng(0)
    return [rng.integers(0, 255, (60, 80, 3), dtype=np.uint8) for _ in range(n)]


@pytest.fixture
def exported(tmp_path):
    net = _net()
    path = export_onnx(net, SIZE, tmp_path / "m.onnx")
    return net, path


def test_export_contract(exported):
    _, path = exported
    model = onnx.load(str(path))
    inp, out = model.graph.input[0], model.graph.output[0]
    assert inp.name == "image" and out.name == "descriptor"
    assert inp.type.tensor_type.elem_type == onnx.TensorProto.UINT8
    dims = [d.dim_value for d in inp.type.tensor_type.shape.dim]
    assert dims == [1, 32, 48, 3]


def test_onnx_matches_torch(exported):
    net, path = exported
    torch_desc = VprModel("t", net, SIZE).embed(_images(3))
    onnx_desc = OnnxEmbedder(path).embed(_images(3))
    cos = np.sum(torch_desc * onnx_desc, axis=1)
    assert onnx_desc.shape == (3, 8)
    assert np.all(cos > 0.9999)
    assert np.linalg.norm(onnx_desc, axis=1) == pytest.approx([1, 1, 1], abs=1e-5)


def test_embedder_metadata(exported):
    _, path = exported
    emb = OnnxEmbedder(path)
    assert emb.image_size == SIZE
    assert emb.name.startswith("onnx-m-") and len(emb.name) == len("onnx-m-") + 8
    assert emb.size_mb() > 0


def test_to_model_input_shape_and_channel_order():
    img = np.zeros((10, 10, 3), np.uint8)
    img[..., 0] = 255  # синий в BGR
    x = to_model_input(img, (4, 6))
    assert x.shape == (1, 4, 6, 3) and x.dtype == np.uint8
    assert x[0, 0, 0].tolist() == [0, 0, 255]  # RGB: синий в последнем канале


def test_load_model_onnx_prefix(exported):
    _, path = exported
    emb = load_model(f"onnx:{path}")
    assert isinstance(emb, OnnxEmbedder)


def test_make_parity_roundtrip(tmp_path, exported):
    _, path = exported
    out = make_parity(path, _images(1)[0], tmp_path / "parity")
    expected = np.fromfile(out / "expected.f32", dtype="<f4")
    assert expected.shape == (8,)
    import cv2
    png = cv2.imread(str(out / "input.png"))  # BGR
    assert png.shape == (32, 48, 3)
    again = OnnxEmbedder(path).embed([png])[0]
    assert float(np.dot(again, expected)) > 0.9999
