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
        self.token_provider = token_provider or KeycloakTokenProvider()
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
        if self.token_provider:
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
        }

        # 2. Xếp hàng theo từng zone
        for zone in sorted_zones:
            zone_pkgs = stops_dict.get(zone.stop_id, [])
            # Sắp xếp kiện hàng trong zone: giảm dần theo thể tích
            sorted_pkgs = sorted(zone_pkgs, key=lambda p: (p.l * p.w * p.h, p.weight), reverse=True)

            # Khởi tạo Extreme Points (EP) cho zone
            ep_list = [(float(zone.zone_start_x), 0.0, 0.0)]

            for pkg in sorted_pkgs:
                if vt.max_payload_kg > 0 and (total_placed_weight + pkg.weight > vt.max_payload_kg):
                    unplaced.append(UnplacedData(
                        package_id=pkg.id,
                        reason="WEIGHT_LIMIT_EXCEEDED",
                        violated_constraints=["WEIGHT_LIMIT_EXCEEDED"],
                    ))
                    continue

                # Kiểm tra kích thước cơ bản xem có thể chứa trong xe không
                is_rot_allowed = getattr(pkg, "rotation_allowed", True) is not False
                can_fit_unrotated = (pkg.l <= vt.inner_l and pkg.w <= vt.inner_w and pkg.h <= vt.inner_h)
                can_fit_rotated = (pkg.w <= vt.inner_l and pkg.l <= vt.inner_w and pkg.h <= vt.inner_h)

                if not can_fit_unrotated and not (can_fit_rotated and is_rot_allowed):
                    violation = "ROTATION_NOT_ALLOWED" if (can_fit_rotated and not is_rot_allowed) else "DIMENSIONS_EXCEEDED"
                    unplaced.append(UnplacedData(
                        package_id=pkg.id,
                        reason="CONSTRAINT_VIOLATED" if violation == "ROTATION_NOT_ALLOWED" else "DOES_NOT_FIT_DIMENSIONS",
                        violated_constraints=[violation],
                    ))
                    continue

                # Xác định các hướng xoay cho phép
                orientations = [(pkg.l, pkg.w, pkg.h, 0)]
                if is_rot_allowed and (pkg.l != pkg.w):
                    orientations.append((pkg.w, pkg.l, pkg.h, 1))

                # Thử tìm vị trí trong zone hiện tại
                chosen_placement = None
                last_violations: list[str] = []

                # Sắp xếp EP theo DBLF: x tăng dần (sâu), z tăng dần (dưới đáy), y tăng dần (trái)
                sorted_eps = sorted(ep_list, key=lambda pt: (pt[0], pt[2], pt[1]))

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
                            chosen_placement = candidate
                            break
                        else:
                            last_violations = [v.violation_code for v in violations]
                    if chosen_placement:
                        break

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

            inner_l = float(vt.inner_l)
            rear_axle_load = round(total_placed_weight * (cog_x / inner_l), 2) if inner_l > 0 else 0.0
            front_axle_load = round(total_placed_weight - rear_axle_load, 2)

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

