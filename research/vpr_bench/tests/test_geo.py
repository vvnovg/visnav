import math

import numpy as np
import pytest

from vpr_bench.geo import (
    BBox,
    TrackPoint,
    bearing_deg,
    haversine_m,
    haversine_m_vec,
    interpolate_track,
    offset_m,
)


def test_haversine_one_degree_latitude():
    assert haversine_m(0, 0, 1, 0) == pytest.approx(111_195, rel=1e-3)


def test_haversine_vec_matches_scalar():
    lats = np.array([55.75, 55.76, 59.93])
    lons = np.array([37.62, 37.63, 30.31])
    got = haversine_m_vec(55.75, 37.62, lats, lons)
    want = [haversine_m(55.75, 37.62, a, b) for a, b in zip(lats, lons)]
    assert got == pytest.approx(want, rel=1e-9)


def test_bearing_north_and_east():
    assert bearing_deg(0, 0, 1, 0) == pytest.approx(0.0, abs=1e-6)
    assert bearing_deg(0, 0, 0, 1) == pytest.approx(90.0, abs=1e-6)


def test_offset_distance_matches():
    lat, lon = offset_m(55.75, 37.62, east_m=100, north_m=200)
    assert haversine_m(55.75, 37.62, lat, lon) == pytest.approx(math.hypot(100, 200), rel=1e-3)


def test_bbox_tiles_cover_area():
    tiles = BBox(0.0, 0.0, 0.01, 0.01).tiles(0.005)
    assert len(tiles) == 4
    assert tiles[-1] == BBox(0.005, 0.005, 0.01, 0.01)


def test_bbox_parse_and_param_roundtrip():
    b = BBox.parse("37.60,55.74,37.62,55.76")
    assert b == BBox(37.60, 55.74, 37.62, 55.76)
    assert b.as_param() == "37.6,55.74,37.62,55.76"


def test_bbox_parse_rejects_inverted():
    with pytest.raises(ValueError):
        BBox.parse("37.62,55.74,37.60,55.76")


def test_interpolate_midpoint_and_out_of_range():
    track = [TrackPoint(0.0, 55.0, 37.0), TrackPoint(10.0, 56.0, 38.0)]
    assert interpolate_track(track, 5.0) == pytest.approx((55.5, 37.5))
    assert interpolate_track(track, 0.0) == pytest.approx((55.0, 37.0))
    assert interpolate_track(track, -1.0) is None
    assert interpolate_track(track, 11.0) is None
    assert interpolate_track([], 0.0) is None
