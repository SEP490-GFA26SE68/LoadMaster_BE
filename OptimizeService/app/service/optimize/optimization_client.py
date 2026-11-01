from __future__ import annotations

import time
from typing import Optional, Any
import httpx

from app.config.settings import settings
from app.dto.optimization.engine.problem_request import ProblemRequest
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    PlacementData,
    UnplacedData,
    MetricsData,
)
from app.service.auth.keycloak_token_provider import KeycloakTokenProvider


from app.service.optimize.stop_zone_calculator import StopZoneCalculator
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.unload_feasibility_validator import (
    UnloadFeasibilityValidator,
)

class OptimizationClient:
    """
    Client tương tác với Optimization Engine.
    Hỗ trợ:
      1. In-process zone-aware 3D heuristic packing với Extreme Points (EP) và ConstraintEngine (S5b-04)
      2. External HTTP call qua httpx với Bearer token từ Keycloak
    """

    def __init__(
        self,
        token_provider: Optional[Any] = None,
        engine_url: Optional[str] = None,
        timeout_sec: Optional[int] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ):
        # self.token_provider = token_provider or KeycloakTokenProvider()
# Không khởi tạo Keycloak khi chỉ chạy solver nội bộ.
        self.token_provider = token_provider
        self.engine_url = engine_url or settings.OPTIMIZATION_ENGINE_URL
        self.timeout_sec = timeout_sec or settings.OPTIMIZATION_ENGINE_TIMEOUT_SEC
        self.http_client = http_client

    async def solve(self, problem: ProblemRequest) -> EngineOptimizationResponse:
        """
        Giải bài toán tối ưu.
        Nếu http_client được inject hoặc engine_url được cấu hình, thực hiện gọi HTTP.
        Nếu không thành công hoặc cấu hình in-process, chạy thuật toán nội bộ.
        """
        if self.http_client is not None or self.engine_url:
            return await self._solve_http(problem)
        return self.solve_in_process(problem)

    async def _solve_http(self, problem: ProblemRequest) -> EngineOptimizationResponse:
        headers = {}

# Chỉ tạo token provider khi thực sự gọi HTTP.
        if self.token_provider is None:
            self.token_provider = KeycloakTokenProvider()

        token = await self.token_provider.get_service_token()
        headers["Authorization"] = f"Bearer {token}"

        url = f"{self.engine_url.rstrip('/')}/api/v1/optimization/jobs"

        if self.http_client:
            resp = await self.http_client.post(
                url,
                json=problem.model_dump(mode="json"),
                headers=headers,
            )
            resp.raise_for_status()
            return EngineOptimizationResponse.model_validate(resp.json())
        else:
            async with httpx.AsyncClient(timeout=float(self.timeout_sec)) as client:
                resp = await client.post(
                    url,
                    json=problem.model_dump(mode="json"),
                    headers=headers,
                )
                resp.raise_for_status()
                return EngineOptimizationResponse.model_validate(resp.json())

    def solve_in_process(self, problem: ProblemRequest) -> EngineOptimizationResponse:
        """
        Nâng cấp S5b-04: Zone-aware 3D Packing Heuristic với Extreme Points (EP)
        và ConstraintEngine (Fragility, Stacking, Support Area, Rotation, COG, Axle Load).
        """
        start_time = time.time()
        vt = problem.vehicle
        placements: list[PlacementData] = []
        unplaced: list[UnplacedData] = []

        total_placed_weight = 0.0
        total_placed_volume = 0.0
        vehicle_volume = float(vt.inner_l * vt.inner_w * vt.inner_h)
        rehandling_count = 0

        # Map packages by ID
        packages_map = {str(p.id): p for p in problem.packages}

        # 1. Phân nhóm packages theo stop & tính toán stop zones
        stops_dict: dict[Any, list[PackageData]] = {}
        stop_seq_map: dict[Any, int] = {}
        if problem.stops:
            for s in problem.stops:
                stops_dict[s.id] = []
                stop_seq_map[s.id] = s.sequence

        for pkg in problem.packages:
            # Map by stop_index or stop ID if available
            matched_stop_id = None
            if problem.stops:
                for s in problem.stops:
                    if str(s.id) == str(pkg.stop_index) or s.sequence == pkg.stop_index:
                        matched_stop_id = s.id
                        break
                if matched_stop_id is None and problem.stops:
                    matched_stop_id = problem.stops[0].id
            else:
                matched_stop_id = "default-stop"

            if matched_stop_id not in stops_dict:
                stops_dict[matched_stop_id] = []
            stops_dict[matched_stop_id].append(pkg)

        # Build stops input for StopZoneCalculator
        stops_input = []
        for stop_id, pkgs in stops_dict.items():
            stops_input.append({
                "stop_id": stop_id,
                "sequence": stop_seq_map.get(stop_id, 1),
                "packages": [{"volume": p.l * p.w * p.h, "weight": p.weight} for p in pkgs],
            })

        # Tính stop zones
        zone_calc_res = StopZoneCalculator.calculate_zones(vt, stops_input)
        zones_by_id = {str(z.stop_id): z for z in zone_calc_res.zones}

        # Sắp xếp các zone từ sâu nhất (x = 0) đến gần cửa sau (x = inner_l)
        # AC: Xếp hàng của stop cuối cùng trước (sâu nhất), stop đầu tiên sau (gần cửa)
        sorted_zones = sorted(zone_calc_res.zones, key=lambda z: z.zone_start_x)

        # Context cho ConstraintEngine

        context = {
            "vehicle": vt,
            "existing_placements": placements,
            "packages_map": packages_map,

            # HC-11/HC-12 chưa hoàn thiện.
            # Không kiểm tra COG/Axle tại từng placement.
            "check_cog": False,
            "check_axle": False,
        }
        # Heuristic scoring: đánh giá COG tạm thời của các kiện đã xếp
        # cộng với candidate hiện tại.
        # Không thay thế final validation HC-11/HC-12.
        def candidate_cog_score(candidate: PlacementData) -> float:
            total_weight = 0.0
            weighted_x = 0.0
            weighted_y = 0.0

            for placed in [*placements, candidate]:
                package = packages_map[str(placed.package_id)]
                weight = float(package.weight)

                total_weight += weight

                weighted_x += weight * (
                        float(placed.x) + float(placed.packed_l) / 2.0
                )
                weighted_y += weight * (
                        float(placed.y) + float(placed.packed_w) / 2.0
                )

            if total_weight <= 0:
                return 0.0

            cog_x = weighted_x / total_weight
            cog_y = weighted_y / total_weight

            center_x = float(vt.inner_l) / 2.0
            center_y = float(vt.inner_w) / 2.0

            # Chuẩn hóa hai trục để X không áp đảo Y.
            normalized_x = (cog_x - center_x) / float(vt.inner_l)
            normalized_y = (cog_y - center_y) / float(vt.inner_w)

            return normalized_x ** 2 + normalized_y ** 2


# 2. Xếp hàng theo từng zone
        for zone in sorted_zones:
            zone_pkgs = stops_dict.get(zone.stop_id, [])
            # Sắp xếp kiện hàng trong zone: giảm dần theo thể tích
            sorted_pkgs = sorted(zone_pkgs, key=lambda p: (p.l * p.w * p.h, p.weight), reverse=True)


            # Các vị trí ứng viên ban đầu trong stop zone.
            # Giữ điểm gốc DBLF và bổ sung điểm gần tâm thùng
            # để solver có cơ hội tạo phương án cân bằng HC-11.

            zone_start = float(zone.zone_start_x)
            zone_end = float(zone.zone_end_x)
            zone_mid_x = (zone_start + zone_end) / 2.0

            vehicle_mid_y = float(vt.inner_w) / 2.0


# HC-09: Khẩu độ cửa sau theo trục Y.
# Cửa nằm giữa chiều rộng thùng xe.
            door_y_min = (
                float(vt.inner_w) - float(vt.door_w)
             ) / 2.0

            ep_list = [
                (zone_start, 0.0, 0.0),
                (zone_mid_x, 0.0, 0.0),
                (zone_start, vehicle_mid_y, 0.0),
                (zone_mid_x, vehicle_mid_y, 0.0),

                # Thêm Extreme Points nằm tại mép cửa.
                (zone_start, door_y_min, 0.0),
                (zone_mid_x, door_y_min, 0.0),
            ]


            # HC-11: Sinh thêm Extreme Points gần cuối Stop Zone.
# Candidate chỉ được sử dụng sau khi qua bounds,
# overlap và ConstraintEngine như các EP khác.
#
# Không thay đổi giới hạn zone hoặc các Hard Constraints.

            for orientation_l in {float(pkg.l), float(pkg.w)}:
                right_aligned_x = zone_end - orientation_l

                if right_aligned_x >= zone_start:
                    for candidate_y in (door_y_min, vehicle_mid_y):
                        candidate_pt = (
                            right_aligned_x,
                            candidate_y,
                            0.0,
                        )

                        if candidate_pt not in ep_list:
                            ep_list.append(candidate_pt)

            for pkg in sorted_pkgs:
                if vt.max_payload_kg > 0 and (total_placed_weight + pkg.weight > vt.max_payload_kg):
                    unplaced.append(UnplacedData(
                        package_id=pkg.id,
                        reason="WEIGHT_LIMIT_EXCEEDED",
                        violated_constraints=["WEIGHT_LIMIT_EXCEEDED"],
                    ))
                    continue


                # HC-03: Chỉ sinh orientation được PackageData cho phép.
                #
                # Quy ước hiện tại:
                # 0 = (length, width, height)
                # 1 = (width, length, height)
                #
                # Chưa mở rộng 2-5 cho đến khi thống nhất
                # quy ước xoay với 3D Viewer.

                rotation_enabled = pkg.rotation_allowed is True

                allowed_rotations = {
                    int(rotation)
                    for rotation in (pkg.allowed_rotations or [])
                }

                # Công tắc tổng luôn có độ ưu tiên cao nhất.
                if not rotation_enabled:
                    allowed_rotations = {0}

                orientation_candidates = [
                    (float(pkg.l), float(pkg.w), float(pkg.h), 0),  # LWH
                    (float(pkg.w), float(pkg.l), float(pkg.h), 1),  # WLH
                    (float(pkg.l), float(pkg.h), float(pkg.w), 2),  # LHW
                    (float(pkg.w), float(pkg.h), float(pkg.l), 3),  # WHL
                    (float(pkg.h), float(pkg.l), float(pkg.w), 4),  # HLW
                    (float(pkg.h), float(pkg.w), float(pkg.l), 5),  # HWL
                ]

                orientations = [
                    orientation
                    for orientation in orientation_candidates
                    if orientation[3] in allowed_rotations
                       and (
                               orientation[0] <= vt.inner_l
                               and orientation[1] <= vt.inner_w
                               and orientation[2] <= vt.inner_h
                       )
                ]

                if not orientations:
                    unplaced.append(
                        UnplacedData(
                            package_id=pkg.id,
                            reason="CONSTRAINT_VIOLATED",
                            violated_constraints=[
                                "ORIENTATION_NOT_ALLOWED"
                            ],
                        )
                    )
                    continue


                # Thử tìm vị trí trong zone hiện tại
                chosen_placement = None
                last_violations: list[str] = []
                best_candidate_score = float("inf")

                # Sắp xếp EP theo DBLF: x tăng dần (sâu), z tăng dần (dưới đáy), y tăng dần (trái)
                # Ưu tiên Extreme Points gần tâm xe.
# Giữ Z thấp để ưu tiên xếp hàng trên sàn.
# HC-11 vẫn được kiểm tra trên LoadPlan hoàn chỉnh.

                vehicle_center_x = float(vt.inner_l) / 2.0
                vehicle_center_y = float(vt.inner_w) / 2.0

                sorted_eps = sorted(
                    ep_list,
                    key=lambda pt: (
                        pt[2],
                        abs(pt[0] - vehicle_center_x),
                        abs(pt[1] - vehicle_center_y),
                        pt[0],
                        pt[1],
                    ),
                )

                for pt in sorted_eps:
                    px, py, pz = pt
                    for (ol, ow, oh, rot_type) in orientations:
                        # 1. Bounds check trong zone
                        if px + ol > zone.zone_end_x + 1e-4:
                            continue
                        if py + ow > vt.inner_w + 1e-4:
                            continue
                        if pz + oh > vt.inner_h + 1e-4:
                            continue

                        # 2. Overlap check với placements đã có
                        if self._check_overlap(px, py, pz, ol, ow, oh, placements):
                            continue

                        candidate = PlacementData(
                            package_id=pkg.id,
                            x=px,
                            y=py,
                            z=pz,
                            packed_l=ol,
                            packed_w=ow,
                            packed_h=oh,
                            rotation_type=rot_type,
                            step_sequence=len(placements) + 1,
                            stop_zone_id=zone.stop_id,
                        )

                        # 3. ConstraintEngine check
                        violations = ConstraintEngine.check_all(candidate, context)

                        if not violations:
                            score = candidate_cog_score(candidate)

                        # Ưu tiên placement trên sàn trước,
                        # sau đó mới tối ưu COG.
                            score += (float(pz) / float(vt.inner_h)) * 0.1

                            if score < best_candidate_score:
                                best_candidate_score = score
                                chosen_placement = candidate
                        else:
                            last_violations = [
                                v.violation_code for v in violations
                            ]

                # Nếu không đặt được trong zone (zone đầy), thử fallback xếp ở vị trí khác trong xe
                is_rehandling = False
                if not chosen_placement:
                    # Thử toàn bộ xe
                    all_vehicle_eps = sorted(
                        ep_list + [(0.0, 0.0, 0.0)],
                        key=lambda pt: (pt[0], pt[2], pt[1]),
                    )
                    for pt in all_vehicle_eps:
                        px, py, pz = pt
                        for (ol, ow, oh, rot_type) in orientations:
                            if px + ol > vt.inner_l + 1e-4 or py + ow > vt.inner_w + 1e-4 or pz + oh > vt.inner_h + 1e-4:
                                continue
                            if self._check_overlap(px, py, pz, ol, ow, oh, placements):
                                continue

                            candidate = PlacementData(
                                package_id=pkg.id,
                                x=px,
                                y=py,
                                z=pz,
                                packed_l=ol,
                                packed_w=ow,
                                packed_h=oh,
                                rotation_type=rot_type,
                                step_sequence=len(placements) + 1,
                                stop_zone_id=zone.stop_id,
                            )
                            violations = ConstraintEngine.check_all(candidate, context)
                            if not violations:
                                chosen_placement = candidate
                                is_rehandling = True
                                break
                            else:
                                last_violations = [v.violation_code for v in violations]
                        if chosen_placement:
                            break

                if chosen_placement:
                    placements.append(chosen_placement)
                    total_placed_weight += pkg.weight
                    total_placed_volume += chosen_placement.packed_l * chosen_placement.packed_w * chosen_placement.packed_h
                    if is_rehandling:
                        rehandling_count += 1

                    # Sinh thêm Extreme Points mới
                    cx = chosen_placement.x
                    cy = chosen_placement.y
                    cz = chosen_placement.z
                    cl = chosen_placement.packed_l
                    cw = chosen_placement.packed_w
                    ch = chosen_placement.packed_h

                    new_pts = [
                        (cx + cl, cy, cz),
                        (cx, cy + cw, cz),
                        (cx, cy, cz + ch),
                        (cx + cl, cy + cw, cz),
                    ]
                    for npt in new_pts:
                        if npt[0] < vt.inner_l and npt[1] < vt.inner_w and npt[2] < vt.inner_h:
                            if npt not in ep_list:
                                ep_list.append(npt)
                else:
                    unplaced.append(UnplacedData(
                        package_id=pkg.id,
                        reason="CONSTRAINT_VIOLATED",
                        violated_constraints=last_violations or ["NO_FEASIBLE_SPACE"],
                    ))


        # HC-11 / HC-12: Final LoadPlan Validation
        # Chỉ kiểm tra sau khi đã xếp xong tất cả kiện hàng.
        if placements:

            # DEBUG: Quan sát phương án trước final validation.
            print("\n=== BEFORE HC-11 / HC-12 ===")

            total_weight = 0.0
            weighted_x = 0.0
            weighted_y = 0.0
            weighted_z = 0.0

            for p in placements:
                pkg = packages_map[str(p.package_id)]
                weight = float(pkg.weight)

                cx = float(p.x) + float(p.packed_l) / 2
                cy = float(p.y) + float(p.packed_w) / 2
                cz = float(p.z) + float(p.packed_h) / 2

                total_weight += weight
                weighted_x += weight * cx
                weighted_y += weight * cy
                weighted_z += weight * cz

                print(
                    f"Package {p.package_id}: "
                    f"position=({p.x}, {p.y}, {p.z}), "
                    f"center=({cx}, {cy}, {cz}), "
                    f"weight={weight}kg"
                )

            if total_weight > 0:
                print(
                    "COG:",
                    round(weighted_x / total_weight, 2),
                    round(weighted_y / total_weight, 2),
                    round(weighted_z / total_weight, 2),
                )

            print(
                "Allowed COG X:",
                round(float(vt.inner_l) * 0.35, 2),
                "-",
                round(float(vt.inner_l) * 0.65, 2),
            )

            print(
                "Allowed COG Y:",
                round(float(vt.inner_w) * 0.35, 2),
                "-",
                round(float(vt.inner_w) * 0.65, 2),
            )

            final_checks = [
                ConstraintEngine.check_cog(
                    all_placements=placements,
                    vehicle=vt,
                    packages_map=packages_map,
                ),
                ConstraintEngine.check_axle_load(
                    all_placements=placements,
                    vehicle=vt,
                    packages_map=packages_map,
                ),
            ]

            violations = [
                result.violation_code
                for result in final_checks
                if not result.passed
            ]

            if violations:
                # Fail-safe: Không trả về LoadPlan vi phạm
                # HC-11 hoặc HC-12.
                for placement in placements:
                    unplaced.append(
                        UnplacedData(
                            package_id=placement.package_id,
                            reason="CONSTRAINT_VIOLATED",
                            violated_constraints=violations,
                        )
                    )

                placements = []
                total_placed_weight = 0.0
                total_placed_volume = 0.0
                rehandling_count = 0


        # 3. Tính COG tổng và Axle Load
        cog_x, cog_y, cog_z = None, None, None
        front_axle_load, rear_axle_load = None, None

        if placements and total_placed_weight > 0:
            weighted_x = sum((p.x + p.packed_l / 2.0) * float(packages_map.get(str(p.package_id)).weight) for p in placements if str(p.package_id) in packages_map)
            weighted_y = sum((p.y + p.packed_w / 2.0) * float(packages_map.get(str(p.package_id)).weight) for p in placements if str(p.package_id) in packages_map)
            weighted_z = sum((p.z + p.packed_h / 2.0) * float(packages_map.get(str(p.package_id)).weight) for p in placements if str(p.package_id) in packages_map)

            cog_x = round(weighted_x / total_placed_weight, 3)
            cog_y = round(weighted_y / total_placed_weight, 3)
            cog_z = round(weighted_z / total_placed_weight, 3)


            # HC-12: Tải trục tổng = tải xe rỗng + tải hàng.
            front_x = float(vt.front_axle_x)
            rear_x = float(vt.rear_axle_x)
            wheelbase = rear_x - front_x

            rear_cargo_load = sum(
                float(packages_map[str(p.package_id)].weight)
                * (
                        float(p.x)
                        + float(p.packed_l) / 2.0
                        - front_x
                )
                / wheelbase
                for p in placements
            )

            front_cargo_load = (
                    total_placed_weight - rear_cargo_load
            )

            front_axle_load = round(
                float(vt.empty_front_axle_load_kg)
                + front_cargo_load,
                2,
                )

            rear_axle_load = round(
                float(vt.empty_rear_axle_load_kg)
                + rear_cargo_load,
                2,
                )


        comp_ms = int((time.time() - start_time) * 1000)
        vol_util = round(total_placed_volume / vehicle_volume, 4) if vehicle_volume > 0 else 0.0
        wt_util = round(total_placed_weight / vt.max_payload_kg, 4) if vt.max_payload_kg > 0 else 0.0

        metrics = MetricsData(
            volume_utilization=vol_util,
            weight_utilization=wt_util,
            packed_count=len(placements),
            computation_ms=comp_ms,
            cog_x=cog_x,
            cog_y=cog_y,
            cog_z=cog_z,
            front_axle_load=front_axle_load,
            rear_axle_load=rear_axle_load,
            rehandling_count=rehandling_count,
        )


        # HC-09: Final Unload Feasibility Validation
        # Kiểm tra toàn bộ LoadPlan sau khi xếp.

        unload_result = UnloadFeasibilityValidator.validate(
            placements=placements,
            packages_map=packages_map,
            vehicle=vt,
        )

        if not unload_result.passed:
            # Không trả về một phương án xếp hàng
            # có trình tự dỡ không hợp lệ.
            #
            # Đây là cơ chế fail-safe tạm thời:
            # từ chối toàn bộ phương án,
            # chưa thực hiện tìm lại phương án khác.

            rejected_ids = {
                str(p.package_id)
                for p in placements
            }

            for package_id in rejected_ids:
                unplaced.append(
                    UnplacedData(
                        package_id=package_id,
                        reason="CONSTRAINT_VIOLATED",
                        violated_constraints=[
                            unload_result.violation_code
                            or "MULTI_DROP_LIFO_VIOLATION"
                        ],
                    )
                )

            # Xóa placements đã bị từ chối.
            placements = []

            # Đồng bộ lại metrics với kết quả thực tế.
            metrics = MetricsData(
                volume_utilization=0.0,
                weight_utilization=0.0,
                packed_count=0,
                computation_ms=int(
                    (time.time() - start_time) * 1000
                ),
                cog_x=None,
                cog_y=None,
                cog_z=None,
                front_axle_load=None,
                rear_axle_load=None,
                rehandling_count=0,
            )

        return EngineOptimizationResponse(
            placements=placements,
            unplaced=unplaced,
            metrics=metrics,
        )


    @staticmethod
    def _check_overlap(
        x: float, y: float, z: float, l: float, w: float, h: float,
        placements: list[PlacementData]
    ) -> bool:
        """Kiểm tra xem hộp (x, y, z, l, w, h) có va chạm với placements không."""
        eps = 1e-4
        for p in placements:
            if not (
                x >= p.x + p.packed_l - eps
                or x + l <= p.x + eps
                or y >= p.y + p.packed_w - eps
                or y + w <= p.y + eps
                or z >= p.z + p.packed_h - eps
                or z + h <= p.z + eps
            ):
                return True
        return False

