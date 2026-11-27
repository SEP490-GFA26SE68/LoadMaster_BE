"""
Ba mô hình Lumo tự xếp cả phương án (gói ULTIMATE, EP_DBLF_GA_AI) — chỉ suy luận, không huấn luyện.

  LUMO_R1    app/ml/models/Lumo-R1.pt              bản phát hành R1 (PPO thuần)
  LUMO_R1_1  app/ml/models/Lumo-R1.1.pt            bản phát hành R1.1 (học bắt chước → PPO)
  LUMO_R2    app/ml/models/Lumo-R2-thu-nghiem.pt   bản thử nghiệm đầu vào v2, CHƯA qua tiêu chí phát hành

Mỗi mô hình chạy một lượt greedy trên bài toán (qua gói app/ml/lmai, chép từ LoadMaster_AI), rồi vài lượt
lấy mẫu nếu còn thời gian. M11 (cờ OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED, mặc định tắt): Lumo-R1.1 chạy thêm chế độ
"biết tính trước" (lmai/lookahead.py: ở bước mô hình không chắc, thử vài nước của mô hình + 4 bộ Heuristic-4, cho mô
hình xếp tiếp tới hết, đi nước có phương án cuối tốt nhất), chấm theo J của hồ sơ job (cờ PLAN_SCORE_J) hoặc cách chấm
cũ; tối đa LMAI_LOOKAHEAD_SEC giây; không bao giờ kém lượt greedy; vẫn mang mã LUMO_R1_1. Cách xếp của mô hình được kiểm lại bằng
ConstraintEngine / HC-11 / HC-12 / UnloadFeasibilityValidator của chính service và tính chỉ số theo cùng
một cách với EP (OptimizationClient.evaluate_fixed_layout); vi phạm ràng buộc cứng thì bỏ cả cách xếp.

lmai không biết kiện ghim: bài có kiện ghim thì bỏ qua cả ba mô hình (không bao giờ dời kiện ghim).
Thiếu torch / numpy, thiếu file trọng số hoặc mô hình lỗi: bỏ qua mô hình đó, ghi log, job vẫn chạy.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from app.config.settings import settings
from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, PlacementData
from app.dto.optimization.engine.problem_request import ProblemRequest
from app.service.optimize import plan_score
from app.service.optimize.constraint_engine import ConstraintEngine

logger = logging.getLogger(__name__)

MODEL_FILES: dict[str, str] = {
    PlanAlgorithm.LUMO_R1.value: "Lumo-R1.pt",
    PlanAlgorithm.LUMO_R1_1.value: "Lumo-R1.1.pt",
    PlanAlgorithm.LUMO_R2.value: "Lumo-R2-thu-nghiem.pt",
}

_loaded: dict[str, tuple] = {}
_failed: dict[str, str] = {}

LOOKAHEAD_CODE = PlanAlgorithm.LUMO_R1_1.value
LOOKAHEAD_MIN_SEC = 1.0      # còn ít hơn chừng này thì không chạy "biết tính trước"
LOOKAHEAD_TAIL = 1.5         # phần giữ = LOOKAHEAD_TAIL × lượt greedy (hết giờ thì đi nốt bằng greedy) + CHECK_SEC
LOOKAHEAD_CHECK_SEC = 1.0    # phần giữ cho bước kiểm lại bằng luật của service


@dataclass
class LumoRun:
    layouts: list[EngineOptimizationResponse] = field(default_factory=list)
    tried: list[str] = field(default_factory=list)
    skipped: dict[str, str] = field(default_factory=dict)


def torch_available() -> bool:
    try:
        import numpy  # noqa: F401
        import torch  # noqa: F401
    except ImportError:
        return False
    return True


def model_path(code: str) -> Path:
    path = Path(settings.LUMO_MODELS_DIR) / MODEL_FILES[code]
    if not path.is_absolute():
        path = Path(__file__).resolve().parents[2] / path
    return path


def _load(code: str):
    """(mô hình, EnvConfig) đã nạp một lần cho cả tiến trình; lỗi thì nhớ lý do, không thử lại."""
    if code in _loaded:
        return _loaded[code]
    if code in _failed:
        raise RuntimeError(_failed[code])
    try:
        from app.ml.lmai.ppo import load_policy

        path = model_path(code)
        if not path.is_file():
            raise FileNotFoundError(str(path))
        model, env_cfg, _ = load_policy(str(path), "cpu")
        _loaded[code] = (model, env_cfg)
        return _loaded[code]
    except Exception as exc:
        _failed[code] = f"{type(exc).__name__}: {exc}"
        raise


def _rollout(model, env_cfg, lm_problem, generator=None):
    """Một lượt xếp của mô hình: greedy khi không có generator, lấy mẫu theo xác suất khi có."""
    import torch

    from app.ml.lmai.env import PackingEnv
    from app.ml.lmai.model import obs_to_torch
    from app.ml.lmai.ppo import _stack

    env = PackingEnv(env_cfg)
    obs = env.reset(lm_problem)
    if env.done:
        return env.result("AI")
    with torch.inference_mode():
        while True:
            logits, _ = model(obs_to_torch(_stack([obs]), "cpu"))
            if generator is None:
                action = int(logits[0].argmax().item())
            else:
                probs = torch.softmax(logits[0].float(), -1)
                action = int(torch.multinomial(probs, 1, generator=generator).item())
            obs, _, done, info = env.step(action)
            if done:
                return info["result"]


def _plan_score(result, problem: Optional[ProblemRequest] = None):
    """
    Như AISolver của LoadMaster_AI: hợp lệ trước, rồi tỉ lệ hàng lên xe.
    Cờ PLAN_SCORE_J (M6): hợp lệ trước, rồi J theo hồ sơ của lần chạy.
    """
    if problem is not None and plan_score.enabled():
        if not result.verdict.valid:
            return plan_score.selection_key(problem, [])
        return plan_score.selection_key(problem, _to_placements(result))
    return (10.0 if result.verdict.valid else 0.0) + result.cargo_frac + 0.001 * result.packed_frac_raw


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


def _lmai_profile(problem: ProblemRequest):
    """Hồ sơ của job (PLAN_SCORE_J bật) dưới dạng PlanProfile của lmai; tắt: None = cách chấm cũ của lmai."""
    if not plan_score.enabled():
        return None
    from app.ml.lmai.profiles import PlanProfile as LmaiProfile

    p = plan_score.resolve_profile(problem.plan_profile)
    return LmaiProfile(p.code, p.name, p.w_vol, p.w_n, tuple(p.weights), p.assumed)


def _run_lookahead(problem: ProblemRequest, lm_problem, best: dict, cost: dict, deadline: Optional[float]):
    """M11: Lumo-R1.1 + "biết tính trước"; thay best[LUMO_R1_1] khi hơn (theo cùng khoá chọn của job). Trả phương án
    bị thay (để lùi về nếu luật của service từ chối phương án mới) hoặc None."""
    code = LOOKAHEAD_CODE
    limit = float(settings.LMAI_LOOKAHEAD_SEC)
    if deadline is not None:
        limit = min(limit, deadline - time.monotonic() - LOOKAHEAD_TAIL * cost[code] - LOOKAHEAD_CHECK_SEC)
    if limit < LOOKAHEAD_MIN_SEC:
        logger.info("%s lookahead skipped: còn %.1fs", code, limit)
        return None
    from app.ml.lmai.lookahead import Lookahead

    model, env_cfg = _load(code)
    la = Lookahead(model, env_cfg, topk=3, use_heur=True, time_limit=limit, profile=_lmai_profile(problem))
    result = la.solve(lm_problem)
    st = la.stats
    better = _plan_score(result, problem) > _plan_score(best[code], problem)
    logger.info("%s lookahead: %d bước, thử %d, đổi %d, %.1fs (giới hạn %.1fs), %s", code, st["steps"], st["tried"],
                st["changed"], st["seconds"], limit, "hơn" if better else "không hơn")
    if not better:
        return None
    previous = best[code]
    best[code] = result
    cost[code] += st["seconds"]
    return previous


def run_lumo_models(
    problem: ProblemRequest,
    codes: list[str],
    client,
    deadline: Optional[float] = None,
    samples: Optional[int] = None,
) -> LumoRun:
    """Chạy các mô hình trong `codes` (thứ tự giữ nguyên) trên bài toán, trả cách xếp đã kiểm lại."""
    out = LumoRun()
    codes = [c for c in codes if c in MODEL_FILES]
    if not codes:
        return out
    if problem.pinned:
        logger.info("Lumo models skipped: bài có %d kiện ghim, lmai không giữ được ghim", len(problem.pinned))
        out.skipped = {c: "PINNED" for c in codes}
        return out
    if not torch_available():
        logger.warning(
            "Gói ULTIMATE chạy KHÔNG có mô hình AI (%s): chưa cài torch / numpy, phương án chỉ từ heuristic. "
            "Cài bằng: pip install -r requirements-ai.txt",
            ", ".join(codes),
        )
        out.skipped = {c: "TORCH_MISSING" for c in codes}
        return out

    import torch

    from app.ml.lmai.problem import Problem

    start = time.monotonic()
    end = start + float(settings.LUMO_TIME_BUDGET_SEC)
    if deadline is not None:
        end = min(end, deadline)
    samples = int(settings.LUMO_SAMPLES) if samples is None else samples
    # Luật ràng buộc của lmai theo cờ PHYSICS_RULES_V2 của service (tắt: luật cũ, như trước khi đồng bộ).
    lm_problem = Problem.from_request(problem.model_dump(mode="json"), physics_v2=ConstraintEngine.physics_v2())

    # Lượt 1: greedy cho từng mô hình (mỗi mô hình có ít nhất một lượt trước khi lượt nào lấy mẫu).
    best: dict[str, object] = {}
    cost: dict[str, float] = {}
    started: dict[str, float] = {}
    for code in codes:
        if time.monotonic() >= end:
            out.skipped[code] = "TIME_LIMIT"
            logger.info("%s skipped: hết thời gian", code)
            continue
        try:
            model, env_cfg = _load(code)
        except Exception as exc:
            out.skipped[code] = "LOAD_FAILED"
            logger.warning("%s skipped: không nạp được mô hình (%s)", code, exc)
            continue
        started[code] = time.monotonic()
        try:
            best[code] = _rollout(model, env_cfg, lm_problem)
        except Exception as exc:
            out.skipped[code] = "INFERENCE_FAILED"
            logger.warning("%s skipped: lỗi khi suy luận (%s)", code, exc)
            continue
        cost[code] = time.monotonic() - started[code]
        out.tried.append(code)

    # Lượt 2: vài lượt lấy mẫu theo xác suất, lần lượt từng mô hình, khi còn thời gian.
    generator = torch.Generator(device="cpu").manual_seed(1)
    for _ in range(max(samples, 0)):
        for code in list(best):
            if time.monotonic() + cost[code] > end:
                continue
            model, env_cfg = _load(code)
            try:
                sampled = _rollout(model, env_cfg, lm_problem, generator=generator)
            except Exception as exc:
                logger.warning("%s: bỏ lượt lấy mẫu lỗi (%s)", code, exc)
                continue
            if _plan_score(sampled, problem) > _plan_score(best[code], problem):
                best[code] = sampled

    # M11: Lumo-R1.1 + "biết tính trước" (ngân sách riêng, trong hạn của job). Lỗi thì giữ kết quả đã có.
    fallback: dict[str, object] = {}
    if settings.OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED and LOOKAHEAD_CODE in best:
        try:
            previous = _run_lookahead(problem, lm_problem, best, cost, deadline)
            if previous is not None:
                fallback[LOOKAHEAD_CODE] = previous
        except Exception as exc:
            logger.warning("%s lookahead failed: %s", LOOKAHEAD_CODE, exc)

    # Kiểm lại bằng luật của service; vi phạm ràng buộc cứng thì bỏ cả cách xếp.
    for code, result in best.items():
        if not result.verdict.valid:
            out.skipped[code] = "INVALID_PLAN"
            logger.info("%s: phương án bị bộ kiểm của lmai từ chối (%s)", code, result.verdict.reasons())
            continue
        layout, reason = client.evaluate_fixed_layout(
            problem, _to_placements(result), code, started_at=time.time() - cost[code]
        )
        if layout is None and code in fallback and fallback[code].verdict.valid:
            # lmai và service chưa khớp tuyệt đối: phương án "biết tính trước" bị từ chối thì lùi về phương án trước đó
            logger.warning("%s: luật của service từ chối phương án biết tính trước (%s) → dùng phương án greedy",
                           code, reason)
            layout, reason = client.evaluate_fixed_layout(
                problem, _to_placements(fallback[code]), code, started_at=time.time() - cost[code]
            )
        if layout is None:
            out.skipped[code] = "CONSTRAINT_VIOLATED"
            logger.warning("%s: bỏ cách xếp vì ConstraintEngine của service từ chối (%s)", code, reason)
            continue
        out.layouts.append(layout)
        logger.info(
            "%s: %d/%d kiện, thể tích %.4f, greedy %.2fs",
            code, len(layout.placements), len(problem.packages),
            layout.metrics.volume_utilization, cost[code],
        )
    return out
