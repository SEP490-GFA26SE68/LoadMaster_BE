
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from app.dto.optimization.engine.problem_request import (
    PackageData,
    VehicleData,
)
from app.dto.optimization.engine.engine_response import PlacementData


@dataclass
class UnloadValidationResult:
    passed: bool
    violation_code: Optional[str] = None
    detail: str = ""
    unload_sequence: List[str] = field(default_factory=list)


class UnloadFeasibilityValidator:
    """
    HC-09: Multi-drop LIFO — Straight-X Unloading.

    Quy ước:
    - X = 0: đầu sâu trong xe.
    - X = inner_l: cửa sau.
    - Dỡ kiện theo chiều +X.
    - Không xoay, nâng hay dịch ngang khi rút kiện.
    - stop_index nhỏ hơn được giao trước.
    - Kiểm tra toàn bộ trình tự dỡ hàng.

    Giả định V1:
    - Cửa sau nằm chính giữa theo chiều Y.
    - Mép dưới cửa ở Z = 0.
    - Các kiện là hộp chữ nhật song song trục XYZ.
    """

    EPSILON = 1e-4
    MAX_SEARCH_STATES = 20000

    @classmethod
    def validate(
            cls,
            placements: List[PlacementData],
            packages_map: Dict[str, PackageData],
            vehicle: VehicleData,
    ) -> UnloadValidationResult:

        def fail(code: str, detail: str) -> UnloadValidationResult:
            return UnloadValidationResult(
                passed=False,
                violation_code=code,
                detail=detail,
            )

        # 1. Kiểm tra hình học xe và cửa.
        vehicle_values = (
            vehicle.inner_l,
            vehicle.inner_w,
            vehicle.inner_h,
            vehicle.door_w,
            vehicle.door_h,
        )

        if any(value is None for value in vehicle_values):
            return fail(
                "INVALID_UNLOAD_DATA",
                "Missing vehicle or cargo door dimensions",
            )

        if not all(
                math.isfinite(float(value))
                and float(value) > 0
                for value in vehicle_values
        ):
            return fail(
                "INVALID_UNLOAD_DATA",
                "Invalid vehicle or cargo door dimensions",
            )

        inner_l = float(vehicle.inner_l)
        inner_w = float(vehicle.inner_w)
        inner_h = float(vehicle.inner_h)
        door_w = float(vehicle.door_w)
        door_h = float(vehicle.door_h)

        if (
                door_w > inner_w + cls.EPSILON
                or door_h > inner_h + cls.EPSILON
        ):
            return fail(
                "INVALID_UNLOAD_DATA",
                "Cargo door exceeds vehicle interior dimensions",
            )

        # Cửa nằm chính giữa chiều rộng xe.
        door_y_min = (inner_w - door_w) / 2.0
        door_y_max = door_y_min + door_w

        if not placements:
            return UnloadValidationResult(passed=True)

        # 2. Kiểm tra metadata và hình học ban đầu.
        stops: List[int] = []
        seen_ids = set()

        for p in placements:
            package_id = str(p.package_id)

            if package_id in seen_ids:
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Duplicate placement for package {package_id}",
                )

            seen_ids.add(package_id)

            pkg = packages_map.get(package_id)

            if pkg is None or pkg.stop_index is None:
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Missing stop information for package {package_id}",
                )

            try:
                stop = int(pkg.stop_index)
                if (
                        isinstance(pkg.stop_index, bool)
                        or stop < 1
                        or float(pkg.stop_index) != stop
                ):
                    raise ValueError()
            except (ValueError, TypeError, OverflowError):
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Invalid stop_index for package {package_id}",
                )

            stops.append(stop)

            values = (
                p.x, p.y, p.z,
                p.packed_l, p.packed_w, p.packed_h,
            )

            if not all(
                    math.isfinite(float(v))
                    for v in values
            ):
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Invalid geometry for package {package_id}",
                )

            x, y, z = float(p.x), float(p.y), float(p.z)
            l = float(p.packed_l)
            w = float(p.packed_w)
            h = float(p.packed_h)

            if (
                    l <= 0 or w <= 0 or h <= 0
                    or x < -cls.EPSILON
                    or y < -cls.EPSILON
                    or z < -cls.EPSILON
                    or x + l > inner_l + cls.EPSILON
                    or y + w > inner_w + cls.EPSILON
                    or z + h > inner_h + cls.EPSILON
            ):
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Package {package_id} exceeds vehicle boundaries",
                )

        # 3. Kiểm tra toàn bộ load plan ban đầu hợp lệ
        # về va chạm và hỗ trợ hình học.
        from app.service.optimize.constraint_engine import ConstraintEngine

        for i, p in enumerate(placements):
            others = [
                other
                for j, other in enumerate(placements)
                if j != i
            ]

            if not ConstraintEngine.check_non_overlap(
                    p, others
            ).passed:
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Overlapping packages near {p.package_id}",
                )

        def stable_remaining(indices: frozenset[int]) -> bool:
            """
            Những kiện còn trong xe phải tiếp tục
            thỏa HC-05 và HC-06.
            """
            for i in indices:
                p = placements[i]
                others = [
                    placements[j]
                    for j in indices
                    if j != i
                ]

                if not ConstraintEngine.check_support_area(
                        p, others
                ).passed:
                    return False

                if not ConstraintEngine.check_stability(
                        p, others
                ).passed:
                    return False

            return True

        initial_state = frozenset(range(len(placements)))

        if not stable_remaining(initial_state):
            return fail(
                "INVALID_UNLOAD_DATA",
                "Initial load plan has unsupported or unstable packages",
            )

        def can_extract(
                index: int,
                remaining: frozenset[int],
        ) -> bool:
            """
            Kiểm tra một kiện có thể trượt thẳng
            theo trục +X để ra khỏi cửa xe hay không.
            """
            p = placements[index]

            x = float(p.x)
            y = float(p.y)
            z = float(p.z)
            w = float(p.packed_w)
            h = float(p.packed_h)

            # Kiện phải nằm trong đúng khẩu độ cửa.
            # Chỉ kiểm tra kích thước là chưa đủ:
            # vị trí Y cũng phải phù hợp vì không
            # được phép dịch ngang khi dỡ.
            if (
                    y < door_y_min - cls.EPSILON
                    or y + w > door_y_max + cls.EPSILON
                    or z < -cls.EPSILON
                    or z + h > door_h + cls.EPSILON
            ):
                return False

            # Swept volume từ vị trí hiện tại tới cửa:
            # [x, inner_l] x [y, y+w] x [z, z+h].
            #
            # Nếu bất kỳ kiện còn lại giao thể tích
            # này, kiện đang xét không thể kéo ra.
            for j in remaining:
                if j == index:
                    continue

                other = placements[j]

                overlap_x = (
                        min(
                            inner_l,
                            float(other.x + other.packed_l),
                        )
                        - max(x, float(other.x))
                )

                overlap_y = (
                        min(
                            y + w,
                            float(other.y + other.packed_w),
                            )
                        - max(y, float(other.y))
                )

                overlap_z = (
                        min(
                            z + h,
                            float(other.z + other.packed_h),
                            )
                        - max(z, float(other.z))
                )

                if (
                        overlap_x > cls.EPSILON
                        and overlap_y > cls.EPSILON
                        and overlap_z > cls.EPSILON
                ):
                    return False

            return True

        # 4. Tìm một trình tự dỡ hàng hợp lệ.
        #
        # Dùng DFS + memoization:
        # Nếu một lựa chọn dỡ hàng thất bại,
        # thử kiện khác cùng stop.
        #
        # Không bao giờ chuyển sang stop tiếp theo
        # nếu stop hiện tại vẫn còn kiện.
        impossible_states = set()
        explored_states = 0
        search_limit_reached = False

        def search(
                remaining: frozenset[int],
        ) -> Optional[Tuple[int, ...]]:
            nonlocal explored_states, search_limit_reached

            if not remaining:
                return ()

            if remaining in impossible_states:
                return None

            explored_states += 1

            if explored_states > cls.MAX_SEARCH_STATES:
                search_limit_reached = True
                return None

            current_stop = min(stops[i] for i in remaining)

            candidates = [
                i for i in remaining
                if stops[i] == current_stop
            ]

            # Ưu tiên kiện gần cửa, nhưng vẫn thử
            # các phương án khác nếu cần.
            candidates.sort(
                key=lambda i: float(placements[i].x),
                reverse=True,
            )

            for i in candidates:
                if not can_extract(i, remaining):
                    continue

                next_remaining = remaining - {i}

                # Dỡ một kiện không được khiến các kiện
                # còn lại mất vùng đỡ hoặc mất ổn định.
                if not stable_remaining(next_remaining):
                    continue

                suffix = search(next_remaining)

                if suffix is not None:
                    return (i,) + suffix

                if search_limit_reached:
                    return None

            impossible_states.add(remaining)
            return None

        sequence = search(initial_state)

        if sequence is None:
            if search_limit_reached:
                return fail(
                    "UNLOAD_SEARCH_LIMIT_REACHED",
                    (
                        "Unable to determine unloading feasibility "
                        "within the configured search limit"
                    ),
                )

            return fail(
                "MULTI_DROP_LIFO_VIOLATION",
                (
                    "No feasible straight-X unloading sequence "
                    "exists for the delivery stop order"
                ),
            )

        return UnloadValidationResult(
            passed=True,
            unload_sequence=[
                str(placements[i].package_id)
                for i in sequence
            ],
        )
