from vpr_bench.dataset import Place, read_places, write_places


def test_roundtrip(tmp_path):
    places = [
        Place("images/a.jpg", 55.75, 37.62, 10.0),
        Place("images/b.jpg", 55.76, 37.63, 359.5),
    ]
    csv_path = tmp_path / "sub" / "refs.csv"
    write_places(csv_path, places)
    assert read_places(csv_path) == places


def test_empty_file_roundtrip(tmp_path):
    csv_path = tmp_path / "empty.csv"
    write_places(csv_path, [])
    assert read_places(csv_path) == []
