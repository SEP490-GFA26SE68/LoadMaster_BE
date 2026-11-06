from __future__ import annotations

import time
import logging
import math
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

from app.service.optimize.load_balance_search import LoadBalanceSearch, center_of_mass, validate_plan
from app.dto.optimization.engine.rotation_contract import orientations as package_orientations, matches_rotation

logger = logging.getLogger(__name__)


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
            deadline = time.monotonic() + float(problem.time_limit_sec)
            result = await self._solve_http(problem)
            # Enforce the rotation contract even without obstacles or a trusted remote engine.
            metadata = {str(p.id): p for p in problem.packages}
            for placement in result.placements:
                package = metadata.get(str(placement.package_id))
                if package is None or not matches_rotation(package, placement, ConstraintEngine.EPSILON):
                    raise ValueError("External engine returned a forbidden rotation or inconsistent dimensions")
            if problem.vehicle.obstacles:
                # A remote engine may ignore a new DTO field; never trust that omission.
                expected = {str(p.id) for p in problem.packages}
                returned = [str(p.package_id) for p in [*result.placements, *result.unplaced]]
                if len(expected) != len(problem.packages) or len(returned) != len(set(returned)) or set(returned) != expected:
                    raise ValueError("External engine returned an invalid package partition")
                codes = validate_plan(result.placements, problem.vehicle,
                                      {str(p.id): p for p in problem.packages}, deadline)
                if codes:
                    raise ValueError(f"External engine returned an unsafe or unvalidated plan: {codes}")
            return result
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
        start_time = time.monotonic()
        budget = float(problem.time_limit_sec)
        if not math.isfinite(budget) or budget < 0:
            raise ValueError("time_limit_sec must be finite and non-negative")
        deadline = start_time + budget
        initial_deadline = start_time + budget * 0.50
        search_deadline = start_time + budget * 0.90
        vt = problem.vehicle
        placements: list[PlacementData] = []
        unplaced: list[UnplacedData] = []

        total_placed_weight = 0.0
        total_placed_volume = 0.0
        vehicle_volume = float(vt.inner_l * vt.inner_w * vt.inner_h)
        rehandling_count = 0

        input_keys = [str(p.id) for p in problem.packages]
        if len(input_keys) != len(set(input_keys)):
            raise ValueError("Duplicate package IDs in optimization input")
        stop_ids = [str(s.id) for s in problem.stops]
        stop_sequences = [s.sequence for s in problem.stops]
        if (len(stop_ids) != len(set(stop_ids)) or len(stop_sequences) != len(set(stop_sequences))
                or any(sequence < 1 for sequence in stop_sequences)):
            raise ValueError("Stop IDs and positive delivery sequences must be unique")

        # Map packages by ID
        packages_map = {str(p.id): p for p in problem.packages}


        def reject_current_placements(
            violation_codes: list[str],
        ) -> None:
            nonlocal placements
            nonlocal unplaced
            nonlocal total_placed_weight
            nonlocal total_placed_volume
            nonlocal rehandling_count

            existing_unplaced = {
                str(item.package_id): item
                for item in unplaced
            }

            for placement in placements:
                package_id = str(placement.package_id)

                if package_id not in existing_unplaced:
                    existing_unplaced[package_id] = UnplacedData(
                        package_id=placement.package_id,
                        reason="CONSTRAINT_VIOLATED",
                        violated_constraints=violation_codes,
                    )

            unplaced = list(existing_unplaced.values())
            placements = []

            total_placed_weight = 0.0
            total_placed_volume = 0.0
            rehandling_count = 0


        unload_checks = 0
        unload_rejections = 0
        last_unload_code = None
        def is_unload_feasible(candidate: PlacementData) -> bool:
            nonlocal unload_checks, unload_rejections, last_unload_code

            unload_checks += 1

            trial_placements = [*placements, candidate]

            result = UnloadFeasibilityValidator.validate(
                placements=trial_placements,
                packages_map=packages_map,
                vehicle=vt,
                deadline=initial_deadline,
            )

            last_unload_code = None if result.passed else result.violation_code
            if not result.passed:
                unload_rejections += 1

                # Chỉ in vài lỗi đầu tiên để tránh quá nhiều log
                if unload_rejections <= 10:
                    print(
                        f"[UNLOAD REJECTED] "
                        f"Package={candidate.package_id}, "
                        f"Position=({candidate.x}, "
                        f"{candidate.y}, {candidate.z}), "
                        f"Reason={result.violation_code}, "
                        f"Detail={result.detail}"
                    )

            return result.passed





        # 1. Phân nhóm packages theo stop & tính toán stop zones
        stops_dict: dict[Any, list] = {}
        stop_seq_map: dict[Any, int] = {}

        if problem.stops:
            for stop in problem.stops:
                stops_dict[stop.id] = []
                stop_seq_map[stop.id] = stop.sequence

        for pkg in problem.packages:
            if problem.stops:
                matched_stop = next(
                    (
                        stop
                        for stop in problem.stops
                        if stop.sequence == pkg.stop_index
                    ),
                    None,
                )

                if matched_stop is None:
                    raise ValueError(
                        f"Package {pkg.id} has stop_index={pkg.stop_index}, "
                        f"but no matching StopData.sequence exists"
                    )

                matched_stop_id = matched_stop.id
            else:
                if pkg.stop_index != 1:
                    raise ValueError(
                        f"Package {pkg.id} has invalid stop_index "
                        f"{pkg.stop_index} for a single-stop trip"
                    )

                matched_stop_id = "default-stop"

            if matched_stop_id not in stops_dict:
                stops_dict[matched_stop_id] = []
                stop_seq_map[matched_stop_id] = 1

            # Mỗi kiện được thêm đúng một lần
            stops_dict[matched_stop_id].append(pkg)

        grouped_ids = [
            str(pkg.id)
            for group in stops_dict.values()
            for pkg in group
        ]

        input_ids = [str(pkg.id) for pkg in problem.packages]

        if (
            len(grouped_ids) != len(input_ids)
            or len(set(grouped_ids)) != len(grouped_ids)
            or set(grouped_ids) != set(input_ids)
        ):
            raise ValueError(
                "Package grouping contains duplicate or missing packages"
            )



        logger.info("[INPUT CHECK] Total=%s Grouped=%s Stops=%s", len(input_ids), len(grouped_ids), len(stops_dict))
        search = LoadBalanceSearch(problem, search_deadline)
        input_codes = search.input_violations() if problem.packages else []
        if time.monotonic() >= deadline:
            input_codes = ["OPTIMIZATION_TIME_LIMIT_REACHED"]
        if input_codes:
            logger.info("[FINAL VALIDATION] Input rejected: %s", input_codes)
            logger.info("[OPTIMIZATION SUMMARY] Packed=0/%s Unplaced=%s TimeMs=%s", len(problem.packages),
                        len(problem.packages), int((time.monotonic() - start_time) * 1000))
            return EngineOptimizationResponse(unplaced=[UnplacedData(package_id=p.id, reason="CONSTRAINT_VIOLATED",
                    violated_constraints=input_codes) for p in problem.packages],
                    metrics=MetricsData(computation_ms=int((time.monotonic() - start_time) * 1000)))

    # Build stops input for StopZoneCalculator
        stops_input = []
        for stop_id, pkgs in stops_dict.items():
            stops_input.append({
                "stop_id": stop_id,
                "sequence": stop_seq_map.get(stop_id, 1),
                "packages": [{"volume": p.l * p.w * p.h, "weight": p.weight} for p in pkgs],
            })

        # Tính stop zones
        try:
            zone_calc_res = StopZoneCalculator.calculate_zones(vt, stops_input, weight_blend=0.5)
        except ValueError as error:
            if "Insufficient vehicle length for stop zones" not in str(error):
                raise
            # A heuristic gap must not make an otherwise packable small/many-stop load impossible.
            zone_calc_res = StopZoneCalculator.calculate_zones(vt, stops_input, accessibility_buffer=0, weight_blend=0.5)
        zones_by_id = {str(z.stop_id): z for z in zone_calc_res.zones}

        # Sắp xếp các zone từ sâu nhất (x = 0) đến gần cửa sau (x = inner_l)
        # AC: Xếp hàng của stop cuối cùng trước (sâu nhất), stop đầu tiên sau (gần cửa)
        sorted_zones = sorted(zone_calc_res.zones, key=lambda z: z.zone_start_x)

        # Context cho ConstraintEngine

        context = {
            "vehicle": vt,
            "existing_placements": placements,
            "packages_map": packages_map,

            # Partial-load COG/axle are scores; full-load HC-11/HC-12 stay mandatory at final validation.
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



            for pkg in sorted_pkgs:
                if time.monotonic() >= initial_deadline:
                    unplaced.append(UnplacedData(package_id=pkg.id, reason="CONSTRAINT_VIOLATED",
                                                 violated_constraints=["OPTIMIZATION_TIME_LIMIT_REACHED"]))
                    continue

                # Sinh EP căn phải theo kích thước kiện hiện tại.
                # Với 6 hướng xoay, trục X có thể là L, W hoặc H.
                for orientation_l in {
                    float(pkg.l),
                    float(pkg.w),
                    float(pkg.h),
                }:
                    right_aligned_x = zone_end - orientation_l

                    if right_aligned_x >= zone_start:
                        for candidate_y in (
                                door_y_min,
                                vehicle_mid_y,
                        ):
                            candidate_pt = (
                                right_aligned_x,
                                candidate_y,
                                0.0,
                            )

                            if len(ep_list) < LoadBalanceSearch.MAX_GENERATED_CANDIDATES and candidate_pt not in ep_list:
                                ep_list.append(candidate_pt)

                if vt.max_payload_kg > 0 and (total_placed_weight + pkg.weight > vt.max_payload_kg):
                    unplaced.append(UnplacedData(
                        package_id=pkg.id,
                        reason="OVER_WEIGHT",
                        violated_constraints=["WEIGHT_LIMIT_EXCEEDED"],
                    ))
                    continue



                # HC-03: Xác định 6 hướng xoay được phép
                # HC-03: Quy ước 6 hướng xoay kiện hàng
                # 0 = LWH
                # 1 = WLH
                # 2 = LHW
                # 3 = WHL
                # 4 = HLW
                # 5 = HWL
                #
                # Quy ước này phải đồng bộ với 3D Viewer.
                orientations = [o for o in package_orientations(pkg)
                                if o[0] <= vt.inner_l and o[1] <= vt.inner_w and o[2] <= vt.inner_h]

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
                    if time.monotonic() >= initial_deadline:
                        break
                    px, py, pz = pt
                    for (ol, ow, oh, rot_type) in orientations:
                        if time.monotonic() >= initial_deadline:
                            break
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

                        if not violations and is_unload_feasible(candidate):
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
                            ] or ([last_unload_code] if last_unload_code else [])

                # Nếu không đặt được trong zone (zone đầy), thử fallback xếp ở vị trí khác trong xe
                is_rehandling = False
                if not chosen_placement:
                    # Thử toàn bộ xe
                    all_vehicle_eps = sorted(
                        ep_list + [(0.0, 0.0, 0.0)],
                        key=lambda pt: (pt[0], pt[2], pt[1]),
                    )
                    for pt in all_vehicle_eps:
                        if time.monotonic() >= initial_deadline:
                            break
                        px, py, pz = pt
                        for (ol, ow, oh, rot_type) in orientations:
                            if time.monotonic() >= initial_deadline:
                                break
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

                            if not violations and is_unload_feasible(candidate):
                                chosen_placement = candidate
                                is_rehandling = True
                                break
                            else:
                                last_violations = [v.violation_code for v in violations] or ([last_unload_code] if last_unload_code else [])
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
                            if len(ep_list) < LoadBalanceSearch.MAX_GENERATED_CANDIDATES and npt not in ep_list:
                                ep_list.append(npt)
                else:
                    unplaced.append(UnplacedData(
                        package_id=pkg.id,
                        reason="CONSTRAINT_VIOLATED",
                        violated_constraints=(["OPTIMIZATION_TIME_LIMIT_REACHED"]
                                              if time.monotonic() >= initial_deadline
                                              else last_violations or ["NO_FEASIBLE_SPACE"]),
                    ))


        initial_cog = center_of_mass(placements, packages_map)
        logger.info("[INITIAL PLAN] Packed=%s COG_X=%s COG_Y=%s", len(placements),
                    initial_cog[0] if initial_cog else None, initial_cog[1] if initial_cog else None)
        improved = search.improve(placements, zones_by_id)
        if improved is not placements:
            placements = improved
            rehandling_count = 0  # Every accepted repack has a verified unloading sequence without rehandling.
        cached = search.validated_copy(placements)
        if cached is not None:
            # Exact immutable snapshot already passed ALL checks for this problem.
            # A second validation timing out must not erase that proven result.
            placements, violations = cached, []
        elif not placements and search.last_violations:
            violations = search.last_violations
        else:
            violations = validate_plan(placements, vt, packages_map, deadline)
        if violations and search.best_plan:
            logger.info("[FINAL VALIDATION] Retaining best validated plan after: %s", violations)
            placements, violations = search.best_plan_copy(), []
            rehandling_count = 0
        packed_keys = {str(p.package_id) for p in placements}
        unplaced_by_id = {str(u.package_id): u for u in unplaced}
        missing_codes = violations or (["OPTIMIZATION_TIME_LIMIT_REACHED"]
                                      if time.monotonic() >= search_deadline else ["NO_FEASIBLE_SPACE"])
        unplaced = [unplaced_by_id.get(str(p.id)) or UnplacedData(package_id=p.id, reason="CONSTRAINT_VIOLATED",
                    violated_constraints=missing_codes) for p in problem.packages if str(p.id) not in packed_keys]
        total_placed_weight = sum(float(packages_map[str(p.package_id)].weight) for p in placements)
        total_placed_volume = sum(p.packed_l * p.packed_w * p.packed_h for p in placements)
        logger.info("[FINAL VALIDATION] COG=%s Axle=%s Unload=%s Packed=%s Violations=%s",
                    True if not violations else "failed_or_not_evaluated",
                    True if not violations else "failed_or_not_evaluated",
                    "passed" if not violations else "failed_or_not_evaluated",
                    len(placements), violations)
        if violations:
            reject_current_placements(violations)

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


        comp_ms = int((time.monotonic() - start_time) * 1000)
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


        # Full HC-01..HC-12 and unload validation completed before computing returned metrics.

        packed_ids = [
            str(p.package_id)
            for p in placements
        ]

        unplaced_ids = [
            str(p.package_id)
            for p in unplaced
        ]

        assert len(packed_ids) == len(set(packed_ids)), (
            "Duplicate package_id in placements"
        )

        assert len(unplaced_ids) == len(set(unplaced_ids)), (
            "Duplicate package_id in unplaced"
        )

        assert not (set(packed_ids) & set(unplaced_ids)), (
            "Package appears in both placements and unplaced"
        )

        input_ids = {
            str(p.id)
            for p in problem.packages
        }

        assert set(packed_ids) | set(unplaced_ids) == input_ids, (
            "Optimization result does not match input packages"
        )

        logger.info("[OPTIMIZATION SUMMARY] Packed=%s/%s Unplaced=%s UnloadChecks=%s UnloadRejections=%s TimeMs=%s",
                    len(placements), len(problem.packages), len(unplaced), unload_checks, unload_rejections,
                    int((time.monotonic() - start_time) * 1000))

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

