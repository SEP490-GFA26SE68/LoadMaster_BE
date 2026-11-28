# Chép từ LoadMaster_AI/lmai/generator.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Sinh bộ dữ liệu ngẫu nhiên (có seed) sát với LoadMaster.

- Xe: 7 xe tải thật trong dữ liệu mẫu của frontend (kích thước thùng, cửa, tải trọng, hai trục — số ước lượng).
- Kiện: mỗi điểm giao gồm vài "mã hàng" lặp lại (giống lô hàng thật), kích thước co giãn để tổng thể tích
  đạt một tỉ lệ lấp đầy mục tiêu; cân nặng chia theo thể tích x mật độ ngẫu nhiên.
- GenConfig(kind="real"): bài từ đơn hàng thật BED-BPP trên xe Van (xem generate_real, lmai/real_data.py).
- GenConfig(v3=True) — sinh bài v3 cho luật mới (M5, HUONG-DAN-TRAIN-LUMO-R3 mục 2.8): bài chấm theo luật mới
  (`Problem.physics_v2`), có σ, `must_load`, `priority`, `packing_group`, q_sàn, đuôi mật độ; xe Van khai trục.
  Phần v3 dùng bộ số ngẫu nhiên RIÊNG (seed, 3): cùng seed thì kích thước, số kiện, điểm giao, xe… y hệt bài cũ; chỉ
  khối lượng đổi (đuôi mật độ) và thêm trường mới.
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


# Xe Van chở hàng nội thành — lòng thùng & tải trọng theo GrabExpress (nguồn: datasets/README.md).
# Cửa: chưa có nguồn → bằng mặt cắt lòng thùng. Trục: KHÔNG khai (None) — chưa có số liệu, không bịa.
# Lưu ý: OptimizeService chặn xe thiếu dữ liệu trục (AXLE_CONFIG_MISSING, cố ý — Q-D7, 25/11/2026) và từ M2 bộ kiểm
# lmai cũng vậy → mọi phương án trên xe Van này đều KHÔNG hợp lệ cho tới khi có số trục (Q-D17, M5).
VAN_VEHICLES = [
    ("Van 500 kg", 160, 120, 120, 500),
    ("Van 1000 kg", 200, 150, 150, 1000),
]
# Số trục xe Van (Q-D17, M5): lấy từ bảng `vehicle_types` thật của LoadMaster (DB dùng chung, đọc 26/11/2026:
# "Xe Van 500 kg" id 10, "Xe Van 1.000 kg" id 11 — cùng lòng thùng và cửa với VAN_VEHICLES). Chỉ dùng cho bài v3;
# bộ `thuc-v1` (luật cũ) giữ xe Van không khai trục như cũ.
# (x trục trước, x trục sau, tối đa trục trước, tối đa trục sau, rỗng trục trước, rỗng trục sau)
VAN_AXLES = [(-60.0, 120.0, 750.0, 900.0, 520.0, 380.0), (-80.0, 150.0, 1150.0, 1500.0, 760.0, 540.0)]


def van_vehicle(i: int, axles: bool = False) -> Vehicle:
    n, L, W, H, pay = VAN_VEHICLES[i]
    v = Vehicle(inner_l=L, inner_w=W, inner_h=H, max_payload_kg=pay, door_w=W, door_h=H,
                max_cog_offset_ratio=0.15, name=n)
    if axles:
        (v.front_axle_x, v.rear_axle_x, v.front_axle_limit_kg, v.rear_axle_limit_kg, v.empty_front_axle_load_kg,
         v.empty_rear_axle_load_kg) = VAN_AXLES[i]
    return v


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
    # --- dữ liệu thật (kind="real"): các trường trên không dùng, trừ khi ghi rõ
    kind: str = "synthetic"                     # "synthetic" | "real"
    split: str = "train"                        # tập đơn hàng BED-BPP: train | val | test
    orders: Tuple[int, int] = (1, 3)            # số đơn mỗi chuyến (mỗi đơn = 1 điểm giao)
    max_fill: float = 0.85                      # tổng thể tích hàng ≤ max_fill × thể tích thùng
    vehicle_pool: str = "van"                   # chỉ "van" (đơn BED-BPP ~1 m³ — quá nhỏ cho xe tải 20–40 m³)
    # --- v3: luật vật lý mới (M5). Các số dưới đây là GIẢ ĐỊNH để mô hình gặp đủ trường hợp, không phải số xe / hàng thật.
    v3: bool = False
    p_sigma_missing: float = 0.05               # mã hàng không khai σ lẫn tải chồng → không nhận tải (như backend)
    p_must_load: float = 0.10                   # chỉ khi fill ≤ must_load_max_fill
    must_load_max_fill: float = 0.85
    must_load_max_vol: float = 0.50             # tổng thể tích kiện must_load ≤ 50 % thùng
    p_priority: float = 0.10                    # priority ∈ {1, 2, 3} (thang chưa chốt — dùng ở J, M6)
    p_floor: float = 0.30                       # tỉ lệ bài có tải trọng sàn
    # q_sàn kg/m². 300–900 từ rb-v2 (26/11): khoảng cũ 800–3.000 cao hơn áp lực sàn thật của bài sinh (EP-DBLF: p50
    # 230–480, p90 520–960 kg/m²) nên H10 gần như không bao giờ chặn. Thấp hơn sàn xe tải thật — GIẢ ĐỊNH để mô hình
    # gặp H10, không phải số xe thật. rb-v1 dùng (800, 3000).
    floor_range: Tuple[float, float] = (300.0, 900.0)
    p_dense_sku: float = 0.05                   # mã hàng có mật độ × dense_factor (để H7 / H10 thật sự chặn)
    dense_factor: float = 3.0


def _fit_door(dims: List[float], veh: Vehicle) -> bool:
    l, w, h = dims
    hmax = min(veh.inner_h, veh.door_h)
    for a, b, c in ((l, w, h), (w, l, h), (l, h, w), (w, h, l), (h, l, w), (h, w, l)):
        if a <= veh.inner_l and b <= veh.door_w and c <= hmax:
            return True
    return False


def generate(seed: int, cfg: GenConfig = GenConfig()) -> Problem:
    if cfg.kind == "real":
        return generate_real(seed, cfg)
    if cfg.kind != "synthetic":
        raise ValueError(f"GenConfig.kind không hợp lệ: {cfg.kind!r}")
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
    rng3 = np.random.default_rng([int(seed), 3]) if cfg.v3 else None
    if cfg.v3:   # đuôi mật độ: vài mã hàng nặng gấp dense_factor
        for sku in sorted(dens):
            if rng3.random() < cfg.p_dense_sku:
                dens[sku] *= cfg.dense_factor
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
    if cfg.v3:
        _apply_v3(pkgs, [r[2] for r in raw], veh, fill, cfg, rng3)
    # trộn thứ tự đầu vào (thứ tự xếp do thuật toán quyết định)
    order = rng.permutation(len(pkgs))
    pkgs = [pkgs[i] for i in order]
    stops = [Stop(id=str(s + 1), sequence=s + 1) for s in range(s_count)]
    return Problem(veh, pkgs, stops, seed=int(seed), physics_v2=cfg.v3)


def _apply_v3(pkgs: List[Package], skus: Sequence, veh: Vehicle, fill: float, cfg: GenConfig,
              rng: np.random.Generator) -> None:
    """Thêm trường của luật mới (mục 2.8) vào kiện đã sinh; `skus[i]` = mã hàng của pkgs[i]."""
    # q_sàn theo bài (giả định)
    if rng.random() < cfg.p_floor:
        veh.floor_load_capacity_kg_m2 = round(float(rng.uniform(*cfg.floor_range)), 1)
    # σ theo mã hàng: giữ phân bố cũ — σ = tải chồng cũ K / A_top (hướng gốc); không chồng / dễ vỡ → 0;
    # p_sigma_missing: không khai gì (kiện không nhận tải, như backend khi trống)
    missing = {}
    for sku in dict.fromkeys(skus):
        missing[sku] = rng.random() < cfg.p_sigma_missing
    for p, sku in zip(pkgs, skus):
        if missing[sku]:
            p.top_load_capacity_kg_m2 = None
            p.max_stack_weight_kg = None
        elif p.fragile or not p.max_stack_weight_kg:
            p.top_load_capacity_kg_m2 = 0.0
        else:
            p.top_load_capacity_kg_m2 = round(float(p.max_stack_weight_kg) / (p.l * p.w / 1e4), 1)
        p.packing_group = f"G{sku}"
    # must_load — ràng buộc sinh bài để bài LUÔN có lời giải (rủi ro M5): chỉ bài chưa quá đầy; xếp thử bằng EP-DBLF
    # theo luật mới (chưa có kiện bắt buộc); chỉ khi phương án thử hợp lệ mới chọn, và chỉ chọn trong số kiện nó đã xếp
    # → phương án thử là một lời giải hợp lệ chứa đủ kiện must_load. Tổng thể tích kiện bắt buộc ≤ must_load_max_vol.
    if fill <= cfg.must_load_max_fill:
        from .heuristics import ep_dblf   # tránh import vòng
        ref = ep_dblf(Problem(veh, pkgs, [], physics_v2=True))
        placed = {pl.package_id for pl in ref.placements} if ref.verdict.valid else set()
        budget = cfg.must_load_max_vol * veh.volume
        for p in pkgs:
            pick = rng.random() < cfg.p_must_load
            if pick and p.id in placed and p.volume <= budget:
                p.must_load = True
                budget -= p.volume
    for p in pkgs:
        if rng.random() < cfg.p_priority:
            p.priority = int(rng.integers(1, 4))


def generate_real(seed: int, cfg: GenConfig = GenConfig(kind="real")) -> Problem:
    """Một chuyến xe Van từ đơn hàng thật BED-BPP (tập cfg.split).

    - Bốc ngẫu nhiên (theo seed) các đơn của tập; nhận đơn nếu tổng khối lượng ≤ tải trọng và tổng thể tích
      ≤ max_fill × thể tích của Van lớn nhất; dừng khi đủ số đơn đã bốc (cfg.orders) hoặc thử quá nhiều đơn.
      Mỗi đơn là một điểm giao, giữ nguyên kích thước / khối lượng từng kiện (không co giãn như generate).
    - Chọn Van nhỏ nhất chở được (khối lượng ≤ tải trọng, thể tích ≤ max_fill × thùng).
    - Hướng xoay / chịu tải / dễ vỡ: GIẢ ĐỊNH theo mã hàng — xem lmai/real_data.item_attrs.
    """
    from .real_data import item_attrs, load_bedbpp

    if cfg.vehicle_pool != "van":
        raise ValueError("Dữ liệu thật chỉ hỗ trợ vehicle_pool='van' (một đơn BED-BPP ~1 m³, xe tải cần 14–34 đơn)")
    db = load_bedbpp()
    pool = db.by_split[cfg.split]
    rng = np.random.default_rng(seed)
    vans = [van_vehicle(i) for i in range(len(VAN_VEHICLES))]
    big = vans[-1]
    want = int(rng.integers(cfg.orders[0], cfg.orders[1] + 1))
    chosen, vol, kg = [], 0.0, 0.0
    for t in range(max(20, 10 * want)):
        o = db.orders[pool[int(rng.integers(len(pool)))]]
        if any(o.id == c.id for c in chosen):
            continue
        if kg + o.total_weight <= big.max_payload_kg and vol + o.volume <= cfg.max_fill * big.volume:
            chosen.append(o)
            vol += o.volume
            kg += o.total_weight
            if len(chosen) >= want:
                break
    if not chosen:
        raise RuntimeError("Không chọn được đơn nào vừa xe Van")
    veh = next(v for v in vans if kg <= v.max_payload_kg and vol <= cfg.max_fill * v.volume)
    if cfg.v3:   # luật mới: mọi xe phải khai trục (Q-D7) — số trục Van từ DB (Q-D17)
        veh = van_vehicle(vans.index(veh), axles=True)

    pkgs: List[Package] = []
    skus: List[str] = []
    for s, o in enumerate(chosen):
        for j in range(o.n):
            l, w, h = (float(x) for x in o.dims[j])
            wt = round(float(o.weight[j]), 2)
            rots, stack, fragile = item_attrs(o.article[j], wt)
            pkgs.append(Package(id=f"P{len(pkgs) + 1:04d}", l=l, w=w, h=h, weight=wt, stop_index=s + 1,
                                allowed_rotations=rots, max_stack_weight_kg=stack, fragile=fragile,
                                rotation_allowed=True))
            skus.append(o.article[j])
    if cfg.v3:   # σ = (3–8 × khối lượng) / A_top theo mã hàng = tải chồng giả định của item_attrs / A_top
        _apply_v3(pkgs, skus, veh, vol / veh.volume, cfg, np.random.default_rng([int(seed), 3]))
    order = rng.permutation(len(pkgs))
    pkgs = [pkgs[i] for i in order]
    stops = [Stop(id=str(s + 1), sequence=s + 1) for s in range(len(chosen))]
    return Problem(veh, pkgs, stops, seed=int(seed),
                   source={"dataset": "bed-bpp", "split": cfg.split, "orders": [o.id for o in chosen]},
                   physics_v2=cfg.v3)


def dataset(seeds: Sequence[int], cfg: GenConfig = GenConfig()) -> List[Problem]:
    return [generate(int(s), cfg) for s in seeds]
