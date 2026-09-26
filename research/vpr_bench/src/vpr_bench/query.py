"""Кадры-запросы: видео поездки + GPX-трек → изображения с истинной позицией и курсом."""
from __future__ import annotations

import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

import cv2

from vpr_bench.dataset import Place, write_places
from vpr_bench.geo import TrackPoint, bearing_deg, haversine_m, interpolate_track


def parse_gpx(path: Path) -> list[TrackPoint]:
    root = ET.parse(path).getroot()
    points: list[TrackPoint] = []
    for el in root.iter():
        if not el.tag.endswith("trkpt"):
            continue
        time_el = next((c for c in el if c.tag.endswith("time")), None)
        if time_el is None or not time_el.text:
            continue
        t = datetime.fromisoformat(time_el.text.strip().replace("Z", "+00:00")).timestamp()
        points.append(TrackPoint(t, float(el.get("lat")), float(el.get("lon"))))
    points.sort(key=lambda p: p.t)
    return points


class _FrameClock:
    """Turns a video container's (possibly unreliable) per-frame timestamp into
    a monotonic frame time, falling back to idx / fps when the container's
    value is negative or behind the latest raw timestamp actually accepted so
    far (not just the previous frame's — a fallback frame must not reset the
    anchor, or a later raw timestamp that is still behind an already-emitted
    frame would slip through as if it were new)."""

    def __init__(self, fps: float) -> None:
        self._fps = fps
        self._last_good_raw_t: float | None = None

    def next(self, idx: int, raw_msec: float) -> float:
        raw_t = raw_msec / 1000.0
        if raw_t < 0 or (self._last_good_raw_t is not None and raw_t < self._last_good_raw_t):
            return idx / self._fps
        self._last_good_raw_t = raw_t
        return raw_t


def pose_at(track: list[TrackPoint], t: float) -> tuple[float, float, float, float] | None:
    here = interpolate_track(track, t)
    before = interpolate_track(track, t - 1.0)
    after = interpolate_track(track, t + 1.0)
    if here is None or before is None or after is None:
        return None
    speed = haversine_m(*before, *after) / 2.0
    heading = bearing_deg(*before, *after)
    return here[0], here[1], heading, speed


def extract_query_frames(
    video: Path,
    track: list[TrackPoint],
    video_start_epoch: float,
    every_s: float,
    out_dir: Path,
    min_speed_mps: float = 2.0,
) -> list[Place]:
    if every_s <= 0:
        raise ValueError(f"every_s must be > 0, got {every_s!r}")
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise ValueError(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    (out_dir / "images").mkdir(parents=True, exist_ok=True)

    places: list[Place] = []
    try:
        idx = 0
        next_sample_t = 0.0
        clock = _FrameClock(fps)
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            # Variable-frame-rate video (phone/dashcam) drifts against idx / fps,
            # so take each frame's time from the container and sample by time.
            frame_t = clock.next(idx, cap.get(cv2.CAP_PROP_POS_MSEC))
            if frame_t >= next_sample_t:
                pose = pose_at(track, video_start_epoch + frame_t)
                if pose is not None and pose[3] >= min_speed_mps:
                    rel = f"images/q_{idx:06d}.jpg"
                    cv2.imwrite(str(out_dir / rel), frame)
                    places.append(Place(rel, pose[0], pose[1], pose[2]))
                # Catch up rather than advance by a single step: a gap in
                # timestamps (e.g. dropped frames) must not leave next_sample_t
                # far behind, which would otherwise let a burst of frames right
                # after the gap all satisfy frame_t >= next_sample_t.
                while next_sample_t <= frame_t:
                    next_sample_t += every_s
            idx += 1
    finally:
        cap.release()
    write_places(out_dir / "queries.csv", places)
    return places
