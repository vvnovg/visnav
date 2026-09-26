import numpy as np
import pytest
import torch

from vpr_bench.models import MODEL_SPECS, VprModel, load_model


def _pool_net():
    return torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())


class TupleNet(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.inner = _pool_net()

    def forward(self, x):
        return self.inner(x), "attention-maps"


def _images(n):
    return [np.random.default_rng(i).integers(0, 255, (60, 80, 3), dtype=np.uint8) for i in range(n)]


def test_embed_returns_normalized_descriptors():
    model = VprModel("fake", _pool_net(), (32, 48))
    desc = model.embed(_images(2))
    assert desc.shape == (2, 3)
    assert desc.dtype == np.float32
    assert np.linalg.norm(desc, axis=1) == pytest.approx([1.0, 1.0], abs=1e-5)


def test_preprocess_resizes_to_model_size():
    model = VprModel("fake", _pool_net(), (32, 48))
    assert tuple(model.preprocess(_images(1)).shape) == (1, 3, 32, 48)


def test_embed_unwraps_tuple_output():
    desc = VprModel("fake", TupleNet(), (32, 32)).embed(_images(1))
    assert desc.shape == (1, 3)


def test_size_mb_counts_parameters():
    net = torch.nn.Linear(1000, 1000)  # ≈ 1 001 000 float32 = 4.004 МБ
    assert VprModel("lin", net, (1, 1)).size_mb() == pytest.approx(4.004, rel=1e-3)


def test_registry_has_expected_models():
    assert set(MODEL_SPECS) == {"cosplace-r50", "eigenplaces-r50", "salad-dinov2", "boq-dinov2"}


def test_load_unknown_model_raises():
    with pytest.raises(KeyError):
        load_model("nope")


@pytest.mark.slow
@pytest.mark.parametrize("name", sorted(MODEL_SPECS))
def test_real_model_embeds(name):
    model = load_model(name)
    desc = model.embed(_images(1))
    assert desc.shape[0] == 1 and desc.shape[1] >= 256
