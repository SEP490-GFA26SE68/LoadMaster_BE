# Chép từ LoadMaster_AI/lmai/teacher.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
""""Thầy" cho học bắt chước (Lộ trình B2): tìm phương án tốt cho từng bài bằng heuristic có tham số + nhiễu,
chạy ngay trong môi trường RL (cùng tập ứng viên với AI) nên lưu được cặp (quan sát, nước đi) để mô hình bắt chước.

Điểm mỗi ứng viên = tổng có trọng số các đặc trưng ứng viên (cùng đặc trưng mô hình nhìn thấy) + nhiễu ngẫu nhiên.
Mỗi bài thử nhiều bộ trọng số, giữ phương án hợp lệ chở nhiều hàng nhất.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

from .env import EnvConfig, PackingEnv
from .problem import Problem
from .result import PlanResult

# chỉ số đặc trưng trong obs["cand"] (xem env.observe)
F_X, F_Z, F_XZ0, F_XZ1, F_TOP, F_SUP, F_VOL, F_SLOT, F_CX, F_CY, F_FA, F_RA, F_OZ, F_WALL = 3, 5, 6, 7, 8, 9, 13, 14, 16, 17, 18, 19, 20, 21

# các bộ trọng số khởi đầu (điểm càng thấp càng tốt)
PRESETS: List[Dict[int, float]] = [
    {F_OZ: 10, F_XZ0: 1.0, F_Z: 1.0, F_VOL: -0.5},                         # sâu → thấp, ưu tiên kiện to (DBLF)
    {F_OZ: 10, F_CX: 0.6, F_CY: 0.6, F_Z: 0.3, F_VOL: -0.3},                # kéo trọng tâm về giữa
    {F_OZ: 10, F_TOP: 1.0, F_XZ0: 0.5, F_SUP: -0.5, F_VOL: -0.5},          # lớp thấp, đỡ chắc
    {F_OZ: 10, F_XZ0: 0.8, F_Z: 0.8, F_CY: 0.5, F_WALL: -0.3, F_VOL: -0.8},
]


def _score(cand: np.ndarray, w: Dict[int, float], absf=(F_CX, F_CY)) -> np.ndarray:
    s = np.zeros(len(cand))
    for k, v in w.items():
        f = np.abs(cand[:, k]) if k in absf else cand[:, k]
        s += v * f
    return s


@dataclass
class Demo:
    obs: List[Dict[str, np.ndarray]]
    actions: List[int]
    rewards: List[float]
    result: PlanResult
    j: Optional[float] = None    # Lumo-R3: J sản phẩm theo hồ sơ của lượt (phương án hợp lệ)


def _rollout(problem: Problem, env_cfg: EnvConfig, w: Dict[int, float], noise: float, rng: np.random.Generator) -> Demo:
    env = PackingEnv(env_cfg)
    obs = env.reset(problem)
    traj_o, traj_a, traj_r = [], [], []
    res = None
    while not env.done:
        n = int(obs["cand_mask"].sum())
        s = _score(obs["cand"][:n], w) + noise * rng.standard_normal(n)
        a = int(np.argmin(s))
        traj_o.append(obs)
        traj_a.append(a)
        obs, r, d, info = env.step(a)
        traj_r.append(r)
        if d:
            res = info["result"]
    if res is None:
        res = env.result("thay")
    return Demo(traj_o, traj_a, traj_r, res)


from .plan_score import plan_score  # noqa: E402,F401 — một hàm chấm chung; selfimprove.py import tên này từ đây


def _product_key(r: PlanResult, profile):
    """Khoá chọn của sản phẩm theo hồ sơ `profile` (w_n của file hồ sơ): hợp lệ → số kiện → J (mục 2.9)."""
    from . import profiles as PF
    from .plan_score import score
    if not r.verdict.valid or not r.placements:
        return (False, 0, 0.0)
    return (True, len(r.placements) if PF.pack_count_first() else 0, score(r.problem, r.placements, profile).j)


def best_demo(problem: Problem, env_cfg: EnvConfig, tries: int = 16, seed: int = 0) -> Demo:
    """Thử các bộ trọng số có sẵn + biến thể ngẫu nhiên, trả về phương án tốt nhất."""
    rng = np.random.default_rng(seed)
    best: Optional[Demo] = None
    best_key = None
    # Lumo-R3: hồ sơ của lượt (lấy mẫu theo seed của bài, giống env khi train) → chọn theo khoá sản phẩm của hồ sơ đó
    profile = None
    if env_cfg.reward_j or env_cfg.obs_v3:
        probe = PackingEnv(env_cfg)
        probe.problem = problem
        probe._set_profile(None)
        profile = probe.profile
    for t in range(tries):
        base = PRESETS[t % len(PRESETS)]
        if t < len(PRESETS):
            w, noise = base, 0.0
        else:
            w = {k: v * float(np.exp(0.4 * rng.standard_normal())) for k, v in base.items()}
            w[F_OZ] = 10.0
            for k in (F_CX, F_CY, F_SUP, F_WALL):
                if rng.random() < 0.3:
                    w[k] = w.get(k, 0.0) + float(rng.normal(0, 0.4))
            noise = float(rng.uniform(0.0, 0.15))
        d = _rollout(problem, env_cfg, w, noise, rng)
        key = plan_score(d.result) if profile is None else _product_key(d.result, profile)
        if best is None or key > best_key:
            best, best_key = d, key
    if profile is not None and best is not None and best_key[0]:
        best.j = float(best_key[2])      # khoá sản phẩm (hợp lệ, số kiện, J): phần tử cuối là J
    return best
