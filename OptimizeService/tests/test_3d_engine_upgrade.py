import unittest
from app.dto.optimization.engine.problem_request import (
    ProblemRequest,
    VehicleData,
    PackageData,
    StopData,
)
from app.service.optimize.optimization_client import OptimizationClient


def truck(l, w, h, payload, cog_ratio=0.5, axle_limit=1_000_000.0):
    """Xe đơn vị cm, có cửa bằng mặt cắt thùng và hai trục ở hai đầu thùng (bộ giải đòi đủ cửa và trục)."""
    return VehicleData(
        inner_l=l,
        inner_w=w,
        inner_h=h,
        max_payload_kg=payload,
        door_w=w,
        door_h=h,
        front_axle_x=0.0,
        rear_axle_x=l,
        front_axle_limit_kg=axle_limit,
        rear_axle_limit_kg=axle_limit,
        empty_front_axle_load_kg=0.0,
        empty_rear_axle_load_kg=0.0,
        max_cog_offset_ratio=cog_ratio,
    )


class Test3DEngineUpgrade(unittest.TestCase):

    def setUp(self):
        self.vehicle = truck(600.0, 200.0, 200.0, 5000.0, axle_limit=3000.0)
        self.client = OptimizationClient()

    def test_zone_aware_packing_order(self):
        """
        AC: Pack theo zone: stop cuối cùng xếp trước (sâu nhất, x thấp),
        stop đầu tiên xếp sau (gần cửa, x cao).
        Stop 1 (first delivery stop) vs Stop 2 (last delivery stop).
        """
        stops = [
            StopData(id="stop-1", sequence=1),
            StopData(id="stop-2", sequence=2),
        ]
        # Pkg for stop-1 (delivered first, so near door)
        pkg_stop1 = PackageData(id="p-stop1", l=100.0, w=100.0, h=100.0, weight=100.0, stop_index=1, allowed_rotations=[0, 1])
        # Pkg for stop-2 (delivered last, so deepest inside)
        pkg_stop2 = PackageData(id="p-stop2", l=100.0, w=100.0, h=100.0, weight=100.0, stop_index=2, allowed_rotations=[0, 1])

        problem = ProblemRequest(
            vehicle=self.vehicle,
            packages=[pkg_stop1, pkg_stop2],
            stops=stops,
        )

        response = self.client.solve_in_process(problem)
        self.assertEqual(len(response.placements), 2)

        p_by_id = {str(p.package_id): p for p in response.placements}

        # Stop 2 package should be placed deeper (smaller x) than Stop 1 package
        self.assertLess(p_by_id["p-stop2"].x, p_by_id["p-stop1"].x)
        # Stop zone IDs should be assigned
        self.assertEqual(str(p_by_id["p-stop1"].stop_zone_id), "stop-1")
        self.assertEqual(str(p_by_id["p-stop2"].stop_zone_id), "stop-2")

    def test_fragility_constraint_in_packing(self):
        """Fragile package must not have any package placed on top of it."""
        stops = [StopData(id="stop-1", sequence=1)]
        pkg_fragile = PackageData(id="fragile-1", l=100.0, w=100.0, h=60.0, weight=50.0, fragile=True, stop_index=1, allowed_rotations=[0, 1])
        pkg_heavy = PackageData(id="heavy-1", l=100.0, w=100.0, h=40.0, weight=20.0, fragile=False, stop_index=1, allowed_rotations=[0, 1])

        # Restricted small vehicle where height is 1.0, length 1.0, width 1.0
        small_truck = truck(100.0, 100.0, 100.0, 1000.0)
        # If fragile is placed at z=0, heavy cannot be placed at z=0.6 above it!
        problem = ProblemRequest(
            vehicle=small_truck,
            packages=[pkg_fragile, pkg_heavy],
            stops=stops,
        )

        response = self.client.solve_in_process(problem)
        # One package placed, but the second cannot be placed on top of fragile package!
        # So only 1 placed, 1 unplaced with constraint violation
        self.assertEqual(len(response.placements), 1)
        self.assertEqual(len(response.unplaced), 1)
        self.assertEqual(response.unplaced[0].reason, "CONSTRAINT_VIOLATED")

    def test_metrics_cog_axle_loads(self):
        """Metrics should include COG (x, y, z), axle loads, and rehandling count."""
        stops = [StopData(id="stop-1", sequence=1)]
        pkg = PackageData(id="p1", l=200.0, w=200.0, h=100.0, weight=1000.0, stop_index=1, allowed_rotations=[0, 1])

        problem = ProblemRequest(
            vehicle=self.vehicle,
            packages=[pkg],
            stops=stops,
        )

        response = self.client.solve_in_process(problem)
        m = response.metrics
        self.assertIsNotNone(m.cog_x)
        self.assertIsNotNone(m.cog_y)
        self.assertIsNotNone(m.cog_z)
        self.assertIsNotNone(m.front_axle_load)
        self.assertIsNotNone(m.rear_axle_load)
        self.assertGreaterEqual(m.rehandling_count, 0)
        self.assertAlmostEqual(m.front_axle_load + m.rear_axle_load, 1000.0, places=1)

    def test_rotation_forbidden_in_packing(self):
        """Package with rotation_allowed=False should not be rotated even if it fits rotated."""
        # Vehicle: inner_l=2.0, inner_w=1.0, inner_h=1.0
        # Package: l=1.5, w=0.5 -> if rotation_type=0, takes l=1.5, w=0.5 (fits)
        # If we have vehicle of inner_l=1.0, inner_w=2.0, package l=1.5, w=0.5 can only fit if rotated 90 deg.
        # But if rotation_allowed=False, it cannot be rotated and must be unplaced!
        v_wide = truck(100.0, 200.0, 100.0, 1000.0)
        pkg = PackageData(id="no-rot", l=150.0, w=50.0, h=50.0, weight=50.0, rotation_allowed=False, stop_index=1, allowed_rotations=[0])
        problem = ProblemRequest(
            vehicle=v_wide,
            packages=[pkg],
            stops=[StopData(id="stop-1", sequence=1)],
        )
        response = self.client.solve_in_process(problem)
        self.assertEqual(len(response.placements), 0)
        self.assertEqual(len(response.unplaced), 1)
        self.assertIn("ORIENTATION_NOT_ALLOWED", response.unplaced[0].violated_constraints)

    def test_rehandling_count_when_zone_overflows(self):
        """When a zone cannot fit all packages of a stop, packages spill over to available vehicle space and increment rehandling_count."""
        # Thùng 500 cm; vùng chia theo thể tích, nhưng ba kiện thấp của điểm 2 không chồng được lên nhau
        # (2 x 60 > 100 cm) nên cần nhiều sàn hơn vùng của chúng: một kiện nằm ngoài vùng.
        vehicle = truck(500.0, 100.0, 100.0, 5000.0)
        stops = [
            StopData(id="stop-1", sequence=1),
            StopData(id="stop-2", sequence=2),
        ]
        packages = [PackageData(id="s1-pkg", l=100.0, w=100.0, h=100.0, weight=10.0, stop_index=1, allowed_rotations=[0])]
        packages += [
            PackageData(id=f"s2-{i}", l=150.0, w=100.0, h=60.0, weight=10.0, stop_index=2, allowed_rotations=[0])
            for i in range(3)
        ]

        problem = ProblemRequest(
            vehicle=vehicle,
            packages=packages,
            stops=stops,
        )
        response = self.client.solve_in_process(problem)
        # Check if rehandling occurred
        self.assertGreater(response.metrics.rehandling_count, 0)


if __name__ == "__main__":
    unittest.main()
