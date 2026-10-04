import dataclasses
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
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META, version=1)
    g, meta = read_roadpack(tmp_path)
    assert (tmp_path / "roadpack.bin").stat().st_size == 16 + 16 * 3 + 18 * 2
    np.testing.assert_array_equal(g.node_lons, [37.60, 37.60, 37.602])
    assert list(g.edge_way) == [101, 202] and list(g.edge_flags) == [0, 3] and list(g.edge_class) == [7, 3]
    assert meta["format"] == "VNRD/1" and meta["node_count"] == 3 and meta["edge_count"] == 2
    assert meta["created_at"] == "2026-10-02T00:00:00Z"


def test_committed_fixture_matches_writer(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META, version=1)
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


from vpr_bench.roadpack import DEFAULT_SPEED_KMH, FLAG_ROUNDABOUT, KIND_NO, KIND_ONLY

FIXTURE_V2_DIR = Path(__file__).parent / "data" / "roadpack_v2_fixture"


def fixture_graph_v2() -> RoadGraph:
    """fixture_graph() + скорость, названия (кириллица), круговое движение на ребре 1 и два запрета."""
    g = fixture_graph()
    return RoadGraph(
        g.node_lats, g.node_lons, g.edge_way, g.edge_from, g.edge_to,
        np.array([0, FLAG_ONEWAY | FLAG_TUNNEL | FLAG_ROUNDABOUT], dtype=np.uint8), g.edge_class,
        edge_speed=np.array([20, 48], dtype=np.uint8), edge_name=np.array([0, -1], dtype=np.int32),
        names=("Тверская улица",),
        restrictions=np.array([[0, 1, 1, KIND_NO], [1, 1, 0, KIND_ONLY]], dtype=np.int32),
    )


def test_v2_roundtrip_and_fixture(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META, version=2)
    g, meta = read_roadpack(tmp_path)
    assert meta["format"] == "VNRD/2"
    assert list(g.edge_speed) == [20, 48] and list(g.edge_name) == [0, -1] and g.names == ("Тверская улица",)
    assert g.restrictions.tolist() == [[0, 1, 1, KIND_NO], [1, 1, 0, KIND_ONLY]]
    assert (tmp_path / "roadpack.bin").read_bytes() == (FIXTURE_V2_DIR / "roadpack.bin").read_bytes()


def test_v2_defaults_without_attributes(tmp_path):
    write_roadpack(tmp_path, fixture_graph(), FIXTURE_META)  # v3 по умолчанию
    g, _ = read_roadpack(tmp_path)
    assert list(g.edge_speed) == [DEFAULT_SPEED_KMH[7], DEFAULT_SPEED_KMH[3]]
    assert list(g.edge_name) == [-1, -1] and g.names == () and len(g.restrictions) == 0


def test_v1_file_reads_with_defaults():
    g, meta = read_roadpack(FIXTURE_DIR)
    assert meta["format"] == "VNRD/1"
    assert list(g.edge_speed) == [DEFAULT_SPEED_KMH[7], DEFAULT_SPEED_KMH[3]] and g.names == ()


def test_v1_writer_rejects_attributes(tmp_path):
    with pytest.raises(ValueError, match="v1"):
        write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META, version=1)


def test_v2_rejects_trailing_bytes(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META, version=2)
    p = tmp_path / "roadpack.bin"
    p.write_bytes(p.read_bytes() + b"\x00")
    with pytest.raises(ValueError, match="trailing"):
        read_roadpack(tmp_path)


def test_bad_restriction_rejected():
    g = fixture_graph()
    with pytest.raises(ValueError, match="restriction"):
        RoadGraph(g.node_lats, g.node_lons, g.edge_way, g.edge_from, g.edge_to, g.edge_flags, g.edge_class,
                  restrictions=np.array([[0, 1, 5, KIND_NO]], dtype=np.int32))


FIXTURE_V3_DIR = Path(__file__).parent / "data" / "roadpack_v3_fixture"


def fixture_graph_v3() -> RoadGraph:
    g = fixture_graph_v2()
    return dataclasses.replace(g, boundary_nodes=np.array([0, 2], dtype=np.int32))


def test_v3_roundtrip(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META)
    g, meta = read_roadpack(tmp_path)
    assert meta["format"] == "VNRD/3" and g.boundary_nodes.tolist() == [0, 2]


def test_v2_still_readable_and_has_no_boundary(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v2(), FIXTURE_META, version=2)
    g, meta = read_roadpack(tmp_path)
    assert meta["format"] == "VNRD/2" and g.boundary_nodes.tolist() == []


def test_v3_fixture_matches_writer(tmp_path):
    # Фикстура для Kotlin: пересоздаётся, если отличается (как roadpack_v2_fixture), и сравнивается побайтно.
    write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META)
    if not FIXTURE_V3_DIR.exists() or (FIXTURE_V3_DIR / "roadpack.bin").read_bytes() != (tmp_path / "roadpack.bin").read_bytes():
        FIXTURE_V3_DIR.mkdir(parents=True, exist_ok=True)
        write_roadpack(FIXTURE_V3_DIR, fixture_graph_v3(), FIXTURE_META)
    assert (FIXTURE_V3_DIR / "roadpack.bin").read_bytes() == (tmp_path / "roadpack.bin").read_bytes()


@pytest.mark.parametrize("bad", [[2, 0], [0, 0], [-1], [99]])
def test_bad_boundary_rejected(bad):
    with pytest.raises(ValueError, match="boundary"):
        dataclasses.replace(fixture_graph_v2(), boundary_nodes=np.array(bad, dtype=np.int32))


def test_v3_truncated_boundary_raises(tmp_path):
    write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META)
    raw = (tmp_path / "roadpack.bin").read_bytes()
    (tmp_path / "roadpack.bin").write_bytes(raw[:-2])
    with pytest.raises(ValueError):
        read_roadpack(tmp_path)


def test_v1_v2_cannot_store_boundary(tmp_path):
    with pytest.raises(ValueError, match="boundary"):
        write_roadpack(tmp_path, fixture_graph_v3(), FIXTURE_META, version=2)
