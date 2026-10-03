"""roadpack v1 — дорожный граф OSM для телефона. Формат описан в плане M2b (Global Constraints)."""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"VNRD"
VERSION = 1
HEADER = struct.Struct("<4sHHII")
FLAG_ONEWAY = 1
FLAG_TUNNEL = 2
FLAG_BRIDGE = 4


@dataclass(frozen=True)
class RoadGraph:
    node_lats: np.ndarray   # float64[n]
    node_lons: np.ndarray   # float64[n]
    edge_way: np.ndarray    # int64[m]  — OSM way id
    edge_from: np.ndarray   # int32[m]
    edge_to: np.ndarray     # int32[m]
    edge_flags: np.ndarray  # uint8[m]  — FLAG_*
    edge_class: np.ndarray  # uint8[m]  — код класса дороги

    def __post_init__(self) -> None:
        n, m = len(self.node_lats), len(self.edge_from)
        if len(self.node_lons) != n:
            raise ValueError("node_lats and node_lons must have equal length")
        if not (len(self.edge_to) == len(self.edge_way) == len(self.edge_flags) == len(self.edge_class) == m):
            raise ValueError("edge arrays must have equal length")
        if m and (min(self.edge_from.min(), self.edge_to.min()) < 0
                  or max(self.edge_from.max(), self.edge_to.max()) >= n):
            raise ValueError("edge node index out of range")


def write_roadpack(out_dir: Path, g: RoadGraph, meta: dict) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    n, m = len(g.node_lats), len(g.edge_from)
    with (out_dir / "roadpack.bin").open("wb") as f:
        f.write(HEADER.pack(MAGIC, VERSION, 0, n, m))
        f.write(np.asarray(g.node_lats, dtype="<f8").tobytes())
        f.write(np.asarray(g.node_lons, dtype="<f8").tobytes())
        f.write(np.asarray(g.edge_way, dtype="<i8").tobytes())
        f.write(np.asarray(g.edge_from, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_to, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_flags, dtype="u1").tobytes())
        f.write(np.asarray(g.edge_class, dtype="u1").tobytes())
    full = {**meta, "format": "VNRD/1", "node_count": n, "edge_count": m}
    (out_dir / "roadpack.json").write_text(json.dumps(full, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return out_dir / "roadpack.bin"


def read_roadpack(dir_: Path) -> tuple[RoadGraph, dict]:
    raw = (dir_ / "roadpack.bin").read_bytes()
    if len(raw) < HEADER.size:
        raise ValueError("roadpack too short")
    magic, version, _, n, m = HEADER.unpack_from(raw, 0)
    if magic != MAGIC:
        raise ValueError("not a roadpack (bad magic)")
    if version != VERSION:
        raise ValueError(f"unsupported roadpack version={version}")
    expected = HEADER.size + 16 * n + 18 * m
    if len(raw) != expected:
        raise ValueError(f"roadpack size {len(raw)} != expected {expected}")
    off = HEADER.size

    def take(dtype: str, count: int, size: int) -> np.ndarray:
        nonlocal off
        arr = np.frombuffer(raw, dtype=dtype, count=count, offset=off).copy()
        off += count * size
        return arr

    lats, lons = take("<f8", n, 8), take("<f8", n, 8)
    way = take("<i8", m, 8)
    fr, to = take("<i4", m, 4), take("<i4", m, 4)
    flags, cls = take("u1", m, 1), take("u1", m, 1)
    meta = json.loads((dir_ / "roadpack.json").read_text(encoding="utf-8"))
    return RoadGraph(lats, lons, way, fr.astype(np.int32), to.astype(np.int32), flags, cls), meta
