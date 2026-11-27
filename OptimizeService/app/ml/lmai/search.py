# Chép từ LoadMaster_AI/lmai/search.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""CP1 — tìm kiếm có mô hình dẫn đường khi chạy (beam search kiểu AlphaZero rút gọn).

Mỗi bước: với mỗi nhánh đang giữ, lấy `topk` nước mô hình thích nhất, đi thử (sao chép môi trường), chấm nhánh con bằng
   điểm thưởng đã nhận + đầu giá trị của mô hình (ước lượng phần thưởng còn lại, đã tính phạt trọng tâm / tải trục).
Giữ `width` nhánh tốt nhất, lặp tới khi xếp xong. Luôn chạy kèm 1 lần greedy và trả về phương án tốt nhất → không bao giờ
kém chạy 1 lần. Sửa đúng điểm yếu "sai một bước kéo sai các bước sau".
"""
from __future__ import annotations

import copy
import time
from typing import List, Optional

import numpy as np

from .env import EnvConfig, PackingEnv
from .problem import Problem
from .plan_score import plan_score
from .result import PlanResult


_score = plan_score  # một hàm chấm chung (lmai/plan_score.py); lookahead.py import tên này


def clone_env(env: PackingEnv) -> PackingEnv:
    return copy.deepcopy(env, memo={id(env.problem): env.problem})


class _Beam:
    __slots__ = ("env", "obs", "ret", "done", "result")

    def __init__(self, env, obs, ret, done=False, result=None):
        self.env, self.obs, self.ret, self.done, self.result = env, obs, ret, done, result


def _forward(model, obs_list, device):
    import torch
    from .model import obs_to_torch
    batch = {k: np.stack([o[k] for o in obs_list]) for k in obs_list[0]}
    with torch.no_grad():
        logits, v = model(obs_to_torch(batch, device))
    return logits.float().cpu().numpy(), v.float().cpu().numpy()


def greedy(model, problem: Problem, env_cfg: EnvConfig, device="cpu") -> PlanResult:
    env = PackingEnv(env_cfg)
    obs = env.reset(problem)
    res = None
    while not env.done:
        lg, _ = _forward(model, [obs], device)
        obs, _, d, info = env.step(int(lg[0].argmax()))
        if d:
            res = info["result"]
    return res if res is not None else env.result("AI")


def beam_search(model, problem: Problem, env_cfg: EnvConfig, width: int = 3, topk: int = 3, device="cpu",
                time_limit: Optional[float] = None) -> PlanResult:
    t0 = time.perf_counter()
    best = greedy(model, problem, env_cfg, device)
    env = PackingEnv(env_cfg)
    obs = env.reset(problem)
    if env.done:
        return best
    beams: List[_Beam] = [_Beam(env, obs, 0.0)]
    finished: List[PlanResult] = []
    while beams:
        if time_limit and time.perf_counter() - t0 > time_limit:
            break
        logits, _ = _forward(model, [b.obs for b in beams], device)
        children: List[_Beam] = []
        for b, lg in zip(beams, logits):
            n = int(b.obs["cand_mask"].sum())
            acts = np.argsort(-lg[:n])[:topk]
            for j, a in enumerate(acts):
                e = b.env if j == len(acts) - 1 else clone_env(b.env)  # nhánh cuối dùng luôn môi trường gốc
                o, r, d, info = e.step(int(a))
                if d:
                    finished.append(info["result"])
                else:
                    children.append(_Beam(e, o, b.ret + r))
        if not children:
            break
        _, vals = _forward(model, [c.obs for c in children], device)
        prio = np.array([c.ret for c in children]) + vals
        keep = np.argsort(-prio)[:width]
        beams = [children[i] for i in keep]
    for r in finished:
        if _score(r) > _score(best):
            best = r
    best.seconds = time.perf_counter() - t0
    return best


# ------------------------------------------------------------------ chạy song song (chấm điểm)
_W = {}


def _init(model_path, env_d):
    import torch
    torch.set_num_threads(1)
    from .ppo import load_policy
    _W["model"], _, _ = load_policy(model_path, "cpu")
    _W["env_cfg"] = EnvConfig(**env_d)


def _job(args):
    i, problem, width, topk, tl = args
    r = beam_search(_W["model"], problem, _W["env_cfg"], width, topk, "cpu", tl)
    return i, r


def beam_many(model_path: str, problems: List[Problem], env_cfg: EnvConfig, width: int, topk: int, workers: int,
              time_limit: Optional[float] = None) -> List[PlanResult]:
    import multiprocessing as mp
    from dataclasses import asdict
    out: List[Optional[PlanResult]] = [None] * len(problems)
    ctx = mp.get_context("spawn")
    with ctx.Pool(workers, initializer=_init, initargs=(model_path, asdict(env_cfg))) as pool:
        for i, r in pool.imap_unordered(_job, [(i, p, width, topk, time_limit) for i, p in enumerate(problems)]):
            out[i] = r
    return out


# ------------------------------------------------------------------ "biết tính trước" khi chạy
def lookahead(model, problem: Problem, env_cfg: EnvConfig, topk: int = 3, use_heur: bool = True,
              confident: float = 0.97, device="cpu", time_limit: Optional[float] = None) -> PlanResult:
    """Mỗi bước: thử `topk` nước mô hình thích nhất (+ nước heuristic), cho chính mô hình xếp tiếp tới hết, đi nước có
    phương án cuối tốt nhất. Bản nhanh ở lmai/lookahead.py (cùng kết quả, nhớ điểm + chấm theo lô)."""
    from .lookahead import Lookahead
    return Lookahead(model, env_cfg, topk, use_heur, confident, time_limit, device).solve(problem)


def _job_la(args):
    i, problem, topk, tl, conf, heur = args
    return i, lookahead(_W["model"], problem, _W["env_cfg"], topk, heur, conf, "cpu", tl)


def lookahead_many(model_path: str, problems: List[Problem], env_cfg: EnvConfig, topk: int, workers: int,
                   time_limit: Optional[float] = None, confident: float = 0.97, use_heur: bool = True) -> List[PlanResult]:
    import multiprocessing as mp
    from dataclasses import asdict
    out: List[Optional[PlanResult]] = [None] * len(problems)
    order = sorted(range(len(problems)), key=lambda i: -len(problems[i].packages))
    ctx = mp.get_context("spawn")
    with ctx.Pool(workers, initializer=_init, initargs=(model_path, asdict(env_cfg))) as pool:
        jobs = [(i, problems[i], topk, time_limit, confident, use_heur) for i in order]
        for i, r in pool.imap_unordered(_job_la, jobs):
            out[i] = r
    return out
