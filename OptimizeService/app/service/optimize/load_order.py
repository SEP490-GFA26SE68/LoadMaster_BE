"""
Thứ tự bốc lên xe (step_sequence) cho một phương án đã xếp xong.

Bộ dựng biết H13 (hai lượt) đặt kiện bắt buộc của mọi vùng trước, nên thứ tự đặt không còn là thứ tự bốc được:
kiện sát cửa có thể đứng trước kiện sâu trong thùng. Hàm dưới đây đánh lại số bước, không đổi vị trí hay hướng:
  - kiện đỡ luôn đứng trước kiện nằm trên nó (mặt trên chạm mặt dưới, diện tích giao > 0);
  - trong các kiện đã đủ chỗ tựa: sâu trước (x nhỏ), rồi thấp trước (z), rồi trái trước (y), hoà thì giữ thứ tự cũ.
Bản `lmai/load_order.py` của LoadMaster_AI dùng đúng thuật toán này.
"""
from __future__ import annotations

import heapq
from typing import Sequence

from app.dto.optimization.engine.engine_response import PlacementData

EPS = 1e-4


def loading_order(placements: Sequence[PlacementData]) -> list[PlacementData]:
    """Bản sao các chỗ đặt, theo thứ tự bốc lên xe, step_sequence = 1..n."""
    items = list(placements)
    n = len(items)
    above: list[list[int]] = [[] for _ in range(n)]
    waiting = [0] * n
    for i, top in enumerate(items):
        if float(top.z) <= EPS:
            continue
        for j, base in enumerate(items):
            if j == i or abs(float(base.z) + float(base.packed_h) - float(top.z)) > EPS:
                continue
            ox = min(float(top.x) + float(top.packed_l), float(base.x) + float(base.packed_l)) - max(float(top.x), float(base.x))
            oy = min(float(top.y) + float(top.packed_w), float(base.y) + float(base.packed_w)) - max(float(top.y), float(base.y))
            if ox > EPS and oy > EPS:
                above[j].append(i)
                waiting[i] += 1

    def key(i: int) -> tuple:
        p = items[i]
        return round(float(p.x), 4), round(float(p.z), 4), round(float(p.y), 4), i

    ready = [key(i) for i in range(n) if waiting[i] == 0]
    heapq.heapify(ready)
    out: list[PlacementData] = []
    while ready:
        i = heapq.heappop(ready)[-1]
        out.append(items[i].model_copy(update={"step_sequence": len(out) + 1}))
        for k in above[i]:
            waiting[k] -= 1
            if waiting[k] == 0:
                heapq.heappush(ready, key(k))
    if len(out) != n:  # không thể xảy ra (z tăng ngặt theo cạnh) — giữ nguyên để không mất kiện
        return items
    return out
