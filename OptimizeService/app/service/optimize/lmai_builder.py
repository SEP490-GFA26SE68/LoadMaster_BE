"""
Bộ dựng chung từ LoadMaster_AI (`app/ml/lmai`, M9 — KE-HOACH-TOI-UU, quyết định D1 26/11/2026).

Cờ OPTIMIZATION_LMAI_BUILDER_ENABLED (mặc định tắt). Bật thì bước EP của job (mọi gói) dựng phương án bằng
`state` / `packer` của lmai thay cho bộ dựng EP của service:

  EP-DBLF        lmai.heuristics.ep_dblf   (sâu → thấp → trái)
  EP-trọng-tâm   lmai.heuristics.ep_center (kéo trọng tâm về giữa)
  Heuristic-4    lmai.teacher.PRESETS      (4 bộ trọng số chấm chỗ đặt, không nhiễu — mỗi bộ một cách xếp)

Mọi cách xếp vẫn mang mã EP_DBLF (đều là bộ dựng Extreme Points, chỉ khác cách chấm chỗ đặt — như bốn chiến lược
của bộ dựng cũ), nên không thêm mã thuật toán mới cho FE. Kiểm cuối vẫn bằng luật của service
(`OptimizationClient.evaluate_fixed_layout`: ConstraintEngine từng chỗ đặt, HC-11 / HC-12, UnloadFeasibilityValidator,
H13); cách xếp nào bị từ chối thì bỏ. Kiểm theo thứ tự khoá chọn của job (tốt nhất trước) cho tới hạn chót.

Không dùng được (bài có kiện ghim — lmai không giữ ghim; thiếu numpy; không cách xếp nào qua kiểm) → trả rỗng và
bước EP chạy bộ dựng cũ như khi tắt cờ.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Optional

from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, PlacementData
from app.dto.optimization.engine.problem_request import ProblemRequest
from app.service.optimize import plan_score
from app.service.optimize.constraint_engine import ConstraintEngine

logger = logging.getLogger(__name__)

# (nhãn trong log, chiến lược của bộ dựng cũ dùng làm hạt giống cho GA / SA)
BUILDS = (
    ("EP-DBLF", "DEEP_FIRST"),
    ("EP-trong-tam", "CENTER_FIRST"),
    ("H4-0", "DEEP_FIRST"),
    ("H4-1", "CENTER_FIRST"),
    ("H4-2", "HEAVY_FIRST"),
    ("H4-3", "DEEP_FIRST"),
)


@dataclass
class LmaiBuild:
    layouts: list[EngineOptimizationResponse] = field(default_factory=list)
    # (chiến lược, cách xếp) cho run_ga_sa — cùng dạng `ep_runs` của bộ dựng cũ
    ep_runs: list[tuple[str, EngineOptimizationResponse]] = field(default_factory=list)
    skipped: Optional[str] = None
    rejected: dict[str, str] = field(default_factory=dict)


def numpy_available() -> bool:
    try:
        import numpy  # noqa: F401
    except ImportError:
        return False
    return True


def _to_placements(result) -> list[PlacementData]:
    return [
        PlacementData(
            package_id=p.package_id,
            x=float(p.x), y=float(p.y), z=float(p.z),
            packed_l=float(p.packed_l), packed_w=float(p.packed_w), packed_h=float(p.packed_h),
            rotation_type=int(p.rotation_type),
            step_sequence=int(p.step_sequence),
        )
        for p in sorted(result.placements, key=lambda item: item.step_sequence)
    ]


def _plans(lm_problem, deadline: float) -> list[tuple[str, str, object, float]]:
    """Các phương án lmai (nhãn, chiến lược, PlanResult, giây), dừng mở lượt mới khi tới `deadline`."""
    import numpy as np

    from app.ml.lmai.env import EnvConfig
    from app.ml.lmai.heuristics import ep_center, ep_dblf
    from app.ml.lmai.teacher import PRESETS, _rollout

    out = []
    for name, strategy in BUILDS:
        if out and time.monotonic() >= deadline:
            break
        t0 = time.monotonic()
        if name == "EP-DBLF":
            res = ep_dblf(lm_problem)
        elif name == "EP-trong-tam":
            res = ep_center(lm_problem)
        else:
            res = _rollout(lm_problem, EnvConfig(), PRESETS[int(name[-1])], 0.0, np.random.default_rng(0)).result
        out.append((name, strategy, res, time.monotonic() - t0))
    return out


def build_layouts(problem: ProblemRequest, client, deadline: float) -> LmaiBuild:
    """Dựng + kiểm các cách xếp lmai cho bước EP của job. `deadline`: lúc phải dừng (đã trừ phần giữ cho bước sau)."""
    out = LmaiBuild()
    if problem.pinned:
        out.skipped = "PINNED"
        return out
    if not numpy_available():
        out.skipped = "NUMPY_MISSING"
        return out
    from app.ml.lmai.problem import Problem

    lm_problem = Problem.from_request(problem.model_dump(mode="json"), physics_v2=ConstraintEngine.physics_v2())
    plans = _plans(lm_problem, deadline)

    def key(item):
        name, _, res, _ = item
        if not res.verdict.valid or not res.placements:
            return (False,)
        if plan_score.enabled():
            return (True,) + tuple(plan_score.selection_key(problem, _to_placements(res)))
        return (True, res.cargo_frac, res.packed_frac_raw)

    seen: set[tuple] = set()
    for name, strategy, res, sec in sorted(plans, key=key, reverse=True):
        if not res.verdict.valid or not res.placements:
            out.rejected[name] = "LMAI_INVALID:" + ",".join(res.verdict.reasons()[:2])
            continue
        sig = tuple(sorted((p.package_id, round(p.x, 2), round(p.y, 2), round(p.z, 2)) for p in res.placements))
        if sig in seen:          # hai cách chấm cho cùng phương án: kiểm một lần
            continue
        if out.layouts and time.monotonic() >= deadline:
            out.rejected[name] = "TIME_LIMIT"
            continue
        seen.add(sig)
        layout, reason = client.evaluate_fixed_layout(
            problem, _to_placements(res), PlanAlgorithm.EP_DBLF.value, started_at=time.time() - sec, deadline=deadline,
        )
        if layout is None:
            out.rejected[name] = reason or "REJECTED"
            logger.warning("lmai %s: bỏ cách xếp vì luật của service từ chối (%s)", name, reason)
            continue
        out.layouts.append(layout)
        out.ep_runs.append((strategy, layout))
        logger.info("lmai %s: %d/%d kiện, dựng %.2fs", name, len(layout.placements), len(problem.packages), sec)
    return out
