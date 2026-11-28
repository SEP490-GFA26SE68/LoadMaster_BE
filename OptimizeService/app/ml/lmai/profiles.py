# Chép từ LoadMaster_AI/lmai/profiles.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Hồ sơ ưu tiên của hàm điểm J (spec mục 6.3; KE-HOACH-TOI-UU M6) — đọc `lmai/plan_profiles.json`, bản sao giống hệt
`OptimizeService/app/config/plan_profiles.json` của backend (tests/test_parity_j.py kiểm). Trọng số chốt 26/11/2026 (Q-D4).
"""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from functools import lru_cache
from typing import Dict, Optional, Tuple

PROFILE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plan_profiles.json")
COMPONENTS = ("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8")
CANDIDATE_PROFILES = ("A", "B", "C")


@dataclass(frozen=True)
class PlanProfile:
    code: str
    name: str
    w_vol: float
    w_n: float
    weights: Tuple[float, ...]   # theo thứ tự COMPONENTS
    assumed: bool = True


@lru_cache(maxsize=1)
def config() -> dict:
    with open(PROFILE_FILE, encoding="utf-8") as fh:
        return json.load(fh)


def _profile(code: str, data: dict, assumed: bool) -> PlanProfile:
    w = data.get("w") or {}
    weights = tuple(float(w.get(c, 0.0)) for c in COMPONENTS)
    values = (float(data["w_vol"]), float(data["w_n"]), *weights)
    if any(not math.isfinite(v) or v < 0 for v in values):
        raise ValueError(f"Hồ sơ {code}: trọng số phải là số ≥ 0")
    return PlanProfile(code, str(data.get("name", code)), float(data["w_vol"]), float(data["w_n"]), weights, assumed)


def profiles() -> Dict[str, PlanProfile]:
    cfg = config()
    return {code: _profile(code, data, bool(cfg.get("assumed", True))) for code, data in cfg["profiles"].items()}


def get(code: Optional[str]) -> Optional[PlanProfile]:
    """Hồ sơ theo mã (default | A | B | C); None → None (giữ cách chấm cũ của lmai)."""
    if code is None:
        return None
    known = profiles()
    if code not in known:
        raise ValueError(f"Hồ sơ ưu tiên không tồn tại: {code} (có: {sorted(known)})")
    return known[code]


def pack_count_first() -> bool:
    return bool(config().get("pack_count_first", True))


# ------------------------------------------------------------------ Lumo-R3: hồ sơ khi train (HUONG-DAN-TRAIN-LUMO-R3 2.6, 2.7)
PRESET_CODES = ("default", "A", "B", "C")


def with_w_n(profile: PlanProfile, w_n: float, code: Optional[str] = None) -> PlanProfile:
    """Cùng trọng số, đổi w_n (thưởng khi train dùng reward_w_n = 3; sản phẩm giữ w_n của file hồ sơ)."""
    return PlanProfile(code or profile.code, profile.name, profile.w_vol, float(w_n), profile.weights, profile.assumed)


def sample_profile(rng, p_preset: float = 0.4, log_sigma: float = 0.5, p_zero: float = 0.2) -> PlanProfile:
    """Hồ sơ cho một lượt train (mục 2.7), với w_n của file hồ sơ (sản phẩm):
    - p_preset: một trong 4 hồ sơ mẫu (chọn đều);
    - còn lại: quanh một hồ sơ mẫu chọn đều — w_vol và mỗi wₖ × e^{N(0; log_sigma)}; mỗi wₖ (k ≥ 2) có p_zero bị đặt
      về 0; trọng số mẫu bằng 0 giữ 0. w_n không lấy mẫu (thưởng dùng reward_w_n, mục 2.6)."""
    known = profiles()
    base = known[PRESET_CODES[int(rng.integers(len(PRESET_CODES)))]]
    if rng.random() < p_preset:
        return base
    w_vol = base.w_vol * float(math.exp(log_sigma * rng.standard_normal()))
    ws = []
    for k, w in enumerate(base.weights):
        x = w * float(math.exp(log_sigma * rng.standard_normal())) if w > 0 else 0.0
        if k >= 1 and rng.random() < p_zero:
            x = 0.0
        ws.append(x)
    return PlanProfile(f"{base.code}~", base.name, w_vol, base.w_n, tuple(ws), base.assumed)


def glob_features(profile: PlanProfile) -> Tuple[float, ...]:
    """8 cột `glob` của quan sát v3 (mục 2.5): w_vol, w_n, w₁…w₆ chia cho trọng số lớn nhất."""
    vals = (profile.w_vol, profile.w_n, *profile.weights[:6])
    top = max(vals) or 1.0
    return tuple(v / top for v in vals)
