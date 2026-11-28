"""
Hàm điểm phương án J và hồ sơ ưu tiên (spec "Ràng buộc vật lý và điểm phương án" mục 6; KE-HOACH-TOI-UU M6).

    J = 100 · ( w_vol·U_vol + w_n·U_n − Σₖ wₖ·Vₖ )

  U_vol = thể tích kiện đã xếp / thể tích thùng;  U_n = số kiện đã xếp / N.
  S1  V₁ = R / N, R = rehandling_count (kiện có tâm X nằm trong vùng của điểm giao khác — StopZoneCalculator).
  S2  V₂ = |ȳ_G − W/2| / (r_y·W), r_y = ConstraintEngine.cog_offset_limit_y.
  S3  V₃ = |R_tr/G_tr − R_sau/G_sau| (mô hình đòn bẩy của HC-12, tải trục gồm tải xe rỗng); xe thiếu dữ liệu trục:
      V₃ = 0 và ghi "không tính" (computed = False).
  S4  V₄ = z̄_G / H.
  S5  V₅ = max(0, số cụm liền kề − số nhóm) / N; nhóm = packing_group nếu có, không thì điểm giao; hai kiện cùng nhóm
      "liền kề" khi hai mặt đối diện cách nhau ≤ adjacent_gap_cm và giao nhau dương trên hai trục còn lại.
  S6  V₆ = trung bình (L − mặt phía cửa) / L của kiện đã xếp có priority ≥ high_priority_min.
  S7, S8 (giai đoạn sau): V = 0, computed = False.
Mỗi Vₖ kẹp vào [0, 1]. Trọng số và hồ sơ đọc từ app/config/plan_profiles.json (chốt 26/11/2026, Q-D4).
Assumed = false khi trọng số đã chốt; khoá `decided_on` của file ghi ngày chốt.

Chỉ chấm phương án đã qua toàn bộ ràng buộc cứng: phương án rỗng (bộ giải trả rỗng khi vi phạm) không có J.

CHỌN phương án (`selection_key`, `selection_scalar`): rỗng đứng sau mọi phương án có kiện; khi `pack_count_first`
(cấu hình, chốt bật cho cả 4 hồ sơ ngày 26/11/2026) thì so số kiện xếp được trước, rồi mới tới J. Lý do: giao thiếu
hàng nặng hơn mọi bất tiện; với w_n = 0,5 J thuần có thể chọn bỏ một kiện ở kho thay vì chịu phạt dỡ-xếp lại / tách
nhóm (dữ liệu M7: mất 0,4–3,9 % số kiện). Tắt `pack_count_first` = chọn đúng theo J của spec.
J hiển thị luôn là J của spec. Bản `lmai/plan_score.py` của LoadMaster_AI dùng đúng các công thức này.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Optional, Sequence

from app.config.settings import settings

PROFILE_FILE = Path(__file__).resolve().parents[2] / "config" / "plan_profiles.json"   # app/config/
COMPONENTS = ("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8")
COMPONENT_NAMES = {
    "S1": "Dỡ-xếp lại",
    "S2": "Lệch trọng tâm ngang",
    "S3": "Mất cân bằng hai trục",
    "S4": "Trọng tâm cao",
    "S5": "Kiện cùng nhóm bị tách",
    "S6": "Ưu tiên vị trí",
    "S7": "Nguy cơ lật khi phanh / cua",
    "S8": "Khe hở dọc thùng",
}
CANDIDATE_PROFILES = ("A", "B", "C")
NO_SCORE = -1e6   # điểm vô hướng cho phương án rỗng ở GA / SA (dưới mọi J của phương án có kiện)
EPS = 1e-4


@dataclass(frozen=True)
class PlanProfile:
    code: str
    name: str
    w_vol: float
    w_n: float
    weights: tuple[float, ...]  # theo thứ tự COMPONENTS
    assumed: bool = True


@dataclass
class PlanScore:
    profile: PlanProfile
    j: float
    u_vol: float
    u_n: float
    values: dict[str, float] = field(default_factory=dict)
    computed: dict[str, bool] = field(default_factory=dict)

    def contribution(self, code: str) -> float:
        return -100.0 * self.profile.weights[COMPONENTS.index(code)] * self.values[code]


@lru_cache(maxsize=1)
def _config() -> dict:
    with open(PROFILE_FILE, encoding="utf-8") as fh:
        return json.load(fh)


def _profile_from(code: str, data: dict, assumed: bool) -> PlanProfile:
    w = data.get("w") or {}
    weights = tuple(float(w.get(c, 0.0)) for c in COMPONENTS)
    values = (float(data["w_vol"]), float(data["w_n"]), *weights)
    if any(not math.isfinite(v) or v < 0 for v in values):
        raise ValueError(f"Hồ sơ {code}: trọng số phải là số ≥ 0")
    return PlanProfile(code, str(data.get("name", code)), float(data["w_vol"]), float(data["w_n"]), weights, assumed)


def profiles() -> dict[str, PlanProfile]:
    cfg = _config()
    return {code: _profile_from(code, data, bool(cfg.get("assumed", True))) for code, data in cfg["profiles"].items()}


def resolve_profile(request_profile=None) -> PlanProfile:
    """
    Hồ sơ cho lần chạy: trống → "default"; có mã → hồ sơ mẫu đó; có trọng số → hồ sơ mẫu (hoặc default) với các
    trọng số được ghi đè (chỉnh cho một lần chạy, không đổi hồ sơ mẫu).
    """
    known = profiles()
    if request_profile is None:
        return known["default"]
    code = getattr(request_profile, "code", None) or "default"
    if code not in known:
        raise ValueError(f"Hồ sơ ưu tiên không tồn tại: {code}")
    base = known[code]
    w_vol = getattr(request_profile, "w_vol", None)
    w_n = getattr(request_profile, "w_n", None)
    weights = getattr(request_profile, "weights", None) or {}
    if w_vol is None and w_n is None and not weights:
        return base
    unknown = set(weights) - set(COMPONENTS)
    if unknown:
        raise ValueError(f"Thành phần J không tồn tại: {sorted(unknown)}")
    data = {"name": base.name, "w_vol": base.w_vol if w_vol is None else w_vol, "w_n": base.w_n if w_n is None else w_n,
            "w": {c: weights.get(c, base.weights[i]) for i, c in enumerate(COMPONENTS)}}
    return _profile_from(f"{code}*", data, base.assumed)


def enabled() -> bool:
    return bool(settings.PLAN_SCORE_J)


# ---------------------------------------------------------------------------------------------------- thành phần

def rehandling_count(problem, placements) -> int:
    """R của S1: đúng cách đếm `rehandling_count` của bộ giải (tâm X ngoài vùng điểm giao của kiện)."""
    from app.service.optimize.optimization_client import OptimizationClient
    from app.service.optimize.stop_zone_calculator import StopZoneCalculator

    _, zone_res, package_stop = OptimizationClient._stop_groups(problem)
    return StopZoneCalculator.count_rehandling(zone_res.zones, list(placements), package_stop)


def _axle_values(vt):
    values = (vt.front_axle_x, vt.rear_axle_x, vt.empty_front_axle_load_kg, vt.empty_rear_axle_load_kg,
              vt.front_axle_limit_kg, vt.rear_axle_limit_kg)
    if any(v is None for v in values):
        return None
    fx, rx, ef, er, gf, gr = (float(v) for v in values)
    if rx <= fx or gf <= 0 or gr <= 0:
        return None
    return fx, rx, ef, er, gf, gr


def _clusters(boxes: list[tuple[float, ...]], gap: float) -> int:
    """Số thành phần liên thông của quan hệ "liền kề" (hai mặt đối diện cách nhau ≤ gap, giao dương hai trục kia)."""
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
            touching = False
            for ax in range(3):
                lo_a, hi_a, lo_b, hi_b = a[ax], a[ax] + a[ax + 3], b[ax], b[ax] + b[ax + 3]
                dist = max(lo_b - hi_a, lo_a - hi_b)
                if dist > gap + EPS or dist < -EPS:
                    continue
                o1, o2 = ((ax + 1) % 3, (ax + 2) % 3)
                ov1 = min(a[o1] + a[o1 + 3], b[o1] + b[o1 + 3]) - max(a[o1], b[o1])
                ov2 = min(a[o2] + a[o2 + 3], b[o2] + b[o2 + 3]) - max(a[o2], b[o2])
                if ov1 > EPS and ov2 > EPS:
                    touching = True
                    break
            if touching:
                ri, rj = find(i), find(j)
                if ri != rj:
                    parent[ri] = rj
    return len({find(i) for i in range(n)})


def components(problem, placements: Sequence, rehandling: Optional[int] = None) -> tuple[float, float, dict, dict]:
    """(U_vol, U_n, V theo mã S, đã tính được hay không theo mã S) của một phương án."""
    cfg = _config()
    vt = problem.vehicle
    pk = {str(p.id): p for p in problem.packages}
    n_total = max(len(problem.packages), 1)
    values = {c: 0.0 for c in COMPONENTS}
    computed = {c: c not in ("S7", "S8") for c in COMPONENTS}
    axle = _axle_values(vt)
    computed["S3"] = axle is not None
    if not placements:
        return 0.0, 0.0, values, computed

    L, W, H = float(vt.inner_l), float(vt.inner_w), float(vt.inner_h)
    vol = sum(float(p.packed_l) * float(p.packed_w) * float(p.packed_h) for p in placements)
    u_vol = vol / (L * W * H)
    u_n = len(placements) / n_total

    weights = [float(pk[str(p.package_id)].weight) for p in placements]
    mass = sum(weights)
    cx = sum(m * (float(p.x) + float(p.packed_l) / 2.0) for m, p in zip(weights, placements))
    cy = sum(m * (float(p.y) + float(p.packed_w) / 2.0) for m, p in zip(weights, placements))
    cz = sum(m * (float(p.z) + float(p.packed_h) / 2.0) for m, p in zip(weights, placements))

    r = rehandling_count(problem, placements) if rehandling is None else int(rehandling)
    values["S1"] = r / n_total
    if mass > 0:
        from app.service.optimize.constraint_engine import ConstraintEngine

        r_y = ConstraintEngine.cog_offset_limit_y(vt)
        values["S2"] = abs(cy / mass - W / 2.0) / (r_y * W) if r_y > 0 else 0.0
        values["S4"] = (cz / mass) / H
        if axle is not None:
            fx, rx, ef, er, gf, gr = axle
            rear_cargo = sum(m * (float(p.x) + float(p.packed_l) / 2.0 - fx) for m, p in zip(weights, placements)) / (rx - fx)
            front = ef + (mass - rear_cargo)
            rear = er + rear_cargo
            values["S3"] = abs(front / gf - rear / gr)

    groups: dict[str, list] = {}
    for p in placements:
        q = pk[str(p.package_id)]
        key = f"g:{q.packing_group}" if q.packing_group else f"s:{q.stop_index}"
        groups.setdefault(key, []).append((float(p.x), float(p.y), float(p.z),
                                           float(p.packed_l), float(p.packed_w), float(p.packed_h)))
    gap = float(cfg.get("adjacent_gap_cm", 1.0))
    clusters = sum(_clusters(boxes, gap) for boxes in groups.values())
    values["S5"] = max(0, clusters - len(groups)) / n_total

    hp = int(cfg.get("high_priority_min", 2))
    dists = [(L - (float(p.x) + float(p.packed_l))) / L for p in placements
             if pk[str(p.package_id)].priority is not None and int(pk[str(p.package_id)].priority) >= hp]
    values["S6"] = sum(dists) / len(dists) if dists else 0.0

    values = {c: min(max(v, 0.0), 1.0) for c, v in values.items()}
    return u_vol, u_n, values, computed


def score(problem, placements: Sequence, profile: Optional[PlanProfile] = None,
          rehandling: Optional[int] = None) -> PlanScore:
    profile = profile or resolve_profile(getattr(problem, "plan_profile", None))
    u_vol, u_n, values, computed = components(problem, placements, rehandling)
    penalty = sum(w * values[c] for w, c in zip(profile.weights, COMPONENTS))
    j = 100.0 * (profile.w_vol * u_vol + profile.w_n * u_n - penalty) if placements else 0.0
    return PlanScore(profile, j, u_vol, u_n, values, computed)


def pack_count_first() -> bool:
    return bool(_config().get("pack_count_first", True))


def selection_key(problem, placements: Sequence, profile: Optional[PlanProfile] = None,
                  rehandling: Optional[int] = None) -> tuple[bool, int, float]:
    """Khoá chọn phương án (lớn hơn = tốt hơn): có kiện, (số kiện nếu pack_count_first), J."""
    if not placements:
        return False, 0, 0.0
    j = score(problem, placements, profile, rehandling).j
    return True, (len(placements) if pack_count_first() else 0), j


def selection_scalar(problem, placements: Sequence, profile: Optional[PlanProfile] = None,
                     rehandling: Optional[int] = None) -> float:
    """Như selection_key nhưng là một số (GA / SA): mỗi kiện thêm 1.000 điểm khi pack_count_first."""
    if not placements:
        return NO_SCORE
    j = score(problem, placements, profile, rehandling).j
    return j + (1000.0 * len(placements) if pack_count_first() else 0.0)


def to_data(s: PlanScore):
    from app.dto.optimization.engine.engine_response import PlanScoreComponentData, PlanScoreData

    comps = [
        PlanScoreComponentData(
            code=c, name=COMPONENT_NAMES[c], value=s.values[c], weight=s.profile.weights[i],
            contribution=s.contribution(c), computed=s.computed[c],
        )
        for i, c in enumerate(COMPONENTS)
    ]
    return PlanScoreData(profile=s.profile.code, profile_name=s.profile.name, assumed_weights=s.profile.assumed,
                         j=s.j, u_vol=s.u_vol, u_n=s.u_n, w_vol=s.profile.w_vol, w_n=s.profile.w_n, components=comps)
