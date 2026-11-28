"""
Lý do đặt và số đo của từng chỗ đặt (spec 7.3; KE-HOACH-TOI-UU M6) — dữ liệu cho màn hình và trợ lý Lumo giải thích,
không để mô hình ngôn ngữ đoán. Chỉ chạy khi PLAN_SCORE_J bật.

Mọi mã và số SUY TỪ PHƯƠNG ÁN CUỐI (không phải nhật ký của bộ giải):
  ZONE_STOP_n            tâm X của kiện nằm trong vùng của điểm giao n của chính nó;
  OUT_OF_ZONE_NO_SPACE   tâm X nằm trong vùng của điểm giao khác (đúng kiện được đếm ở rehandling_count / S1);
  BOTTOM_HIGH_CAPACITY   kiện đang đỡ kiện khác (pⱼ > 0);
  HEAVY_LOW              kiện nằm sàn và thuộc 25 % kiện nặng nhất của phương án;
  MUST_LOAD              kiện bắt buộc (H13);  PINNED  kiện ghim.
Số đo: pⱼ = tải nhận từ trên / diện tích mặt trên đang chịu tải (cùng cách truyền tải của ConstraintEngine.check_stacking),
σⱼ (ConstraintEngine.top_load_capacity), tỉ lệ đỡ = diện tích đáy được đỡ / diện tích đáy (1 khi nằm sàn),
khoảng cách tới cửa = L − (x + l).
"""
from __future__ import annotations

from typing import Optional

from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, PlacementData
from app.service.optimize import plan_score
from app.service.optimize.constraint_engine import ConstraintEngine

EPS = ConstraintEngine.EPSILON


def _transfer(placements, weights, obstacles):
    """(tải nhận từ trên, diện tích mặt trên chịu tải cm², diện tích đáy được đỡ cm²) — như check_stacking."""
    n = len(placements)
    received = [0.0] * n
    loaded_area = [0.0] * n
    supported_area = [0.0] * n
    for top_idx in sorted(range(n), key=lambda i: float(placements[i].z), reverse=True):
        top = placements[top_idx]
        if float(top.z) <= EPS:
            continue
        supports = []
        for bottom_idx, bottom in enumerate(placements):
            if bottom_idx == top_idx or abs(float(bottom.z) + float(bottom.packed_h) - float(top.z)) > EPS:
                continue
            ox = max(0.0, min(float(top.x + top.packed_l), float(bottom.x + bottom.packed_l)) - max(float(top.x), float(bottom.x)))
            oy = max(0.0, min(float(top.y + top.packed_w), float(bottom.y + bottom.packed_w)) - max(float(top.y), float(bottom.y)))
            if ox * oy > EPS:
                supports.append((bottom_idx, ox * oy))
        for obstacle in obstacles or []:
            if not obstacle.load_bearing or abs(obstacle.z + obstacle.height - top.z) > EPS:
                continue
            dx = max(0.0, min(top.x + top.packed_l, obstacle.x + obstacle.length) - max(top.x, obstacle.x))
            dy = max(0.0, min(top.y + top.packed_w, obstacle.y + obstacle.width) - max(top.y, obstacle.y))
            if dx * dy > EPS:
                supports.append((None, dx * dy))
        total = sum(a for _, a in supports)
        supported_area[top_idx] = total
        if total <= 0:
            continue
        load = weights[top_idx] + received[top_idx]
        for bottom_idx, area in supports:
            if bottom_idx is not None:
                received[bottom_idx] += load * area / total
                loaded_area[bottom_idx] += area
    return received, loaded_area, supported_area


def annotate_placements(problem, placements: list[PlacementData]) -> list[PlacementData]:
    if not placements:
        return []
    from app.service.optimize.optimization_client import OptimizationClient
    from app.service.optimize.stop_zone_calculator import StopZoneCalculator

    vt = problem.vehicle
    pk = {str(p.id): p for p in problem.packages}
    _, zone_res, package_stop = OptimizationClient._stop_groups(problem)
    weights = [float(pk[str(p.package_id)].weight) for p in placements]
    received, loaded_area, supported_area = _transfer(placements, weights, vt.obstacles)
    heavy = sorted(weights)[int(0.75 * (len(weights) - 1))] if weights else 0.0
    out = []
    for i, p in enumerate(placements):
        pkg = pk[str(p.package_id)]
        codes = []
        if p.pinned:
            codes.append("PINNED")
        if pkg.must_load:
            codes.append("MUST_LOAD")
        zone = StopZoneCalculator.zone_at(zone_res.zones, float(p.x) + float(p.packed_l) / 2.0)
        own = package_stop.get(str(p.package_id))
        if zone is not None:
            codes.append(f"ZONE_STOP_{pkg.stop_index}" if str(zone.stop_id) == str(own) else "OUT_OF_ZONE_NO_SPACE")
        if received[i] > EPS:
            codes.append("BOTTOM_HIGH_CAPACITY")
        if float(p.z) <= EPS and weights[i] >= heavy and len(weights) >= 4:
            codes.append("HEAVY_LOW")
        base = float(p.packed_l) * float(p.packed_w)
        pressure = received[i] / (loaded_area[i] / 1e4) if received[i] > EPS and loaded_area[i] > 0 else 0.0
        out.append(p.model_copy(update={
            "reason_codes": codes,
            "stack_pressure_kg_m2": round(pressure, 3),
            "top_load_capacity_kg_m2": ConstraintEngine.top_load_capacity(pkg),
            "support_ratio": 1.0 if float(p.z) <= EPS else round(min(supported_area[i] / base, 1.0), 4),
            "distance_to_door_cm": round(float(vt.inner_l) - (float(p.x) + float(p.packed_l)), 3),
        }))
    return out


def annotate_result(problem, result: EngineOptimizationResponse, score_data=None,
                    profile: Optional[plan_score.PlanProfile] = None) -> EngineOptimizationResponse:
    """Kết quả kèm J + phân rã (giữ `score_data` nếu đã có) và lý do đặt cho từng chỗ đặt."""
    if score_data is None:
        score_data = plan_score.to_data(plan_score.score(problem, result.placements, profile))
    return result.model_copy(update={
        "placements": annotate_placements(problem, list(result.placements)),
        "plan_score": score_data,
    })
