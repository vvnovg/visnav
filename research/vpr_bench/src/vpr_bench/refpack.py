"""refpack v1 — база эталонов для телефона. Формат описан в плане M1 (Global Constraints)."""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"VNRP"
VERSION = 1
DTYPE_F16 = 1
HEADER = struct.Struct("<4sHHII")


@dataclass(frozen=True)
class RefPackData:
    lats: np.ndarray
    lons: np.ndarray
    headings: np.ndarray
    descriptors: np.ndarray
    meta: dict


def write_refpack(out_dir: Path, lats, lons, headings, descriptors: np.ndarray, meta: dict) -> Path:
    descriptors = np.asarray(descriptors)
    if descriptors.ndim != 2:
        raise ValueError("descriptors must be 2-D (count, dim)")
    count, dim = descriptors.shape
    if not (len(lats) == len(lons) == len(headings) == count):
        raise ValueError("lats, lons, headings and descriptors must have equal length")
    out_dir.mkdir(parents=True, exist_ok=True)
    with (out_dir / "refpack.bin").open("wb") as f:
        f.write(HEADER.pack(MAGIC, VERSION, DTYPE_F16, count, dim))
        f.write(np.asarray(lats, dtype="<f8").tobytes())
        f.write(np.asarray(lons, dtype="<f8").tobytes())
        f.write(np.asarray(headings, dtype="<f4").tobytes())
        f.write(np.ascontiguousarray(descriptors, dtype="<f2").tobytes())
    full_meta = {**meta, "format": "VNRP/1", "count": count, "dim": dim}
    (out_dir / "refpack.json").write_text(json.dumps(full_meta, ensure_ascii=False, indent=2))
    return out_dir


def read_refpack(out_dir: Path) -> RefPackData:
    raw = (out_dir / "refpack.bin").read_bytes()
    magic, version, dtype, count, dim = HEADER.unpack_from(raw, 0)
    if magic != MAGIC or version != VERSION or dtype != DTYPE_F16:
        raise ValueError("not a refpack v1 float16 file")
    off = HEADER.size
    lats = np.frombuffer(raw, "<f8", count, off); off += 8 * count
    lons = np.frombuffer(raw, "<f8", count, off); off += 8 * count
    headings = np.frombuffer(raw, "<f4", count, off); off += 4 * count
    desc = np.frombuffer(raw, "<f2", count * dim, off).reshape(count, dim)
    if off + 2 * count * dim != len(raw):
        raise ValueError("refpack size does not match header")
    meta = json.loads((out_dir / "refpack.json").read_text())
    return RefPackData(lats.copy(), lons.copy(), headings.copy(), desc.copy(), meta)
