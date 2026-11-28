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
