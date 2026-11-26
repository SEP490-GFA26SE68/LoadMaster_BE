# Chép từ LoadMaster_AI/lmai/plan_score.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Một hàm chấm phương án cho mọi nơi trong lmai (P17, KE-HOACH-TOI-UU M2).

Trước 25/11/2026 công thức này nằm lặp ở 4 chỗ (`ppo._score`, `search._score`, `solver._score`,
`teacher.plan_score`). Giữ **đúng công thức cũ** (luật cũ): hợp lệ trước, rồi hàng lên xe, rồi số kiện.
Ở M6 (spec R2) hàm này đổi sang J theo hồ sơ ưu tiên — chỉ sửa ở đây.
"""
from __future__ import annotations

from .result import PlanResult


def plan_score(r: PlanResult) -> float:
    return (10.0 if r.verdict.valid else 0.0) + r.cargo_frac + 0.001 * r.packed_frac_raw
