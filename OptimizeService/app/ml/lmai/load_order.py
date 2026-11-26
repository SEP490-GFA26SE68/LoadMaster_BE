# Chép từ LoadMaster_AI/lmai/load_order.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Thứ tự bốc lên xe (step_sequence) cho một phương án đã xếp xong — bản sao thuật toán của backend
`app/service/optimize/load_order.py` (giữ khớp).

Bộ dựng biết H13 (hai lượt) đặt kiện bắt buộc của mọi vùng trước, nên thứ tự đặt không còn là thứ tự bốc được. Hàm này
đánh lại số bước, không đổi vị trí hay hướng:
  - kiện đỡ luôn đứng trước kiện nằm trên nó (mặt trên chạm mặt dưới, diện tích giao > 0);
  - trong các kiện đã đủ chỗ tựa: sâu trước (x nhỏ), rồi thấp trước (z), rồi trái trước (y), hoà thì giữ thứ tự cũ.
"""
from __future__ import annotations

import dataclasses
import heapq
from typing import List, Sequence

from .problem import Placement

EPS = 1e-4


def loading_order(placements: Sequence[Placement]) -> List[Placement]:
    items = list(placements)
    n = len(items)
    above: List[List[int]] = [[] for _ in range(n)]
    waiting = [0] * n
    for i, top in enumerate(items):
        if top.z <= EPS:
            continue
        for j, base in enumerate(items):
            if j == i or abs(base.z + base.packed_h - top.z) > EPS:
                continue
            ox = min(top.x + top.packed_l, base.x + base.packed_l) - max(top.x, base.x)
            oy = min(top.y + top.packed_w, base.y + base.packed_w) - max(top.y, base.y)
            if ox > EPS and oy > EPS:
                above[j].append(i)
                waiting[i] += 1

    def key(i: int) -> tuple:
        p = items[i]
        return round(float(p.x), 4), round(float(p.z), 4), round(float(p.y), 4), i

    ready = [key(i) for i in range(n) if waiting[i] == 0]
    heapq.heapify(ready)
    out: List[Placement] = []
    while ready:
        i = heapq.heappop(ready)[-1]
        out.append(dataclasses.replace(items[i], step_sequence=len(out) + 1))
        for k in above[i]:
            waiting[k] -= 1
            if waiting[k] == 0:
                heapq.heappush(ready, key(k))
    return out if len(out) == n else items
