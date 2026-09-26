import requests

from vpr_bench.geo import BBox
from vpr_bench.mapillary import MIN_TILE_DEG, PAGE_LIMIT, MapillaryClient, RefImage, parse_image


class FakeResponse:
    def __init__(self, json_data=None, content=b"", status_code=200, headers=None):
        self._json = json_data
        self.content = content
        self.status_code = status_code
        self.headers = headers or {}

    def raise_for_status(self):
        if self.status_code >= 400:
            err = requests.HTTPError(f"{self.status_code} error")
            err.response = self
            raise err

    def json(self):
        return self._json


class FakeSession:
    def __init__(self, pages):
        self.pages = list(pages)
        self.calls = []

    def get(self, url, params=None, timeout=None, headers=None):
        self.calls.append((url, params, headers))
        return self.pages.pop(0)


class SleepRecorder:
    def __init__(self):
        self.calls = []

    def __call__(self, seconds):
        self.calls.append(seconds)


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


def test_parse_image_skips_items_missing_id_or_with_malformed_coords():
    item = _item("3", 1, 1)
    del item["id"]
    assert parse_image(item) is None

    item2 = _item("4", 1, 1)
    item2["computed_geometry"] = {"coordinates": [1.0]}
    assert parse_image(item2) is None


def test_list_images_tiles_bbox_and_dedupes():
    session = FakeSession([
        FakeResponse({"data": [_item("1", 0.001, 0.001), _item("2", 0.002, 0.002)]}),
        FakeResponse({"data": [_item("2", 0.002, 0.002)]}),
    ])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=0.005)
    images = client.list_images(BBox(0.0, 0.0, 0.01, 0.005))
    assert sorted(i.id for i in images) == ["1", "2"]
    assert len(session.calls) == 2
    first_url, first_params, first_headers = session.calls[0]
    assert first_params["bbox"] == "0.0,0.0,0.005,0.005"
    assert "access_token" not in first_params
    assert first_headers == {"Authorization": "OAuth TOKEN"}
    assert "TOKEN" not in first_url


def test_list_images_subdivides_dense_tile():
    parent_items = [_item(str(i), 0.0001 * i, 0.0001 * i) for i in range(PAGE_LIMIT)]
    child_pages = [
        FakeResponse({"data": [_item(f"{q}{i}", 0.0, 0.0) for i in range(3)]})
        for q in "abcd"
    ]
    session = FakeSession([FakeResponse({"data": parent_items}), *child_pages])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=0.01)
    images = client.list_images(BBox(0.0, 0.0, 0.01, 0.01))
    assert len(session.calls) == 5  # 1 parent + 4 quadrants
    assert len(images) == 12


def test_list_images_warns_when_min_size_tile_still_truncated():
    items = [_item(str(i), 0.0, 0.0) for i in range(PAGE_LIMIT)]
    session = FakeSession([FakeResponse({"data": items})])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=MIN_TILE_DEG)
    import pytest

    with pytest.warns(UserWarning, match="truncated"):
        images = client.list_images(BBox(0.0, 0.0, MIN_TILE_DEG, MIN_TILE_DEG))
    assert len(images) == PAGE_LIMIT


def test_list_images_respects_tile_filter():
    session = FakeSession([FakeResponse({"data": [_item("1", 0.001, 0.001)]})])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=0.005)
    images = client.list_images(
        BBox(0.0, 0.0, 0.01, 0.005), tile_filter=lambda t: t.min_lon < 0.005
    )
    assert [i.id for i in images] == ["1"]
    assert len(session.calls) == 1


def test_list_images_skips_malformed_items_with_one_warning():
    data = [
        {"id": "1"},
        {"computed_geometry": {"coordinates": [1, 2]}, "thumb_2048_url": "u"},
        {"id": "3", "computed_geometry": {"coordinates": [1]}, "thumb_2048_url": "u"},
        _item("4", 1, 1),
    ]
    session = FakeSession([FakeResponse({"data": data})])
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=1.0)
    import pytest

    with pytest.warns(UserWarning, match=r"skipped 3 Mapillary items"):
        images = client.list_images(BBox(0.0, 0.0, 0.5, 0.5))
    assert [i.id for i in images] == ["4"]


def test_list_images_retries_on_429_then_succeeds():
    session = FakeSession([
        FakeResponse(status_code=429, headers={"Retry-After": "2"}),
        FakeResponse({"data": [_item("1", 0.001, 0.001)]}),
    ])
    sleeper = SleepRecorder()
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=1.0, sleep=sleeper)
    images = client.list_images(BBox(0.0, 0.0, 0.5, 0.5))
    assert [i.id for i in images] == ["1"]
    assert sleeper.calls == [2.0]


def test_list_images_raises_after_exhausting_retries_on_503():
    import pytest

    session = FakeSession([FakeResponse(status_code=503) for _ in range(6)])
    sleeper = SleepRecorder()
    client = MapillaryClient(
        "TOKEN", session=session, tile_step_deg=1.0, max_retries=5, backoff_s=0.1, sleep=sleeper
    )
    with pytest.raises(requests.HTTPError) as exc_info:
        client.list_images(BBox(0.0, 0.0, 0.5, 0.5))
    assert len(sleeper.calls) == 5
    assert "TOKEN" not in str(exc_info.value)


def test_list_images_no_retry_on_404():
    import pytest

    session = FakeSession([FakeResponse(status_code=404)])
    sleeper = SleepRecorder()
    client = MapillaryClient("TOKEN", session=session, tile_step_deg=1.0, sleep=sleeper)
    with pytest.raises(requests.HTTPError):
        client.list_images(BBox(0.0, 0.0, 0.5, 0.5))
    assert sleeper.calls == []
    assert len(session.calls) == 1


def test_download_writes_bytes(tmp_path):
    session = FakeSession([FakeResponse(content=b"JPEGDATA")])
    client = MapillaryClient("TOKEN", session=session)
    img = parse_image(_item("7", 1, 1))
    dest = client.download(img, tmp_path / "raw" / "7.jpg")
    assert dest.read_bytes() == b"JPEGDATA"
    url, params, headers = session.calls[0]
    assert url == "https://img/7.jpg"
    assert not headers
    assert "TOKEN" not in url
    assert not params or "TOKEN" not in str(params)


def test_download_is_atomic(tmp_path):
    session = FakeSession([FakeResponse(content=b"JPEGDATA")])
    client = MapillaryClient("TOKEN", session=session)
    img = parse_image(_item("7", 1, 1))
    dest = tmp_path / "raw" / "7.jpg"
    result = client.download(img, dest)
    assert result.read_bytes() == b"JPEGDATA"
    assert not dest.with_name("7.jpg.part").exists()


def test_download_retries_on_connection_error(tmp_path):
    class FlakySession:
        def __init__(self):
            self.attempts = 0

        def get(self, url, timeout=None):
            self.attempts += 1
            if self.attempts == 1:
                raise requests.ConnectionError("boom")
            return FakeResponse(content=b"OK")

    session = FlakySession()
    sleeper = SleepRecorder()
    client = MapillaryClient("TOKEN", session=session, sleep=sleeper)
    img = parse_image(_item("7", 1, 1))
    dest = client.download(img, tmp_path / "7.jpg")
    assert dest.read_bytes() == b"OK"
    assert sleeper.calls == [1.0]
