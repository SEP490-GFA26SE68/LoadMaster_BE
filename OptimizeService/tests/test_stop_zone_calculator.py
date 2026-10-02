import unittest
from app.dto.optimization.engine.problem_request import VehicleData
from app.service.optimize.stop_zone_calculator import StopZoneCalculator
from app.dto.optimization.engine.zone_data import (
    StopZoneInput,
    StopPackageData,
    ZoneData,
    StopZoneCalculationResponse,
)


class TestStopZoneCalculator(unittest.TestCase):

    def setUp(self):
        # Vehicle: 6m length x 2m width x 2m height, 5000kg payload
        self.vehicle = VehicleData(
            inner_l=6.0,
            inner_w=2.0,
            inner_h=2.0,
            max_payload_kg=5000.0,
        )

    def test_dynamic_sizing_three_stops_20_35_45(self):
        """
        AC: 3 stops với volume ratio 20%/35%/45% → verify zone sizes.
        Total buffers = (3 - 1) * 0.1 = 0.2m.
        Usable length = 6.0 - 0.2 = 5.8m.
        Expected zone lengths:
          - Stop 1 (20%): 5.8 * 0.20 = 1.16m
          - Stop 2 (35%): 5.8 * 0.35 = 2.03m
          - Stop 3 (45%): 5.8 * 0.45 = 2.61m
        """
        stops = [
            {
                "stop_id": "stop-1",
                "packages": [{"volume": 20.0, "weight": 500.0}],
            },
            {
                "stop_id": "stop-2",
                "packages": [{"volume": 35.0, "weight": 1000.0}],
            },
            {
                "stop_id": "stop-3",
                "packages": [{"volume": 45.0, "weight": 1500.0}],
            },
        ]

        result = StopZoneCalculator.calculate_zones(self.vehicle, stops, accessibility_buffer=0.1)
        self.assertIsInstance(result, StopZoneCalculationResponse)
        self.assertEqual(len(result.zones), 3)

        # Map zones by stop_id for easy assertion
        zones_by_id = {z.stop_id: z for z in result.zones}

        z1 = zones_by_id["stop-1"]
        z2 = zones_by_id["stop-2"]
        z3 = zones_by_id["stop-3"]

        # Verify zone lengths (size)
        len1 = round(z1.zone_end_x - z1.zone_start_x, 4)
        len2 = round(z2.zone_end_x - z2.zone_start_x, 4)
        len3 = round(z3.zone_end_x - z3.zone_start_x, 4)

        self.assertAlmostEqual(len1, 1.16, places=2)
        self.assertAlmostEqual(len2, 2.03, places=2)
        self.assertAlmostEqual(len3, 2.61, places=2)

        # AC: Tổng zone_length <= vehicle.inner_l (trừ sum of buffers)
        total_zone_length = len1 + len2 + len3
        sum_of_buffers = 2 * 0.1  # 0.2m
        self.assertLessEqual(round(total_zone_length, 4), round(self.vehicle.inner_l - sum_of_buffers, 4))
        self.assertAlmostEqual(total_zone_length, 5.8, places=2)

        # AC: Zone cuối = sâu nhất (x=0) — stop giao sau (stop-3)
        # AC: Zone đầu tiên = gần cửa sau (x=inner_l) — stop giao trước (stop-1)
        self.assertAlmostEqual(z3.zone_start_x, 0.0, places=2)
        self.assertAlmostEqual(z1.zone_end_x, self.vehicle.inner_l, places=2)

        # Buffer between Stop 3 and Stop 2 is 0.1m
        gap_3_2 = round(z2.zone_start_x - z3.zone_end_x, 4)
        self.assertAlmostEqual(gap_3_2, 0.1, places=2)

        # Buffer between Stop 2 and Stop 1 is 0.1m
        gap_2_1 = round(z1.zone_start_x - z2.zone_end_x, 4)
        self.assertAlmostEqual(gap_2_1, 0.1, places=2)

        # Verify zone volumes
        vehicle_cross_section = self.vehicle.inner_w * self.vehicle.inner_h  # 4.0
        self.assertAlmostEqual(z1.zone_volume, len1 * vehicle_cross_section, places=2)
        self.assertAlmostEqual(z2.zone_volume, len2 * vehicle_cross_section, places=2)
        self.assertAlmostEqual(z3.zone_volume, len3 * vehicle_cross_section, places=2)

        # Verify zone weight limits (allocated proportionally)
        self.assertGreater(z3.zone_weight_limit, z2.zone_weight_limit)
        self.assertGreater(z2.zone_weight_limit, z1.zone_weight_limit)

    def test_single_stop(self):
        """Single stop should take the entire vehicle without internal buffer."""
        stops = [
            StopZoneInput(
                stop_id="single-stop",
                packages=[StopPackageData(volume=10.0, weight=500.0)],
            )
        ]
        result = StopZoneCalculator.calculate_zones(self.vehicle, stops)
        self.assertEqual(len(result.zones), 1)
        z = result.zones[0]
        self.assertAlmostEqual(z.zone_start_x, 0.0, places=2)
        self.assertAlmostEqual(z.zone_end_x, 6.0, places=2)
        self.assertAlmostEqual(z.zone_volume, 6.0 * 2.0 * 2.0, places=2)
        self.assertAlmostEqual(z.zone_weight_limit, 5000.0, places=2)

    def test_dimensions_in_millimeter(self):
        """Vehicle in mm (inner_l = 6000mm) auto-adjusts buffer to 100mm (0.1m)."""
        vehicle_mm = VehicleData(
            inner_l=6000.0,
            inner_w=2000.0,
            inner_h=2000.0,
            max_payload_kg=5000.0,
        )
        stops = [
            {"stop_id": 1, "packages": [{"l": 1000, "w": 1000, "h": 500, "weight": 100}]},
            {"stop_id": 2, "packages": [{"l": 1000, "w": 1000, "h": 500, "weight": 100}]},
        ]
        result = StopZoneCalculator.calculate_zones(vehicle_mm, stops)
        self.assertEqual(len(result.zones), 2)
        # Usable length = 6000 - 100 = 5900mm. Each 50% = 2950mm
        z_by_id = {z.stop_id: z for z in result.zones}
        self.assertAlmostEqual(z_by_id[2].zone_start_x, 0.0, places=1)
        self.assertAlmostEqual(z_by_id[2].zone_end_x, 2950.0, places=1)
        self.assertAlmostEqual(z_by_id[1].zone_start_x, 3050.0, places=1)
        self.assertAlmostEqual(z_by_id[1].zone_end_x, 6000.0, places=1)


if __name__ == "__main__":
    unittest.main()
