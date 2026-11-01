from __future__ import annotations
import math
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
        """
        HC-08: Fragility Constraint.

        Kiện fragile không được nhận tải trọng
        từ bất kỳ kiện nào phía trên, kể cả
        truyền tải gián tiếp qua nhiều tầng.

        Mô hình: quan hệ tiếp xúc trực tiếp theo
        mặt phẳng XY và chiều cao Z.
        """
        import math

        all_placements = list(existing_placements) + [placement]

        def fail(detail: str) -> ConstraintResult:
            return ConstraintResult.fail(
                violation_code="FRAGILITY_VIOLATION",
                detail=detail,
            )

        # Kiểm tra dữ liệu bắt buộc.
        for p in all_placements:
            if str(p.package_id) not in packages:
                return fail(
                    f"Missing package metadata: {p.package_id}"
                )

            values = (
                p.x, p.y, p.z,
                p.packed_l, p.packed_w, p.packed_h,
            )

            if not all(math.isfinite(float(v)) for v in values):
                return fail(
                    f"Invalid geometry for package {p.package_id}"
                )

            if (
                    p.packed_l <= 0
                    or p.packed_w <= 0
                    or p.packed_h <= 0
            ):
                return fail(
                    f"Invalid dimensions for package {p.package_id}"
                )

        # Đồ thị: top -> những kiện đỡ trực tiếp bên dưới.
        supporters = {
            i: [] for i in range(len(all_placements))
        }

        for top_idx, top in enumerate(all_placements):
            for bottom_idx, bottom in enumerate(all_placements):
                if top_idx == bottom_idx:
                    continue

                bottom_top_z = (
                        float(bottom.z) + float(bottom.packed_h)
                )

                if abs(bottom_top_z - float(top.z)) > cls.EPSILON:
                    continue

                overlap_x = min(
                    float(top.x + top.packed_l),
                    float(bottom.x + bottom.packed_l),
                ) - max(float(top.x), float(bottom.x))

                overlap_y = min(
                    float(top.y + top.packed_w),
                    float(bottom.y + bottom.packed_w),
                ) - max(float(top.y), float(bottom.y))

                # Chỉ có tiếp xúc mặt đủ diện tích mới
                # tạo thành quan hệ truyền tải.
                if overlap_x > cls.EPSILON and overlap_y > cls.EPSILON:
                    supporters[top_idx].append(bottom_idx)

        # Mỗi kiện phía trên truyền tải xuống tất cả
        # các kiện hỗ trợ trực tiếp và gián tiếp.
        for top_idx, top in enumerate(all_placements):
            visited = set()
            stack = list(supporters[top_idx])

            while stack:
                bottom_idx = stack.pop()

                if bottom_idx in visited:
                    continue

                visited.add(bottom_idx)

                bottom = all_placements[bottom_idx]
                bottom_pkg = packages[str(bottom.package_id)]

                if bottom_pkg.fragile is True:
                    return fail(
                        f"Package {top.package_id} transfers load "
                        f"onto fragile package {bottom.package_id}"
                    )

                stack.extend(supporters[bottom_idx])

        return ConstraintResult.success()



    @classmethod
    def check_stacking(
            cls,
            placement: PlacementData,
            existing_placements: List[PlacementData],
            packages: Dict[str, PackageData],
    ) -> ConstraintResult:
        """
        HC-07: Stacking Load.

        Truyền tải trọng từ trên xuống dưới qua
        các mặt tiếp xúc trực tiếp.

        Nếu một kiện có nhiều kiện đỡ, phân phối
        tải theo tỷ lệ diện tích tiếp xúc.

        Đơn vị: cm, kg.
        """
        import math

        def fail(code: str, detail: str) -> ConstraintResult:
            return ConstraintResult.fail(
                violation_code=code,
                detail=detail,
            )

        all_placements = list(existing_placements) + [placement]

        if not all_placements:
            return ConstraintResult.success()

        # 1. Kiểm tra dữ liệu và lấy trọng lượng.
        weights = []

        for p in all_placements:
            pkg = packages.get(str(p.package_id))

            if pkg is None:
                return fail(
                    "INVALID_STACKING_DATA",
                    f"Missing package data: {p.package_id}",
                )

            values = (
                p.x, p.y, p.z,
                p.packed_l, p.packed_w, p.packed_h,
                pkg.weight,
            )

            if not all(math.isfinite(float(v)) for v in values):
                return fail(
                    "INVALID_STACKING_DATA",
                    f"Invalid geometry or weight: {p.package_id}",
                )

            if (
                    p.packed_l <= 0
                    or p.packed_w <= 0
                    or p.packed_h <= 0
                    or pkg.weight <= 0
            ):
                return fail(
                    "INVALID_STACKING_DATA",
                    f"Invalid dimensions or weight: {p.package_id}",
                )

            weights.append(float(pkg.weight))

        # 2. Tải nhận từ phía trên.
        received_load = [0.0] * len(all_placements)

        # Duyệt từ kiện cao nhất xuống thấp nhất.
        sorted_indices = sorted(
            range(len(all_placements)),
            key=lambda i: float(all_placements[i].z),
            reverse=True,
        )

        for top_idx in sorted_indices:
            top = all_placements[top_idx]

            # Tổng tải kiện này truyền xuống:
            # trọng lượng bản thân + tải nhận từ phía trên.
            transmitted_load = (
                    weights[top_idx] + received_load[top_idx]
            )

            if top.z <= cls.EPSILON:
                continue

            # Tìm các kiện đỡ trực tiếp.
            supports = []

            for bottom_idx, bottom in enumerate(all_placements):
                if top_idx == bottom_idx:
                    continue

                bottom_top_z = (
                        float(bottom.z) + float(bottom.packed_h)
                )

                if abs(bottom_top_z - float(top.z)) > cls.EPSILON:
                    continue

                overlap_x = max(
                    0.0,
                    min(
                        float(top.x + top.packed_l),
                        float(bottom.x + bottom.packed_l),
                    )
                    - max(float(top.x), float(bottom.x)),
                    )

                overlap_y = max(
                    0.0,
                    min(
                        float(top.y + top.packed_w),
                        float(bottom.y + bottom.packed_w),
                    )
                    - max(float(top.y), float(bottom.y)),
                    )

                area = overlap_x * overlap_y

                if area > cls.EPSILON:
                    supports.append((bottom_idx, area))

            if not supports:
                return fail(
                    "INVALID_STACKING_DATA",
                    (
                        f"Package {top.package_id} "
                        "has no direct supporting package"
                    ),
                )

            total_contact_area = sum(
                area for _, area in supports
            )

            # 3. Phân phối tải theo diện tích tiếp xúc.
            for bottom_idx, area in supports:
                load_share = (
                        transmitted_load * area / total_contact_area
                )
                received_load[bottom_idx] += load_share

        # 4. Kiểm tra tải chồng trên từng kiện.
        for idx, p in enumerate(all_placements):
            pkg = packages[str(p.package_id)]

            limit_value = pkg.max_stack_weight_kg

            # HC-07 — Phương án A:
            # NULL = chưa khai báo khả năng chịu tải.
            # Không cho phép bất kỳ tải trọng nào
            # đặt lên kiện này.
            if limit_value is None:
                if received_load[idx] > cls.EPSILON:
                    return fail(
                        "STACKING_WEIGHT_EXCEEDED",
                        (
                            f"Package {p.package_id} has no declared "
                            "stacking capacity but receives "
                            f"{received_load[idx]:.3f} kg"
                        ),
                    )

                # Không có kiện nào truyền tải xuống
                # thì không vi phạm HC-07.
                continue

            limit = float(limit_value)


            if not math.isfinite(limit) or limit < 0:
                return fail(
                    "INVALID_STACKING_DATA",
                    (
                        f"Invalid stack weight limit "
                        f"for package {p.package_id}"
                    ),
                )

            if received_load[idx] > limit + cls.EPSILON:
                return fail(
                    "STACKING_WEIGHT_EXCEEDED",
                    (
                        f"Package {p.package_id} receives "
                        f"{received_load[idx]:.3f} kg, "
                        f"exceeding stack limit {limit:.3f} kg"
                    ),
                )

        return ConstraintResult.success()



    @classmethod
    def check_support_area(
            cls,
            placement: PlacementData,
            existing_placements: List[PlacementData],
    ) -> ConstraintResult:
        """
        HC-05: Support Area >= 70%.

        Kiện trên sàn được xem là nâng đỡ 100%.
        Kiện nằm trên cao phải có >= 70% diện tích đáy
        được nâng đỡ bởi các kiện tiếp xúc trực tiếp.

        Đơn vị tọa độ và diện tích: cm, cm².
        """
        import math

        x = float(placement.x)
        y = float(placement.y)
        z = float(placement.z)
        l = float(placement.packed_l)
        w = float(placement.packed_w)

        if not all(math.isfinite(v) for v in (x, y, z, l, w)):
            return ConstraintResult.fail(
                violation_code="INSUFFICIENT_SUPPORT_AREA",
                detail="Invalid or non-finite package geometry",
            )

        if l <= 0 or w <= 0 or z < -cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="INSUFFICIENT_SUPPORT_AREA",
                detail="Invalid package base dimensions or height",
            )

        # Kiện nằm trên sàn: nâng đỡ toàn bộ.
        if z <= cls.EPSILON:
            return ConstraintResult.success()

        base_area = l * w

        # Các hình chữ nhật tiếp xúc trực tiếp với đáy kiện.
        rectangles = []

        for p in existing_placements:
            values = (
                p.x, p.y, p.z,
                p.packed_l, p.packed_w, p.packed_h,
            )

            if not all(math.isfinite(float(v)) for v in values):
                return ConstraintResult.fail(
                    violation_code="INSUFFICIENT_SUPPORT_AREA",
                    detail="Invalid geometry of supporting package",
                )

            if (
                    p.packed_l <= 0
                    or p.packed_w <= 0
                    or p.packed_h <= 0
            ):
                return ConstraintResult.fail(
                    violation_code="INSUFFICIENT_SUPPORT_AREA",
                    detail="Invalid dimensions of supporting package",
                )

            top_z = float(p.z) + float(p.packed_h)

            # Chỉ kiện có mặt trên chạm đáy mới hỗ trợ.
            if abs(top_z - z) > cls.EPSILON:
                continue

            # Giao hình chữ nhật trên mặt phẳng XY.
            x1 = max(x, float(p.x))
            x2 = min(x + l, float(p.x + p.packed_l))

            y1 = max(y, float(p.y))
            y2 = min(y + w, float(p.y + p.packed_w))

            if x2 > x1 and y2 > y1:
                rectangles.append((x1, x2, y1, y2))

        if not rectangles:
            return ConstraintResult.fail(
                violation_code="INSUFFICIENT_SUPPORT_AREA",
                detail=(
                    f"Package {placement.package_id} "
                    "has no supporting surface"
                ),
            )

        # Tính diện tích hợp của các hình chữ nhật
        # bằng phương pháp chia dải theo trục X.
        x_edges = sorted({
            edge
            for x1, x2, _, _ in rectangles
            for edge in (x1, x2)
        })

        support_area = 0.0

        for i in range(len(x_edges) - 1):
            left = x_edges[i]
            right = x_edges[i + 1]

            if right <= left:
                continue

            # Các khoảng Y được phủ trong dải X này.
            y_intervals = [
                (y1, y2)
                for x1, x2, y1, y2 in rectangles
                if x1 < right and x2 > left
            ]

            if not y_intervals:
                continue

            y_intervals.sort()

            covered_y = 0.0
            current_start, current_end = y_intervals[0]

            for start, end in y_intervals[1:]:
                if start <= current_end:
                    current_end = max(current_end, end)
                else:
                    covered_y += current_end - current_start
                    current_start, current_end = start, end

            covered_y += current_end - current_start

            support_area += (right - left) * covered_y

        ratio = min(1.0, support_area / base_area)

        # Dung sai riêng cho tỷ lệ, tránh dùng EPSILON cm
        # trực tiếp cho một đại lượng không có đơn vị.
        ratio_epsilon = 1e-9

        if ratio < 0.70 - ratio_epsilon:
            return ConstraintResult.fail(
                violation_code="INSUFFICIENT_SUPPORT_AREA",
                detail=(
                    f"Package {placement.package_id} "
                    f"support ratio {ratio:.4%} "
                    "is below required 70%"
                ),
            )

        return ConstraintResult.success()



    @classmethod
    def check_stability(
            cls,
            placement: PlacementData,
            existing_placements: List[PlacementData],
    ) -> ConstraintResult:
        """
        HC-06: Static Geometric Stability.

        Hình chiếu trọng tâm của kiện phải nằm trong
        bao lồi của các vùng tiếp xúc trực tiếp bên dưới.

        Giả định kiện có khối lượng phân bố đều.
        Đơn vị: cm.
        """
        import math

        def fail(detail: str) -> ConstraintResult:
            return ConstraintResult.fail(
                violation_code="STABILITY_VIOLATION",
                detail=detail,
            )

        values = (
            placement.x,
            placement.y,
            placement.z,
            placement.packed_l,
            placement.packed_w,
            placement.packed_h,
        )

        if not all(math.isfinite(float(v)) for v in values):
            return fail("Invalid or non-finite package geometry")

        x = float(placement.x)
        y = float(placement.y)
        z = float(placement.z)
        l = float(placement.packed_l)
        w = float(placement.packed_w)
        h = float(placement.packed_h)

        if l <= 0 or w <= 0 or h <= 0 or z < -cls.EPSILON:
            return fail("Invalid package dimensions or Z coordinate")

        # Nằm trực tiếp trên sàn xe.
        if z <= cls.EPSILON:
            return ConstraintResult.success()

        # Tâm hình chiếu của kiện trên mặt phẳng XY.
        center_x = x + l / 2.0
        center_y = y + w / 2.0

        # Tập đỉnh của các vùng tiếp xúc bên dưới.
        contact_points = []

        for p in existing_placements:
            support_values = (
                p.x, p.y, p.z,
                p.packed_l, p.packed_w, p.packed_h,
            )

            if not all(
                    math.isfinite(float(v))
                    for v in support_values
            ):
                return fail("Invalid supporting package geometry")

            if (
                    p.packed_l <= 0
                    or p.packed_w <= 0
                    or p.packed_h <= 0
            ):
                return fail("Invalid supporting package dimensions")

            top_z = float(p.z) + float(p.packed_h)

            # Chỉ xét mặt đỡ trực tiếp.
            if abs(top_z - z) > cls.EPSILON:
                continue

            x1 = max(x, float(p.x))
            x2 = min(x + l, float(p.x + p.packed_l))
            y1 = max(y, float(p.y))
            y2 = min(y + w, float(p.y + p.packed_w))

            if x2 <= x1 or y2 <= y1:
                continue

            contact_points.extend([
                (x1, y1),
                (x1, y2),
                (x2, y1),
                (x2, y2),
            ])

        if not contact_points:
            return fail(
                f"Package {placement.package_id} "
                "has no direct supporting surface"
            )

        # Dựng convex hull bằng thuật toán monotonic chain.
        points = sorted(set(contact_points))

        def cross(o, a, b):
            return (
                    (a[0] - o[0]) * (b[1] - o[1])
                    - (a[1] - o[1]) * (b[0] - o[0])
            )

        lower = []
        for point in points:
            while (
                    len(lower) >= 2
                    and cross(lower[-2], lower[-1], point) <= 0
            ):
                lower.pop()
            lower.append(point)

        upper = []
        for point in reversed(points):
            while (
                    len(upper) >= 2
                    and cross(upper[-2], upper[-1], point) <= 0
            ):
                upper.pop()
            upper.append(point)

        hull = lower[:-1] + upper[:-1]

        if len(hull) < 3:
            return fail("Supporting area is geometrically degenerate")

        # Convex hull được dựng ngược chiều kim đồng hồ.
        # Tâm phải nằm trong hoặc trên tất cả các cạnh.
        for i in range(len(hull)):
            a = hull[i]
            b = hull[(i + 1) % len(hull)]

            signed_area = cross(
                a,
                b,
                (center_x, center_y),
            )

            if signed_area < -cls.EPSILON:
                return fail(
                    f"Package {placement.package_id} "
                    "center of gravity lies outside "
                    "the supporting convex hull"
                )

        return ConstraintResult.success()


    @classmethod
    def check_rotation_allowed(
            cls,
            package: PackageData,
            placement: Optional[PlacementData] = None,
    ) -> ConstraintResult:
        """
        HC-03: Orientation Constraint.

        Hướng xoay của placement phải thuộc danh sách
        allowed_rotations của kiện.

        Quy ước mã orientation phải thống nhất giữa
        Backend, Optimizer và 3D Viewer.
        """
        if placement is None:
            return ConstraintResult.fail(
                violation_code="ORIENTATION_NOT_ALLOWED",
                detail="Placement is required to validate orientation",
            )

        rotation_type = str(placement.rotation_type)

        # Không cho phép xoay nếu kiện bị khóa xoay.
        if package.rotation_allowed is False:
            if rotation_type != "0":
                return ConstraintResult.fail(
                    violation_code="ORIENTATION_NOT_ALLOWED",
                    detail=(
                        f"Package {package.id} does not allow "
                        f"rotation {rotation_type}"
                    ),
                )

        allowed_rotations = package.allowed_rotations

        # Nếu có danh sách hướng xoay cụ thể,
        # hướng được chọn bắt buộc nằm trong danh sách.
        if allowed_rotations:
            allowed = {
                str(rotation)
                for rotation in allowed_rotations
            }

            if rotation_type not in allowed:
                return ConstraintResult.fail(
                    violation_code="ORIENTATION_NOT_ALLOWED",
                    detail=(
                        f"Package {package.id}: orientation "
                        f"{rotation_type} is not permitted. "
                        f"Allowed orientations: {sorted(allowed)}"
                    ),
                )

        return ConstraintResult.success()



    @classmethod
    def check_cog(
        cls,
        all_placements: List[PlacementData],
        vehicle: VehicleData,
        packages_map: Optional[Dict[str, PackageData]] = None,
    ) -> ConstraintResult:
        """
        HC-11: Center of Gravity.

        Kiểm tra trọng tâm hàng hóa theo X/Y.
        Ngưỡng thử nghiệm: tối đa 15% kích thước thùng xe
        tính từ tâm hình học mỗi trục.

        Trọng tâm Z được tính nhưng chưa áp giới hạn.
        """

        if not all_placements:
            return ConstraintResult.success()

        # 1. Bắt buộc có dữ liệu kiện hàng.
        if packages_map is None:
            return ConstraintResult.fail(
                violation_code="COG_PACKAGE_DATA_MISSING",
                detail="HC-11: packages_map is required",
            )

    # 2. Kiểm tra kích thước thùng xe.
        length = float(vehicle.inner_l)
        width = float(vehicle.inner_w)

        if length <= 0 or width <= 0:
            return ConstraintResult.fail(
                violation_code="COG_CONFIG_INVALID",
                detail="HC-11: Vehicle length and width must be positive",
            )

        max_ratio = float(vehicle.max_cog_offset_ratio)

        total_mass = 0.0
        weighted_x = 0.0
        weighted_y = 0.0
        weighted_z = 0.0

        # 3. Tính trọng tâm dựa trên khối lượng thực tế.
        for placement in all_placements:
            package = packages_map.get(str(placement.package_id))

            if package is None:
                return ConstraintResult.fail(
                    violation_code="COG_PACKAGE_DATA_MISSING",
                    detail=(
                        f"HC-11: Package {placement.package_id} not found"
                    ),
                )

            mass = float(package.weight)

            if not math.isfinite(mass) or mass <= 0:
                return ConstraintResult.fail(
                    violation_code="COG_INVALID_WEIGHT",
                    detail=(
                        f"HC-11: Invalid weight for package "
                        f"{placement.package_id}: {mass}"
                    ),
                )

            center_x = float(placement.x) + float(placement.packed_l) / 2.0
            center_y = float(placement.y) + float(placement.packed_w) / 2.0
            center_z = float(placement.z) + float(placement.packed_h) / 2.0

            total_mass += mass
            weighted_x += mass * center_x
            weighted_y += mass * center_y
            weighted_z += mass * center_z

        cog_x = weighted_x / total_mass
        cog_y = weighted_y / total_mass
        cog_z = weighted_z / total_mass

        # 4. Kiểm tra độ lệch X/Y so với tâm thùng xe.
        center_x = length / 2.0
        center_y = width / 2.0

        offset_ratio_x = abs(cog_x - center_x) / length
        offset_ratio_y = abs(cog_y - center_y) / width

        if (
                offset_ratio_x > max_ratio + cls.EPSILON
                or offset_ratio_y > max_ratio + cls.EPSILON
        ):
            return ConstraintResult.fail(
                violation_code="CENTER_OF_GRAVITY_OUT_OF_RANGE",
                detail=(
                    f"HC-11: COG ({cog_x:.2f}, {cog_y:.2f}, "
                    f"{cog_z:.2f}) exceeds the X/Y "
                    f"offset limit of {max_ratio:.0%}. "
                    f"X offset: {offset_ratio_x:.2%}, "
                    f"Y offset: {offset_ratio_y:.2%}"
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
        """
        HC-12: Axle Load.

        Mô hình cân bằng tĩnh xe hai trục.
        Đơn vị tọa độ: cm.
        Đơn vị tải trọng: kg.
        """

        required_fields = (
            "front_axle_x",
            "rear_axle_x",
            "front_axle_limit_kg",
            "rear_axle_limit_kg",
            "empty_front_axle_load_kg",
            "empty_rear_axle_load_kg",
        )

        # 1. Kiểm tra đầy đủ cấu hình xe.
        for field_name in required_fields:
            value = getattr(vehicle, field_name, None)

            if value is None:
                return ConstraintResult.fail(
                    violation_code="AXLE_CONFIG_MISSING",
                    detail=f"HC-12: Missing vehicle field {field_name}",
                )

            if not math.isfinite(float(value)):
                return ConstraintResult.fail(
                    violation_code="AXLE_CONFIG_INVALID",
                    detail=f"HC-12: Invalid vehicle field {field_name}",
                )

        front_x = float(vehicle.front_axle_x)
        rear_x = float(vehicle.rear_axle_x)

        front_limit = float(vehicle.front_axle_limit_kg)
        rear_limit = float(vehicle.rear_axle_limit_kg)

        empty_front = float(vehicle.empty_front_axle_load_kg)
        empty_rear = float(vehicle.empty_rear_axle_load_kg)

        # 2. Kiểm tra cấu hình hợp lệ.
        wheelbase = rear_x - front_x

        if (
            wheelbase <= 0
            or front_limit <= 0
            or rear_limit <= 0
            or empty_front < 0
            or empty_rear < 0
        ):
            return ConstraintResult.fail(
                violation_code="AXLE_CONFIG_INVALID",
                detail="HC-12: Invalid axle positions, limits or empty loads",
            )

        # 3. Bắt buộc có dữ liệu khối lượng hàng.
        if all_placements and packages_map is None:
            return ConstraintResult.fail(
                violation_code="AXLE_PACKAGE_DATA_MISSING",
                detail="HC-12: packages_map is required",
            )

        total_cargo_weight = 0.0
        cargo_moment = 0.0

        for placement in all_placements:
            package = packages_map.get(str(placement.package_id))

            if package is None:
                return ConstraintResult.fail(
                    violation_code="AXLE_PACKAGE_DATA_MISSING",
                    detail=(
                        f"HC-12: Package {placement.package_id} not found"
                    ),
                )

            mass = float(package.weight)

            if not math.isfinite(mass) or mass <= 0:
                return ConstraintResult.fail(
                    violation_code="AXLE_INVALID_WEIGHT",
                    detail=(
                        f"HC-12: Invalid weight for package "
                        f"{placement.package_id}: {mass}"
                    ),
                )

            cargo_center_x = (
                float(placement.x)
                + float(placement.packed_l) / 2.0
            )

            if not math.isfinite(cargo_center_x):
                return ConstraintResult.fail(
                    violation_code="AXLE_CONFIG_INVALID",
                    detail="HC-12: Invalid cargo center coordinate",
                )

            total_cargo_weight += mass
            cargo_moment += mass * (cargo_center_x - front_x)

        # 4. Phân bố khối lượng hàng lên hai trục.
        rear_cargo_load = cargo_moment / wheelbase
        front_cargo_load = total_cargo_weight - rear_cargo_load

        # 5. Cộng tải trục xe rỗng.
        front_total = empty_front + front_cargo_load
        rear_total = empty_rear + rear_cargo_load

        # 6. Kiểm tra giới hạn tải trục.
        if front_total > front_limit + cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="AXLE_LOAD_EXCEEDED",
                detail=(
                    f"HC-12: Front axle load {front_total:.2f} kg "
                    f"exceeds limit {front_limit:.2f} kg"
                ),
            )

        if rear_total > rear_limit + cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="AXLE_LOAD_EXCEEDED",
                detail=(
                    f"HC-12: Rear axle load {rear_total:.2f} kg "
                    f"exceeds limit {rear_limit:.2f} kg"
                ),
            )

        # 7. Phản lực âm biểu thị cấu hình có vấn đề
        # hoặc xe có nguy cơ mất tiếp xúc tại một trục.
        if (
            front_total < -cls.EPSILON
            or rear_total < -cls.EPSILON
        ):
            return ConstraintResult.fail(
                violation_code="AXLE_LOAD_INVALID",
                detail=(
                    f"HC-12: Negative axle reaction. "
                    f"Front={front_total:.2f} kg, "
                    f"Rear={rear_total:.2f} kg"
                ),
            )

        return ConstraintResult.success()



    @classmethod
    def check_non_overlap(
            cls,
            placement: PlacementData,
            existing_placements: List[PlacementData],
    ) -> ConstraintResult:
        """
        HC-02: Non-overlap.

        Không cho phép hai kiện chiếm cùng thể tích 3D.
        Cho phép tiếp xúc mặt, cạnh và góc.

        Đơn vị: cm.
        """
        import math

        x1 = float(placement.x)
        y1 = float(placement.y)
        z1 = float(placement.z)

        l1 = float(placement.packed_l)
        w1 = float(placement.packed_w)
        h1 = float(placement.packed_h)

        for existing in existing_placements:
            x2 = float(existing.x)
            y2 = float(existing.y)
            z2 = float(existing.z)

            l2 = float(existing.packed_l)
            w2 = float(existing.packed_w)
            h2 = float(existing.packed_h)

            values = (
                x1, y1, z1, l1, w1, h1,
                x2, y2, z2, l2, w2, h2,
            )

            if not all(math.isfinite(v) for v in values):
                return ConstraintResult.fail(
                    violation_code="NON_OVERLAP_VIOLATION",
                    detail="Invalid or non-finite placement geometry",
                )

            if any(v <= 0 for v in (l1, w1, h1, l2, w2, h2)):
                return ConstraintResult.fail(
                    violation_code="NON_OVERLAP_VIOLATION",
                    detail="Package dimensions must be positive",
                )

            # Hai hộp tách rời nếu không giao nhau
            # trên ít nhất một trong ba trục.
            separated = (
                    x1 + l1 <= x2 + cls.EPSILON
                    or x2 + l2 <= x1 + cls.EPSILON
                    or y1 + w1 <= y2 + cls.EPSILON
                    or y2 + w2 <= y1 + cls.EPSILON
                    or z1 + h1 <= z2 + cls.EPSILON
                    or z2 + h2 <= z1 + cls.EPSILON
            )

            if not separated:
                return ConstraintResult.fail(
                    violation_code="NON_OVERLAP_VIOLATION",
                    detail=(
                        f"Package {placement.package_id} "
                        f"overlaps package {existing.package_id}"
                    ),
                )

        return ConstraintResult.success()



    @classmethod
    def check_payload(
            cls,
            placement: PlacementData,
            existing_placements: List[PlacementData],
            vehicle: VehicleData,
            packages_map: Dict[str, PackageData],
    ) -> ConstraintResult:
        """
        HC-04: Payload Limit.

        Tổng khối lượng của các kiện đã xếp
        và kiện đang xét không vượt max_payload_kg.

        Đơn vị: kg.
        """
        import math

        payload_limit = float(vehicle.max_payload_kg)

        if not math.isfinite(payload_limit) or payload_limit <= 0:
            return ConstraintResult.fail(
                violation_code="INVALID_PAYLOAD_DATA",
                detail="Vehicle maximum payload must be positive and finite",
            )

        total_weight = 0.0
        all_placements = existing_placements + [placement]

        for item in all_placements:
            package = packages_map.get(str(item.package_id))

            if package is None:
                return ConstraintResult.fail(
                    violation_code="INVALID_PAYLOAD_DATA",
                    detail=(
                        f"Missing package data for "
                        f"package {item.package_id}"
                    ),
                )

            weight = float(package.weight)

            if not math.isfinite(weight) or weight <= 0:
                return ConstraintResult.fail(
                    violation_code="INVALID_PAYLOAD_DATA",
                    detail=(
                        f"Package {item.package_id} "
                        "has invalid weight"
                    ),
                )

            total_weight += weight

        if total_weight > payload_limit + cls.EPSILON:
            return ConstraintResult.fail(
                violation_code="PAYLOAD_EXCEEDED",
                detail=(
                    f"Total cargo weight {total_weight:.3f} kg "
                    f"exceeds vehicle payload "
                    f"{payload_limit:.3f} kg"
                ),
            )

        return ConstraintResult.success()

    @classmethod
    def check_boundary(
            cls,
            placement: PlacementData,
            vehicle: VehicleData,
    ) -> ConstraintResult:
        """
        HC-01: Vehicle Boundary.

        Kiểm tra kiện hàng nằm hoàn toàn trong
        lòng thùng xe.

        Đơn vị kích thước và tọa độ: cm.
        """
        import math

        values = [
            placement.x,
            placement.y,
            placement.z,
            placement.packed_l,
            placement.packed_w,
            placement.packed_h,
            vehicle.inner_l,
            vehicle.inner_w,
            vehicle.inner_h,
        ]

        if not all(math.isfinite(float(v)) for v in values):
            return ConstraintResult.fail(
                violation_code="BOUNDARY_VIOLATION",
                detail="Invalid or non-finite placement dimensions",
            )

        x = float(placement.x)
        y = float(placement.y)
        z = float(placement.z)

        l = float(placement.packed_l)
        w = float(placement.packed_w)
        h = float(placement.packed_h)

        L = float(vehicle.inner_l)
        W = float(vehicle.inner_w)
        H = float(vehicle.inner_h)

        # Kích thước phải hợp lệ
        if (
                l <= 0 or w <= 0 or h <= 0
                or L <= 0 or W <= 0 or H <= 0
        ):
            return ConstraintResult.fail(
                violation_code="BOUNDARY_VIOLATION",
                detail="Vehicle or package dimensions must be positive",
            )

        # Tọa độ không được âm
        if (
                x < -cls.EPSILON
                or y < -cls.EPSILON
                or z < -cls.EPSILON
        ):
            return ConstraintResult.fail(
                violation_code="BOUNDARY_VIOLATION",
                detail="Package coordinates cannot be negative",
            )

        # Kiện không được vượt kích thước thùng xe
        if (
                x + l > L + cls.EPSILON
                or y + w > W + cls.EPSILON
                or z + h > H + cls.EPSILON
        ):
            return ConstraintResult.fail(
                violation_code="BOUNDARY_VIOLATION",
                detail=(
                    f"Package {placement.package_id} "
                    "exceeds vehicle cargo boundaries"
                ),
            )

        return ConstraintResult.success()


    @classmethod
    def check_door(
            cls,
            placement: PlacementData,
            vehicle: VehicleData,
    ) -> ConstraintResult:
        """
        HC-10: Door Clearance.

        Kiểm tra kích thước kiện sau xoay có lọt
        qua cửa khoang hàng hay không.

        Đơn vị: cm.
        """
        door_w = vehicle.door_w
        door_h = vehicle.door_h

        # Không bỏ qua HC-10 nếu thiếu thông số cửa.
        if (
                door_w is None
                or door_h is None
                or door_w <= 0
                or door_h <= 0
        ):
            return ConstraintResult.fail(
                violation_code="PACKAGE_CANNOT_PASS_DOOR",
                detail="Missing or invalid cargo door dimensions",
            )

        if (
                placement.packed_w > door_w + cls.EPSILON
                or placement.packed_h > door_h + cls.EPSILON
        ):
            return ConstraintResult.fail(
                violation_code="PACKAGE_CANNOT_PASS_DOOR",
                detail=(
                    f"Package {placement.package_id} "
                    f"dimensions {placement.packed_w} x "
                    f"{placement.packed_h} cm exceed "
                    f"door {door_w} x {door_h} cm"
                ),
            )

        return ConstraintResult.success()



    @classmethod
    def check_multi_drop_lifo(
            cls,
            placement: PlacementData,
            existing_placements: List[PlacementData],
            packages_map: Dict[str, PackageData],
    ) -> ConstraintResult:
        """
        HC-09: Multi-drop LIFO.

        X tăng dần về phía cửa sau.
        stop_index nhỏ hơn = giao sớm hơn.

        Kiểm tra:
        1. Kiện giao sau không chặn lối ra của kiện giao trước.
        2. Kiện giao sau không được đè lên kiện giao trước.

        Mô hình tiếp cận hình học, không mô phỏng
        đường di chuyển vật lý của kiện.
        """
        import math

        def fail(detail: str) -> ConstraintResult:
            return ConstraintResult.fail(
                violation_code="MULTI_DROP_LIFO_VIOLATION",
                detail=detail,
            )

        all_placements = list(existing_placements) + [placement]

        stop_sequences = []

        for p in all_placements:
            pkg = packages_map.get(str(p.package_id))

            if pkg is None or pkg.stop_index is None:
                return fail(
                    f"Missing delivery stop information "
                    f"for package {p.package_id}"
                )

            try:
                stop_seq = int(pkg.stop_index)
            except (TypeError, ValueError, OverflowError):
                return fail(
                    f"Invalid stop_index for package {p.package_id}"
                )

            if stop_seq < 1 or str(pkg.stop_index) != str(stop_seq):
                return fail(
                    f"Invalid stop_index for package {p.package_id}"
                )

            values = (
                p.x, p.y, p.z,
                p.packed_l, p.packed_w, p.packed_h,
            )

            if not all(math.isfinite(float(v)) for v in values):
                return fail(
                    f"Invalid geometry for package {p.package_id}"
                )

            if (
                    p.packed_l <= 0
                    or p.packed_w <= 0
                    or p.packed_h <= 0
            ):
                return fail(
                    f"Invalid dimensions for package {p.package_id}"
                )

            stop_sequences.append(stop_seq)

        # So sánh các cặp kiện thuộc những stop khác nhau.
        for i in range(len(all_placements)):
            a = all_placements[i]

            for j in range(i + 1, len(all_placements)):
                b = all_placements[j]

                if stop_sequences[i] == stop_sequences[j]:
                    continue

                if stop_sequences[i] < stop_sequences[j]:
                    early = a
                    late = b
                else:
                    early = b
                    late = a

                # A. Kiểm tra vùng giao trên mặt phẳng YZ.
                overlap_y = (
                        min(
                            float(early.y + early.packed_w),
                            float(late.y + late.packed_w),
                        )
                        - max(float(early.y), float(late.y))
                )

                overlap_z = (
                        min(
                            float(early.z + early.packed_h),
                            float(late.z + late.packed_h),
                        )
                        - max(float(early.z), float(late.z))
                )

                # Kiện giao sau chắn đường ra cửa của kiện
                # giao trước nếu cùng hành lang YZ và ở phía cửa.
                if (
                        overlap_y > cls.EPSILON
                        and overlap_z > cls.EPSILON
                        and float(late.x) >= (
                        float(early.x + early.packed_l)
                        - cls.EPSILON
                )
                ):
                    return fail(
                        f"Package {late.package_id} "
                        "for a later stop blocks access to "
                        f"earlier-stop package {early.package_id}"
                    )

                # B. Kiện giao sau không được đặt trực tiếp
                # lên kiện giao trước.
                overlap_x = (
                        min(
                            float(early.x + early.packed_l),
                            float(late.x + late.packed_l),
                        )
                        - max(float(early.x), float(late.x))
                )

                if (
                        overlap_x > cls.EPSILON
                        and overlap_y > cls.EPSILON
                        and abs(
                    float(late.z)
                    - float(early.z + early.packed_h)
                ) <= cls.EPSILON
                ):
                    return fail(
                        f"Later-stop package {late.package_id} "
                        "is stacked on earlier-stop package "
                        f"{early.package_id}"
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
        # HC-01: Vehicle Boundary
        if vehicle is not None:
            res_boundary = cls.check_boundary(
                placement,
                vehicle,
            )

            if not res_boundary.passed:
                violations.append(res_boundary)


        # HC-02: Non-overlap
        res_overlap = cls.check_non_overlap(
            placement,
            existing_placements,
        )


        # HC-04: Vehicle Payload Limit
        if vehicle is not None and packages_map:
            res_payload = cls.check_payload(
                placement,
                existing_placements,
                vehicle,
                packages_map,
            )

            if not res_payload.passed:
                violations.append(res_payload)


        if not res_overlap.passed:
            violations.append(res_overlap)



        # HC-09: Multi-drop LIFO
        # Chỉ cần kiểm tra thứ tự dỡ khi có từ hai stop.
        if packages_map:
            res_lifo = cls.check_multi_drop_lifo(
                placement,
                existing_placements,
                packages_map,
            )

            if not res_lifo.passed:
                violations.append(res_lifo)


        # HC-10: Door Clearance
        if vehicle is not None:
            res_door = cls.check_door(
                placement,
                vehicle,
            )

            if not res_door.passed:
                violations.append(res_door)


        # 1. Rotation allowed
        if pkg:
            res_rot = cls.check_rotation_allowed(pkg, placement)
            if not res_rot.passed:
                violations.append(res_rot)

        # 2. Support area
        res_supp = cls.check_support_area(placement, existing_placements)
        if not res_supp.passed:
            violations.append(res_supp)


        # HC-06: Static Geometric Stability
        res_stability = cls.check_stability(
            placement,
            existing_placements,
        )

        if not res_stability.passed:
            violations.append(res_stability)


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
