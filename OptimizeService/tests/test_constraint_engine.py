import unittest
from uuid import uuid4
from app.dto.optimization.engine.problem_request import VehicleData, PackageData
from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.constraint_result import ConstraintResult
from app.service.optimize.constraint_engine import ConstraintEngine


class TestConstraintEngine(unittest.TestCase):

    def setUp(self):
        self.vehicle = VehicleData(
            inner_l=6.0,
            inner_w=2.0,
            inner_h=2.0,
            max_payload_kg=5000.0,
            front_axle_limit_kg=2500.0,
            rear_axle_limit_kg=3500.0,
            max_cog_offset_ratio=0.15,
        )

    def test_fragility_constraint(self):
        """FRAGILE không bị xếp đè (không có package khác nằm trên)."""
        pkg_fragile = PackageData(id="p1", l=1.0, w=1.0, h=1.0, weight=10.0, fragile=True)
        pkg_normal = PackageData(id="p2", l=1.0, w=1.0, h=1.0, weight=20.0, fragile=False)
        packages_map = {"p1": pkg_fragile, "p2": pkg_normal}

        # p1 is on the floor
        place_p1 = PlacementData(package_id="p1", x=0.0, y=0.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        existing = [place_p1]

        # Try to place p2 on top of p1
        place_p2_on_p1 = PlacementData(package_id="p2", x=0.0, y=0.0, z=1.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        result = ConstraintEngine.check_fragility(place_p2_on_p1, existing, packages_map)
        self.assertFalse(result.passed)
        self.assertEqual(result.violation_code, "FRAGILITY_VIOLATION")

        # Place p2 beside p1 on floor -> should pass
        place_p2_beside = PlacementData(package_id="p2", x=1.0, y=0.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        result_ok = ConstraintEngine.check_fragility(place_p2_beside, existing, packages_map)
        self.assertTrue(result_ok.passed)

    def test_stacking_constraint(self):
        """Tổng weight đặt lên <= max_stack_weight_kg."""
        pkg_base = PackageData(id="p1", l=1.0, w=1.0, h=1.0, weight=50.0, max_stack_weight_kg=30.0)
        pkg_heavy = PackageData(id="p2", l=1.0, w=1.0, h=1.0, weight=40.0)  # 40 > 30
        pkg_light = PackageData(id="p3", l=1.0, w=1.0, h=1.0, weight=20.0)  # 20 <= 30
        packages_map = {"p1": pkg_base, "p2": pkg_heavy, "p3": pkg_light}

        place_base = PlacementData(package_id="p1", x=0.0, y=0.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        existing = [place_base]

        # Placing heavy package on top exceeds 30kg max stack weight
        place_heavy = PlacementData(package_id="p2", x=0.0, y=0.0, z=1.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        result_heavy = ConstraintEngine.check_stacking(place_heavy, existing, packages_map)
        self.assertFalse(result_heavy.passed)
        self.assertEqual(result_heavy.violation_code, "STACKING_WEIGHT_EXCEEDED")

        # Placing light package (20kg) on top passes
        place_light = PlacementData(package_id="p3", x=0.0, y=0.0, z=1.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        result_light = ConstraintEngine.check_stacking(place_light, existing, packages_map)
        self.assertTrue(result_light.passed)

    def test_support_area_constraint(self):
        """Diện tích đáy tiếp xúc >= 70% (không overhang quá 30%)."""
        # Base package: 1.0 x 1.0 at (0, 0, 0)
        place_base = PlacementData(package_id="base", x=0.0, y=0.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        existing = [place_base]

        # On the floor (z = 0) -> always 100% supported
        on_floor = PlacementData(package_id="floor", x=2.0, y=0.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        res_floor = ConstraintEngine.check_support_area(on_floor, existing)
        self.assertTrue(res_floor.passed)

        # 80% support: x offset by 0.2m -> overlap is 0.8 * 1.0 = 0.8 / 1.0 = 80% >= 70% -> PASS
        place_80 = PlacementData(package_id="top1", x=0.2, y=0.0, z=1.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        res_80 = ConstraintEngine.check_support_area(place_80, existing)
        self.assertTrue(res_80.passed)

        # 50% support: x offset by 0.5m -> overlap is 0.5 * 1.0 = 0.5 / 1.0 = 50% < 70% -> FAIL
        place_50 = PlacementData(package_id="top2", x=0.5, y=0.0, z=1.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        res_50 = ConstraintEngine.check_support_area(place_50, existing)
        self.assertFalse(res_50.passed)
        self.assertEqual(res_50.violation_code, "INSUFFICIENT_SUPPORT_AREA")

    def test_rotation_allowed_constraint(self):
        """Nếu rotation_allowed=false thì chỉ dùng rotation_type=0."""
        pkg_no_rotate = PackageData(id="p1", l=1.0, w=0.5, h=0.5, weight=10.0, rotation_allowed=False)
        pkg_can_rotate = PackageData(id="p2", l=1.0, w=0.5, h=0.5, weight=10.0, rotation_allowed=True)

        place_rotated = PlacementData(package_id="p1", x=0.0, y=0.0, z=0.0, packed_l=0.5, packed_w=1.0, packed_h=0.5, rotation_type=1)
        res_rotated = ConstraintEngine.check_rotation_allowed(pkg_no_rotate, place_rotated)
        self.assertFalse(res_rotated.passed)
        self.assertEqual(res_rotated.violation_code, "ROTATION_NOT_ALLOWED")

        place_unrotated = PlacementData(package_id="p1", x=0.0, y=0.0, z=0.0, packed_l=1.0, packed_w=0.5, packed_h=0.5, rotation_type=0)
        res_unrotated = ConstraintEngine.check_rotation_allowed(pkg_no_rotate, place_unrotated)
        self.assertTrue(res_unrotated.passed)

        res_can_rotate = ConstraintEngine.check_rotation_allowed(pkg_can_rotate, place_rotated)
        self.assertTrue(res_can_rotate.passed)

    def test_cog_constraint(self):
        """Trọng tâm không lệch quá max_cog_offset_ratio theo cả X và Y."""
        # Vehicle: L=6.0, W=2.0 -> Center is X=3.0, Y=1.0
        # max_cog_offset_ratio = 0.15 -> max delta X = 6.0 * 0.15 = 0.9m (range [2.1, 3.9])
        #                               max delta Y = 2.0 * 0.15 = 0.3m (range [0.7, 1.3])

        # Centered placement: X=2.5 to 3.5 (center at 3.0), Y=0.5 to 1.5 (center at 1.0)
        centered_place = PlacementData(package_id="p1", x=2.5, y=0.5, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        packages_map = {"p1": PackageData(id="p1", l=1.0, w=1.0, h=1.0, weight=100.0)}

        res_centered = ConstraintEngine.check_cog([centered_place], self.vehicle, packages_map)
        self.assertTrue(res_centered.passed)

        # Extreme placement at rear-right corner: X=5.0 to 6.0 (center 5.5 -> deltaX = 2.5 > 0.9)
        corner_place = PlacementData(package_id="p1", x=5.0, y=1.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        res_corner = ConstraintEngine.check_cog([corner_place], self.vehicle, packages_map)
        self.assertFalse(res_corner.passed)
        self.assertEqual(res_corner.violation_code, "COG_OFFSET_EXCEEDED")

    def test_axle_load_constraint(self):
        """Tải trục trước/sau không vượt giới hạn."""
        # Vehicle limits: front=2500kg, rear=3500kg.
        # Place 4000kg at the very rear (x=5.0..6.0, cogX ~ 5.5/6.0 -> 91% on rear axle = 3666kg > 3500kg)
        packages_map = {"heavy": PackageData(id="heavy", l=1.0, w=1.0, h=1.0, weight=4000.0)}
        rear_place = PlacementData(package_id="heavy", x=5.0, y=0.5, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)

        res_rear = ConstraintEngine.check_axle_load([rear_place], self.vehicle, packages_map)
        self.assertFalse(res_rear.passed)
        self.assertEqual(res_rear.violation_code, "AXLE_LOAD_EXCEEDED")

        # Balanced 2000kg in center -> 1000kg front, 1000kg rear -> PASS
        packages_map_ok = {"ok": PackageData(id="ok", l=1.0, w=1.0, h=1.0, weight=2000.0)}
        mid_place = PlacementData(package_id="ok", x=2.5, y=0.5, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0)
        res_ok = ConstraintEngine.check_axle_load([mid_place], self.vehicle, packages_map_ok)
        self.assertTrue(res_ok.passed)

    def test_check_all(self):
        """check_all(placement, context) chạy tất cả constraints, trả về list violations."""
        context = {
            "vehicle": self.vehicle,
            "existing_placements": [],
            "packages_map": {"p1": PackageData(id="p1", l=1.0, w=1.0, h=1.0, weight=100.0, rotation_allowed=False)},
        }
        # Placement with rotation_type=1 on package that forbids rotation
        bad_placement = PlacementData(package_id="p1", x=0.0, y=0.0, z=0.0, packed_l=1.0, packed_w=1.0, packed_h=1.0, rotation_type=1)
        violations = ConstraintEngine.check_all(bad_placement, context)
        self.assertGreater(len(violations), 0)
        self.assertEqual(violations[0].violation_code, "ROTATION_NOT_ALLOWED")


if __name__ == "__main__":
    unittest.main()
