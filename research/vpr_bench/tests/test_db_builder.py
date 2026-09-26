import json

import cv2
import numpy as np
import pytest

from vpr_bench.dataset import read_places
from vpr_bench.db_builder import Corridor, ViewConfig, build_reference_db
from vpr_bench.geo import BBox, TrackPoint, offset_m
from vpr_bench.mapillary import RefImage


class FakeSource:
    def __init__(self, images):
        self.images = images
        self.downloads = 0
        self.list_calls = []

    def list_images(self, bbox, tile_filter=None):
        self.list_calls.append((bbox, tile_filter))
        if tile_filter is not None:
            return [img for img in self.images if tile_filter(BBox(img.lon, img.lat, img.lon + 1e-6, img.lat + 1e-6))]
        return self.images

    def download(self, img, dest):
        self.downloads += 1
        shape = (100, 200, 3) if img.is_pano else (48, 64, 3)
        dest.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(dest), np.full(shape, 128, np.uint8))
        return dest


def test_builds_views_for_pano_and_copies_perspective(tmp_path):
    source = FakeSource([
        RefImage("p1", 55.75, 37.62, 10.0, 0, True, "u1"),
        RefImage("f1", 55.76, 37.63, 200.0, 0, False, "u2"),
    ])
    views = ViewConfig(n_views=8, fov_deg=90.0, width=64, height=48)
    places = build_reference_db(source, BBox(37.6, 55.7, 37.7, 55.8), tmp_path, views)

    assert len(places) == 9
    pano_places = [p for p in places if "p1_" in p.path]
    assert [p.heading for p in pano_places] == [10, 55, 100, 145, 190, 235, 280, 325]
    assert all((tmp_path / p.path).exists() for p in places)
    assert read_places(tmp_path / "refs.csv") == places


def test_skips_download_when_raw_exists(tmp_path):
    source = FakeSource([RefImage("f1", 55.76, 37.63, 0.0, 0, False, "u")])
    build_reference_db(source, BBox(0, 0, 1, 1), tmp_path, ViewConfig(width=64, height=48))
    build_reference_db(source, BBox(0, 0, 1, 1), tmp_path, ViewConfig(width=64, height=48))
    assert source.downloads == 1


class UndecodableSource:
    def __init__(self, images):
        self.images = images

    def list_images(self, bbox, tile_filter=None):
        return self.images

    def download(self, img, dest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(b"not a jpeg")
        return dest


def test_warns_on_undecodable_panorama(tmp_path):
    source = UndecodableSource([RefImage("p1", 55.75, 37.62, 10.0, 0, True, "u1")])
    views = ViewConfig(n_views=8, fov_deg=90.0, width=64, height=48)
    with pytest.warns(UserWarning, match="undecodable"):
        places = build_reference_db(source, BBox(37.6, 55.7, 37.7, 55.8), tmp_path, views)
    assert len(places) == 0
    assert (tmp_path / "refs.csv").exists()


def test_skips_rerender_when_all_views_already_exist(tmp_path, monkeypatch):
    source = FakeSource([RefImage("p1", 55.75, 37.62, 10.0, 0, True, "u1")])
    views = ViewConfig(n_views=4, fov_deg=90.0, width=64, height=48)
    first = build_reference_db(source, BBox(37.6, 55.7, 37.7, 55.8), tmp_path, views)
    assert len(first) == 4

    reads = []
    real_imread = cv2.imread

    def counting_imread(path, *a, **kw):
        reads.append(path)
        return real_imread(path, *a, **kw)

    monkeypatch.setattr(cv2, "imread", counting_imread)
    second = build_reference_db(source, BBox(37.6, 55.7, 37.7, 55.8), tmp_path, views)
    assert reads == []
    assert second == first


def test_parallel_download_exception_is_reraised_after_others_finish(tmp_path):
    class FlakySource:
        def __init__(self, images):
            self.images = images
            self.done = []

        def list_images(self, bbox, tile_filter=None):
            return self.images

        def download(self, img, dest):
            if img.id == "bad":
                raise RuntimeError("boom")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(b"x")
            self.done.append(img.id)
            return dest

    source = FlakySource([
        RefImage("ok1", 1, 1, 0.0, 0, False, "u1"),
        RefImage("bad", 1, 1, 0.0, 0, False, "u2"),
        RefImage("ok2", 1, 1, 0.0, 0, False, "u3"),
    ])
    with pytest.raises(RuntimeError, match="boom"):
        build_reference_db(source, BBox(0, 0, 1, 1), tmp_path, ViewConfig(width=64, height=48))
    assert sorted(source.done) == ["ok1", "ok2"]


def test_writes_meta_json(tmp_path):
    source = FakeSource([
        RefImage("p1", 55.75, 37.62, 10.0, 1_700_000_000_000, True, "u1"),
        RefImage("f1", 55.76, 37.63, 200.0, 1_700_100_000_000, False, "u2"),
    ])
    views = ViewConfig(n_views=4, fov_deg=90.0, width=64, height=48)
    bbox = BBox(37.6, 55.7, 37.7, 55.8)
    places = build_reference_db(source, bbox, tmp_path, views)

    meta = json.loads((tmp_path / "meta.json").read_text())
    assert meta["n_images"] == 2
    assert meta["n_panos"] == 1
    assert meta["n_places"] == len(places)
    assert meta["captured_at_min"] == "2023-11-14T22:13:20Z"
    assert meta["captured_at_max"] == "2023-11-16T02:00:00Z"
    assert meta["bbox"] == bbox.as_param()
    assert meta["corridor_buffer_m"] is None
    assert meta["views"] == {"n_views": 4, "fov_deg": 90.0, "width": 64, "height": 48}
    assert "created_at" in meta


# --- Corridor -----------------------------------------------------------

def _straight_track(n=11, step_m=100.0):
    """~1 km straight track heading north from (55.75, 37.62)."""
    lat, lon = 55.75, 37.62
    pts = []
    for i in range(n):
        lat_i, lon_i = offset_m(lat, lon, 0.0, i * step_m)
        pts.append(TrackPoint(float(i), lat_i, lon_i))
    return pts


def test_corridor_contains_and_bbox_and_intersects():
    track = _straight_track()
    corridor = Corridor([track], buffer_m=50.0)

    # a point right on the track is contained
    assert corridor.contains(track[5].lat, track[5].lon)
    # a point 2 km away from the whole track is not
    far_lat, far_lon = offset_m(track[5].lat, track[5].lon, 2000.0, 0.0)
    assert not corridor.contains(far_lat, far_lon)

    bbox = corridor.bbox()
    assert bbox.min_lat < track[0].lat
    assert bbox.max_lat > track[-1].lat

    near_tile = BBox(track[5].lon - 0.0001, track[5].lat - 0.0001, track[5].lon + 0.0001, track[5].lat + 0.0001)
    assert corridor.intersects(near_tile)

    far_tile_lat, far_tile_lon = offset_m(track[5].lat, track[5].lon, 5000.0, 0.0)
    far_tile = BBox(far_tile_lon - 0.0001, far_tile_lat - 0.0001, far_tile_lon + 0.0001, far_tile_lat + 0.0001)
    assert not corridor.intersects(far_tile)


def test_build_reference_db_drops_image_outside_corridor_and_skips_far_tiles(tmp_path):
    track = _straight_track()
    corridor = Corridor([track], buffer_m=50.0)

    near_lat, near_lon = track[5].lat, track[5].lon
    far_lat, far_lon = offset_m(track[5].lat, track[5].lon, 2000.0, 0.0)

    class RecordingSource(FakeSource):
        def list_images(self, bbox, tile_filter=None):
            self.list_calls.append((bbox, tile_filter))
            result = []
            for img in self.images:
                tile = BBox(img.lon - 1e-6, img.lat - 1e-6, img.lon + 1e-6, img.lat + 1e-6)
                if tile_filter is None or tile_filter(tile):
                    result.append(img)
            return result

    source = RecordingSource([
        RefImage("near", near_lat, near_lon, 0.0, 0, False, "u1"),
        RefImage("far", far_lat, far_lon, 0.0, 0, False, "u2"),
    ])

    places = build_reference_db(source, corridor.bbox(), tmp_path, corridor=corridor)

    assert [p.path for p in places] == ["images/near.jpg"]
    assert source.downloads == 1
    # the far tile was excluded before it ever reached list_images's filter check
    bbox_arg, tile_filter_arg = source.list_calls[0]
    assert tile_filter_arg is not None
    far_tile = BBox(far_lon - 1e-6, far_lat - 1e-6, far_lon + 1e-6, far_lat + 1e-6)
    assert not tile_filter_arg(far_tile)
