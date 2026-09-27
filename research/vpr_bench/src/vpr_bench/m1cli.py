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

    qz = sub.add_parser("quantize-onnx", help="статическая INT8-квантизация по снимкам-эталонам")
    qz.add_argument("--onnx", required=True, type=Path)
    qz.add_argument("--calib", required=True, type=Path, help="refs.csv, из которого берутся снимки для калибровки")
    qz.add_argument("--n", type=int, default=200)
    qz.add_argument("--out", required=True, type=Path)
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


def _quantize(args) -> int:
    from vpr_bench.dataset import read_places
    from vpr_bench.quantize import cosine_parity, quantize_int8

    places = read_places(args.calib)
    if not places or args.n <= 0:
        print("error: need a non-empty --calib and --n > 0", file=sys.stderr)
        return 2
    step = max(1, len(places) // args.n)
    chosen = places[::step][: args.n]
    images = [cv2.imread(str(args.calib.parent / p.path)) for p in chosen]
    if any(img is None for img in images):
        print("error: some calibration images could not be read", file=sys.stderr)
        return 2
    calib, check = images[::2], images[1::2] or images
    quantize_int8(args.onnx, args.out, calib)
    parity = cosine_parity(args.onnx, args.out, check)
    print(
        f"int8 -> {args.out} ({args.out.stat().st_size / 1e6:.1f} MB, fp32 "
        f"{args.onnx.stat().st_size / 1e6:.1f} MB); cosine mean={parity['mean']:.4f} min={parity['min']:.4f}"
    )
    return 0


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
    if args.command == "quantize-onnx":
        return _quantize(args)
    return 2


if __name__ == "__main__":
    sys.exit(main())
