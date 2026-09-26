"""Реестр VPR-моделей (torch.hub) и единый интерфейс получения глобальных дескрипторов."""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np
import torch

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


@dataclass(frozen=True)
class ModelSpec:
    repo: str
    entry: str
    kwargs: dict
    image_size: tuple[int, int]  # (h, w)


# Repos are pinned to a reviewed commit via torch.hub's "owner/repo:ref" syntax
# so a `bench` run cannot silently pick up unreviewed upstream changes.
# SALAD/BoQ hubconf may load facebookresearch/dinov2 internally, which this pin
# does not cover; reviewed dinov2 HEAD at time of pinning was
# 7764ea0f912e53c92e82eb78a2a1631e92725fc8.
MODEL_SPECS: dict[str, ModelSpec] = {
    "cosplace-r50": ModelSpec(
        "gmberton/cosplace:52b56e95ea62245281281f3bafd7b9390d19a0fd",
        "get_trained_model", {"backbone": "ResNet50", "fc_output_dim": 2048}, (480, 640)
    ),
    "eigenplaces-r50": ModelSpec(
        "gmberton/eigenplaces:a2969f71d5ea31017443af490b15273ca4c50af1",
        "get_trained_model", {"backbone": "ResNet50", "fc_output_dim": 2048}, (480, 640)
    ),
    "salad-dinov2": ModelSpec("serizba/salad:6aede13a3f6c25750bf7fde10209c06cb73060bb", "dinov2_salad", {}, (322, 322)),
    "boq-dinov2": ModelSpec(
        "amaralibey/bag-of-queries:1a4965ea7dfd9bd0dd846adf7a0e430f68101d12",
        "get_trained_boq", {"backbone_name": "dinov2", "output_dim": 12288}, (322, 322)
    ),
}


class VprModel:
    def __init__(self, name: str, net: torch.nn.Module, image_size: tuple[int, int], device: str = "cpu"):
        self.name = name
        self.net = net.eval().to(device)
        self.image_size = image_size
        self.device = device

    def preprocess(self, images_bgr: list[np.ndarray]) -> torch.Tensor:
        h, w = self.image_size
        batch = []
        for img in images_bgr:
            resized = cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA)
            rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
            batch.append((rgb - IMAGENET_MEAN) / IMAGENET_STD)
        arr = np.ascontiguousarray(np.stack(batch).transpose(0, 3, 1, 2))
        return torch.from_numpy(arr).to(self.device)

    @torch.inference_mode()
    def embed(self, images_bgr: list[np.ndarray]) -> np.ndarray:
        out = self.net(self.preprocess(images_bgr))
        if isinstance(out, (tuple, list)):
            out = out[0]
        out = torch.nn.functional.normalize(out.float().flatten(1), dim=1)
        return out.cpu().numpy().astype(np.float32)

    def size_mb(self) -> float:
        return sum(p.numel() * p.element_size() for p in self.net.parameters()) / 1e6


def pick_device() -> str:
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def load_model(name: str, device: str = "cpu") -> VprModel:
    if name not in MODEL_SPECS:
        raise KeyError(f"unknown model {name!r}; known: {sorted(MODEL_SPECS)}")
    spec = MODEL_SPECS[name]
    net = torch.hub.load(spec.repo, spec.entry, trust_repo=True, **spec.kwargs)
    return VprModel(name, net, spec.image_size, device)
