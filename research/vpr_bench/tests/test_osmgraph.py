import json

import pytest

from vpr_bench.m2cli import main
from vpr_bench.osmgraph import RawRestriction, RawWay, build_graph, direction, road_class, speed_kmh, street_name
from vpr_bench.roadpack import (
    DEFAULT_SPEED_KMH, FLAG_BRIDGE, FLAG_ONEWAY, FLAG_ROUNDABOUT, FLAG_TUNNEL, KIND_NO, KIND_ONLY, read_roadpack)


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


def test_speed_and_name():
    assert speed_kmh({"maxspeed": "60"}, 3) == 48
    assert speed_kmh({"maxspeed": "RU:urban"}, 7) == 48
    assert speed_kmh({"maxspeed": "130"}, 1) == 88   # ограничено 110 → 88
    assert speed_kmh({"maxspeed": "30 mph"}, 7) == 39
    assert speed_kmh({"maxspeed": "signals"}, 4) == DEFAULT_SPEED_KMH[4]
    assert speed_kmh({}, 9) == DEFAULT_SPEED_KMH[9]
    assert street_name({"name": "Тверская улица", "ref": "M1"}) == "Тверская улица"
    assert street_name({"ref": "M1"}) == "M1" and street_name({}) is None


def test_build_graph_names_speed_roundabout_restrictions():
    a, b, c, d = (1, 55.75, 37.60), (2, 55.751, 37.60), (3, 55.752, 37.60), (4, 55.751, 37.602)
    ways = [
        _way(10, {"name": "Тверская улица", "maxspeed": "60"}, a, b, c),
        _way(11, {"name": "Тверская улица"}, b, d),
        _way(12, {"junction": "roundabout"}, d, c),
    ]
    rs = [RawRestriction(10, 2, 11, KIND_NO), RawRestriction(11, 2, 10, KIND_ONLY), RawRestriction(10, 99, 11, KIND_NO)]
    g = build_graph(ways, restrictions=rs)
    assert g.names == ("Тверская улица",)
    assert list(g.edge_name) == [0, 0, 0, -1]
    assert list(g.edge_speed) == [48, 48, DEFAULT_SPEED_KMH[7], DEFAULT_SPEED_KMH[7]]
    assert g.edge_flags[3] & FLAG_ROUNDABOUT and g.edge_flags[3] & FLAG_ONEWAY
    # way 10 касается узла b двумя рёбрами (0: a→b, 1: b→c); way 11 — одним (2: b→d); запрет с узлом 99 отброшен
    assert sorted(map(tuple, g.restrictions.tolist())) == [
        (0, 1, 2, KIND_NO), (1, 1, 2, KIND_NO), (2, 1, 0, KIND_ONLY), (2, 1, 1, KIND_ONLY)]


OSM_RESTRICTION_XML = OSM_XML.replace("</osm>", """  <relation id="50" version="1">
    <member type="way" ref="10" role="from"/><member type="node" ref="2" role="via"/>
    <member type="way" ref="11" role="to"/>
    <tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/>
  </relation>
</osm>
""")


def test_read_osm_restrictions(tmp_path):
    pytest.importorskip("osmium")
    from vpr_bench.osmgraph import read_osm
    p = tmp_path / "t.osm"
    p.write_text(OSM_RESTRICTION_XML)
    ways, rs = read_osm(p)
    assert sorted(w.id for w in ways) == [10, 11, 13]
    assert rs == [RawRestriction(10, 2, 11, KIND_NO)]


def test_pack_roads_writes_v2(tmp_path):
    pytest.importorskip("osmium")
    (tmp_path / "t.osm").write_text(OSM_RESTRICTION_XML)
    (tmp_path / "t.gpx").write_text(GPX)
    out = tmp_path / "roads"
    assert main(["pack-roads", "--pbf", str(tmp_path / "t.osm"), "--gpx", str(tmp_path / "t.gpx"),
                 "--out", str(out)]) == 0
    g, meta = read_roadpack(out)
    assert meta["format"] == "VNRD/2" and len(g.restrictions) >= 1
