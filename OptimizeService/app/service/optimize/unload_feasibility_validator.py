
from __future__ import annotations

import bisect
import math
import time
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
    - Không xoay; có thể nâng/dịch ngang rồi kéo ra nếu đường đi không bị chặn.
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
            *,
            deadline: Optional[float] = None,
            incremental: bool = True,
    ) -> UnloadValidationResult:
        """
        `incremental` (mặc định bật, 28/11/2026): khi tìm trình tự dỡ, sau khi lấy một kiện ra chỉ kiểm lại HC-05 / HC-06
        cho các kiện mà kiện đó có thể đang đỡ trực tiếp. Cùng kết luận với cách kiểm lại mọi kiện còn trong xe
        (`incremental=False`, giữ làm bản đối chiếu cho test):
        - check_support_area / check_stability của một kiện chỉ dùng tập kiện có mặt trên chạm đáy nó và giao XY > 0
          (thứ tự không ảnh hưởng; hình học mọi kiện đã kiểm ở bước 2, nên vòng kiểm dữ liệu của chúng luôn qua);
        - search chỉ đi tiếp từ trạng thái đã ổn định (trạng thái đầu đã kiểm đủ; trạng thái sau chỉ nhận khi ổn định),
          nên bỏ kiện i chỉ có thể đổi kết quả của các kiện được i đỡ.
        Đường tìm kiếm, trình tự dỡ và mã lỗi giữ nguyên; chỉ nhanh hơn (bài 289 kiện: 36 s → vài giây).
        """

        def fail(code: str, detail: str) -> UnloadValidationResult:
            return UnloadValidationResult(
                passed=False,
                violation_code=code,
                detail=detail,
            )

        def expired() -> bool:
            return deadline is not None and time.monotonic() >= deadline

        def timeout() -> UnloadValidationResult:
            return fail("UNLOAD_TIME_LIMIT_REACHED", "Unload validation exceeded the optimization deadline")

        if expired():
            return timeout()

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
            if expired():
                return timeout()
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
        from app.constant.optimization.violation_codes import LIFO_BLOCKED
        from app.service.optimize.constraint_engine import ConstraintEngine

        for i, p in enumerate(placements):
            if expired():
                return timeout()
            others = [
                other
                for j, other in enumerate(placements)
                if j != i
            ]

            obstacle_result = ConstraintEngine.check_obstacles(p, vehicle)
            if not obstacle_result.passed:
                return fail(obstacle_result.violation_code, obstacle_result.detail)

            if not ConstraintEngine.check_non_overlap(
                    p, others
            ).passed:
                return fail(
                    "INVALID_UNLOAD_DATA",
                    f"Overlapping packages near {p.package_id}",
                )

        def stable_one(i: int, indices: frozenset[int]) -> bool:
            """Kiện i (thuộc `indices`) thỏa HC-05 và HC-06 khi các kiện `indices` còn trong xe."""
            p = placements[i]
            others = [
                placements[j]
                for j in indices
                if j != i
            ]

            if not ConstraintEngine.check_support_area(
                    p, others, obstacles=vehicle.obstacles,
                    required_ratio=ConstraintEngine.support_ratio_for(
                        packages_map.get(str(p.package_id))),
            ).passed:
                return False

            return ConstraintEngine.check_stability(
                p, others, obstacles=vehicle.obstacles
            ).passed

        def stable_remaining(indices: frozenset[int]) -> bool:
            """
            Những kiện còn trong xe phải tiếp tục
            thỏa HC-05 và HC-06.
            """
            for i in indices:
                if expired():
                    return False
                if not stable_one(i, indices):
                    return False

            return True

        # Kiện k có thể được kiện i đỡ trực tiếp: mặt trên của i ở cao độ đáy k và hình chiếu XY chạm nhau. Lấy rộng
        # (dung sai 10 × EPSILON, chạm mép cũng tính) — tập rộng hơn chỉ làm kiểm lại thêm, không đổi kết quả.
        tol = 10 * cls.EPSILON
        boxes = [
            (
                float(p.x), float(p.y), float(p.z),
                float(p.x) + float(p.packed_l),
                float(p.y) + float(p.packed_w),
                float(p.z) + float(p.packed_h),
            )
            for p in placements
        ]
        dependents: List[List[int]] = [[] for _ in placements]
        if incremental:
            by_bottom = sorted(range(len(boxes)), key=lambda k: boxes[k][2])
            bottoms = [boxes[k][2] for k in by_bottom]
            for i, (ax0, ay0, _, ax1, ay1, az1) in enumerate(boxes):
                lo = bisect.bisect_left(bottoms, az1 - tol)
                hi = bisect.bisect_right(bottoms, az1 + tol)
                for k in by_bottom[lo:hi]:
                    bx0, by0, _, bx1, by1, _ = boxes[k]
                    if (
                            k != i
                            and ax0 <= bx1 + tol and bx0 <= ax1 + tol
                            and ay0 <= by1 + tol and by0 <= ay1 + tol
                    ):
                        dependents[i].append(k)

        def stable_after_removal(next_remaining: frozenset[int], removed: int) -> bool:
            """= stable_remaining(next_remaining) khi next_remaining ∪ {removed} đã ổn định (bất biến của search)."""
            if not incremental:
                return stable_remaining(next_remaining)
            for i in dependents[removed]:
                if i not in next_remaining:
                    continue
                if expired():
                    return False
                if not stable_one(i, next_remaining):
                    return False

            return True

        initial_state = frozenset(range(len(placements)))

        if not stable_remaining(initial_state):
            if expired():
                return timeout()
            return fail(
                "INVALID_UNLOAD_DATA",
                "Initial load plan has unsupported or unstable packages",
            )


        def can_extract(
                index: int,
                remaining: frozenset[int],
        ) -> bool:
            p = placements[index]

            x = float(p.x)
            y = float(p.y)
            z = float(p.z)

            l = float(p.packed_l)
            w = float(p.packed_w)
            h = float(p.packed_h)

            # Kiện ở hướng xếp hiện tại phải lọt cửa.
            # Xoay khi dỡ sẽ bổ sung ở bước sau.
            if w > door_w + cls.EPSILON:
                return False
            if h > door_h + cls.EPSILON:
                return False

            # Kiểm tra va chạm trên cả đoạn di chuyển.
            def path_clear(start, end) -> bool:
                x1, y1, z1 = start
                x2, y2, z2 = end

                # Bao hình học quét của một hộp
                # tịnh tiến theo một trục.
                min_x = min(x1, x2)
                max_x = max(x1, x2) + l

                min_y = min(y1, y2)
                max_y = max(y1, y2) + w

                min_z = min(z1, z2)
                max_z = max(z1, z2) + h

                # Fixed obstacles persist in every DFS state and block every movement segment.
                for obstacle in vehicle.obstacles:
                    if expired():
                        return False
                    if ConstraintEngine.obstacle_overlaps_bounds(obstacle,
                            (min_x, min_y, min_z), (max_x, max_y, max_z)):
                        return False

                for j in remaining:
                    if expired():
                        return False
                    if j == index:
                        continue

                    other = placements[j]

                    overlap_x = (
                            min(
                                max_x,
                                float(other.x + other.packed_l),
                            )
                            - max(min_x, float(other.x))
                    )

                    overlap_y = (
                            min(
                                max_y,
                                float(other.y + other.packed_w),
                            )
                            - max(min_y, float(other.y))
                    )

                    overlap_z = (
                            min(
                                max_z,
                                float(other.z + other.packed_h),
                            )
                            - max(min_z, float(other.z))
                    )

                    if (
                            overlap_x > cls.EPSILON
                            and overlap_y > cls.EPSILON
                            and overlap_z > cls.EPSILON
                    ):
                        return False

                return True

            # Các vị trí Y phù hợp với cửa giữa xe.
            min_door_y = door_y_min
            max_door_y = door_y_max - w

            if max_door_y < min_door_y - cls.EPSILON:
                return False

            target_y_candidates = {
                min_door_y,
                max_door_y,
                (min_door_y + max_door_y) / 2.0,
                min(max(y, min_door_y), max_door_y),
                }

            # Chỉ cho phép giữ độ cao hoặc nâng lên.
            # Chưa hỗ trợ hạ kiện bằng thiết bị.
            max_target_z = min(
                door_h - h,
                inner_h - h,
                )

            if max_target_z < z - cls.EPSILON:
                return False

            target_z_candidates = {
                z,
                max_target_z,
            }

            start = (x, y, z)

            for target_y in sorted(target_y_candidates):
                if (
                        target_y < -cls.EPSILON
                        or target_y + w > inner_w + cls.EPSILON
                ):
                    continue

                for target_z in sorted(target_z_candidates):
                    if (
                            target_z < z - cls.EPSILON
                            or target_z + h > inner_h + cls.EPSILON
                    ):
                        continue

                    aligned = (x, target_y, target_z)
                    exit_position = (
                        inner_l,
                        target_y,
                        target_z,
                    )

                    # Phương án A: dịch ngang -> nâng -> kéo ra
                    via_y = (x, target_y, z)

                    if (
                            path_clear(start, via_y)
                            and path_clear(via_y, aligned)
                            and path_clear(aligned, exit_position)
                    ):
                        return True

                    # Phương án B: nâng -> dịch ngang -> kéo ra
                    via_z = (x, y, target_z)

                    if (
                            path_clear(start, via_z)
                            and path_clear(via_z, aligned)
                            and path_clear(aligned, exit_position)
                    ):
                        return True

            return False
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

            if expired():
                return None

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
                if not stable_after_removal(next_remaining, i):
                    continue

                suffix = search(next_remaining)

                if suffix is not None:
                    return (i,) + suffix

                if search_limit_reached or expired():
                    return None

            impossible_states.add(remaining)
            return None

        sequence = search(initial_state)

        if expired():
            return timeout()

        if sequence is None:
            if expired():
                return timeout()
            if search_limit_reached:
                return fail(
                    "UNLOAD_SEARCH_LIMIT_REACHED",
                    (
                        "Unable to determine unloading feasibility "
                        "within the configured search limit"
                    ),
                )

            return fail(
                # H12: luật mới ghi mã mới; mã cũ là bí danh (violation_codes.canonical).
                LIFO_BLOCKED if ConstraintEngine.physics_v2() else "MULTI_DROP_LIFO_VIOLATION",
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
