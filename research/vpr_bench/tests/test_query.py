from datetime import datetime, timezone

import cv2
import numpy as np
import pytest

from vpr_bench.dataset import read_places
from vpr_bench.query import extract_query_frames, parse_gpx, pose_at

GPX = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="55.7510" lon="37.6000"><time>2026-09-20T10:00:10Z</time></trkpt>
<trkpt lat="55.7500" lon="37.6000"><time>2026-09-20T10:00:00Z</time></trkpt>
<trkpt lat="55.7520" lon="37.6000"><time>2026-09-20T10:00:20Z</time></trkpt>
</trkseg></trk></gpx>
"""
T0 = datetime(2026, 9, 20, 10, 0, 0, tzinfo=timezone.utc).timestamp()


@pytest.fixture
def track(tmp_path):
    p = tmp_path / "track.gpx"
    p.write_text(GPX)
    return parse_gpx(p)


def _write_video(path, n_frames, fps):
    w = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), fps, (64, 48))
    for i in range(n_frames):
        w.write(np.full((48, 64, 3), i % 255, np.uint8))
    w.release()


def test_parse_gpx_sorts_by_time(track):
    assert [p.t - T0 for p in track] == [0.0, 10.0, 20.0]
    assert track[0].lat == 55.75


def test_pose_at_heading_north_and_speed(track):
    lat, lon, heading, speed = pose_at(track, T0 + 5)
    assert lat == pytest.approx(55.7505)
    assert heading == pytest.approx(0.0, abs=1e-6)
    assert speed == pytest.approx(11.1, rel=0.01)
    assert pose_at(track, T0) is None  # нет окна t-1


def test_extract_query_frames(tmp_path, track):
    video = tmp_path / "drive.avi"
    _write_video(video, n_frames=200, fps=10)
    places = extract_query_frames(video, track, T0, every_s=2.0, out_dir=tmp_path / "q")
    assert len(places) == 9  # t = 2..18 с; t = 0 отброшен
    assert places[0].lat == pytest.approx(55.7502)
    assert all((tmp_path / "q" / p.path).exists() for p in places)
    assert read_places(tmp_path / "q" / "queries.csv") == places


def test_extract_rejects_missing_video(tmp_path, track):
    with pytest.raises(ValueError):
        extract_query_frames(tmp_path / "nope.avi", track, T0, 1.0, tmp_path / "q")
