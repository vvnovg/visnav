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
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise ValueError(f"cannot open video: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(every_s * fps))
    (out_dir / "images").mkdir(parents=True, exist_ok=True)

    places: list[Place] = []
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % step == 0:
            pose = pose_at(track, video_start_epoch + idx / fps)
            if pose is not None and pose[3] >= min_speed_mps:
                rel = f"images/q_{idx:06d}.jpg"
                cv2.imwrite(str(out_dir / rel), frame)
                places.append(Place(rel, pose[0], pose[1], pose[2]))
        idx += 1
    cap.release()
    write_places(out_dir / "queries.csv", places)
    return places
