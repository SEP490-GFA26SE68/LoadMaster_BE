# Chép từ LoadMaster_AI/lmai/lookahead.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
""""Biết tính trước" bản nhanh — dùng cho gói Ultimate (chế độ kỹ).

Giống lmai/search.lookahead (mỗi bước: thử vài nước, cho mô hình xếp tiếp tới hết, đi nước tốt nhất) nhưng nhanh hơn nhờ:
1. Nhớ điểm phương án đang theo: phần xếp tiếp của nước vừa chọn chính là cách mô hình xếp tiếp từ bước sau, nên nước
   "greedy" ở bước sau đã biết điểm — khỏi chạy lại (tiết kiệm 1 lượt thử mỗi bước).
2. Xếp tiếp các nước thử theo lô: mọi nhánh bước cùng nhau, mô hình chấm 1 lần cho cả lô.
3. Tuỳ chọn chia các nhánh cho nhiều tiến trình (`workers`) — dùng trong service để 1 chuyến chạy trên nhiều nhân CPU.
Kết quả không bao giờ kém chạy 1 lần (luôn giữ phương án tốt nhất đã biết). Hết `time_limit` giây thì đi tiếp bằng greedy.

`strict_limit=True` (backend M11): chỉ mở một lượt thử khi ước tính nó xong trước `time_limit` (số nhánh × thời gian
greedy đo ở đầu × phần kiện còn lại). Không bật thì như cũ: lượt thử mở sát giờ có thể chạy tới vài lần lượt greedy sau
`time_limit` (đo 27/11, 40 kiện: tới 7 lần) — giữ mặc định để số nghiên cứu cũ không đổi.

Chấm phương án: `profile` = ACTIVE (mặc định: hồ sơ đặt cho tiến trình bằng `plan_score.set_profile`), một `PlanProfile`
(backend M11: hồ sơ của job → khoá hợp lệ → số kiện → J, như backend chọn phương án), hoặc None (cách chấm cũ).
"""
from __future__ import annotations

import pickle
import time
from typing import List, Optional, Sequence, Tuple

import numpy as np

from .env import EnvConfig, PackingEnv
from .problem import Problem
from .result import PlanResult
from .plan_score import ACTIVE, argbest, better
from .search import _forward, _score, clone_env
from .teacher import PRESETS, _score as heur_score


def finish_many(model, envs: List[PackingEnv], obs: List[dict], device="cpu") -> List[PlanResult]:
    """Cho mô hình xếp tiếp (greedy) nhiều môi trường cùng lúc, chấm theo lô."""
    out: List[Optional[PlanResult]] = [None] * len(envs)
    active = []
    for i, e in enumerate(envs):
        if e.done:
            out[i] = e.result("AI")
        else:
            active.append(i)
    while active:
        lg, _ = _forward(model, [obs[i] for i in active], device)
        nxt = []
        for j, i in enumerate(active):
            o, _, d, info = envs[i].step(int(lg[j].argmax()))
            if d:
                out[i] = info["result"]
            else:
                obs[i] = o
                nxt.append(i)
        active = nxt
    return out


def try_actions(model, env: PackingEnv, actions: Sequence[int], device="cpu", profile=ACTIVE) -> List[float]:
    """Điểm phương án cuối khi đi từng nước trong `actions` rồi để mô hình xếp tiếp."""
    envs, obs, scores, idx = [], [], [None] * len(actions), []
    for k, a in enumerate(actions):
        e = clone_env(env)
        o, _, d, info = e.step(int(a))
        if d:
            scores[k] = _score(info["result"], profile)
        else:
            envs.append(e)
            obs.append(o)
            idx.append(k)
    for k, r in zip(idx, finish_many(model, envs, obs, device)):
        scores[k] = _score(r, profile)
    return scores


# ---------------------------------------------------------------- tiến trình phụ (cho service)
_W = {}


def _winit(model_path: str):
    import torch
    torch.set_num_threads(1)
    from .ppo import load_policy
    _W["model"], _, _ = load_policy(model_path, "cpu")


def _wtry(args) -> List[float]:
    env_bytes, actions, profile = args
    env = pickle.loads(env_bytes)
    return try_actions(_W["model"], env, actions, "cpu", profile)


class Lookahead:
    def __init__(self, model, env_cfg: EnvConfig, topk: int = 3, use_heur: bool = True, confident: float = 0.97,
                 time_limit: Optional[float] = None, device="cpu", workers: int = 0, model_path: Optional[str] = None,
                 profile=ACTIVE, strict_limit: bool = False):
        self.model, self.env_cfg, self.device, self.profile = model, env_cfg, device, profile
        self.strict_limit = strict_limit
        self.topk, self.use_heur, self.confident, self.time_limit = topk, use_heur, confident, time_limit
        self.pool = None
        if workers and workers > 1:
            import multiprocessing as mp
            assert model_path, "cần model_path để tiến trình phụ nạp mô hình"
            self.pool = mp.get_context("spawn").Pool(workers, initializer=_winit, initargs=(model_path,))
            self.workers = workers
        self.stats = {}

    def close(self):
        if self.pool:
            self.pool.close()
            self.pool.join()
            self.pool = None

    def _scores(self, env: PackingEnv, actions: List[int]) -> List[float]:
        if not actions:
            return []
        if self.pool is None or len(actions) == 1:
            return try_actions(self.model, env, actions, self.device, self.profile)
        b = pickle.dumps(env)
        chunks = [actions[i::self.workers] for i in range(min(self.workers, len(actions)))]
        res = self.pool.map(_wtry, [(b, c, self.profile) for c in chunks])
        out = {}
        for c, r in zip(chunks, res):
            out.update(zip(c, r))
        return [out[a] for a in actions]

    def solve(self, problem: Problem) -> PlanResult:
        t0 = time.perf_counter()
        env = PackingEnv(self.env_cfg)
        obs = env.reset(problem)
        # điểm phương án greedy từ trạng thái hiện tại (= nước "m0" ở mọi bước sau, nếu cứ đi theo nhánh tốt nhất)
        g = clone_env(env)
        cached = _score(finish_many(self.model, [g], [obs], self.device)[0], self.profile)
        greedy_score = cached
        greedy_sec = time.perf_counter() - t0          # một lượt greedy đầy đủ (ước tính giá một nhánh thử)
        n_pk = max(1, len(problem.packages))
        tried = changed = steps = 0
        changed_p0, win_src = [], {"mô hình": 0, "heuristic": 0}
        res = None
        while not env.done:
            lg, _ = _forward(self.model, [obs], self.device)
            lg = lg[0]
            n = int(obs["cand_mask"].sum())
            order = np.argsort(-lg[:n])
            a = int(order[0])
            p0 = 1.0 / np.exp(lg[:n] - lg[a]).sum()
            in_time = not (self.time_limit and time.perf_counter() - t0 > self.time_limit)
            do_try = n > 1 and p0 < self.confident and in_time
            if do_try:
                cands = [int(x) for x in order[: self.topk]]
                if self.use_heur:
                    for w in PRESETS:
                        h = int(np.argmin(heur_score(obs["cand"][:n], w)))
                        if h not in cands:
                            cands.append(h)
                others = cands[1:]
                if self.strict_limit and self.time_limit:   # lượt thử phải xong trước time_limit (ước tính)
                    est = len(others) * greedy_sec * max(1, n_pk - steps) / n_pk
                    do_try = time.perf_counter() - t0 + est <= self.time_limit
            if do_try:
                sc = [cached] + self._scores(env, others)
                b = argbest(sc)   # điểm có thể là bộ (hồ sơ J) → không dùng np.argmax / "+ 1e-9"
                tried += 1
                if better(sc[b], sc[0]):
                    a, cached = cands[b], sc[b]
                    changed += 1
                    changed_p0.append(float(p0))
                    win_src["mô hình" if b < min(self.topk, len(order)) else "heuristic"] += 1
            obs, _, d, info = env.step(a)
            steps += 1
            if d:
                res = info["result"]
        res = res if res is not None else env.result("AI")
        res.seconds = time.perf_counter() - t0
        self.stats = dict(steps=steps, tried=tried, changed=changed, greedy=greedy_score, final=_score(res, self.profile),
                          seconds=res.seconds, changed_p0=changed_p0, win_src=win_src)
        res.la_stats = self.stats
        return res
