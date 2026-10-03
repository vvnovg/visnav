import random

from _roads import LAT0, LON0, graph_from_lines, line
from vpr_bench.geo import TrackPoint, offset_m
from vpr_bench.roadmatch import RoadNet, match_track
from vpr_bench.roadpack import FLAG_ONEWAY


def _track(points, t0=1000.0):
    return [TrackPoint(t0 + i, *offset_m(LAT0, LON0, e, n)) for i, (e, n) in enumerate(points)]


def test_parallel_roads_noise_stays_on_true_road():
    net = RoadNet(graph_from_lines([(1, line(0, -100, 0, 3100), 0, 7), (2, line(25, -100, 25, 3100), 0, 7)]))
    rnd = random.Random(1)
    snaps = match_track(net, _track([(rnd.gauss(0, 5), 10.0 * i) for i in range(300)]))
    assert sum(s is not None and s.way_id == 1 for s in snaps) >= 294


def test_turn_at_junction():
    net = RoadNet(graph_from_lines([(1, line(0, 0, 500, 0), 0, 7), (3, line(500, 0, 500, 500), 0, 7),
                                    (4, line(500, 0, 1000, 0), 0, 7)]))
    pts = [(10.0 * i, 0.0) for i in range(51)] + [(500.0, 10.0 * i) for i in range(1, 51)]
    snaps = match_track(net, _track(pts))
    assert all(s.way_id == 1 for s in snaps[:45]) and all(s.way_id == 3 for s in snaps[56:])


def test_oneway_forbids_wrong_direction():
    net = RoadNet(graph_from_lines([(1, line(0, 0, 0, 1000), FLAG_ONEWAY, 7), (2, line(15, 0, 15, 1000), 0, 7)]))
    snaps = match_track(net, _track([(7.5, 1000.0 - 10.0 * i) for i in range(90)]))  # на юг, против way 1
    assert sum(s is not None and s.way_id == 2 for s in snaps) >= 88


def test_off_road_point_is_none_and_gap_splits_chain():
    net = RoadNet(graph_from_lines([(1, line(0, 0, 0, 1000), 0, 7)]))
    track = _track([(0.0, 10.0 * i) for i in range(20)])
    track[10] = TrackPoint(track[10].t, *offset_m(LAT0, LON0, 300.0, 100.0))
    late = [TrackPoint(p.t + 60.0, p.lat, p.lon) for p in _track([(0.0, 500.0 + 10.0 * i) for i in range(10)])]
    snaps = match_track(net, track + late)
    assert snaps[10] is None
    assert all(s is not None and s.way_id == 1 for i, s in enumerate(snaps) if i != 10)


def test_dense_nodes_parallel_roads():
    net = RoadNet(graph_from_lines([(1, line(0, -100, 0, 3100, step=10.0), 0, 7),
                                    (2, line(25, -100, 25, 3100, step=10.0), 0, 7)]))
    rnd = random.Random(4)
    snaps = match_track(net, _track([(rnd.gauss(0, 5), 10.0 * i) for i in range(300)]))
    assert sum(s is not None and s.way_id == 1 for s in snaps) >= 294
