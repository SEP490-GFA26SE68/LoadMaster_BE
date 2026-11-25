# Chép từ LoadMaster_AI/lmai/seed_guard.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Dải seed dành riêng — không được dùng để sinh bài TRAIN (AGENTS.md mục 3; KE-HOACH-TOI-UU M2 mục 5).

- Bộ chuẩn (`benchmark.SUITE`): mỗi bộ chiếm [seed, seed + n).
- Bộ kiểm định chọn mô hình: [900.000, 900.000 + VAL_RESERVED).

Ghi chú 25/11/2026: `VecEnv` cũ lấy seed env 0 = `seed × 1.000.003`; với `seed = 1` thì giai đoạn đầu chạy qua
1.000.003…, trùng DẢI SỐ của bộ `vua`. Ở `configs/train_v2.yaml` giai đoạn đó sinh bài 15–30 kiện (khác cấu hình
`vua` 30–60 kiện) nên bài sinh ra khác nhau — không lộ bài chuẩn, nhưng chỉ nhờ may. Từ nay `ppo.py` dừng hẳn khi
chạm dải này, trừ khi config đặt `allow_reserved_seeds: true` (chỉ để tái lập lần train cũ).
"""
from __future__ import annotations

from typing import List, Optional, Tuple

VAL_SEED = 900_000
VAL_RESERVED = 1_000  # rộng hơn val_size lớn nhất từng dùng (128)


def reserved_ranges() -> List[Tuple[int, int, str]]:
    from .benchmark import SUITE
    out = [(VAL_SEED, VAL_SEED + VAL_RESERVED, "kiểm định")]
    out += [(t.seed, t.seed + t.n, f"bộ chuẩn {name}") for name, t in SUITE.items()]
    return out


_CACHE: Optional[List[Tuple[int, int, str]]] = None


def reserved_owner(seed: int) -> Optional[str]:
    """Tên dải dành riêng chứa `seed`, hoặc None."""
    global _CACHE
    if _CACHE is None:
        _CACHE = reserved_ranges()
    for lo, hi, name in _CACHE:
        if lo <= seed < hi:
            return name
    return None


def check_train_seed(seed: int, allow: bool = False) -> None:
    owner = reserved_owner(seed)
    if owner and not allow:
        raise ValueError(f"seed train {seed} nằm trong dải dành riêng cho {owner} — đổi `ppo.seed` "
                         f"(hoặc `allow_reserved_seeds: true` nếu chỉ tái lập lần train cũ)")
