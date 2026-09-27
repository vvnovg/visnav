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


@pytest.mark.parametrize("every_s", [0.0, -1.0])
def test_extract_rejects_non_positive_every_s(monkeypatch, tmp_path, track, every_s):
    # every_s <= 0 would make `while next_sample_t <= frame_t: next_sample_t
    # += every_s` loop forever (or never advance), so it must be rejected up
    # front, before even opening the video — proven here by making
    # cv2.VideoCapture blow up if it's ever reached (using a missing video
    # path would raise ValueError anyway and mask whether this guard fired).
    def _boom(_path):
        raise AssertionError("cv2.VideoCapture must not be called when every_s <= 0")

    monkeypatch.setattr("vpr_bench.query.cv2.VideoCapture", _boom)
    with pytest.raises(ValueError, match="every_s"):
        extract_query_frames(tmp_path / "drive.avi", track, T0, every_s, tmp_path / "q")


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


WIDE_LAT0 = 55.7500
WIDE_DEG_PER_S = 0.0001


def _recovered_t(lat, lat0=WIDE_LAT0, deg_per_s=WIDE_DEG_PER_S):
    """Invert Place.lat back to the wide_track time it was interpolated from,
    so timing assertions compare seconds with a tight absolute tolerance
    instead of comparing latitudes with pytest.approx's default *relative*
    tolerance — which, at lat≈55.75, is loose enough (≈±0.06 s worth of
    latitude) to let a wrong expected time pass by accident."""
    return (lat - lat0) / deg_per_s


def test_extract_samples_by_container_time_not_index(monkeypatch, tmp_path, wide_track):
    # Irregular (VFR-like) timestamps, ms: 900, 1050, 1300, 1650, 1800, 2100, 2400.
    times_ms = [900, 1050, 1300, 1650, 1800, 2100, 2400]
    fake = FakeCapture(times_ms)
    monkeypatch.setattr("vpr_bench.query.cv2.VideoCapture", lambda path: fake)

    places = extract_query_frames(
        tmp_path / "irregular.avi", wide_track, T0, every_s=0.5, out_dir=tmp_path / "q"
    )

    # next_sample_t starts at 0.0 and, once a frame is sampled, catches up past
    # that frame's own time (a while-loop, not a single += every_s) so a gap
    # can't leave next_sample_t behind and cause a later burst of kept frames:
    # 0.9->keep(catch-up next=1.0), 1.05->keep(next=1.5), 1.3->skip(<1.5),
    # 1.65->keep(next=2.0), 1.8->skip(<2.0), 2.1->keep(next=2.5), 2.4->skip.
    expected_t = [0.9, 1.05, 1.65, 2.1]
    assert len(places) == len(expected_t)
    for place, t in zip(places, expected_t):
        assert _recovered_t(place.lat) == pytest.approx(t, abs=1e-6)


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
    expected_t = [0.9, 2.0]
    assert len(places) == len(expected_t)
    for place, t in zip(places, expected_t):
        assert _recovered_t(place.lat) == pytest.approx(t, abs=1e-6)


def test_frame_clock_anchors_backward_check_to_last_good_raw_not_prev():
    from vpr_bench.query import _FrameClock

    # POS_MSEC (ms): 2000, 800, 1900. The 800 is behind the first good raw
    # (2000) and correctly falls back to idx/fps. The 1900 is *also* behind
    # that same good raw (2000) — it must not be accepted just because it is
    # ahead of the previous frame's fallback value (0.1s); anchoring to the
    # last *good raw* timestamp (2000ms=2.0s) catches it too.
    clock = _FrameClock(fps=10.0)
    assert clock.next(0, 2000) == pytest.approx(2.0)
    assert clock.next(1, 800) == pytest.approx(0.1)  # idx/fps = 1/10
    assert clock.next(2, 1900) == pytest.approx(0.2)  # idx/fps = 2/10, not 1.9


def test_extract_catches_up_after_timestamp_gap_without_bursting(monkeypatch, tmp_path, wide_track):
    # A ~4s gap between frame 1 (t=1.0s) and frame 2 (t=5.0s), then frames
    # every 0.1s up to t=5.8s.
    times_ms = [0, 1000, 5000, 5100, 5200, 5300, 5400, 5500, 5600, 5700, 5800]
    fake = FakeCapture(times_ms)
    monkeypatch.setattr("vpr_bench.query.cv2.VideoCapture", lambda path: fake)

    places = extract_query_frames(
        tmp_path / "gap.avi", wide_track, T0, every_s=0.5, out_dir=tmp_path / "q"
    )

    # Without catch-up, next_sample_t would sit at 1.5 after the t=1.0 frame,
    # and every one of the seven frames from 5.1s to 5.7s (all >= 1.5) would
    # be kept in a burst. With catch-up, next_sample_t jumps to 5.5 as soon as
    # the t=5.0 frame is sampled (while next_sample_t<=5.0: += 0.5 lands it at
    # 5.5), so the very next frame at t=5.5 is the one kept (5.5 >= 5.5), and
    # next_sample_t then jumps to 6.0 — past every remaining frame up to 5.8.
    # One frame per every_s interval survives: t = 0.0, 1.0, 5.0, 5.5.
    expected_t = [0.0, 1.0, 5.0, 5.5]
    assert len(places) == len(expected_t)
    for place, t in zip(places, expected_t):
        assert _recovered_t(place.lat) == pytest.approx(t, abs=1e-6)


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


def test_parse_gpx_reads_hdop(tmp_path):
    gpx = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="55.7500" lon="37.6000"><time>2026-09-20T10:00:00Z</time><hdop>1.5</hdop></trkpt>
<trkpt lat="55.7510" lon="37.6000"><time>2026-09-20T10:00:10Z</time></trkpt>
</trkseg></trk></gpx>
"""
    p = tmp_path / "hdop.gpx"
    p.write_text(gpx)
    track = parse_gpx(p)
    assert track[0].hdop == pytest.approx(1.5)
    assert track[1].hdop is None


def test_parse_gpx_unparsable_hdop_is_none(tmp_path):
    gpx = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="55.7500" lon="37.6000"><time>2026-09-20T10:00:00Z</time><hdop>bad</hdop></trkpt>
</trkseg></trk></gpx>
"""
    p = tmp_path / "hdop_bad.gpx"
    p.write_text(gpx)
    track = parse_gpx(p)
    assert track[0].hdop is None
