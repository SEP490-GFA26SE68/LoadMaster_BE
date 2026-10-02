import unittest
from app.dto.optimization.engine.problem_request import (
    ProblemRequest,
    VehicleData,
    PackageData,
    StopData,
)
from app.service.optimize.optimization_client import OptimizationClient


class Test3DEngineUpgrade(unittest.TestCase):

    def setUp(self):
        self.vehicle = VehicleData(
            inner_l=6.0,
            inner_w=2.0,
            inner_h=2.0,
            max_payload_kg=5000.0,
            front_axle_limit_kg=3000.0,
            rear_axle_limit_kg=3000.0,
            max_cog_offset_ratio=0.25,
        )
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
        pkg_stop1 = PackageData(id="p-stop1", l=1.0, w=1.0, h=1.0, weight=100.0, stop_index=1)
        # Pkg for stop-2 (delivered last, so deepest inside)
        pkg_stop2 = PackageData(id="p-stop2", l=1.0, w=1.0, h=1.0, weight=100.0, stop_index=2)

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
        pkg_fragile = PackageData(id="fragile-1", l=1.0, w=1.0, h=0.6, weight=50.0, fragile=True, stop_index=1)
        pkg_heavy = PackageData(id="heavy-1", l=1.0, w=1.0, h=0.4, weight=20.0, fragile=False, stop_index=1)

        # Restricted small vehicle where height is 1.0, length 1.0, width 1.0
        small_truck = VehicleData(inner_l=1.0, inner_w=1.0, inner_h=1.0, max_payload_kg=1000.0)
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
        pkg = PackageData(id="p1", l=2.0, w=2.0, h=1.0, weight=1000.0, stop_index=1)

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
        v_wide = VehicleData(inner_l=1.0, inner_w=2.0, inner_h=1.0, max_payload_kg=1000.0)
        pkg = PackageData(id="no-rot", l=1.5, w=0.5, h=0.5, weight=50.0, rotation_allowed=False, stop_index=1)
        problem = ProblemRequest(
            vehicle=v_wide,
            packages=[pkg],
            stops=[StopData(id="stop-1", sequence=1)],
        )
        response = self.client.solve_in_process(problem)
        self.assertEqual(len(response.placements), 0)
        self.assertEqual(len(response.unplaced), 1)
        self.assertIn("ROTATION_NOT_ALLOWED", response.unplaced[0].violated_constraints)

    def test_rehandling_count_when_zone_overflows(self):
        """When a zone cannot fit all packages of a stop, packages spill over to available vehicle space and increment rehandling_count."""
        # Truck: L=5.0, W=1.0, H=1.0
        truck = VehicleData(inner_l=5.0, inner_w=1.0, inner_h=1.0, max_payload_kg=5000.0)
        stops = [
            StopData(id="stop-1", sequence=1),
            StopData(id="stop-2", sequence=2),
        ]
        # Stop-1 has small package
        pkg_s1 = PackageData(id="s1-pkg", l=0.5, w=1.0, h=1.0, weight=10.0, stop_index=1)
        # Stop-2 has 2 packages: one 2.8m long, one 1.5m long.
        # Zone 2 will have ~ (2.8*1*1 + 1.5*1*1) / total_vol ratio ~ 4.3/4.8 * 4.9m ~ 4.38m.
        # Pkg 1 placed at x=0 -> [0, 2.8].
        # Pkg 2 needs 1.5m -> 2.8 + 1.5 = 4.3m. If zone 2 ends at 4.0m, it spills over!
        pkg_s2_a = PackageData(id="s2-a", l=2.8, w=1.0, h=1.0, weight=50.0, stop_index=2)
        pkg_s2_b = PackageData(id="s2-b", l=1.8, w=1.0, h=1.0, weight=50.0, stop_index=2)

        problem = ProblemRequest(
            vehicle=truck,
            packages=[pkg_s1, pkg_s2_a, pkg_s2_b],
            stops=stops,
        )
        response = self.client.solve_in_process(problem)
        # Check if rehandling occurred
        self.assertGreater(response.metrics.rehandling_count, 0)


if __name__ == "__main__":
    unittest.main()
