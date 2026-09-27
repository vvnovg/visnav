"""Кадры-запросы: видео поездки + GPX-трек → изображения с истинной позицией и курсом."""
from __future__ import annotations

import bisect
import collections
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
        hdop_el = next((c for c in el if c.tag.endswith("hdop")), None)
        hdop: float | None = None
        if hdop_el is not None and hdop_el.text:
            try:
                hdop = float(hdop_el.text.strip())
            except ValueError:
                hdop = None
        points.append(TrackPoint(t, float(el.get("lat")), float(el.get("lon")), hdop))
    points.sort(key=lambda p: p.t)
    return points


def _implied_speed_mps(a: TrackPoint, b: TrackPoint) -> float:
    dt = b.t - a.t
    if dt <= 0:
        return float("inf")
    return haversine_m(a.lat, a.lon, b.lat, b.lon) / dt


def _split_segments(points: list[TrackPoint], max_speed_mps: float) -> list[list[TrackPoint]]:
    if not points:
        return []
    segments: list[list[TrackPoint]] = [[points[0]]]
    for a, b in zip(points, points[1:]):
        if _implied_speed_mps(a, b) > max_speed_mps:
            segments.append([b])
        else:
            segments[-1].append(b)
    return segments


def _find_spoof_bridge(
    segments: list[list[TrackPoint]], max_speed_mps: float
) -> tuple[int, int] | None:
    """Find the smallest-span pair of segments (i, j), j > i+1, whose last/first
    points are mutually plausible, so segments strictly between them can be
    dropped as a spoof run. Smallest span first, so a short, confident fix is
    always preferred over swallowing more segments than necessary."""
    n = len(segments)
    for span in range(2, n):
        for i in range(0, n - span):
            j = i + span
            if _implied_speed_mps(segments[i][-1], segments[j][0]) <= max_speed_mps:
                return i, j
    return None


def clean_track(
    track: list[TrackPoint],
    max_speed_mps: float = 70.0,
    max_hdop: float | None = 5.0,
) -> tuple[list[TrackPoint], dict[str, int]]:
    """Remove untrustworthy GPX points before they reach the benchmark.

    Points with poor HDOP are dropped outright; the remaining points are cut
    into segments wherever the implied speed between consecutive points is
    physically impossible. Any run of one or more interior segments bracketed
    by two mutually plausible segments is merged away as a GPS-spoofing
    excursion (smallest run first, so a short, confident bridge is always
    preferred over a longer, riskier one). If more than one segment survives,
    only the longest-duration one is kept (the rest are "detached" fragments,
    e.g. a spoof block with no real point before it).
    """
    counts = {"hdop": 0, "spoof": 0, "detached": 0}
    if not track:
        return [], counts

    ordered = sorted(track, key=lambda p: p.t)
    if max_hdop is not None:
        kept = [p for p in ordered if not (p.hdop is not None and p.hdop > max_hdop)]
        counts["hdop"] = len(ordered) - len(kept)
    else:
        kept = ordered

    segments = _split_segments(kept, max_speed_mps)

    while len(segments) > 2:
        bridge = _find_spoof_bridge(segments, max_speed_mps)
        if bridge is None:
            break
        i, j = bridge
        for k in range(i + 1, j):
            counts["spoof"] += len(segments[k])
        segments[i : j + 1] = [segments[i] + segments[j]]

    if len(segments) > 1:
        def _duration(seg: list[TrackPoint]) -> float:
            return seg[-1].t - seg[0].t

        best = max(segments, key=lambda seg: (_duration(seg), len(seg)))
        for seg in segments:
            if seg is not best:
                counts["detached"] += len(seg)
        segments = [best]

    return (segments[0] if segments else []), counts


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


def _has_gap(times: list[float], t: float, max_gap_s: float) -> bool:
    """True if the track points bracketing [t-1, t+1] are too sparse to trust
    the straight-line interpolation across them (e.g. a tunnel GPS outage)."""
    lo, hi = t - 1.0, t + 1.0
    i_before = bisect.bisect_right(times, lo) - 1
    i_after = bisect.bisect_left(times, hi)
    if i_before < 0 or i_after >= len(times):
        return True
    for i in range(i_before, i_after):
        if times[i + 1] - times[i] > max_gap_s:
            return True
    return False


def extract_query_frames(
    video: Path,
    track: list[TrackPoint],
    video_start_epoch: float,
    every_s: float,
    out_dir: Path,
    min_speed_mps: float = 2.0,
    max_gap_s: float = 3.0,
    stats: collections.Counter | None = None,
) -> list[Place]:
    if every_s <= 0:
        raise ValueError(f"every_s must be > 0, got {every_s!r}")
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise ValueError(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    (out_dir / "images").mkdir(parents=True, exist_ok=True)
    times = [p.t for p in track]

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
                t = video_start_epoch + frame_t
                pose = pose_at(track, t)
                if pose is None:
                    if stats is not None:
                        stats["no_pose"] += 1
                elif _has_gap(times, t, max_gap_s):
                    if stats is not None:
                        stats["gap"] += 1
                elif pose[3] < min_speed_mps:
                    if stats is not None:
                        stats["stationary"] += 1
                else:
                    if stats is not None:
                        stats["kept"] += 1
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
