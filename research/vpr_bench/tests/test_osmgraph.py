import json

import pytest

from vpr_bench.m2cli import main
from vpr_bench.osmgraph import RawWay, build_graph, direction, road_class
from vpr_bench.roadpack import FLAG_BRIDGE, FLAG_ONEWAY, FLAG_TUNNEL, read_roadpack


def test_road_class_filters():
    assert road_class({"highway": "residential"}) == 7
    assert road_class({"highway": "primary_link"}) == 3
    assert road_class({"highway": "footway"}) is None
    assert road_class({"highway": "service", "service": "parking_aisle"}) is None
    assert road_class({"highway": "service"}) == 9
    assert road_class({"highway": "residential", "access": "private"}) is None
    assert road_class({"highway": "pedestrian", "area": "yes"}) is None


def test_direction():
    assert direction({"oneway": "yes"}) == 1
    assert direction({"oneway": "-1"}) == -1
    assert direction({"junction": "roundabout"}) == 1
    assert direction({"highway": "motorway"}) == 1
    assert direction({"highway": "motorway", "oneway": "no"}) == 0
    assert direction({"highway": "primary"}) == 0


def _way(i, tags, *nodes):
    return RawWay(i, {"highway": "residential", **tags}, list(nodes))


def test_build_graph_edges_flags_and_shared_nodes():
    a, b, c, d = (1, 55.75, 37.60), (2, 55.751, 37.60), (3, 55.752, 37.60), (4, 55.751, 37.602)
    g = build_graph([
        _way(10, {}, a, b, c),
        _way(11, {"oneway": "-1", "tunnel": "yes"}, b, d),
        _way(12, {"bridge": "viaduct"}, c, c, d),   # повтор узла пропускается
    ])
    assert len(g.node_lats) == 4 and len(g.edge_from) == 4
    assert list(g.edge_way) == [10, 10, 11, 12]
    k = 2  # way 11 развёрнут: d → b
    assert (g.node_lons[g.edge_from[k]], g.node_lons[g.edge_to[k]]) == (37.602, 37.60)
    assert g.edge_flags[k] == FLAG_ONEWAY | FLAG_TUNNEL
    assert g.edge_flags[3] == FLAG_BRIDGE and g.edge_flags[0] == 0
    assert g.edge_to[0] == g.edge_from[1]  # узел b общий


def test_build_graph_keep_drops_far_edges_and_nodes():
    near1, near2, far1, far2 = (1, 55.75, 37.60), (2, 55.751, 37.60), (3, 56.0, 38.0), (4, 56.001, 38.0)
    g = build_graph([_way(1, {}, near1, near2), _way(2, {}, far1, far2)], keep=lambda lat, lon: lat < 55.9)
    assert len(g.edge_from) == 1 and len(g.node_lats) == 2


OSM_XML = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" version="1" lat="55.7500" lon="37.6000"/>
  <node id="2" version="1" lat="55.7509" lon="37.6000"/>
  <node id="3" version="1" lat="55.7518" lon="37.6000"/>
  <node id="4" version="1" lat="55.7509" lon="37.6020"/>
  <way id="10" version="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
  <way id="11" version="1"><nd ref="2"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
  <way id="12" version="1"><nd ref="1"/><nd ref="4"/><tag k="highway" v="footway"/></way>
  <way id="13" version="1"><nd ref="1"/><nd ref="99"/><nd ref="3"/><nd ref="4"/><tag k="highway" v="service"/></way>
</osm>
"""

GPX = """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
<trkpt lat="55.7500" lon="37.6000"><time>2026-10-02T10:00:00Z</time></trkpt>
<trkpt lat="55.7518" lon="37.6000"><time>2026-10-02T10:00:20Z</time></trkpt>
</trkseg></trk></gpx>"""


def test_read_ways_splits_at_missing_nodes(tmp_path):
    pytest.importorskip("osmium")
    from vpr_bench.osmgraph import read_ways
    p = tmp_path / "t.osm"
    p.write_text(OSM_XML)
    ways = read_ways(p)
    assert sorted(w.id for w in ways) == [10, 11, 13]       # footway отброшен
    w13 = [w for w in ways if w.id == 13]
    assert len(w13) == 1 and [n[0] for n in w13[0].nodes] == [3, 4]  # узел 99 разорвал линию


def test_pack_roads_cli(tmp_path):
    pytest.importorskip("osmium")
    (tmp_path / "t.osm").write_text(OSM_XML)
    (tmp_path / "t.gpx").write_text(GPX)
    out = tmp_path / "roads"
    assert main(["pack-roads", "--pbf", str(tmp_path / "t.osm"), "--gpx", str(tmp_path / "t.gpx"),
                 "--out", str(out)]) == 0
    g, meta = read_roadpack(out)
    assert len(g.edge_from) == 4 and len(g.node_lats) == 4
    assert meta["source"] == "t.osm" and meta["buffer_m"] == 300.0 and "OpenStreetMap" in meta["attribution"]


def test_pack_roads_without_track_returns_2(tmp_path):
    (tmp_path / "t.osm").write_text(OSM_XML)
    assert main(["pack-roads", "--pbf", str(tmp_path / "t.osm"), "--out", str(tmp_path / "r")]) == 2
