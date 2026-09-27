import json
import struct

import cv2
import numpy as np
import pytest
import torch

from vpr_bench.dataset import Place, write_places
from vpr_bench.m1cli import main
from vpr_bench.onnx_export import export_onnx
from vpr_bench.refpack import read_refpack, write_refpack

LATS = [55.7500, 55.7509, 55.7518]
LONS = [37.6, 37.6, 37.6]
HEAD = [0.0, 90.0, 180.0]
DESC = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0.6, 0.8, 0]], dtype=np.float32)


def test_binary_layout(tmp_path):
    write_refpack(tmp_path, LATS, LONS, HEAD, DESC, {"model": "m"})
    raw = (tmp_path / "refpack.bin").read_bytes()
    assert raw[:4] == b"VNRP"
    version, dtype, count, dim = struct.unpack("<HHII", raw[4:16])
    assert (version, dtype, count, dim) == (1, 1, 3, 4)
    assert len(raw) == 16 + 3 * 8 * 2 + 3 * 4 + 3 * 4 * 2
    assert struct.unpack("<d", raw[16:24])[0] == 55.75
    first_desc_off = 16 + 3 * 20
    assert raw[first_desc_off:first_desc_off + 2] == bytes.fromhex("003c")  # float16 1.0, little-endian


def test_roundtrip_and_meta(tmp_path):
    write_refpack(tmp_path, LATS, LONS, HEAD, DESC, {"model": "m", "created_at": "2026-09-27T00:00:00Z"})
    rp = read_refpack(tmp_path)
    assert rp.lats.tolist() == LATS and rp.lons.tolist() == LONS
    assert rp.headings.tolist() == HEAD
    assert rp.descriptors.dtype == np.float16
    assert rp.descriptors.astype(np.float32) == pytest.approx(DESC, abs=1e-3)
    meta = json.loads((tmp_path / "refpack.json").read_text())
    assert meta["format"] == "VNRP/1" and meta["count"] == 3 and meta["dim"] == 4 and meta["model"] == "m"


def test_length_mismatch_raises(tmp_path):
    with pytest.raises(ValueError):
        write_refpack(tmp_path, LATS[:2], LONS, HEAD, DESC, {})


def _refs(root):
    places = []
    colors = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
    (root / "images").mkdir(parents=True)
    for i, c in enumerate(colors):
        cv2.imwrite(str(root / f"images/{i}.jpg"), np.full((48, 64, 3), c, np.uint8))
        places.append(Place(f"images/{i}.jpg", LATS[i], LONS[i], HEAD[i]))
    write_places(root / "refs.csv", places)
    return root / "refs.csv"


def test_pack_refs_cli(tmp_path):
    torch.manual_seed(0)
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    onnx_path = export_onnx(net, (32, 32), tmp_path / "m.onnx")
    refs = _refs(tmp_path / "refs")
    out = tmp_path / "bundle"
    assert main(["pack-refs", "--refs", str(refs), "--onnx", str(onnx_path), "--out", str(out)]) == 0
    rp = read_refpack(out)
    assert rp.descriptors.shape == (3, 3)
    assert (out / "model.onnx").read_bytes() == onnx_path.read_bytes()
    assert rp.meta["input_h"] == 32 and rp.meta["input_w"] == 32
    assert rp.meta["source"].startswith("Mapillary")
    assert len(rp.meta["onnx_sha256"]) == 64


def test_pack_refs_cli_with_gpx_corridor_filters_far_ref(tmp_path):
    # Two refs near the GPX track (the usual 3-point cluster spans ~200 m) and
    # one ref ~5 km away; only the near ones should survive the corridor filter.
    torch.manual_seed(0)
    net = torch.nn.Sequential(torch.nn.AdaptiveAvgPool2d(1), torch.nn.Flatten())
    onnx_path = export_onnx(net, (32, 32), tmp_path / "m.onnx")

    root = tmp_path / "refs"
    (root / "images").mkdir(parents=True)
    colors = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
    far_lat = LATS[0] + 0.045  # ~5 km north
    lats = LATS + [far_lat]
    lons = LONS + [LONS[0]]
    places = []
    for i, (lat, lon) in enumerate(zip(lats, lons)):
        cv2.imwrite(str(root / f"images/{i}.jpg"), np.full((48, 64, 3), colors[i % len(colors)], np.uint8))
        places.append(Place(f"images/{i}.jpg", lat, lon, 0.0))
    write_places(root / "refs.csv", places)

    gpx_path = tmp_path / "track.gpx"
    gpx_path.write_text(f"""<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<trk><trkseg>
<trkpt lat="{LATS[0]}" lon="{LONS[0]}"><time>2026-09-20T10:00:00Z</time></trkpt>
<trkpt lat="{LATS[-1]}" lon="{LONS[-1]}"><time>2026-09-20T10:00:10Z</time></trkpt>
</trkseg></trk></gpx>
""")

    out = tmp_path / "bundle"
    result = main([
        "pack-refs", "--refs", str(root / "refs.csv"), "--onnx", str(onnx_path),
        "--gpx", str(gpx_path), "--out", str(out),
    ])
    assert result == 0
    rp = read_refpack(out)
    assert rp.descriptors.shape == (3, 3)  # far ref (index 3) excluded, only the 3-point cluster packed
    assert far_lat not in rp.lats.tolist()
