from __future__ import annotations

from typing import Optional, List, Dict, Any, Union
from app.dto.optimization.engine.problem_request import VehicleData, PackageData
from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.constraint_result import ConstraintResult


class ConstraintEngine:
    """
    Engine kiểm tra tất cả ràng buộc vật lý khi xếp package vào vị trí đề xuất:
      1. check_fragility — FRAGILE không bị xếp đè
      2. check_stacking — Tổng weight đặt lên <= max_stack_weight_kg
      3. check_support_area — Diện tích đáy tiếp xúc >= 70% (không overhang quá 30%)
      4. check_rotation_allowed — Nếu rotation_allowed=False thì chỉ dùng rotation_type=0
      5. check_cog — Trọng tâm không lệch quá max_cog_offset_ratio theo cả X và Y
      6. check_axle_load — Tải trục trước/sau không vượt giới hạn
      7. check_all — Chạy tất cả constraints, trả về list violations
    """

    EPSILON = 1e-4

    @classmethod
    def check_fragility(
        cls,
        placement: PlacementData,
        existing_placements: List[PlacementData],
        packages: Dict[str, PackageData],
    ) -> ConstraintResult:
        """Kiểm tra: Kiện hàng dễ vỡ (FRAGILE) không có package khác nằm trên."""
        for p in existing_placements:
            pkg_info = packages.get(str(p.package_id))
            if not pkg_info or not getattr(pkg_info, "fragile", False):
                continue

            # p is fragile. Check if placement is on top of p.
            if cls._is_resting_on(placement, p):
                return ConstraintResult.fail(
                    violation_code="FRAGILITY_VIOLATION",
                    detail=f"Cannot place package {placement.package_id} on top of fragile package {p.package_id}",
                )

        return ConstraintResult.success()

    @classmethod
    def check_stacking(
        cls,
        placement: PlacementData,
        existing_placements: List[PlacementData],
        packages: Dict[str, PackageData],
    ) -> ConstraintResult:
        """Kiểm tra: Tổng weight đặt lên một package <= max_stack_weight_kg."""
        pkg_new = packages.get(str(placement.package_id))
        new_weight = float(pkg_new.weight if pkg_new else 0.0)

        for p in existing_placements:
            pkg_base = packages.get(str(p.package_id))
            if not pkg_base or pkg_base.max_stack_weight_kg is None or pkg_base.max_stack_weight_kg <= 0:
                continue

            limit = float(pkg_base.max_stack_weight_kg)

            # Check if placement is above p (direct or indirect)
            if cls._is_resting_on(placement, p):
                # Calculate current weight resting on p
                current_weight = 0.0
                for other in existing_placements:
                    if other.package_id != p.package_id and cls._is_resting_on(other, p):
                        other_pkg = packages.get(str(other.package_id))
                        current_weight += float(other_pkg.weight if other_pkg else 0.0)

                total_stacked = current_weight + new_weight
                if total_stacked > limit + cls.EPSILON:
                    return ConstraintResult.fail(
                        violation_code="STACKING_WEIGHT_EXCEEDED",
                        detail=f"Package {p.package_id} max stack weight {limit}kg exceeded (total stacked: {total_stacked}kg)",
                    )

        return ConstraintResult.success()

    @classmethod
    def check_support_area(
        cls,
        placement: PlacementData,
        existing_placements: List[PlacementData],
    ) -> ConstraintResult:
        """Kiểm tra: Diện tích đáy tiếp xúc >= 70% (không overhang quá 30%)."""
        # If placed directly on vehicle floor (z == 0) -> 100% supported
        if placement.z <= cls.EPSILON:
            return ConstraintResult.success()

        base_area = float(placement.packed_l) * float(placement.packed_w)
        if base_area <= cls.EPSILON:
            return ConstraintResult.success()

        support_area = 0.0
        placement_z = float(placement.z)

        for p in existing_placements:
            top_z = float(p.z) + float(p.packed_h)
            # Must be directly touching the bottom of placement
            if abs(top_z - placement_z) <= cls.EPSILON:
                overlap_x = max(0.0, min(placement.x + placement.packed_l, p.x + p.packed_l) - max(placement.x, p.x))
                overlap_y = max(0.0, min(placement.y + placement.packed_w, p.y + p.packed_w) - max(placement.y, p.y))
                support_area += overlap_x * overlap_y

        ratio = support_area / base_area
        if ratio < 0.70 - cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="INSUFFICIENT_SUPPORT_AREA",
                detail=f"Package {placement.package_id} support area ratio {ratio:.2%} < required 70%",
            )

        return ConstraintResult.success()

    @classmethod
    def check_rotation_allowed(
        cls,
        package: PackageData,
        placement: Optional[PlacementData] = None,
    ) -> ConstraintResult:
        """Kiểm tra: Nếu rotation_allowed=False thì chỉ được dùng rotation_type=0."""
        is_allowed = getattr(package, "rotation_allowed", True)
        if is_allowed is False:
            rot_type = placement.rotation_type if placement is not None else 0
            # rotation_type 0 is unrotated (default)
            if rot_type not in (0, "0", None):
                return ConstraintResult.fail(
                    violation_code="ROTATION_NOT_ALLOWED",
                    detail=f"Package {package.id} forbids rotation but placement has rotation_type={rot_type}",
                )

        return ConstraintResult.success()

    @classmethod
    def check_cog(
        cls,
        all_placements: List[PlacementData],
        vehicle: VehicleData,
        packages_map: Optional[Dict[str, PackageData]] = None,
    ) -> ConstraintResult:
        """Kiểm tra: Trọng tâm (COG) không lệch quá max_cog_offset_ratio theo cả X và Y."""
        if not all_placements:
            return ConstraintResult.success()

        total_mass = 0.0
        weighted_x = 0.0
        weighted_y = 0.0
        weighted_z = 0.0

        for p in all_placements:
            pkg = packages_map.get(str(p.package_id)) if packages_map else None
            m = float(pkg.weight if pkg else 1.0)
            if m <= 0:
                m = 1.0

            cx = float(p.x) + float(p.packed_l) / 2.0
            cy = float(p.y) + float(p.packed_w) / 2.0
            cz = float(p.z) + float(p.packed_h) / 2.0

            total_mass += m
            weighted_x += m * cx
            weighted_y += m * cy
            weighted_z += m * cz

        if total_mass <= 0:
            return ConstraintResult.success()

        cog_x = weighted_x / total_mass
        cog_y = weighted_y / total_mass

        center_x = float(vehicle.inner_l) / 2.0
        center_y = float(vehicle.inner_w) / 2.0

        delta_x = abs(cog_x - center_x)
        delta_y = abs(cog_y - center_y)

        max_ratio = float(getattr(vehicle, "max_cog_offset_ratio", 0.15) or 0.15)
        offset_ratio_x = delta_x / float(vehicle.inner_l) if vehicle.inner_l > 0 else 0.0
        offset_ratio_y = delta_y / float(vehicle.inner_w) if vehicle.inner_w > 0 else 0.0

        if offset_ratio_x > max_ratio + cls.EPSILON or offset_ratio_y > max_ratio + cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="COG_OFFSET_EXCEEDED",
                detail=(
                    f"COG offset exceeds limit {max_ratio:.2%}: "
                    f"X ratio {offset_ratio_x:.2%}, Y ratio {offset_ratio_y:.2%}"
                ),
            )

        return ConstraintResult.success()

    @classmethod
    def check_axle_load(
        cls,
        all_placements: List[PlacementData],
        vehicle: VehicleData,
        packages_map: Optional[Dict[str, PackageData]] = None,
    ) -> ConstraintResult:
        """Kiểm tra: Tải trọng phân bổ lên trục trước/sau không vượt giới hạn."""
        front_limit = getattr(vehicle, "front_axle_limit_kg", None)
        rear_limit = getattr(vehicle, "rear_axle_limit_kg", None)

        if not front_limit and not rear_limit:
            return ConstraintResult.success()

        if not all_placements:
            return ConstraintResult.success()

        total_weight = 0.0
        weighted_x = 0.0

        for p in all_placements:
            pkg = packages_map.get(str(p.package_id)) if packages_map else None
            w = float(pkg.weight if pkg else 0.0)
            cx = float(p.x) + float(p.packed_l) / 2.0
            total_weight += w
            weighted_x += w * cx

        if total_weight <= 0:
            return ConstraintResult.success()

        cog_x = weighted_x / total_weight
        inner_l = float(vehicle.inner_l)

        # Static moment distribution:
        # x = 0 is Front (cabin), x = inner_l is Rear (door)
        # Rear axle load = Total * (cog_x / inner_l)
        # Front axle load = Total * ((inner_l - cog_x) / inner_l)
        rear_load = total_weight * (cog_x / inner_l) if inner_l > 0 else 0.0
        front_load = total_weight - rear_load

        if front_limit and front_load > float(front_limit) + cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="AXLE_LOAD_EXCEEDED",
                detail=f"Front axle load {front_load:.2f}kg exceeds limit {front_limit}kg",
            )

        if rear_limit and rear_load > float(rear_limit) + cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="AXLE_LOAD_EXCEEDED",
                detail=f"Rear axle load {rear_load:.2f}kg exceeds limit {rear_limit}kg",
            )

        return ConstraintResult.success()

    @classmethod
    def check_all(
        cls,
        placement: PlacementData,
        context: Dict[str, Any],
    ) -> List[ConstraintResult]:
        """Chạy tất cả constraints, trả về list violations (những kết quả không passed)."""
        vehicle = context.get("vehicle")
        existing_placements = context.get("existing_placements", [])
        packages_map = context.get("packages_map", {})

        pkg = packages_map.get(str(placement.package_id))
        violations: List[ConstraintResult] = []

        # 1. Rotation allowed
        if pkg:
            res_rot = cls.check_rotation_allowed(pkg, placement)
            if not res_rot.passed:
                violations.append(res_rot)

        # 2. Support area
        res_supp = cls.check_support_area(placement, existing_placements)
        if not res_supp.passed:
            violations.append(res_supp)

        # 3. Fragility
        res_frag = cls.check_fragility(placement, existing_placements, packages_map)
        if not res_frag.passed:
            violations.append(res_frag)

        # 4. Stacking weight
        res_stack = cls.check_stacking(placement, existing_placements, packages_map)
        if not res_stack.passed:
            violations.append(res_stack)

        # 5. COG & Axle load with new placement added (enabled via context, e.g. for final full plan checks)
        if vehicle and context.get("check_cog", False):
            all_placements = existing_placements + [placement]
            res_cog = cls.check_cog(all_placements, vehicle, packages_map)
            if not res_cog.passed:
                violations.append(res_cog)

        if vehicle and context.get("check_axle", False):
            all_placements = existing_placements + [placement]
            res_axle = cls.check_axle_load(all_placements, vehicle, packages_map)
            if not res_axle.passed:
                violations.append(res_axle)

        return violations

    @classmethod
    def _is_resting_on(cls, top: PlacementData, bottom: PlacementData) -> bool:
        """Kiểm tra xem top có nằm đè lên bottom không (xét hình chiếu XY và toạ độ Z)."""
        # Top must be above bottom
        if float(top.z) < float(bottom.z) + float(bottom.packed_h) - cls.EPSILON:
            return False

        # Check XY bounding box overlap
        overlap_x = max(0.0, min(float(top.x) + float(top.packed_l), float(bottom.x) + float(bottom.packed_l)) - max(float(top.x), float(bottom.x)))
        overlap_y = max(0.0, min(float(top.y) + float(top.packed_w), float(bottom.y) + float(bottom.packed_w)) - max(float(top.y), float(bottom.y)))

        return overlap_x > cls.EPSILON and overlap_y > cls.EPSILON
