from __future__ import annotations

import logging
import time
from typing import Optional, Any, Sequence
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



from app.ml.lumo_scorer import get_lumo_scorer, uses_lumo

from app.service.optimize.candidate_plans import (
    LayoutStrategy,
    axle_balance_target_x,
    layout_strategies_for,
    pick_candidates,
)
from app.service.optimize.stop_zone_calculator import StopZoneCalculator
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.unload_feasibility_validator import (
    UnloadFeasibilityValidator,
)

logger = logging.getLogger(__name__)


def _debug_log(*parts) -> None:
    logger.debug(" ".join(str(part) for part in parts).strip())


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
        return self.solve_candidates(problem)

    def solve_candidates(self, problem: ProblemRequest) -> EngineOptimizationResponse:
        """
        Một job ra ba phương án ứng viên A · B · C (MAX_VOLUME, AXLE_BALANCE, MIN_REHANDLING).
        Xếp thử vài cách (mỗi cách vẫn qua HC-01 → HC-12), rồi mỗi mục tiêu chọn cách tốt nhất
        theo chỉ số của chính nó. Các cách được thử theo hạng thuật toán của job
        (AlgorithmTierService.plan_algorithms_for):
          BASIC    EP + DBLF, bốn chiến lược.
          PRO      + GA và SA (metaheuristic.py), giải mã bằng chính bộ dựng EP + DBLF.
          ULTIMATE + EP có Lumo-R1.1 chấm chỗ đặt, + ba mô hình Lumo tự xếp (lumo_models.py),
                   mọi chỗ đặt của AI được kiểm lại bằng ConstraintEngine.
        Mỗi cách xếp mang mã thuật toán đã sinh ra nó (`algorithm_name`). Giới hạn thời gian
        của job áp cho cả lượt: hết giờ thì dừng thử cách mới, cách đầu luôn được chạy.
        Kết quả trả về là phương án A, kèm cả ba ở `candidates`.
        """
        from app.constant.optimization.plan_algorithm import PlanAlgorithm
        from app.service.optimize.algorithm_tier_service import AlgorithmTierService

        start = time.monotonic()
        deadline = start + max(int(problem.time_limit_sec or 60), 1)
        allowed = AlgorithmTierService.plan_algorithms_for(problem.algorithm_name)
        tried: list[str] = []
        layouts: list[EngineOptimizationResponse] = []
        ep_runs: list[tuple[str, EngineOptimizationResponse]] = []
        # Cách xếp mang nhãn EP_DBLF phải là EP thuần: gói có Lumo chấm điểm thì tắt nó ở đây
        # (Lumo-R1.1 chấm chỗ đặt là một cách xếp riêng, EP_LUMO_R1_1).
        ep_kwargs = {"use_lumo_scorer": False} if uses_lumo(problem.algorithm_name) else {}
        for strategy in layout_strategies_for(problem.vehicle):
            if layouts and time.monotonic() >= deadline:
                break
            layout = self.solve_in_process(problem, strategy=strategy, **ep_kwargs)
            layout = layout.model_copy(update={"algorithm_name": PlanAlgorithm.EP_DBLF.value})
            layouts.append(layout)
            ep_runs.append((strategy, layout))
        tried.append(PlanAlgorithm.EP_DBLF.value)

        if len(allowed) > 1:
            layouts += self._advanced_layouts(problem, allowed, ep_runs, start, deadline, tried)

        candidates = pick_candidates(layouts, problem.vehicle)
        logger.info(
            "Optimization tier=%s tried=%s layouts=%d chosen A/B/C=%s",
            problem.algorithm_name or "EP_DBLF",
            tried,
            len(layouts),
            [c.result.algorithm_name for c in candidates],
        )
        head = candidates[0].result
        total_ms = int((time.monotonic() - start) * 1000)
        return head.model_copy(update={
            "metrics": head.metrics.model_copy(update={"computation_ms": total_ms}),
            "candidates": candidates,
            "algorithms_tried": tried,
        })

    def _advanced_layouts(
        self,
        problem: ProblemRequest,
        allowed: list[str],
        ep_runs: list[tuple[str, EngineOptimizationResponse]],
        start: float,
        deadline: float,
        tried: list[str],
    ) -> list[EngineOptimizationResponse]:
        """
        Cách xếp của gói PRO / ULTIMATE. Thứ tự: mô hình AI greedy (mỗi mô hình một lượt cố định),
        EP + Lumo-R1.1, rồi GA → SA dùng phần thời gian còn lại (tối đa ngân sách trong settings).
        Một bước hỏng (thiếu torch, lỗi mô hình) chỉ bị bỏ qua, không làm hỏng job.
        """
        from app.constant.optimization.plan_algorithm import PlanAlgorithm as P
        from app.service.optimize.metaheuristic import run_ga_sa

        layouts: list[EngineOptimizationResponse] = []
        ai_codes = [c for c in allowed if c in (P.LUMO_R1.value, P.LUMO_R1_1.value, P.LUMO_R2.value)]
        if ai_codes:
            try:
                from app.ml.lumo_models import run_lumo_models

                ai = run_lumo_models(problem, ai_codes, self, deadline=deadline)
                tried.extend(code for code in ai.tried)
                layouts += ai.layouts
            except Exception as exc:  # không bao giờ làm hỏng job vì AI
                logger.warning("Lumo models skipped: %s", exc)

        if P.EP_LUMO_R1_1.value in allowed and time.monotonic() < deadline:
            # Kiện ghim vẫn giữ nguyên: Lumo-R1.1 chỉ chọn trong các chỗ đặt hợp lệ của EP.
            scorer = None
            try:
                scorer = get_lumo_scorer()
            except Exception as exc:
                logger.warning("EP_LUMO_R1_1 skipped: %s", exc)
            if scorer is not None:
                layout = self.solve_in_process(problem, strategy=LayoutStrategy.CENTER_FIRST,
                                               use_lumo_scorer=True, quiet=True)
                layouts.append(layout.model_copy(update={"algorithm_name": P.EP_LUMO_R1_1.value}))
                tried.append(P.EP_LUMO_R1_1.value)
            else:
                logger.info("EP_LUMO_R1_1 skipped: Lumo-R1.1 scorer unavailable (torch or weights missing)")

        if P.GA.value in allowed and time.monotonic() < deadline:
            # Chuyến lớn: một lần giải mã (một lượt EP + DBLF) có thể mất vài giây, nên ngân sách
            # được nới tới đủ METAHEURISTIC_MIN_DECODES lần giải mã — vẫn trong giới hạn của request.
            decode_sec = max(((r.metrics.computation_ms or 0) / 1000.0 for _, r in ep_runs), default=0.0)
            budget = min(
                max(float(settings.METAHEURISTIC_TIME_BUDGET_SEC),
                    int(settings.METAHEURISTIC_MIN_DECODES) * decode_sec),
                max(0.0, deadline - time.monotonic() - 0.2),
            )
            if budget > 0:
                meta = run_ga_sa(self, problem, ep_runs, budget_sec=budget,
                                 max_evaluations=int(settings.METAHEURISTIC_MAX_EVALUATIONS),
                                 run_sa=P.SA.value in allowed)
                tried.extend(meta.tried)
                layouts += meta.layouts
        return layouts

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

    def solve_in_process(
        self,
        problem: ProblemRequest,
        strategy: str = LayoutStrategy.CENTER_FIRST,
        order: Optional[Sequence[str]] = None,
        orientation_pref: Optional[dict[str, int]] = None,
        use_lumo_scorer: Optional[bool] = None,
        quiet: bool = False,
    ) -> EngineOptimizationResponse:
        """
        Nâng cấp S5b-04: Zone-aware 3D Packing Heuristic với Extreme Points (EP)
        và ConstraintEngine (Fragility, Stacking, Support Area, Rotation, COG, Axle Load).

        `strategy` chỉ đổi thứ tự xét kiện và cách chấm điểm chỗ đặt (xem LayoutStrategy);
        mọi ràng buộc cứng giữ nguyên.

        Lối của metaheuristic (GA / SA giải mã nhiễm sắc thể bằng chính bộ dựng này):
          order             mã kiện theo thứ tự xét; chỉ đổi thứ tự TRONG mỗi vùng điểm giao
                            (vùng vẫn xếp sâu trước, kiện ghim vẫn đặt trước và không xếp lại).
          orientation_pref  mã kiện → chỉ số hướng ưu tiên trong các hướng được phép (lấy theo modulo);
                            có chỗ đặt hợp lệ với hướng đó thì chọn trong số đó, không thì như thường.
                            Vắng hoặc âm: không ưu tiên.
        use_lumo_scorer: True thì Lumo-R1.1 chọn trong các chỗ đặt hợp lệ (nếu nạp được trọng số);
        None giữ cách cũ (theo algorithm_name của job). quiet: tắt bản ghi gỡ lỗi trước HC-11 / HC-12.
        """
        start_time = time.time()
        # Bản ghi gỡ lỗi trước HC-11 / HC-12 đi qua logger ở mức DEBUG, không in ra console.
        _debug_print = (
            (lambda *args, **kwargs: None)
            if quiet or not logger.isEnabledFor(logging.DEBUG)
            else _debug_log
        )
        lumo = None
        if use_lumo_scorer is None:
            use_lumo_scorer = uses_lumo(problem.algorithm_name)
        if use_lumo_scorer:
            lumo = get_lumo_scorer()
        rank = {str(pid): i for i, pid in enumerate(order)} if order is not None else None
        vt = problem.vehicle
        placements: list[PlacementData] = []
        unplaced: list[UnplacedData] = []

        total_placed_weight = 0.0
        total_placed_volume = 0.0
        vehicle_volume = float(vt.inner_l * vt.inner_w * vt.inner_h)

        # Map packages by ID
        packages_map = {str(p.id): p for p in problem.packages}

        # 1. Phân nhóm packages theo stop & tính toán stop zones
        stops_dict, zone_calc_res, package_stop = self._stop_groups(problem)
        zones_by_id = {str(z.stop_id): z for z in zone_calc_res.zones}

        # Sắp xếp các zone từ sâu nhất (x = 0) đến gần cửa sau (x = inner_l)
        # AC: Xếp hàng của stop cuối cùng trước (sâu nhất), stop đầu tiên sau (gần cửa)
        sorted_zones = sorted(zone_calc_res.zones, key=lambda z: z.zone_start_x)

        # Kiện đã ghim (chạy lại giữ ghim): đặt trước đúng vị trí và hướng cũ,
        # là vật cản cho các kiện còn lại; không xếp lại chúng.
        pinned_placements = self._pinned_placements(problem, packages_map, package_stop)
        pinned_ids = {str(p.package_id) for p in pinned_placements}
        for pinned_placement in pinned_placements:
            placements.append(pinned_placement)
            total_placed_weight += float(packages_map[str(pinned_placement.package_id)].weight)
            total_placed_volume += (
                pinned_placement.packed_l
                * pinned_placement.packed_w
                * pinned_placement.packed_h
            )

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
        # Đích trọng tâm theo X: giữa thùng, hoặc điểm chia đều mức dùng hai nhóm trục (AXLE_TARGET).
        target_x = float(vt.inner_l) / 2.0
        if strategy == LayoutStrategy.AXLE_TARGET:
            axle_x = axle_balance_target_x(
                vt, sum(float(p.weight) for p in problem.packages)
            )
            if axle_x is not None:
                target_x = axle_x

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

            center_x = target_x
            center_y = float(vt.inner_w) / 2.0

            # Chuẩn hóa hai trục để X không áp đảo Y.
            normalized_x = (cog_x - center_x) / float(vt.inner_l)
            normalized_y = (cog_y - center_y) / float(vt.inner_w)

            return normalized_x ** 2 + normalized_y ** 2


        # Điểm ứng viên "sát cuối vùng" dưới đây dùng kích thước của `pkg` còn lại từ vòng lặp trước
        # (kiện cuối của danh sách kiện ở vùng đầu, kiện xét cuối của vùng trước ở các vùng sau).
        # Giữ đúng hành vi đó để EP + DBLF ra cùng kết quả như trước khi tách _stop_groups.
        pkg = problem.packages[-1] if problem.packages else None

# 2. Xếp hàng theo từng zone
        for zone in sorted_zones:
            zone_pkgs = [
                p for p in stops_dict.get(zone.stop_id, [])
                if str(p.id) not in pinned_ids
            ]
            # Sắp xếp kiện hàng trong zone: giảm dần theo thể tích
            if rank is not None:
                # Thứ tự do GA / SA đưa vào; kiện không có trong order đứng cuối, giữ thứ tự cũ.
                sorted_pkgs = sorted(zone_pkgs, key=lambda p: rank.get(str(p.id), len(rank)))
            elif strategy == LayoutStrategy.HEAVY_FIRST:
                sorted_pkgs = sorted(zone_pkgs, key=lambda p: (p.weight, p.l * p.w * p.h), reverse=True)
            else:
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
            # Xe chưa khai cửa: không có điểm mép cửa; ConstraintEngine (HC-10) trả
            # PACKAGE_CANNOT_PASS_DOOR cho từng kiện thay vì solver gãy ở float(None).
            door_y_min = (
                (float(vt.inner_w) - float(vt.door_w)) / 2.0
                if vt.door_w is not None and vt.door_w > 0
                else 0.0
            )

            ep_list = [
                (zone_start, 0.0, 0.0),
                (zone_mid_x, 0.0, 0.0),
                (zone_start, vehicle_mid_y, 0.0),
                (zone_mid_x, vehicle_mid_y, 0.0),

                # Thêm Extreme Points nằm tại mép cửa.
                (zone_start, door_y_min, 0.0),
                (zone_mid_x, door_y_min, 0.0),
            ]

            # Điểm ứng viên sát các kiện đã ghim (mặt sau, mặt bên, mặt trên).
            for pinned_placement in pinned_placements:
                for npt in self._extreme_points_of(pinned_placement):
                    if (
                        npt[0] < vt.inner_l
                        and npt[1] < vt.inner_w
                        and npt[2] < vt.inner_h
                        and npt not in ep_list
                    ):
                        ep_list.append(npt)


            # HC-11: Sinh thêm Extreme Points gần cuối Stop Zone.
# Candidate chỉ được sử dụng sau khi qua bounds,
# overlap và ConstraintEngine như các EP khác.
#
# Không thay đổi giới hạn zone hoặc các Hard Constraints.

            for orientation_l in ({float(pkg.l), float(pkg.w)} if pkg is not None else ()):
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
                valid_candidates: list[PlacementData] = []
                best_candidate_score: Optional[tuple] = None

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

                # Hướng ưu tiên của GA / SA (nếu có): một mã hướng trong các hướng được phép.
                preferred_rot = None
                if orientation_pref:
                    pref_index = orientation_pref.get(str(pkg.id))
                    if pref_index is not None and int(pref_index) >= 0:
                        preferred_rot = orientations[int(pref_index) % len(orientations)][3]

                # 1–2. Chỗ đặt qua biên vùng và không chồng lấn, kèm điểm chấm của chiến lược.
                geometric: list[tuple[tuple, PlacementData]] = []
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
                        if strategy == LayoutStrategy.DEEP_FIRST:
                            # DBLF: thấp nhất, sâu nhất, sát vách trái.
                            score = (float(pz), float(px), float(py))
                        else:
                            # Ưu tiên placement trên sàn trước,
                            # sau đó mới tối ưu COG.
                            score = (
                                candidate_cog_score(candidate)
                                + (float(pz) / float(vt.inner_h)) * 0.1,
                            )
                        geometric.append((score, candidate))

                # 3. ConstraintEngine check.
                if lumo is not None:
                    # Lumo-R1.1 cần mọi chỗ đặt hợp lệ để chọn.
                    for score, candidate in geometric:
                        violations = ConstraintEngine.check_all(candidate, context)
                        if not violations:
                            valid_candidates.append(candidate)
                            if best_candidate_score is None or score < best_candidate_score:
                                best_candidate_score = score
                                chosen_placement = candidate
                        else:
                            last_violations = [v.violation_code for v in violations]
                else:
                    # Chọn chỗ đặt hợp lệ có điểm nhỏ nhất (hoà: đứng trước) — kết quả y hệt việc kiểm
                    # mọi chỗ rồi lấy min, nhưng chỉ kiểm ConstraintEngine tới chỗ hợp lệ đầu tiên theo
                    # thứ tự điểm. Có hướng ưu tiên thì các chỗ đúng hướng đó được xét trước.
                    ordered = sorted(
                        range(len(geometric)),
                        key=lambda i: (
                            preferred_rot is not None and geometric[i][1].rotation_type != preferred_rot,
                            geometric[i][0],
                            i,
                        ),
                    )
                    failed: dict[int, list[str]] = {}
                    for i in ordered:
                        violations = ConstraintEngine.check_all(geometric[i][1], context)
                        if not violations:
                            chosen_placement = geometric[i][1]
                            break
                        failed[i] = [v.violation_code for v in violations]
                    if chosen_placement is None and failed:
                        # Như vòng cũ: lý do của chỗ đặt cuối cùng theo thứ tự Extreme Points.
                        last_violations = failed[max(failed)]

                if lumo is not None and valid_candidates:
                    remaining = sum(1 for zone_pkgs in stops_dict.values() for _ in zone_pkgs) - len(placements)
                    picked = lumo.pick(
                        vt,
                        packages_map,
                        placements,
                        valid_candidates,
                        max(remaining - 1, 0),
                        len(problem.packages),
                        zone_start,
                        zone_end,
                    )
                    if picked is not None:
                        chosen_placement = valid_candidates[picked]

                # Nếu không đặt được trong zone (zone đầy), thử fallback xếp ở vị trí khác trong xe
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
                                break
                            else:
                                last_violations = [v.violation_code for v in violations]
                        if chosen_placement:
                            break

                if chosen_placement:
                    placements.append(chosen_placement)
                    total_placed_weight += pkg.weight
                    total_placed_volume += chosen_placement.packed_l * chosen_placement.packed_w * chosen_placement.packed_h

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
            _debug_print("\n=== BEFORE HC-11 / HC-12 ===")

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

                _debug_print(
                    f"Package {p.package_id}: "
                    f"position=({p.x}, {p.y}, {p.z}), "
                    f"center=({cx}, {cy}, {cz}), "
                    f"weight={weight}kg"
                )

            if total_weight > 0:
                _debug_print(
                    "COG:",
                    round(weighted_x / total_weight, 2),
                    round(weighted_y / total_weight, 2),
                    round(weighted_z / total_weight, 2),
                )

            _debug_print(
                "Allowed COG X:",
                round(float(vt.inner_l) * 0.35, 2),
                "-",
                round(float(vt.inner_l) * 0.65, 2),
            )

            _debug_print(
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

            # HC-11 hỏng vì cả khối hàng dồn về một đầu (vùng chia theo thể tích trên cả chiều dài thùng,
            # hàng nhẹ thì rải ra tới cửa): dồn các khối điểm giao sát nhau theo đúng thứ tự LIFO rồi dời
            # cả khối cho trọng tâm về giữa thùng. Chỉ nhận khi mọi kiện vẫn qua ConstraintEngine và HC-11/HC-12.
            if "CENTER_OF_GRAVITY_OUT_OF_RANGE" in violations:
                rebalanced = self._rebalance_along_x(placements, packages_map, vt)
                if rebalanced is not None:
                    rebalanced_checks = [
                        ConstraintEngine.check_cog(all_placements=rebalanced, vehicle=vt, packages_map=packages_map),
                        ConstraintEngine.check_axle_load(all_placements=rebalanced, vehicle=vt, packages_map=packages_map),
                    ]
                    if all(result.passed for result in rebalanced_checks):
                        placements = rebalanced
                        violations = []

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


        # Số dỡ-xếp lại theo đúng một luật với bản chỉnh tay của LoadMasterService: kiện có tâm theo X nằm
        # ngoài vùng của điểm giao mình, trên cách xếp cuối (kể cả kiện ghim và sau khi dời khối cân trọng tâm).
        rehandling_count = StopZoneCalculator.count_rehandling(
            zone_calc_res.zones, placements, package_stop
        )

        # 3. Tính COG tổng và Axle Load
        comp_ms = int((time.time() - start_time) * 1000)
        metrics = self._plan_metrics(
            placements, packages_map, vt, total_placed_weight, total_placed_volume, rehandling_count, comp_ms
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


    def evaluate_fixed_layout(
        self,
        problem: ProblemRequest,
        placements_in: Sequence[PlacementData],
        algorithm_name: str,
        started_at: Optional[float] = None,
    ) -> tuple[Optional[EngineOptimizationResponse], Optional[str]]:
        """
        Kiểm lại một cách xếp do bộ giải khác (mô hình AI) dựng, bằng đúng các luật và cách tính
        chỉ số của solve_in_process: từng chỗ đặt qua ConstraintEngine.check_all theo thứ tự xếp
        (mỗi kiện so với các kiện đặt trước nó), rồi HC-11 / HC-12 trên cả phương án và
        UnloadFeasibilityValidator. Không sửa chỗ đặt nào (không dời khối cân trọng tâm).
        Trả (cách xếp, None) khi đạt; (None, lý do) khi vi phạm ràng buộc cứng — cả cách xếp bị bỏ.
        """
        start_time = started_at if started_at is not None else time.time()
        vt = problem.vehicle
        packages_map = {str(p.id): p for p in problem.packages}
        _, zone_calc_res, package_stop = self._stop_groups(problem)

        placements: list[PlacementData] = []
        seen: set[str] = set()
        context = {
            "vehicle": vt,
            "existing_placements": placements,
            "packages_map": packages_map,
            "check_cog": False,
            "check_axle": False,
        }
        for item in placements_in:
            key = str(item.package_id)
            pkg = packages_map.get(key)
            if pkg is None:
                return None, f"UNKNOWN_PACKAGE:{key}"
            if key in seen:
                return None, f"DUPLICATE_PACKAGE:{key}"
            seen.add(key)
            placement = item.model_copy(update={
                "package_id": pkg.id,
                "step_sequence": len(placements) + 1,
                "stop_zone_id": package_stop.get(key),
                "pinned": False,
            })
            violations = ConstraintEngine.check_all(placement, context)
            if violations:
                return None, f"{violations[0].violation_code}:{key}"
            placements.append(placement)

        if placements:
            for check in (
                ConstraintEngine.check_cog(all_placements=placements, vehicle=vt, packages_map=packages_map),
                ConstraintEngine.check_axle_load(all_placements=placements, vehicle=vt, packages_map=packages_map),
            ):
                if not check.passed:
                    return None, check.violation_code
            unload = UnloadFeasibilityValidator.validate(
                placements=placements, packages_map=packages_map, vehicle=vt
            )
            if not unload.passed:
                return None, unload.violation_code or "MULTI_DROP_LIFO_VIOLATION"

        unplaced = [
            UnplacedData(package_id=p.id, reason="CONSTRAINT_VIOLATED", violated_constraints=["NO_FEASIBLE_SPACE"])
            for p in problem.packages
            if str(p.id) not in seen
        ]
        total_weight = sum(float(packages_map[str(p.package_id)].weight) for p in placements)
        total_volume = sum(p.packed_l * p.packed_w * p.packed_h for p in placements)
        rehandling = StopZoneCalculator.count_rehandling(zone_calc_res.zones, placements, package_stop)
        metrics = self._plan_metrics(
            placements, packages_map, vt, total_weight, total_volume, rehandling,
            int((time.time() - start_time) * 1000),
        )
        return EngineOptimizationResponse(
            placements=placements, unplaced=unplaced, metrics=metrics, algorithm_name=algorithm_name
        ), None

    @staticmethod
    def _plan_metrics(
        placements: list[PlacementData],
        packages_map: dict[str, PackageData],
        vt,
        total_placed_weight: float,
        total_placed_volume: float,
        rehandling_count: int,
        comp_ms: int,
    ) -> MetricsData:
        """Chỉ số của một cách xếp: thể tích, tải trọng, trọng tâm, tải trục (HC-12), số dỡ-xếp lại."""
        vehicle_volume = float(vt.inner_l * vt.inner_w * vt.inner_h)
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
        return metrics

    @staticmethod
    def _stop_groups(problem: ProblemRequest):
        """Kiện theo điểm giao, vùng điểm giao (StopZoneCalculator) và điểm giao của từng kiện."""
        vt = problem.vehicle
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
                # stop_index là thứ tự điểm (stop_sequence). Khớp theo thứ tự trước:
                # so với id trước thì kiện của điểm thứ tự 2 rơi vào điểm có id 2.
                matched_stop_id = next(
                    (s.id for s in problem.stops if s.sequence == pkg.stop_index),
                    None,
                )
                if matched_stop_id is None:
                    matched_stop_id = next(
                        (s.id for s in problem.stops if str(s.id) == str(pkg.stop_index)),
                        None,
                    )
                if matched_stop_id is None and problem.stops:
                    matched_stop_id = problem.stops[0].id
            else:
                matched_stop_id = "default-stop"

            if matched_stop_id not in stops_dict:
                stops_dict[matched_stop_id] = []
            stops_dict[matched_stop_id].append(pkg)

        # Build stops input for StopZoneCalculator
        # Điểm giao không còn kiện nào (kho bỏ kiện cuối của nó) không chiếm vùng nào trong thùng.
        stops_input = []
        for stop_id, pkgs in stops_dict.items():
            if not pkgs:
                continue
            stops_input.append({
                "stop_id": stop_id,
                "sequence": stop_seq_map.get(stop_id, 1),
                "packages": [{"volume": p.l * p.w * p.h, "weight": p.weight} for p in pkgs],
            })

        # Tính stop zones
        zone_calc_res = StopZoneCalculator.calculate_zones(vt, stops_input)

        package_stop = {
            str(p.id): stop_id
            for stop_id, pkgs in stops_dict.items()
            for p in pkgs
        }
        return stops_dict, zone_calc_res, package_stop

    @staticmethod
    def _rebalance_along_x(
        placements: list[PlacementData],
        packages_map: dict[str, PackageData],
        vt,
    ) -> Optional[list[PlacementData]]:
        """Dời theo trục X, không xoay, không đổi Y/Z: khối kiện của từng điểm giao đi nguyên khối,
        điểm giao muộn nhất sát vách trước, các khối nối liền nhau về phía cửa (giữ LIFO); rồi dời cả
        hàng cho trọng tâm X gần giữa thùng nhất trong lòng thùng. Trả None khi có kiện ghim, khi các
        khối chồng lên nhau theo X, hoặc khi một kiện sau khi dời không qua ConstraintEngine."""
        if not placements or any(p.pinned for p in placements):
            return None
        groups: dict[int, list[PlacementData]] = {}
        for p in placements:
            pkg = packages_map.get(str(p.package_id))
            if pkg is None:
                return None
            groups.setdefault(int(pkg.stop_index), []).append(p)

        moved: list[PlacementData] = []
        cursor = 0.0
        previous_end = None
        for stop_index in sorted(groups, reverse=True):
            group = groups[stop_index]
            start = min(p.x for p in group)
            end = max(p.x + p.packed_l for p in group)
            if previous_end is not None and start < previous_end - 1e-6:
                return None
            previous_end = end
            shift = cursor - start
            moved.extend(p.model_copy(update={"x": p.x + shift}) for p in group)
            cursor += end - start

        total_weight = sum(float(packages_map[str(p.package_id)].weight) for p in moved)
        if total_weight <= 0:
            return None
        cog_x = sum(
            float(packages_map[str(p.package_id)].weight) * (p.x + p.packed_l / 2.0) for p in moved
        ) / total_weight
        min_x = min(p.x for p in moved)
        max_end = max(p.x + p.packed_l for p in moved)
        dx = float(vt.inner_l) / 2.0 - cog_x
        dx = max(-min_x, min(dx, float(vt.inner_l) - max_end))
        moved = [p.model_copy(update={"x": round(p.x + dx, 4)}) for p in moved]

        for p in moved:
            others = [o for o in moved if o is not p]
            context = {
                "vehicle": vt,
                "existing_placements": others,
                "packages_map": packages_map,
                "check_cog": False,
                "check_axle": False,
            }
            if ConstraintEngine.check_all(p, context):
                return None
        return moved

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

    @staticmethod
    def _oriented_size(pkg: PackageData, rotation_type: int) -> tuple[float, float, float]:
        """Kích thước (dài, rộng, cao) theo mã hướng 0..5, cùng quy ước với vòng xếp."""
        l, w, h = float(pkg.l), float(pkg.w), float(pkg.h)
        by_rotation = {
            0: (l, w, h),  # LWH
            1: (w, l, h),  # WLH
            2: (l, h, w),  # LHW
            3: (w, h, l),  # WHL
            4: (h, l, w),  # HLW
            5: (h, w, l),  # HWL
        }
        return by_rotation.get(rotation_type, (l, w, h))

    @classmethod
    def _pinned_placements(
        cls,
        problem: ProblemRequest,
        packages_map: dict[str, PackageData],
        package_stop: dict[str, Any],
    ) -> list[PlacementData]:
        """Placement cố định của các kiện đã ghim. Kiện không thuộc chuyến bị bỏ qua."""
        result: list[PlacementData] = []
        seen: set[str] = set()
        for pin in problem.pinned or []:
            key = str(pin.package_id)
            pkg = packages_map.get(key)
            if pkg is None or key in seen:
                continue
            seen.add(key)
            try:
                rotation = int(pin.rotation_type)
            except (TypeError, ValueError):
                rotation = 0
            size = cls._oriented_size(pkg, rotation)
            result.append(PlacementData(
                package_id=pkg.id,
                x=float(pin.pos_x),
                y=float(pin.pos_y),
                z=float(pin.pos_z),
                packed_l=float(pin.packed_l) if pin.packed_l is not None else size[0],
                packed_w=float(pin.packed_w) if pin.packed_w is not None else size[1],
                packed_h=float(pin.packed_h) if pin.packed_h is not None else size[2],
                rotation_type=rotation,
                step_sequence=len(result) + 1,
                stop_zone_id=package_stop.get(key),
                pinned=True,
            ))
        return result

    @staticmethod
    def _extreme_points_of(p: PlacementData) -> list[tuple[float, float, float]]:
        return [
            (p.x + p.packed_l, p.y, p.z),
            (p.x, p.y + p.packed_w, p.z),
            (p.x, p.y, p.z + p.packed_h),
            (p.x + p.packed_l, p.y + p.packed_w, p.z),
        ]

