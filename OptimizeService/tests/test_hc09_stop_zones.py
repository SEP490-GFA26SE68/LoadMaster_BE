
import pytest

from app.dto.optimization.engine.problem_request import VehicleData
from app.service.optimize.stop_zone_calculator import StopZoneCalculator


def make_vehicle(length=450):
    return VehicleData(
        inner_l=length,
        inner_w=200,
        inner_h=200,
        max_payload_kg=3500,
        door_w=185,
        door_h=190,
    )


def make_stop(stop_id, sequence, volume=10000):
    return {
        "stop_id": stop_id,
        "sequence": sequence,
        "packages": [
            {
                "volume": volume,
                "weight": 20,
            }
        ],
    }


def zones_by_sequence(result):
    return {
        zone.sequence: zone
        for zone in result.zones
    }


def test_hc09_first_stop_nearest_door():
    """Stop giao đầu tiên phải nằm gần cửa sau nhất."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [
            make_stop("S1", 1),
            make_stop("S2", 2),
            make_stop("S3", 3),
        ],
    )

    zones = zones_by_sequence(result)

    assert (
            zones[3].zone_start_x
            < zones[2].zone_start_x
            < zones[1].zone_start_x
    )

    assert zones[1].zone_end_x == pytest.approx(450)


def test_hc09_unsorted_input():
    """Input đảo thứ tự không được làm sai thứ tự giao hàng."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [
            make_stop("S3", 3),
            make_stop("S1", 1),
            make_stop("S2", 2),
        ],
    )

    zones = zones_by_sequence(result)

    assert (
            zones[3].zone_start_x
            < zones[2].zone_start_x
            < zones[1].zone_start_x
    )


def test_hc09_default_buffer_is_10_cm():
    """Khoảng cách giữa các zone mặc định là 10 cm."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [
            make_stop("S1", 1),
            make_stop("S2", 2),
            make_stop("S3", 3),
        ],
    )

    zones = zones_by_sequence(result)

    assert (
                   zones[2].zone_start_x - zones[3].zone_end_x
           ) == pytest.approx(10)

    assert (
                   zones[1].zone_start_x - zones[2].zone_end_x
           ) == pytest.approx(10)


def test_hc09_custom_buffer_cm():
    """Buffer tùy chỉnh được hiểu trực tiếp là cm."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [
            make_stop("S1", 1),
            make_stop("S2", 2),
        ],
        accessibility_buffer=15,
    )

    zones = zones_by_sequence(result)

    assert (
                   zones[1].zone_start_x - zones[2].zone_end_x
           ) == pytest.approx(15)


def test_hc09_equal_volume_zones():
    """Các stop cùng thể tích có zone gần bằng nhau."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [
            make_stop("S1", 1),
            make_stop("S2", 2),
            make_stop("S3", 3),
        ],
    )

    lengths = [z.zone_length for z in result.zones]

    assert sum(lengths) == pytest.approx(430, abs=0.001)
    assert max(lengths) - min(lengths) < 0.001


def test_hc09_zone_length_proportional_to_volume():
    """Stop có thể tích gấp đôi được cấp zone dài gấp đôi."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [
            make_stop("S1", 1, volume=20000),
            make_stop("S2", 2, volume=10000),
        ],
    )

    zones = zones_by_sequence(result)

    assert zones[1].zone_length == pytest.approx(
        zones[2].zone_length * 2,
        abs=0.001,
        )


def test_hc09_single_stop():
    """Một stop được sử dụng toàn bộ chiều dài xe."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [make_stop("S1", 1)],
    )

    assert len(result.zones) == 1
    assert result.zones[0].zone_start_x == pytest.approx(0)
    assert result.zones[0].zone_end_x == pytest.approx(450)


def test_hc09_no_stops():
    """Không có điểm giao thì không sinh zone."""
    result = StopZoneCalculator.calculate_zones(
        make_vehicle(),
        [],
    )

    assert result.zones == []


@pytest.mark.parametrize(
    "buffer",
    [-1, float("nan"), float("inf")],
)
def test_hc09_invalid_buffer(buffer):
    """Buffer không hợp lệ phải bị từ chối."""
    with pytest.raises(ValueError):
        StopZoneCalculator.calculate_zones(
            make_vehicle(),
            [make_stop("S1", 1)],
            accessibility_buffer=buffer,
        )
