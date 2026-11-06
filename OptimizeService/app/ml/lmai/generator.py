# Chép từ LoadMaster_AI/lmai/generator.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Sinh bộ dữ liệu ngẫu nhiên (có seed) sát với LoadMaster.

- Xe: 7 xe tải thật trong dữ liệu mẫu của frontend (kích thước thùng, cửa, tải trọng, hai trục — số ước lượng).
- Kiện: mỗi điểm giao gồm vài "mã hàng" lặp lại (giống lô hàng thật), kích thước co giãn để tổng thể tích
  đạt một tỉ lệ lấp đầy mục tiêu; cân nặng chia theo thể tích x mật độ ngẫu nhiên.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

import numpy as np

from .problem import Package, Problem, Stop, Vehicle

FRONT_AXLE_X = -100.0
# tên, dài, rộng, cao, tải trọng, cửa rộng, cửa cao, x trục sau, (rỗng, tối đa) trục trước, (rỗng, tối đa) trục sau
SEED_VEHICLES = [
    ("Hyundai HD210", 720, 235, 240, 9500, 225, 230, 475.0, (3400, 6500), (2300, 10000)),
    ("Isuzu NQR 550", 570, 210, 215, 5500, 200, 205, 377.5, (2300, 3600), (1700, 6600)),
    ("Hino FC9J dong lanh", 600, 210, 200, 6000, 200, 190, 400.0, (2700, 4000), (2000, 7400)),
    ("Hino XZU720", 520, 200, 200, 3500, 190, 190, 342.5, (1900, 2800), (1500, 5200)),
    ("Thaco Ollin 720", 610, 215, 215, 7000, 205, 205, 400.0, (2400, 4200), (1900, 8000)),
    ("Isuzu FVR 900", 850, 240, 250, 9000, 230, 240, 580.0, (3300, 7100), (2700, 10000)),
    ("Hyundai Mighty EX8", 630, 220, 215, 7000, 210, 205, 420.0, (2300, 4000), (1800, 7800)),
]


def seed_vehicle(i: int) -> Vehicle:
    n, L, W, H, pay, dw, dh, rx, (fe, fm), (re, rm) = SEED_VEHICLES[i]
    return Vehicle(inner_l=L, inner_w=W, inner_h=H, max_payload_kg=pay, door_w=dw, door_h=dh,
                   front_axle_limit_kg=fm, rear_axle_limit_kg=rm, front_axle_x=FRONT_AXLE_X, rear_axle_x=rx,
                   empty_front_axle_load_kg=fe, empty_rear_axle_load_kg=re, max_cog_offset_ratio=0.15, name=n)


@dataclass
class GenConfig:
    n_packages: Tuple[int, int] = (20, 40)      # số kiện mỗi bài
    n_stops: Tuple[int, int] = (1, 3)           # số điểm giao
    fill: Tuple[float, float] = (0.35, 0.85)    # tổng thể tích hàng / thể tích thùng
    weight_frac: Tuple[float, float] = (0.2, 0.8)  # tổng cân nặng / tải trọng xe
    skus_per_stop: Tuple[int, int] = (1, 4)     # số mã hàng mỗi điểm giao
    p_fragile: float = 0.10
    p_nonstack: float = 0.10
    p_upright: float = 0.50
    p_norot: float = 0.10
    min_edge: float = 15.0
    vehicles: Optional[Sequence[int]] = None    # None = cả 7 xe


def _fit_door(dims: List[float], veh: Vehicle) -> bool:
    l, w, h = dims
    hmax = min(veh.inner_h, veh.door_h)
    for a, b, c in ((l, w, h), (w, l, h), (l, h, w), (w, h, l), (h, l, w), (h, w, l)):
        if a <= veh.inner_l and b <= veh.door_w and c <= hmax:
            return True
    return False


def generate(seed: int, cfg: GenConfig = GenConfig()) -> Problem:
    rng = np.random.default_rng(seed)
    vids = list(cfg.vehicles) if cfg.vehicles is not None else list(range(len(SEED_VEHICLES)))
    veh = seed_vehicle(int(rng.choice(vids)))
    n = int(rng.integers(cfg.n_packages[0], cfg.n_packages[1] + 1))
    s_count = int(rng.integers(cfg.n_stops[0], cfg.n_stops[1] + 1))
    s_count = max(1, min(s_count, n))
    fill = float(rng.uniform(*cfg.fill))
    mean_vol = fill * veh.volume / n
    edge = mean_vol ** (1 / 3)
    hmax = min(veh.inner_h, veh.door_h)

    # chia số kiện cho các điểm giao (mỗi điểm ít nhất 1 kiện)
    cuts = np.sort(rng.choice(np.arange(1, n), size=s_count - 1, replace=False)) if s_count > 1 else np.array([], dtype=int)
    sizes = np.diff(np.concatenate([[0], cuts, [n]])).astype(int)

    raw = []  # (dims, stop, sku)
    sku_id = 0
    for s in range(s_count):
        k = int(rng.integers(cfg.skus_per_stop[0], cfg.skus_per_stop[1] + 1))
        k = max(1, min(k, sizes[s]))
        weights = rng.dirichlet(np.ones(k))
        counts = np.maximum(1, np.round(weights * sizes[s]).astype(int))
        while counts.sum() > sizes[s]:
            counts[np.argmax(counts)] -= 1
        while counts.sum() < sizes[s]:
            counts[np.argmin(counts)] += 1
        for c in counts:
            if c <= 0:
                continue
            for _ in range(20):
                a = np.exp(rng.normal(0, 0.3, size=3))
                a = a / np.prod(a) ** (1 / 3)
                scale = edge * float(np.exp(rng.normal(0, 0.25)))
                dims = [max(cfg.min_edge, float(t)) for t in a * scale]
                if rng.random() < 0.6:
                    dims = [max(cfg.min_edge, 5 * round(t / 5)) for t in dims]
                else:
                    dims = [float(round(t)) for t in dims]
                dims[2] = min(dims[2], hmax)  # chiều cao gốc không vượt cửa
                if _fit_door(dims, veh):
                    break
            else:
                dims = [min(edge, veh.door_w * 0.9)] * 3
            kind = rng.random()
            fragile = rng.random() < cfg.p_fragile
            nonstack = rng.random() < cfg.p_nonstack
            for _ in range(int(c)):
                raw.append((dims, s + 1, sku_id, fragile, nonstack, kind))
            sku_id += 1

    # co giãn đồng loạt để tổng thể tích sát tỉ lệ lấp đầy mục tiêu (rồi làm tròn lại)
    cur = sum(d[0] * d[1] * d[2] for d, *_ in raw)
    k = (fill * veh.volume / cur) ** (1 / 3) if cur > 0 else 1.0
    scaled = {}
    for r in raw:
        sku = r[2]
        if sku in scaled:
            continue
        d = [max(cfg.min_edge, float(round(t * k))) for t in r[0]]
        d[2] = min(d[2], hmax)
        scaled[sku] = d if _fit_door(d, veh) else r[0]
    raw = [(scaled[r[2]],) + tuple(r[1:]) for r in raw]
    vols = np.array([d[0] * d[1] * d[2] for d, *_ in raw])
    dens = {sku: float(np.exp(rng.normal(0, 0.45))) for sku in sorted({r[2] for r in raw})}
    wraw = np.array([vols[i] * dens[r[2]] for i, r in enumerate(raw)])
    total_w = float(rng.uniform(*cfg.weight_frac)) * veh.max_payload_kg
    wts = np.maximum(1.0, wraw / wraw.sum() * total_w)

    pkgs: List[Package] = []
    for i, (dims, stop, sku, fragile, nonstack, kind) in enumerate(raw):
        if kind < cfg.p_norot:
            rot_allowed, allowed = False, [0]
        elif kind < cfg.p_norot + cfg.p_upright:
            rot_allowed, allowed = True, [0, 1]
        else:
            rot_allowed, allowed = True, [0, 1, 2, 3, 4, 5]
        w = round(float(wts[i]), 1)
        stack = 0.0 if nonstack else round(float(np.clip(w * rng.uniform(3, 10), 20, 1500)), 1)
        pkgs.append(Package(id=f"P{i + 1:04d}", l=dims[0], w=dims[1], h=dims[2], weight=w, stop_index=stop,
                            allowed_rotations=allowed, max_stack_weight_kg=stack, fragile=bool(fragile),
                            rotation_allowed=rot_allowed))
    # trộn thứ tự đầu vào (thứ tự xếp do thuật toán quyết định)
    order = rng.permutation(len(pkgs))
    pkgs = [pkgs[i] for i in order]
    stops = [Stop(id=str(s + 1), sequence=s + 1) for s in range(s_count)]
    return Problem(veh, pkgs, stops, seed=int(seed))


def dataset(seeds: Sequence[int], cfg: GenConfig = GenConfig()) -> List[Problem]:
    return [generate(int(s), cfg) for s in seeds]
