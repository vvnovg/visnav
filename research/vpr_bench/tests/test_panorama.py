import numpy as np
import pytest

from vpr_bench.panorama import equirect_to_perspective, perspective_views

W, H = 360, 180


def _column_pano():
    # значение пикселя = номер столбца
    return np.tile(np.arange(W, dtype=np.float32), (H, 1))


def _row_pano():
    # значение пикселя = номер строки
    return np.tile(np.arange(H, dtype=np.float32)[:, None], (1, W))


def test_center_of_yaw0_view_samples_pano_center_column():
    view = equirect_to_perspective(_column_pano(), yaw_deg=0, fov_deg=90, out_w=101, out_h=101)
    assert view.shape == (101, 101)
    assert view[50, 50] == pytest.approx(179.5, abs=0.6)


def test_yaw90_view_looks_right_of_center():
    view = equirect_to_perspective(_column_pano(), yaw_deg=90, fov_deg=90, out_w=101, out_h=101)
    assert view[50, 50] == pytest.approx(269.5, abs=0.6)


def test_zero_pitch_center_samples_horizon_row():
    view = equirect_to_perspective(_row_pano(), yaw_deg=0, fov_deg=90, out_w=101, out_h=101)
    assert view[50, 50] == pytest.approx(89.5, abs=0.6)


def test_positive_pitch_looks_up():
    view = equirect_to_perspective(_row_pano(), yaw_deg=0, fov_deg=90, out_w=101, out_h=101, pitch_deg=30)
    assert view[50, 50] < 89.5 - 20


def test_perspective_views_yaws_and_shapes():
    pano = np.zeros((H, W, 3), dtype=np.uint8)
    views = perspective_views(pano, n_views=8, fov_deg=90, out_w=64, out_h=48)
    assert [yaw for yaw, _ in views] == [0, 45, 90, 135, 180, 225, 270, 315]
    assert all(img.shape == (48, 64, 3) for _, img in views)
