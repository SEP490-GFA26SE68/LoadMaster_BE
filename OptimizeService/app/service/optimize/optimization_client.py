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


class OptimizationClient:
    """
    Client tương tác với Optimization Engine.
    Hỗ trợ:
      1. In-process heuristic packing (chạy nội bộ bên trong FastAPI)
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
        Thuật toán xếp hàng 3D heuristic in-process nội bộ.
        """
        start_time = time.time()
        vt = problem.vehicle
        placements: list[PlacementData] = []
        unplaced: list[UnplacedData] = []

        total_placed_weight = 0.0
        total_placed_volume = 0.0
        vehicle_volume = vt.inner_l * vt.inner_w * vt.inner_h

        cur_x = 0.0
        cur_y = 0.0
        cur_z = 0.0
        max_layer_z = 0.0

        for pkg in problem.packages:
            # 1. Kiểm tra kích thước kiện hàng có vừa xe không
            if pkg.l > vt.inner_l or pkg.w > vt.inner_w or pkg.h > vt.inner_h:
                unplaced.append(UnplacedData(package_id=pkg.id, reason="DOES_NOT_FIT_DIMENSIONS"))
                continue

            # 2. Kiểm tra giới hạn tải trọng
            if vt.max_payload_kg > 0 and (total_placed_weight + pkg.weight > vt.max_payload_kg):
                unplaced.append(UnplacedData(package_id=pkg.id, reason="WEIGHT_LIMIT_EXCEEDED"))
                continue

            # 3. Greedy placement along X -> Y -> Z
            if cur_x + pkg.l <= vt.inner_l and cur_y + pkg.w <= vt.inner_w and cur_z + pkg.h <= vt.inner_h:
                placed_x, placed_y, placed_z = cur_x, cur_y, cur_z
                cur_x += pkg.l
                max_layer_z = max(max_layer_z, cur_z + pkg.h)
            elif cur_y + pkg.w <= vt.inner_w and cur_z + pkg.h <= vt.inner_h:
                cur_x = 0.0
                cur_y += pkg.w
                placed_x, placed_y, placed_z = cur_x, cur_y, cur_z
                cur_x += pkg.l
                max_layer_z = max(max_layer_z, cur_z + pkg.h)
            elif max_layer_z + pkg.h <= vt.inner_h:
                cur_x = 0.0
                cur_y = 0.0
                cur_z = max_layer_z
                placed_x, placed_y, placed_z = cur_x, cur_y, cur_z
                cur_x += pkg.l
                max_layer_z = max(max_layer_z, cur_z + pkg.h)
            else:
                unplaced.append(UnplacedData(package_id=pkg.id, reason="VOLUME_EXCEEDED"))
                continue

            placements.append(
                PlacementData(
                    package_id=pkg.id,
                    x=placed_x,
                    y=placed_y,
                    z=placed_z,
                    packed_l=pkg.l,
                    packed_w=pkg.w,
                    packed_h=pkg.h,
                    rotation_type=0,
                    step_sequence=len(placements) + 1,
                )
            )
            total_placed_weight += pkg.weight
            total_placed_volume += pkg.l * pkg.w * pkg.h

        comp_ms = int((time.time() - start_time) * 1000)
        vol_util = round(total_placed_volume / vehicle_volume, 4) if vehicle_volume > 0 else 0.0
        wt_util = round(total_placed_weight / vt.max_payload_kg, 4) if vt.max_payload_kg > 0 else 0.0

        metrics = MetricsData(
            volume_utilization=vol_util,
            weight_utilization=wt_util,
            packed_count=len(placements),
            computation_ms=comp_ms,
        )

        return EngineOptimizationResponse(
            placements=placements,
            unplaced=unplaced,
            metrics=metrics,
        )
