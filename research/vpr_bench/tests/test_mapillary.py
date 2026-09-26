from vpr_bench.geo import BBox
from vpr_bench.mapillary import MapillaryClient, RefImage, parse_image


class FakeResponse:
    def __init__(self, json_data=None, content=b""):
        self._json = json_data
        self.content = content

    def raise_for_status(self):
        pass

    def json(self):
        return self._json


class FakeSession:
    def __init__(self, pages):
        self.pages = list(pages)
        self.calls = []

    def get(self, url, params=None, timeout=None):
        self.calls.append((url, params))
        return self.pages.pop(0)


def _item(id_, lat, lon, pano=False):
    return {
        "id": id_,
        "computed_geometry": {"type": "Point", "coordinates": [lon, lat]},
        "computed_compass_angle": 370.0,
        "captured_at": 1_700_000_000_000,
        "is_pano": pano,
        "thumb_2048_url": f"https://img/{id_}.jpg",
    }


def test_parse_image_normalizes_heading_and_swaps_coords():
    img = parse_image(_item("1", 55.75, 37.62, pano=True))
    assert img == RefImage("1", 55.75, 37.62, 10.0, 1_700_000_000_000, True, "https://img/1.jpg")


def test_parse_image_skips_items_without_geometry_or_url():
    assert parse_image({"id": "1", "thumb_2048_url": "u"}) is None
    item = _item("2", 1, 1)
    del item["thumb_2048_url"]
    assert parse_image(item) is None


def test_list_images_tiles_bbox_and_dedupes():
    session = FakeSession([
        FakeResponse({"data": [_item("1", 0.001, 0.001), _item("2", 0.002, 0.002)]}),
        FakeResponse({"data": [_item("2", 0.002, 0.002)]}),
    ])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=0.005)
    images = client.list_images(BBox(0.0, 0.0, 0.01, 0.005))
    assert sorted(i.id for i in images) == ["1", "2"]
    assert len(session.calls) == 2
    first_params = session.calls[0][1]
    assert first_params["bbox"] == "0.0,0.0,0.005,0.005"
    assert first_params["access_token"] == "TOKEN"


def test_download_writes_bytes(tmp_path):
    session = FakeSession([FakeResponse(content=b"JPEGDATA")])
    client = MapillaryClient("TOKEN", session=session)
    img = parse_image(_item("7", 1, 1))
    dest = client.download(img, tmp_path / "raw" / "7.jpg")
    assert dest.read_bytes() == b"JPEGDATA"
    assert session.calls[0][0] == "https://img/7.jpg"
