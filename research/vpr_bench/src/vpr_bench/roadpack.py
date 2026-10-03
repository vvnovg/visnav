"""roadpack — дорожный граф OSM для телефона. v1 — план M2b; v2 (скорость, названия, запреты) — план M3a."""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"VNRD"
HEADER = struct.Struct("<4sHHII")
FLAG_ONEWAY = 1
FLAG_TUNNEL = 2
FLAG_BRIDGE = 4
FLAG_ROUNDABOUT = 8
KIND_NO = 1
KIND_ONLY = 2
# Скорость для оценки времени в пути по классу дороги, км/ч (общая с Kotlin RoadClass.defaultSpeedKmh).
DEFAULT_SPEED_KMH = {1: 90, 2: 70, 3: 60, 4: 50, 5: 40, 6: 30, 7: 20, 8: 10, 9: 10}


def _default_speeds(cls: np.ndarray) -> np.ndarray:
    return np.array([DEFAULT_SPEED_KMH.get(int(c), 20) for c in cls], dtype=np.uint8)


@dataclass(frozen=True)
class RoadGraph:
    node_lats: np.ndarray   # float64[n]
    node_lons: np.ndarray   # float64[n]
    edge_way: np.ndarray    # int64[m]  — OSM way id
    edge_from: np.ndarray   # int32[m]
    edge_to: np.ndarray     # int32[m]
    edge_flags: np.ndarray  # uint8[m]  — FLAG_*
    edge_class: np.ndarray  # uint8[m]  — код класса дороги
    edge_speed: np.ndarray | None = None    # uint8[m], км/ч; None — по классу
    edge_name: np.ndarray | None = None     # int32[m], индекс в names или -1
    names: tuple[str, ...] = ()
    restrictions: np.ndarray | None = None  # int32[k, 4]: from_edge, via_node, to_edge, kind

    def __post_init__(self) -> None:
        n, m = len(self.node_lats), len(self.edge_from)
        if len(self.node_lons) != n:
            raise ValueError("node_lats and node_lons must have equal length")
        if not (len(self.edge_to) == len(self.edge_way) == len(self.edge_flags) == len(self.edge_class) == m):
            raise ValueError("edge arrays must have equal length")
        if m and (min(self.edge_from.min(), self.edge_to.min()) < 0
                  or max(self.edge_from.max(), self.edge_to.max()) >= n):
            raise ValueError("edge node index out of range")
        if self.edge_speed is None:
            object.__setattr__(self, "edge_speed", _default_speeds(self.edge_class))
        if self.edge_name is None:
            object.__setattr__(self, "edge_name", np.full(m, -1, dtype=np.int32))
        if self.restrictions is None:
            object.__setattr__(self, "restrictions", np.zeros((0, 4), dtype=np.int32))
        if len(self.edge_speed) != m or len(self.edge_name) != m:
            raise ValueError("edge_speed and edge_name must have one entry per edge")
        if m and (self.edge_name.min() < -1 or self.edge_name.max() >= len(self.names)):
            raise ValueError("edge name index out of range")
        r = self.restrictions
        if r.ndim != 2 or r.shape[1] != 4:
            raise ValueError("restrictions must have shape (k, 4)")
        if len(r) and (r[:, [0, 2]].min() < 0 or r[:, [0, 2]].max() >= m or r[:, 1].min() < 0
                       or r[:, 1].max() >= n or not set(r[:, 3].tolist()) <= {KIND_NO, KIND_ONLY}):
            raise ValueError("bad restriction (edge/node index or kind)")


def write_roadpack(out_dir: Path, g: RoadGraph, meta: dict, version: int = 2) -> Path:
    if version not in (1, 2):
        raise ValueError(f"unsupported roadpack version={version}")
    if version == 1 and (g.names or len(g.restrictions)):
        raise ValueError("roadpack v1 cannot store names or restrictions")
    out_dir.mkdir(parents=True, exist_ok=True)
    n, m = len(g.node_lats), len(g.edge_from)
    with (out_dir / "roadpack.bin").open("wb") as f:
        f.write(HEADER.pack(MAGIC, version, 0, n, m))
        f.write(np.asarray(g.node_lats, dtype="<f8").tobytes())
        f.write(np.asarray(g.node_lons, dtype="<f8").tobytes())
        f.write(np.asarray(g.edge_way, dtype="<i8").tobytes())
        f.write(np.asarray(g.edge_from, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_to, dtype="<i4").tobytes())
        f.write(np.asarray(g.edge_flags, dtype="u1").tobytes())
        f.write(np.asarray(g.edge_class, dtype="u1").tobytes())
        if version == 2:
            f.write(np.asarray(g.edge_speed, dtype="u1").tobytes())
            f.write(np.asarray(g.edge_name, dtype="<i4").tobytes())
            f.write(struct.pack("<I", len(g.names)))
            for s in g.names:
                b = s.encode("utf-8")
                f.write(struct.pack("<H", len(b)) + b)
            f.write(struct.pack("<I", len(g.restrictions)))
            for fr, via, to, kind in g.restrictions.tolist():
                f.write(struct.pack("<iiiB", fr, via, to, kind))
    full = {**meta, "format": f"VNRD/{version}", "node_count": n, "edge_count": m}
    (out_dir / "roadpack.json").write_text(json.dumps(full, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return out_dir / "roadpack.bin"


def read_roadpack(dir_: Path) -> tuple[RoadGraph, dict]:
    raw = (dir_ / "roadpack.bin").read_bytes()
    if len(raw) < HEADER.size:
        raise ValueError("roadpack too short")
    magic, version, _, n, m = HEADER.unpack_from(raw, 0)
    if magic != MAGIC:
        raise ValueError("not a roadpack (bad magic)")
    if version not in (1, 2):
        raise ValueError(f"unsupported roadpack version={version}")
    base = HEADER.size + 16 * n + 18 * m
    if (version == 1 and len(raw) != base) or (version == 2 and len(raw) < base + 5 * m + 8):
        raise ValueError(f"roadpack size {len(raw)} does not match header (base {base})")
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
    speed = name = restr = None
    names: tuple[str, ...] = ()
    if version == 2:
        speed, name = take("u1", m, 1), take("<i4", m, 4)
        (count,) = struct.unpack_from("<I", raw, off)
        off += 4
        out = []
        for _ in range(count):
            (ln,) = struct.unpack_from("<H", raw, off)
            out.append(raw[off + 2: off + 2 + ln].decode("utf-8"))
            off += 2 + ln
        names = tuple(out)
        (k,) = struct.unpack_from("<I", raw, off)
        off += 4
        rows = [struct.unpack_from("<iiiB", raw, off + 13 * i) for i in range(k)]
        off += 13 * k
        restr = np.array(rows, dtype=np.int32).reshape(k, 4)
        if off != len(raw):
            raise ValueError(f"roadpack has {len(raw) - off} trailing bytes")
    meta = json.loads((dir_ / "roadpack.json").read_text(encoding="utf-8"))
    g = RoadGraph(lats, lons, way, fr.astype(np.int32), to.astype(np.int32), flags, cls,
                  edge_speed=speed, edge_name=name, names=names, restrictions=restr)
    return g, meta
