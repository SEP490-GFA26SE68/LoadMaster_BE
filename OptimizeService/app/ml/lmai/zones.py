# Chép từ LoadMaster_AI/lmai/zones.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Chia thùng thành vùng theo điểm giao — chép đúng công thức StopZoneCalculator của OptimizeService.

- Chiều dài vùng tỉ lệ thể tích hàng của từng điểm, giữa hai vùng chừa 10 cm.
- Điểm giao cuối nằm sâu nhất (x = 0), điểm giao đầu sát cửa (x = inner_l).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List

from .problem import Problem

ACCESSIBILITY_BUFFER_CM = 10.0


@dataclass
class Zone:
    stop_index: int
    x0: float
    x1: float
    volume_ratio: float

    @property
    def length(self) -> float:
        return self.x1 - self.x0


def compute_zones(problem: Problem, buffer: float = ACCESSIBILITY_BUFFER_CM) -> List[Zone]:
    """Trả về danh sách vùng theo thứ tự xếp: sâu nhất (giao sau cùng) trước."""
    veh = problem.vehicle
    seqs = problem.stop_sequences()
    vol: Dict[int, float] = {s: 0.0 for s in seqs}
    for p in problem.packages:
        vol.setdefault(p.stop_index, 0.0)
        vol[p.stop_index] += p.volume
    seqs = sorted(vol)
    n = len(seqs)
    usable = max(0.0, veh.inner_l - max(0, n - 1) * buffer)
    total = sum(vol.values())
    ratio = {s: (vol[s] / total if total > 0 else 1.0 / n) for s in seqs}
    zones: List[Zone] = []
    cur = 0.0
    deepest_to_door = list(reversed(seqs))  # stop giao cuối → sâu nhất
    for i, s in enumerate(deepest_to_door):
        length = usable * ratio[s]
        x0 = round(cur, 4)
        x1 = round(cur + length, 4)
        cur += length
        if i < n - 1:
            cur += buffer
        zones.append(Zone(stop_index=s, x0=x0, x1=x1, volume_ratio=ratio[s]))
    if zones:
        zones[-1].x1 = round(veh.inner_l, 4)
    return zones
