# Chép từ LoadMaster_AI/lmai/validate.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Chấm một phương án hoàn chỉnh theo đúng luật của OptimizeService (đối chiếu `develop b0f79a4`, 25/11/2026).

- Kiểm đầu vào: xe thiếu / sai cửa hoặc 6 thông số trục → mọi phương án bị chặn (như `LoadBalanceSearch.input_violations`
  của backend; chủ dự án xác nhận 25/11 là cố ý, Q-D7).
- Kiểm từng kiện: HC-01 → HC-10 (như ConstraintEngine.check_all, tính lại từ đầu). Backend đã **bỏ** kiểm HC-09 từng
  cặp khỏi check_all (`check_multi_drop_lifo` không còn nơi gọi) → ở đây cũng bỏ; HC-09 chỉ còn ở bước dỡ cả phương án.
- Kiểm cả phương án: HC-11 trọng tâm, HC-12 tải trục, HC-09 dỡ được theo thứ tự (UnloadFeasibilityValidator: kéo
  thẳng +X, có thể dịch ngang vào khung cửa và / hoặc nâng lên trước khi kéo — P15).
OptimizeService từ chối cả phương án nếu trọng tâm, tải trục hoặc thứ tự dỡ sai — ở đây cũng tính như vậy.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
import math
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
    input_violations: List[str] = field(default_factory=list)  # thiếu / sai cấu hình xe hay dữ liệu kiện
    unload_code: Optional[str] = None  # mã lý do khi unload_ok = False (như violation_code của backend)

    def reasons(self) -> List[str]:
        r = list(self.input_violations) + list(self.placement_violations[:5])
        if not self.cog_ok:
            r.append("CENTER_OF_GRAVITY_OUT_OF_RANGE")
        if not self.axle_ok:
            r.append("AXLE_LOAD_EXCEEDED")
        if not self.unload_ok:
            r.append(self.unload_code or "MULTI_DROP_LIFO_VIOLATION")
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


_AXLE_FIELDS = ("front_axle_x", "rear_axle_x", "front_axle_limit_kg", "rear_axle_limit_kg",
                "empty_front_axle_load_kg", "empty_rear_axle_load_kg")


def _finite_pos(x) -> bool:
    try:
        return x is not None and math.isfinite(float(x)) and float(x) > 0
    except (TypeError, ValueError):
        return False


def input_violations(problem: Problem) -> List[str]:
    """Kiểm đầu vào như `LoadBalanceSearch.input_violations` của backend (trừ kiện ghim — lmai không có).

    Có mã nào thì backend trả 0 chỗ đặt cho MỌI kiện → mọi phương án ở đây coi là không hợp lệ.
    `DOOR_CONFIG_*` được backend báo ra ngoài là `PACKAGE_CANNOT_PASS_DOOR`; ở đây giữ mã gốc cho dễ đọc.
    """
    v = problem.vehicle
    if not all(_finite_pos(getattr(v, f)) for f in ("inner_l", "inner_w", "inner_h", "max_payload_kg")):
        return ["INVALID_VEHICLE_DATA"]
    if not v.door_declared or v.door_w is None or v.door_h is None:
        return ["DOOR_CONFIG_MISSING"]
    if not (_finite_pos(v.door_w) and _finite_pos(v.door_h)):
        return ["DOOR_CONFIG_INVALID"]
    for f in _AXLE_FIELDS:
        x = getattr(v, f)
        if x is None:
            return ["AXLE_CONFIG_MISSING"]
        try:
            if not math.isfinite(float(x)):
                return ["AXLE_CONFIG_INVALID"]
        except (TypeError, ValueError):
            return ["AXLE_CONFIG_INVALID"]
    if (v.rear_axle_x - v.front_axle_x <= 0 or v.front_axle_limit_kg <= 0 or v.rear_axle_limit_kg <= 0
            or v.empty_front_axle_load_kg < 0 or v.empty_rear_axle_load_kg < 0):
        return ["AXLE_CONFIG_INVALID"]
    for p in problem.packages:
        if not all(_finite_pos(x) for x in (p.l, p.w, p.h, p.weight)):
            return ["INVALID_PACKAGE_DATA"]
    return []


def final_checks(problem: Problem, pls: List[Placement]) -> Verdict:
    v = problem.vehicle
    pk = {p.id: p for p in problem.packages}
    ver = Verdict(valid=True)
    if not pls:
        return ver
    # Backend chặn ngay ở kiểm đầu vào, không xếp gì → phương án nào cũng không hợp lệ. Các mục dưới vẫn tính để chẩn đoán.
    ver.input_violations = input_violations(problem)
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
    ver.unload_code = unload_check(problem, pls)
    ver.unload_ok = ver.unload_code is None
    ver.valid = not ver.input_violations and ver.cog_ok and ver.axle_ok and ver.unload_ok
    return ver


def unload_feasible(problem: Problem, pls: List[Placement]) -> bool:
    return unload_check(problem, pls) is None


def unload_check(problem: Problem, pls: List[Placement]) -> Optional[str]:
    """HC-09 cả phương án, cùng thuật toán `UnloadFeasibilityValidator` của backend (`b0f79a4`). None = dỡ được.

    - Dỡ theo thứ tự điểm giao (stop nhỏ trước); trong một điểm giao thử kiện gần cửa trước, DFS + ghi nhớ trạng thái
      không thể, tối đa MAX_SEARCH_STATES trạng thái.
    - Một kiện ra được nếu (không xoay) lọt cửa và có đường đi không chạm kiện còn lại: tới một Y nằm trong khung cửa
      và một Z ≥ Z hiện tại (giữ nguyên hoặc nâng lên cao nhất còn lọt cửa), theo một trong hai thứ tự
      "dịch ngang → nâng → kéo +X" hoặc "nâng → dịch ngang → kéo +X". Không hạ kiện xuống.
    - Bỏ một kiện ra, các kiện còn lại vẫn phải đủ đỡ và ổn định (HC-05 + HC-06).
    - Trước khi tìm: kích thước cửa hợp lệ, kiện nằm trong thùng, không chồng lấn, phương án ban đầu ổn định.
    Chưa hỗ trợ vật cản trong thùng (Problem.from_request từ chối bài có vật cản).
    """
    v = problem.vehicle
    if not pls:
        return None
    if v.door_w is None or v.door_h is None or not (_finite_pos(v.door_w) and _finite_pos(v.door_h)):
        return "INVALID_UNLOAD_DATA"
    inner_l, inner_w, inner_h = float(v.inner_l), float(v.inner_w), float(v.inner_h)
    door_w, door_h = float(v.door_w), float(v.door_h)
    if door_w > inner_w + EPS or door_h > inner_h + EPS:
        return "INVALID_UNLOAD_DATA"
    door_y_min = (inner_w - door_w) / 2.0
    door_y_max = door_y_min + door_w
    pk = {p.id: p for p in problem.packages}
    n = len(pls)
    stops: List[int] = []
    for p in pls:
        pkg = pk.get(p.package_id)
        if pkg is None or pkg.stop_index is None or int(pkg.stop_index) < 1:
            return "INVALID_UNLOAD_DATA"
        stops.append(int(pkg.stop_index))
        if (p.packed_l <= 0 or p.packed_w <= 0 or p.packed_h <= 0 or p.x < -EPS or p.y < -EPS or p.z < -EPS
                or p.x + p.packed_l > inner_l + EPS or p.y + p.packed_w > inner_w + EPS
                or p.z + p.packed_h > inner_h + EPS):
            return "INVALID_UNLOAD_DATA"
    if len({p.package_id for p in pls}) != n:
        return "INVALID_UNLOAD_DATA"
    for i in range(n):
        a = pls[i]
        for j in range(i + 1, n):
            b = pls[j]
            if (min(a.x + a.packed_l, b.x + b.packed_l) - max(a.x, b.x) > EPS
                    and min(a.y + a.packed_w, b.y + b.packed_w) - max(a.y, b.y) > EPS
                    and min(a.z + a.packed_h, b.z + b.packed_h) - max(a.z, b.z) > EPS):
                return "INVALID_UNLOAD_DATA"
    # phương án ban đầu phải ổn định (backend: stable_remaining(initial_state))
    for i, p in enumerate(pls):
        if not supported(p, pls[:i] + pls[i + 1:]):
            return "INVALID_UNLOAD_DATA"

    # Ai đỡ ai (tính trên phương án đầy đủ). Bỏ một kiện chỉ ảnh hưởng các kiện nó đỡ — tương đương kiểm lại mọi kiện
    # còn lại như backend, vì các kiện khác không mất điểm tựa nào.
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
        x, y, z, l, w, h = p.x, p.y, p.z, p.packed_l, p.packed_w, p.packed_h
        if w > door_w + EPS or h > door_h + EPS:
            return False

        def path_clear(start, end) -> bool:
            x1, y1, z1 = start
            x2, y2, z2 = end
            min_x, max_x = min(x1, x2), max(x1, x2) + l
            min_y, max_y = min(y1, y2), max(y1, y2) + w
            min_z, max_z = min(z1, z2), max(z1, z2) + h
            for j in remaining:
                if j == i:
                    continue
                o = pls[j]
                if (min(max_x, o.x + o.packed_l) - max(min_x, o.x) > EPS
                        and min(max_y, o.y + o.packed_w) - max(min_y, o.y) > EPS
                        and min(max_z, o.z + o.packed_h) - max(min_z, o.z) > EPS):
                    return False
            return True

        min_door_y, max_door_y = door_y_min, door_y_max - w
        if max_door_y < min_door_y - EPS:
            return False
        target_ys = {min_door_y, max_door_y, (min_door_y + max_door_y) / 2.0, min(max(y, min_door_y), max_door_y)}
        max_target_z = min(door_h - h, inner_h - h)
        if max_target_z < z - EPS:
            return False
        target_zs = {z, max_target_z}
        start = (x, y, z)
        for ty in sorted(target_ys):
            if ty < -EPS or ty + w > inner_w + EPS:
                continue
            for tz in sorted(target_zs):
                if tz < z - EPS or tz + h > inner_h + EPS:
                    continue
                aligned, exit_pos = (x, ty, tz), (inner_l, ty, tz)
                via_y = (x, ty, z)  # A: dịch ngang → nâng → kéo ra
                if path_clear(start, via_y) and path_clear(via_y, aligned) and path_clear(aligned, exit_pos):
                    return True
                via_z = (x, y, tz)  # B: nâng → dịch ngang → kéo ra
                if path_clear(start, via_z) and path_clear(via_z, aligned) and path_clear(aligned, exit_pos):
                    return True
        return False

    impossible = set()
    explored = [0]
    limit_hit = [False]
    sys.setrecursionlimit(max(1000, n * 4 + 100))

    def search(remaining: frozenset) -> bool:
        if not remaining:
            return True
        if remaining in impossible:
            return False
        explored[0] += 1
        if explored[0] > MAX_SEARCH_STATES:
            limit_hit[0] = True
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
            if limit_hit[0]:
                return False
        impossible.add(remaining)
        return False

    if search(frozenset(range(n))):
        return None
    return "UNLOAD_SEARCH_LIMIT_REACHED" if limit_hit[0] else "MULTI_DROP_LIFO_VIOLATION"


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
        # HC-09 từng cặp: backend đã bỏ khỏi check_all (chỉ còn bước dỡ cả phương án, xem unload_check)
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
