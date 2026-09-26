"""Place — геопривязанный снимок (эталон или запрос) и его CSV-представление."""
from __future__ import annotations

import csv
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

FIELDS = ["path", "lat", "lon", "heading"]


@dataclass(frozen=True)
class Place:
    path: str  # относительно каталога CSV-файла
    lat: float
    lon: float
    heading: float  # градусы, 0 = север, по часовой


def write_places(csv_path: Path, places: Iterable[Place]) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(FIELDS)
        for p in places:
            w.writerow([p.path, repr(p.lat), repr(p.lon), repr(p.heading)])


def read_places(csv_path: Path) -> list[Place]:
    with csv_path.open(newline="") as f:
        return [
            Place(row["path"], float(row["lat"]), float(row["lon"]), float(row["heading"]))
            for row in csv.DictReader(f)
        ]
