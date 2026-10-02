from pathlib import Path

import numpy as np
import pytest

from vpr_bench.roadpack import FLAG_ONEWAY, FLAG_TUNNEL, RoadGraph, read_roadpack, write_roadpack

FIXTURE_DIR = Path(__file__).parent / "data" / "roadpack_fixture"
FIXTURE_META = {"created_at": "2026-10-02T00:00:00Z", "source": "fixture", "buffer_m": 300.0,
                "attribution": "© участники OpenStreetMap, ODbL 1.0"}


def fixture_graph() -> RoadGraph:
    """3 узла, 2 ребра: way 101 (0→1, двусторонняя, residential), way 202 (1→2, односторонняя, тоннель, primary)."""
    return RoadGraph(
        node_lats=np.array([55.75, 55.751, 55.751]), node_lons=np.array([37.60, 37.60, 37.602]),
        edge_way=np.array([101, 202], dtype=np.int64), edge_from=np.array([0, 1], dtype=np.int32),
        edge_to=np.array([1, 2], dtype=np.int32),
        edge_flags=np.array([0, FLAG_ONEWAY | FLAG_TUNNEL], dtype=np.uint8),
        edge_class=np.array([7, 3], dtype=np.uint8),
    )


def test_roundtrip(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)
    g, meta = read_roadpack(tmp_path)
    assert (tmp_path / "roadpack.bin").stat().st_size == 16 + 16 * 3 + 18 * 2
    np.testing.assert_array_equal(g.node_lons, [37.60, 37.60, 37.602])
    assert list(g.edge_way) == [101, 202] and list(g.edge_flags) == [0, 3] and list(g.edge_class) == [7, 3]
    assert meta["format"] == "VNRD/1" and meta["node_count"] == 3 and meta["edge_count"] == 2
    assert meta["created_at"] == "2026-10-02T00:00:00Z"


def test_committed_fixture_matches_writer(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)
    assert (tmp_path / "roadpack.bin").read_bytes() == (FIXTURE_DIR / "roadpack.bin").read_bytes()
    g, meta = read_roadpack(FIXTURE_DIR)
    assert meta["edge_count"] == 2 and list(g.edge_to) == [1, 2]


def test_rejects_bad_magic_and_size(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)
    raw = (tmp_path / "roadpack.bin").read_bytes()
    (tmp_path / "roadpack.bin").write_bytes(b"XXXX" + raw[4:])
    with pytest.raises(ValueError, match="magic"):
        read_roadpack(tmp_path)
    (tmp_path / "roadpack.bin").write_bytes(raw[:-1])
    with pytest.raises(ValueError, match="size"):
        read_roadpack(tmp_path)


def test_rejects_edge_out_of_range():
    g = fixture_graph()
    with pytest.raises(ValueError, match="range"):
        RoadGraph(g.node_lats, g.node_lons, g.edge_way, g.edge_from, np.array([1, 3], dtype=np.int32),
                  g.edge_flags, g.edge_class)
