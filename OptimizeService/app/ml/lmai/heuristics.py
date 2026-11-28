# Chép từ LoadMaster_AI/lmai/heuristics.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Thuật toán mốc để so sánh với AI (cùng bộ kiểm ràng buộc, cùng cách chia vùng).

- ep_dblf  : EP + DBLF cổ điển — kiện theo thể tích giảm dần, chọn điểm sâu nhất → thấp nhất → trái nhất.
- ep_center: giống OptimizeService hiện tại — chọn điểm kéo trọng tâm về giữa thùng, ưu tiên sàn.
"""
from __future__ import annotations

import time
from typing import Callable, Dict

from .packer import PackConfig, Packer
from .problem import Problem
from .result import PlanResult, finish

BASELINE_PACK = PackConfig(buffer_k=1, per_rot=200, max_candidates=100000)


def _run(problem: Problem, pick: Callable[[Packer], int], name: str) -> PlanResult:
    t0 = time.perf_counter()
    pk = Packer(problem, BASELINE_PACK)
    while not pk.done and pk.candidates:
        pk.apply(pick(pk))
    return finish(problem, pk, name, time.perf_counter() - t0)


def _dblf(pk: Packer) -> int:
    cs = pk.candidates
    return min(range(len(cs)), key=lambda i: (pk.cand_oz[i], cs[i].x, cs[i].z, cs[i].y))


def _center(pk: Packer) -> int:
    v = pk.problem.vehicle
    st = pk.state
    best, bi = float("inf"), 0
    for i, c in enumerate(pk.candidates):
        m = pk.problem.packages[c.pkg_idx].weight
        w = st.total_weight + m
        cx = (st.mx + m * (c.x + c.l / 2)) / w
        cy = (st.my + m * (c.y + c.w / 2)) / w
        score = ((cx - v.inner_l / 2) / v.inner_l) ** 2 + ((cy - v.inner_w / 2) / v.inner_w) ** 2 + c.z / v.inner_h * 0.1
        score += 10.0 if pk.cand_oz[i] else 0.0
        if score < best:
            best, bi = score, i
    return bi


def ep_dblf(problem: Problem) -> PlanResult:
    return _run(problem, _dblf, "EP-DBLF")


def ep_center(problem: Problem) -> PlanResult:
    return _run(problem, _center, "EP-trong-tam")


BASELINES: Dict[str, Callable[[Problem], PlanResult]] = {"EP-DBLF": ep_dblf, "EP-trong-tam": ep_center}
