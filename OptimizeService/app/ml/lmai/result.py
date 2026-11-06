# Chép từ LoadMaster_AI/lmai/result.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Kết quả một phương án + đổi sang EngineOptimizationResponse của OptimizeService."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List

from .problem import Placement, Problem
from .validate import Verdict, final_checks


@dataclass
class PlanResult:
    algorithm: str
    problem: Problem
    placements: List[Placement]
    unplaced: List[str]
    verdict: Verdict
    seconds: float
    rehandling: int = 0

    @property
    def placed_volume(self) -> float:
        return sum(p.packed_l * p.packed_w * p.packed_h for p in self.placements)

    @property
    def volume_util(self) -> float:
        """Như OptimizeService: thể tích đã xếp / thể tích thùng (0 nếu phương án bị từ chối)."""
        return self.placed_volume / self.problem.vehicle.volume if self.verdict.valid else 0.0

    @property
    def cargo_frac(self) -> float:
        """Tỉ lệ thể tích hàng của chuyến đã lên xe (0 nếu phương án bị từ chối)."""
        cv = self.problem.cargo_volume
        return (self.placed_volume / cv if cv > 0 else 0.0) if self.verdict.valid else 0.0

    @property
    def packed_frac_raw(self) -> float:
        cv = self.problem.cargo_volume
        return self.placed_volume / cv if cv > 0 else 0.0

    def to_response(self) -> dict:
        """Đúng định dạng EngineOptimizationResponse. Phương án sai trọng tâm/tải trục/thứ tự dỡ bị từ chối như bản gốc."""
        pk = {p.id: p for p in self.problem.packages}
        if self.verdict.valid:
            pls = self.placements
            unpl = [{"package_id": i, "reason": "CONSTRAINT_VIOLATED", "violated_constraints": ["NO_FEASIBLE_SPACE"]} for i in self.unplaced]
        else:
            pls = []
            reasons = self.verdict.reasons() or ["CONSTRAINT_VIOLATED"]
            unpl = [{"package_id": p.id, "reason": "CONSTRAINT_VIOLATED", "violated_constraints": reasons} for p in self.problem.packages]
        v = self.problem.vehicle
        tw = sum(pk[p.package_id].weight for p in pls)
        cog = (None, None, None)
        if pls and tw > 0:
            cog = tuple(round(sum(pk[p.package_id].weight * c for p, c in zip(pls, cs)) / tw, 3) for cs in (
                [p.x + p.packed_l / 2 for p in pls], [p.y + p.packed_w / 2 for p in pls], [p.z + p.packed_h / 2 for p in pls]))
        return {
            "placements": [{"package_id": p.package_id, "x": p.x, "y": p.y, "z": p.z, "packed_l": p.packed_l,
                            "packed_w": p.packed_w, "packed_h": p.packed_h, "rotation_type": p.rotation_type,
                            "step_sequence": p.step_sequence, "stop_zone_id": p.stop_zone_id} for p in pls],
            "unplaced": unpl,
            "metrics": {
                "volume_utilization": round(sum(p.packed_l * p.packed_w * p.packed_h for p in pls) / v.volume, 4),
                "weight_utilization": round(tw / v.max_payload_kg, 4) if v.max_payload_kg else 0.0,
                "packed_count": len(pls),
                "computation_ms": int(self.seconds * 1000),
                "cog_x": cog[0], "cog_y": cog[1], "cog_z": cog[2],
                "front_axle_load": round(self.verdict.front_axle, 2) if pls and self.verdict.front_axle is not None else None,
                "rear_axle_load": round(self.verdict.rear_axle, 2) if pls and self.verdict.rear_axle is not None else None,
                "rehandling_count": self.rehandling if pls else 0,
            },
        }


def finish(problem: Problem, packer, name: str, seconds: float) -> PlanResult:
    st = packer.state
    pls = st.placements()
    unplaced = [problem.packages[i].id for i in packer.unplaced] + [problem.packages[i].id for i in packer.buffer] + \
        [problem.packages[i].id for i in packer.queue]
    placed_ids = {p.package_id for p in pls}
    unplaced = [u for u in dict.fromkeys(unplaced) if u not in placed_ids]
    return PlanResult(name, problem, pls, unplaced, final_checks(problem, pls), seconds, packer.rehandling)
