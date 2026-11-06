# Chép từ LoadMaster_AI/lmai/validate.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Chấm một phương án hoàn chỉnh theo đúng luật của OptimizeService.

- Kiểm từng kiện: HC-01 → HC-10 (như ConstraintEngine.check_all, tính lại từ đầu).
- Kiểm cả phương án: HC-11 trọng tâm, HC-12 tải trục, HC-09 dỡ được theo thứ tự (UnloadFeasibilityValidator).
OptimizeService từ chối cả phương án nếu trọng tâm, tải trục hoặc thứ tự dỡ sai — ở đây cũng tính như vậy.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

from .problem import Placement, Problem
from .state import EPS, SUPPORT_RATIO, center_in_hull, union_area

MAX_SEARCH_STATES = 20000


@dataclass
class Verdict:
    valid: bool
    placement_violations: List[str] = field(default_factory=list)
    cog_ok: bool = True
    axle_ok: bool = True
    unload_ok: bool = True
    cog_x: float = 0.0  # độ lệch tỉ lệ
    cog_y: float = 0.0
    front_axle: Optional[float] = None
    rear_axle: Optional[float] = None

    def reasons(self) -> List[str]:
        r = list(self.placement_violations[:5])
        if not self.cog_ok:
            r.append("CENTER_OF_GRAVITY_OUT_OF_RANGE")
        if not self.axle_ok:
            r.append("AXLE_LOAD_EXCEEDED")
        if not self.unload_ok:
            r.append("MULTI_DROP_LIFO_VIOLATION")
        return r


def _contacts(top: Placement, others: Sequence[Placement]):
    rects, idx = [], []
    for j, p in enumerate(others):
        if abs(p.z + p.packed_h - top.z) > EPS:
            continue
        x1, x2 = max(top.x, p.x), min(top.x + top.packed_l, p.x + p.packed_l)
        y1, y2 = max(top.y, p.y), min(top.y + top.packed_w, p.y + p.packed_w)
        if x2 > x1 + EPS and y2 > y1 + EPS:
            rects.append((x1, x2, y1, y2))
            idx.append(j)
    return rects, idx


def supported(p: Placement, others: Sequence[Placement]) -> bool:
    """HC-05 + HC-06 cho một kiện so với tập kiện còn lại."""
    if p.z <= EPS:
        return True
    rects, _ = _contacts(p, others)
    if not rects:
        return False
    if union_area(rects) / (p.packed_l * p.packed_w) < SUPPORT_RATIO - 1e-9:
        return False
    return center_in_hull(p.x + p.packed_l / 2, p.y + p.packed_w / 2, rects)


def final_checks(problem: Problem, pls: List[Placement]) -> Verdict:
    v = problem.vehicle
    pk = {p.id: p for p in problem.packages}
    ver = Verdict(valid=True)
    if not pls:
        return ver
    tw = sum(pk[p.package_id].weight for p in pls)
    mx = sum(pk[p.package_id].weight * (p.x + p.packed_l / 2) for p in pls)
    my = sum(pk[p.package_id].weight * (p.y + p.packed_w / 2) for p in pls)
    ver.cog_x = abs(mx / tw - v.inner_l / 2) / v.inner_l
    ver.cog_y = abs(my / tw - v.inner_w / 2) / v.inner_w
    ver.cog_ok = ver.cog_x <= v.max_cog_offset_ratio + EPS and ver.cog_y <= v.max_cog_offset_ratio + EPS
    if v.has_axles:
        wb = v.rear_axle_x - v.front_axle_x
        rear = (mx - tw * v.front_axle_x) / wb
        front = tw - rear
        ver.front_axle = v.empty_front_axle_load_kg + front
        ver.rear_axle = v.empty_rear_axle_load_kg + rear
        ver.axle_ok = (ver.front_axle <= v.front_axle_limit_kg + EPS and ver.rear_axle <= v.rear_axle_limit_kg + EPS
                       and ver.front_axle >= -EPS and ver.rear_axle >= -EPS)
    ver.unload_ok = unload_feasible(problem, pls)
    ver.valid = ver.cog_ok and ver.axle_ok and ver.unload_ok
    return ver


def unload_feasible(problem: Problem, pls: List[Placement]) -> bool:
    """HC-09 cả phương án: dỡ được theo thứ tự điểm giao, mỗi kiện trượt thẳng ra cửa (+X),
    kiện còn lại vẫn đủ đỡ và ổn định — cùng thuật toán UnloadFeasibilityValidator."""
    v = problem.vehicle
    if not pls:
        return True
    pk = {p.id: p for p in problem.packages}
    n = len(pls)
    stops = [int(pk[p.package_id].stop_index) for p in pls]
    dy0, dy1 = v.door_y_min, v.door_y_max
    # ai đỡ ai (tính trên phương án đầy đủ); bỏ một kiện chỉ ảnh hưởng các kiện nó đỡ
    above: List[List[int]] = [[] for _ in range(n)]
    for i, p in enumerate(pls):
        _, idx = _contacts(p, pls[:i] + pls[i + 1:])
        for j in idx:
            jj = j if j < i else j + 1
            above[jj].append(i)

    def stable_after_removal(i: int, remaining: frozenset) -> bool:
        for t in above[i]:
            if t in remaining and t != i:
                if not supported(pls[t], [pls[k] for k in remaining if k not in (i, t)]):
                    return False
        return True

    def can_extract(i: int, remaining: frozenset) -> bool:
        p = pls[i]
        if p.y < dy0 - EPS or p.y + p.packed_w > dy1 + EPS or p.z < -EPS or p.z + p.packed_h > v.door_h + EPS:
            return False
        for j in remaining:
            if j == i:
                continue
            o = pls[j]
            ox = min(v.inner_l, o.x + o.packed_l) - max(p.x, o.x)
            oy = min(p.y + p.packed_w, o.y + o.packed_w) - max(p.y, o.y)
            oz = min(p.z + p.packed_h, o.z + o.packed_h) - max(p.z, o.z)
            if ox > EPS and oy > EPS and oz > EPS:
                return False
        return True

    impossible = set()
    explored = [0]
    sys.setrecursionlimit(max(1000, n * 4 + 100))

    def search(remaining: frozenset) -> bool:
        if not remaining:
            return True
        if remaining in impossible:
            return False
        explored[0] += 1
        if explored[0] > MAX_SEARCH_STATES:
            return False
        cur = min(stops[i] for i in remaining)
        cands = sorted((i for i in remaining if stops[i] == cur), key=lambda i: pls[i].x, reverse=True)
        for i in cands:
            if not can_extract(i, remaining):
                continue
            if not stable_after_removal(i, remaining):
                continue
            if search(remaining - {i}):
                return True
            if explored[0] > MAX_SEARCH_STATES:
                return False
        impossible.add(remaining)
        return False

    return search(frozenset(range(n)))


def placement_violations(problem: Problem, pls: List[Placement]) -> List[str]:
    """Kiểm lại từng kiện như ConstraintEngine.check_all khi lần lượt thêm vào (không cần bản gốc)."""
    v = problem.vehicle
    pk = {p.id: p for p in problem.packages}
    out: List[str] = []
    placed: List[Placement] = []
    total_w = 0.0
    for p in pls:
        pkg = pk[p.package_id]
        tag = f"{p.package_id}:"
        if (p.x < -EPS or p.y < -EPS or p.z < -EPS or p.x + p.packed_l > v.inner_l + EPS
                or p.y + p.packed_w > v.inner_w + EPS or p.z + p.packed_h > v.inner_h + EPS):
            out.append(tag + "BOUNDARY_VIOLATION")
        for o in placed:
            if not (p.x + p.packed_l <= o.x + EPS or o.x + o.packed_l <= p.x + EPS or p.y + p.packed_w <= o.y + EPS
                    or o.y + o.packed_w <= p.y + EPS or p.z + p.packed_h <= o.z + EPS or o.z + o.packed_h <= p.z + EPS):
                out.append(tag + "NON_OVERLAP_VIOLATION")
                break
        total_w += pkg.weight
        if total_w > v.max_payload_kg + EPS:
            out.append(tag + "PAYLOAD_EXCEEDED")
        if p.packed_w > v.door_w + EPS or p.packed_h > v.door_h + EPS:
            out.append(tag + "PACKAGE_CANNOT_PASS_DOOR")
        if int(p.rotation_type) not in pkg.rotations():
            out.append(tag + "ORIENTATION_NOT_ALLOWED")
        if tuple(round(t, 4) for t in pkg.dims(int(p.rotation_type))) != (round(p.packed_l, 4), round(p.packed_w, 4), round(p.packed_h, 4)):
            out.append(tag + "ORIENTATION_DIMENSION_MISMATCH")
        if not supported(p, placed):
            out.append(tag + "INSUFFICIENT_SUPPORT_OR_STABILITY")
        # HC-09 từng cặp
        for o in placed:
            so, sp = pk[o.package_id].stop_index, pkg.stop_index
            if so == sp:
                continue
            early, late = (o, p) if so < sp else (p, o)
            oy = min(early.y + early.packed_w, late.y + late.packed_w) - max(early.y, late.y)
            oz = min(early.z + early.packed_h, late.z + late.packed_h) - max(early.z, late.z)
            if oy > EPS and oz > EPS and late.x >= early.x + early.packed_l - EPS:
                out.append(tag + "MULTI_DROP_LIFO_VIOLATION")
                break
            ox = min(early.x + early.packed_l, late.x + late.packed_l) - max(early.x, late.x)
            if ox > EPS and oy > EPS and abs(late.z - (early.z + early.packed_h)) <= EPS:
                out.append(tag + "MULTI_DROP_LIFO_VIOLATION")
                break
        placed.append(p)
    # HC-07 + HC-08: tải chỉ tăng khi thêm kiện, nên kiểm một lần trên phương án cuối là đủ
    out.extend("PLAN:" + c for c in _stack_and_fragile(placed, pk))
    return out


def _stack_and_fragile(placed: List[Placement], pk: Dict) -> List[str]:
    n = len(placed)
    sups: List[List[tuple]] = []
    for i, p in enumerate(placed):
        rects, idx = _contacts(p, placed[:i] + placed[i + 1:])
        sups.append([((j if j < i else j + 1), (r[1] - r[0]) * (r[3] - r[2])) for r, j in zip(rects, idx)])
    errs: List[str] = []
    # dễ vỡ: không kiện nào truyền tải (trực tiếp/gián tiếp) xuống kiện dễ vỡ
    for i in range(n):
        seen, st = set(), [j for j, _ in sups[i]]
        while st:
            j = st.pop()
            if j in seen:
                continue
            seen.add(j)
            if pk[placed[j].package_id].fragile:
                errs.append("FRAGILITY_VIOLATION")
                st = []
                break
            st.extend(k for k, _ in sups[j])
        if errs:
            break
    recv = [0.0] * n
    for i in sorted(range(n), key=lambda k: placed[k].z, reverse=True):
        if placed[i].z <= EPS:
            continue
        if not sups[i]:
            errs.append("INVALID_STACKING_DATA")
            break
        load = pk[placed[i].package_id].weight + recv[i]
        tot = sum(a for _, a in sups[i])
        for j, a in sups[i]:
            recv[j] += load * a / tot
    for i in range(n):
        lim = pk[placed[i].package_id].max_stack_weight_kg
        lim = 0.0 if lim is None else float(lim)
        if recv[i] > lim + EPS:
            errs.append("STACKING_WEIGHT_EXCEEDED")
            break
    return errs
