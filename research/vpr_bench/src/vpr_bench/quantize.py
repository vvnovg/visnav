"""Статическая INT8-квантизация ONNX-модели (QDQ) и проверка близости к FP32."""
from __future__ import annotations

import tempfile
from pathlib import Path

import numpy as np
from onnxruntime.quantization import (
    CalibrationDataReader, QuantFormat, QuantType, quantize_static,
)
from onnxruntime.quantization.shape_inference import quant_pre_process

from vpr_bench.onnx_export import OnnxEmbedder, to_model_input


class _CalibReader(CalibrationDataReader):
    def __init__(self, batches: list[dict[str, np.ndarray]]):
        self._it = iter(batches)

    def get_next(self):
        return next(self._it, None)


def quantize_int8(fp32_path: Path, out_path: Path, calib_images_bgr: list[np.ndarray]) -> Path:
    image_size = OnnxEmbedder(fp32_path).image_size
    batches = [{"image": to_model_input(img, image_size)} for img in calib_images_bgr]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        prepped = Path(tmp) / "prepped.onnx"
        # Skip symbolic shape inference: our exported models have fully static shapes,
        # and symbolic inference fails on the ImageNet-normalization broadcast in DeviceWrapper.
        quant_pre_process(str(fp32_path), str(prepped), skip_symbolic_shape=True)
        quantize_static(
            str(prepped), str(out_path), _CalibReader(batches),
            quant_format=QuantFormat.QDQ, per_channel=True,
            weight_type=QuantType.QInt8, activation_type=QuantType.QUInt8,
        )
    return out_path


def cosine_parity(a_path: Path, b_path: Path, images_bgr: list[np.ndarray]) -> dict[str, float]:
    a = OnnxEmbedder(a_path).embed(images_bgr)
    b = OnnxEmbedder(b_path).embed(images_bgr)
    cos = np.sum(a * b, axis=1)
    return {"mean": float(cos.mean()), "min": float(cos.min())}
