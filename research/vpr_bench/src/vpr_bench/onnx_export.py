"""Экспорт VPR-модели в ONNX для телефона и эмбеддер на ONNX Runtime.

Контракт модели: вход "image" uint8 [1, H, W, 3] RGB, выход "descriptor"
float32 [1, D], L2-нормирован. Нормализация ImageNet встроена в граф, чтобы
телефону оставалось только уменьшить кадр и разложить пиксели в RGB.
"""
from __future__ import annotations

import hashlib
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import torch

from vpr_bench.models import IMAGENET_MEAN, IMAGENET_STD


class DeviceWrapper(torch.nn.Module):
    def __init__(self, net: torch.nn.Module):
        super().__init__()
        self.net = net
        self.register_buffer("mean", torch.tensor(IMAGENET_MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(IMAGENET_STD).view(1, 3, 1, 1))

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        x = image.permute(0, 3, 1, 2).float() / 255.0
        x = (x - self.mean) / self.std
        out = self.net(x)
        if isinstance(out, (tuple, list)):
            out = out[0]
        return torch.nn.functional.normalize(out.flatten(1), dim=1)


def export_onnx(net: torch.nn.Module, image_size: tuple[int, int], out_path: Path, opset: int = 18) -> Path:
    h, w = image_size
    wrapper = DeviceWrapper(net.eval()).eval()
    dummy = torch.zeros((1, h, w, 3), dtype=torch.uint8)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        torch.onnx.export(
            wrapper, (dummy,), str(out_path),
            input_names=["image"], output_names=["descriptor"], opset_version=opset,
        )
    return out_path


def onnx_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def to_model_input(img_bgr: np.ndarray, image_size: tuple[int, int]) -> np.ndarray:
    h, w = image_size
    resized = cv2.resize(img_bgr, (w, h), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)[None].astype(np.uint8)


class OnnxEmbedder:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.session = ort.InferenceSession(str(self.path), providers=["CPUExecutionProvider"])
        shape = self.session.get_inputs()[0].shape  # [1, h, w, 3]
        self.image_size = (int(shape[1]), int(shape[2]))
        self.name = f"onnx-{self.path.stem}-{onnx_sha256(self.path)[:8]}"

    def embed(self, images_bgr: list[np.ndarray]) -> np.ndarray:
        out = [
            self.session.run(["descriptor"], {"image": to_model_input(img, self.image_size)})[0][0]
            for img in images_bgr
        ]
        return np.stack(out).astype(np.float32)

    def size_mb(self) -> float:
        return self.path.stat().st_size / 1e6


def make_parity(onnx_path: Path, image_bgr: np.ndarray, out_dir: Path) -> Path:
    """Эталон для проверки на телефоне: картинка уже размера модели + ожидаемый дескриптор."""
    emb = OnnxEmbedder(onnx_path)
    rgb = to_model_input(image_bgr, emb.image_size)[0]
    out_dir.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out_dir / "input.png"), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    desc = emb.session.run(["descriptor"], {"image": rgb[None]})[0][0]
    desc.astype("<f4").tofile(out_dir / "expected.f32")
    return out_dir
