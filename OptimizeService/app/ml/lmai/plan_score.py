# Chép từ LoadMaster_AI/lmai/plan_score.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Một hàm chấm phương án cho mọi nơi trong lmai (P17, KE-HOACH-TOI-UU M2) + hàm điểm J theo hồ sơ (M6, spec mục 6).

`plan_score(r)` là hàm chấm duy nhất mà thầy (`teacher`), `search` / `lookahead`, `AISolver` (`solver`), `ppo.evaluate`
và `selfimprove` dùng để chọn phương án:
  - chưa đặt hồ sơ (mặc định, như trước M6): hợp lệ trước, rồi hàng lên xe, rồi số kiện — công thức cũ;
  - đã đặt hồ sơ (`set_profile("default" | "A" | "B" | "C")`, hoặc biến môi trường LMAI_HO_SO): hợp lệ trước, rồi (khi
    `pack_count_first`) số kiện, rồi J của hồ sơ — đúng khoá chọn của backend (`plan_score.selection_key`).

J chép đúng công thức của backend `OptimizeService/app/service/optimize/plan_score.py` (tests/test_parity_j.py):
    J = 100 · ( w_vol·U_vol + w_n·U_n − Σₖ wₖ·Vₖ ),  V₁…V₆ như docstring của backend, S7 = S8 = 0, Vₖ kẹp vào [0, 1].
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from . import profiles as P
from .result import PlanResult

EPS = 1e-4
_ACTIVE: Optional[P.PlanProfile] = P.get(os.environ.get("LMAI_HO_SO") or None)
ACTIVE = "active"   # plan_score(r, ACTIVE) = dùng hồ sơ đã đặt cho tiến trình (mặc định); chuỗi để gửi được sang tiến trình phụ


def set_profile(code) -> None:
    """Đặt hồ sơ cho mọi lần chọn phương án trong tiến trình này (None = cách chấm cũ). Nhận mã (default | A | B | C)
    hoặc một `PlanProfile` (hồ sơ lấy mẫu khi train Lumo-R3)."""
    global _ACTIVE
    _ACTIVE = code if isinstance(code, P.PlanProfile) else P.get(code)


def active_profile() -> Optional[P.PlanProfile]:
    return _ACTIVE


def plan_score(r: PlanResult, profile=ACTIVE):
    """`profile`: ACTIVE (hồ sơ của tiến trình, `set_profile`), một `PlanProfile`, hoặc None (cách chấm cũ). Backend truyền
    hồ sơ của job tường minh — nhiều job trong một tiến trình không dùng chung biến toàn cục."""
    prof = _ACTIVE if isinstance(profile, str) and profile == ACTIVE else profile
    if prof is None:
        return (10.0 if r.verdict.valid else 0.0) + r.cargo_frac + 0.001 * r.packed_frac_raw
    if not r.verdict.valid or not r.placements:
        return (False, 0, 0.0)
    return (True, len(r.placements) if P.pack_count_first() else 0, score(r.problem, r.placements, prof).j)


def better(a, b, eps: float = 1e-9) -> bool:
    """a hơn b rõ ràng (hơn quá `eps` ở phần số thực). Điểm cũ là số thực; điểm theo hồ sơ là bộ (hợp lệ, số kiện, J)."""
    if isinstance(a, tuple):
        return a[:-1] > b[:-1] or (a[:-1] == b[:-1] and a[-1] > b[-1] + eps)
    return a > b + eps


def argbest(scores) -> int:
    """Chỉ số của điểm cao nhất, lấy cái đầu tiên khi bằng nhau (như np.argmax với số thực)."""
    return max(range(len(scores)), key=lambda k: scores[k])


# ------------------------------------------------------------------------------------------------------------ J

@dataclass
class PlanScore:
    profile: P.PlanProfile
    j: float
    u_vol: float
    u_n: float
    values: Dict[str, float] = field(default_factory=dict)
    computed: Dict[str, bool] = field(default_factory=dict)


def _zone_at(zones, x: float):
    """Như StopZoneCalculator.zone_at của backend: vùng chứa x; trong khoảng đệm là vùng gần hơn, cách đều thì vùng sâu."""
    nearest, nearest_gap = None, math.inf
    for z in zones:
        gap = max(z.x0 - x, x - z.x1, 0.0)
        closer = gap < nearest_gap - 1e-6
        tie_deeper = nearest is not None and abs(gap - nearest_gap) <= 1e-6 and z.x0 < nearest.x0 - 1e-6
        if closer or tie_deeper:
            nearest, nearest_gap = z, gap
    return nearest


def rehandling_count(problem, placements: Sequence) -> int:
    """R của S1 theo cách đếm của backend: kiện có tâm X nằm trong vùng của điểm giao khác (khác `Packer.rehandling`)."""
    from .zones import compute_zones

    zones = compute_zones(problem)
    stop = {p.id: p.stop_index for p in problem.packages}
    count = 0
    for p in placements:
        z = _zone_at(zones, p.x + p.packed_l / 2.0)
        if z is not None and z.stop_index != stop[p.package_id]:
            count += 1
    return count


def _clusters(boxes: List[Tuple[float, ...]], gap: float) -> int:
    n = len(boxes)
    parent = list(range(n))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(n):
        a = boxes[i]
        for j in range(i + 1, n):
            b = boxes[j]
            for ax in range(3):
                dist = max(b[ax] - (a[ax] + a[ax + 3]), a[ax] - (b[ax] + b[ax + 3]))
                if dist > gap + EPS or dist < -EPS:
                    continue
                o1, o2 = (ax + 1) % 3, (ax + 2) % 3
                ov1 = min(a[o1] + a[o1 + 3], b[o1] + b[o1 + 3]) - max(a[o1], b[o1])
                ov2 = min(a[o2] + a[o2 + 3], b[o2] + b[o2 + 3]) - max(a[o2], b[o2])
                if ov1 > EPS and ov2 > EPS:
                    ri, rj = find(i), find(j)
                    if ri != rj:
                        parent[ri] = rj
                    break
    return len({find(i) for i in range(n)})


def components(problem, placements: Sequence, rehandling: Optional[int] = None):
    """(U_vol, U_n, V theo mã S, đã tính được theo mã S) — như backend `plan_score.components`."""
    cfg = P.config()
    v = problem.vehicle
    pk = {p.id: p for p in problem.packages}
    n_total = max(len(problem.packages), 1)
    values = {c: 0.0 for c in P.COMPONENTS}
    computed = {c: c not in ("S7", "S8") for c in P.COMPONENTS}
    axle = None
    if v.has_axles and v.rear_axle_x > v.front_axle_x and v.front_axle_limit_kg > 0 and v.rear_axle_limit_kg > 0:
        axle = (float(v.front_axle_x), float(v.rear_axle_x), float(v.empty_front_axle_load_kg),
                float(v.empty_rear_axle_load_kg), float(v.front_axle_limit_kg), float(v.rear_axle_limit_kg))
    computed["S3"] = axle is not None
    if not placements:
        return 0.0, 0.0, values, computed

    L, W, H = float(v.inner_l), float(v.inner_w), float(v.inner_h)
    u_vol = sum(p.packed_l * p.packed_w * p.packed_h for p in placements) / (L * W * H)
    u_n = len(placements) / n_total
    weights = [float(pk[p.package_id].weight) for p in placements]
    mass = sum(weights)
    cy = sum(m * (p.y + p.packed_w / 2.0) for m, p in zip(weights, placements))
    cz = sum(m * (p.z + p.packed_h / 2.0) for m, p in zip(weights, placements))

    r = rehandling_count(problem, placements) if rehandling is None else int(rehandling)
    values["S1"] = r / n_total
    if mass > 0:
        r_y = v.cog_limit_y
        values["S2"] = abs(cy / mass - W / 2.0) / (r_y * W) if r_y > 0 else 0.0
        values["S4"] = (cz / mass) / H
        if axle is not None:
            fx, rx, ef, er, gf, gr = axle
            rear_cargo = sum(m * (p.x + p.packed_l / 2.0 - fx) for m, p in zip(weights, placements)) / (rx - fx)
            values["S3"] = abs((ef + (mass - rear_cargo)) / gf - (er + rear_cargo) / gr)

    groups: Dict[str, list] = {}
    for p in placements:
        q = pk[p.package_id]
        key = f"g:{q.packing_group}" if q.packing_group else f"s:{q.stop_index}"
        groups.setdefault(key, []).append((p.x, p.y, p.z, p.packed_l, p.packed_w, p.packed_h))
    gap = float(cfg.get("adjacent_gap_cm", 1.0))
    clusters = sum(_clusters(b, gap) for b in groups.values())
    values["S5"] = max(0, clusters - len(groups)) / n_total

    hp = int(cfg.get("high_priority_min", 2))
    d = [(L - (p.x + p.packed_l)) / L for p in placements
         if pk[p.package_id].priority is not None and int(pk[p.package_id].priority) >= hp]
    values["S6"] = sum(d) / len(d) if d else 0.0

    values = {c: min(max(x, 0.0), 1.0) for c, x in values.items()}
    return u_vol, u_n, values, computed


def score(problem, placements: Sequence, profile: Optional[P.PlanProfile] = None,
          rehandling: Optional[int] = None) -> PlanScore:
    profile = profile or P.get("default")
    u_vol, u_n, values, computed = components(problem, placements, rehandling)
    penalty = sum(w * values[c] for w, c in zip(profile.weights, P.COMPONENTS))
    j = 100.0 * (profile.w_vol * u_vol + profile.w_n * u_n - penalty) if placements else 0.0
    return PlanScore(profile, j, u_vol, u_n, values, computed)


def j_of(r: PlanResult, profile: Optional[P.PlanProfile] = None) -> Optional[float]:
    """J của một PlanResult (None khi không hợp lệ — spec: phương án không hợp lệ không có điểm)."""
    if not r.verdict.valid or not r.placements:
        return None
    return score(r.problem, r.placements, profile).j
