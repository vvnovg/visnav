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


class FakeCapture:
    """Fake cv2.VideoCapture whose POS_MSEC timestamps are irregular (VFR-like)."""

    def __init__(self, times_ms, fps=10.0, frame_shape=(48, 64, 3)):
        self.times_ms = times_ms
        self.fps = fps
        self.frame_shape = frame_shape
        self.i = -1
        self._opened = True

    def isOpened(self):
        return self._opened

    def get(self, prop):
        if prop == cv2.CAP_PROP_FPS:
            return self.fps
        if prop == cv2.CAP_PROP_POS_MSEC:
            return self.times_ms[self.i]
        return 0.0

    def read(self):
        self.i += 1
        if self.i >= len(self.times_ms):
            return False, None
        return True, np.full(self.frame_shape, self.i % 255, np.uint8)

    def release(self):
        self._opened = False


WIDE_GPX = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="55.7490" lon="37.6000"><time>2026-09-20T09:59:50Z</time></trkpt>
<trkpt lat="55.7530" lon="37.6000"><time>2026-09-20T10:00:30Z</time></trkpt>
</trkseg></trk></gpx>
"""


@pytest.fixture
def wide_track(tmp_path):
    p = tmp_path / "wide_track.gpx"
    p.write_text(WIDE_GPX)
    return parse_gpx(p)


def test_extract_samples_by_container_time_not_index(monkeypatch, tmp_path, wide_track):
    # Irregular (VFR-like) timestamps, ms: 900, 1050, 1300, 1650, 1800, 2100, 2400.
    times_ms = [900, 1050, 1300, 1650, 1800, 2100, 2400]
    fake = FakeCapture(times_ms)
    monkeypatch.setattr("vpr_bench.query.cv2.VideoCapture", lambda path: fake)

    places = extract_query_frames(
        tmp_path / "irregular.avi", wide_track, T0, every_s=0.5, out_dir=tmp_path / "q"
    )

    # next_sample_t starts at 0.0 and advances by every_s each time a frame is
    # sampled: 0.9->keep(next=0.5), 1.05->keep(next=1.0), 1.3->keep(next=1.5),
    # 1.65->keep(next=2.0), 1.8->skip, 2.1->keep(next=2.5), 2.4->skip.
    expected_t = [0.9, 1.05, 1.3, 1.65, 2.1]
    assert len(places) == len(expected_t)
    for place, t in zip(places, expected_t):
        assert place.lat == pytest.approx(55.7500 + t * 0.0001)


def test_extract_falls_back_to_index_over_fps_when_pos_msec_invalid(monkeypatch, tmp_path, wide_track):
    # Second timestamp goes backwards (900 -> 800): that frame must fall back
    # to idx/fps = 1/10 = 0.1s instead of the bogus container timestamp.
    times_ms = [900, 800, 2000]
    fake = FakeCapture(times_ms, fps=10.0)
    monkeypatch.setattr("vpr_bench.query.cv2.VideoCapture", lambda path: fake)

    places = extract_query_frames(
        tmp_path / "backwards.avi", wide_track, T0, every_s=0.5, out_dir=tmp_path / "q"
    )

    # idx=0 t=0.9 -> keep (next=0.5); idx=1 raw=0.8s is backwards -> fallback
    # to idx/fps=0.1s, which is < next_sample_t(0.5) -> skip;
    # idx=2 t=2.0 -> keep.
    assert [p.lat for p in places] == [
        pytest.approx(55.7500 + 0.9 * 0.0001),
        pytest.approx(55.7500 + 2.0 * 0.0001),
    ]


def test_extract_drops_stationary_frames(tmp_path):
    # GPX with stationary object (same location at t=0 and t=20)
    stationary_gpx = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="55.7500" lon="37.6000"><time>2026-09-20T10:00:00Z</time></trkpt>
<trkpt lat="55.7500" lon="37.6000"><time>2026-09-20T10:00:20Z</time></trkpt>
</trkseg></trk></gpx>
"""
    gpx_path = tmp_path / "stationary.gpx"
    gpx_path.write_text(stationary_gpx)
    track = parse_gpx(gpx_path)

    video = tmp_path / "stationary.avi"
    _write_video(video, n_frames=200, fps=10)
    places = extract_query_frames(video, track, T0, every_s=2.0, out_dir=tmp_path / "q")

    assert len(places) == 0  # All frames filtered due to zero speed
    assert read_places(tmp_path / "q" / "queries.csv") == []
