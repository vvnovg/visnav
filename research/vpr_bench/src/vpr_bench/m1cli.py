"""CLI этапа M1: vpr-m1 export-onnx | make-parity | quantize-onnx | pack-refs | field-eval."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2

from vpr_bench.models import MODEL_SPECS, load_model
from vpr_bench.onnx_export import OnnxEmbedder, export_onnx, make_parity


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vpr-m1")
    sub = parser.add_subparsers(dest="command", required=True)

    e = sub.add_parser("export-onnx", help="экспортировать модель из реестра в ONNX для телефона")
    e.add_argument("--model", required=True)
    e.add_argument("--out", required=True, type=Path)

    p = sub.add_parser("make-parity", help="эталон для проверки модели на телефоне")
    p.add_argument("--onnx", required=True, type=Path)
    p.add_argument("--image", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    return parser


def _export(args) -> int:
    if args.model not in MODEL_SPECS:
        print(f"error: unknown model {args.model!r}; known: {sorted(MODEL_SPECS)}", file=sys.stderr)
        return 2
    model = load_model(args.model)
    export_onnx(model.net.cpu(), model.image_size, args.out)
    # Проверка: ONNX и PyTorch должны давать один и тот же дескриптор.
    rng_imgs = [_noise(i) for i in range(4)]
    torch_desc = model.embed(rng_imgs)
    onnx_desc = OnnxEmbedder(args.out).embed(rng_imgs)
    min_cos = float((torch_desc * onnx_desc).sum(axis=1).min())
    print(f"exported {args.model} -> {args.out}; min cosine torch/onnx = {min_cos:.5f}")
    if min_cos < 0.999:
        print("error: ONNX output differs from PyTorch (min cosine < 0.999)", file=sys.stderr)
        return 1
    return 0


def _noise(seed: int):
    import numpy as np

    return np.random.default_rng(seed).integers(0, 255, (480, 640, 3), dtype=np.uint8)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "export-onnx":
        return _export(args)
    if args.command == "make-parity":
        img = cv2.imread(str(args.image))
        if img is None:
            print(f"error: cannot read image {args.image}", file=sys.stderr)
            return 2
        out = make_parity(args.onnx, img, args.out)
        print(f"parity fixture -> {out}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
