import cv2
import numpy as np
import pytest

from vpr_bench.dataset import read_places
from vpr_bench.db_builder import ViewConfig, build_reference_db
from vpr_bench.geo import BBox
from vpr_bench.mapillary import RefImage


class FakeSource:
    def __init__(self, images):
        self.images = images
        self.downloads = 0

    def list_images(self, bbox):
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

    def list_images(self, bbox):
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
