"""
Ba mô hình Lumo tự xếp cả phương án (gói ULTIMATE, EP_DBLF_GA_AI) — chỉ suy luận, không huấn luyện.

  LUMO_R1    app/ml/models/Lumo-R1.pt              bản phát hành R1 (PPO thuần)
  LUMO_R1_1  app/ml/models/Lumo-R1.1.pt            bản phát hành R1.1 (học bắt chước → PPO)
  LUMO_R2    app/ml/models/Lumo-R2-thu-nghiem.pt   bản thử nghiệm đầu vào v2, CHƯA qua tiêu chí phát hành

Mỗi mô hình chạy một lượt greedy trên bài toán (qua gói app/ml/lmai, chép từ LoadMaster_AI), rồi vài lượt
lấy mẫu nếu còn thời gian (không dùng chế độ "biết tính trước"). Cách xếp của mô hình được kiểm lại bằng
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

logger = logging.getLogger(__name__)

MODEL_FILES: dict[str, str] = {
    PlanAlgorithm.LUMO_R1.value: "Lumo-R1.pt",
    PlanAlgorithm.LUMO_R1_1.value: "Lumo-R1.1.pt",
    PlanAlgorithm.LUMO_R2.value: "Lumo-R2-thu-nghiem.pt",
}

_loaded: dict[str, tuple] = {}
_failed: dict[str, str] = {}


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


def _plan_score(result) -> float:
    """Như AISolver của LoadMaster_AI: hợp lệ trước, rồi tỉ lệ hàng lên xe."""
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
    lm_problem = Problem.from_request(problem.model_dump(mode="json"))

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
            if _plan_score(sampled) > _plan_score(best[code]):
                best[code] = sampled

    # Kiểm lại bằng luật của service; vi phạm ràng buộc cứng thì bỏ cả cách xếp.
    for code, result in best.items():
        if not result.verdict.valid:
            out.skipped[code] = "INVALID_PLAN"
            logger.info("%s: phương án bị bộ kiểm của lmai từ chối (%s)", code, result.verdict.reasons())
            continue
        layout, reason = client.evaluate_fixed_layout(
            problem, _to_placements(result), code, started_at=time.time() - cost[code]
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
