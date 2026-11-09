# Chép từ LoadMaster_AI/lmai/state.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Trạng thái thùng xe và bộ kiểm ràng buộc nhanh (numpy), khớp ConstraintEngine HC-01 → HC-12 của OptimizeService.

Bản này dùng để train nên phải nhanh. Kết quả cuối vẫn được chấm lại bằng `validate.py` (cùng luật) và,
nếu có mã nguồn OptimizeService, bằng chính ConstraintEngine (xem tests/test_parity.py).

Một luật chặt hơn bản gốc: không cho đặt kiện mới chui xuống dưới đáy kiện đã có (kiện mới sẽ phải đỡ tải
ngược lên). Luật này chỉ loại bớt vài chỗ đặt hiếm gặp, đổi lại tải chồng/dễ vỡ tính tăng dần được và luôn hợp lệ.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .problem import Package, Placement, Problem, Vehicle

EPS = 1e-4
SUPPORT_RATIO = 0.70


def union_area(rects: Sequence[Tuple[float, float, float, float]]) -> float:
    """Diện tích hợp các hình chữ nhật (x1, x2, y1, y2) — cùng cách chia dải như HC-05."""
    if not rects:
        return 0.0
    if len(rects) == 1:
        x1, x2, y1, y2 = rects[0]
        return (x2 - x1) * (y2 - y1)
    xs = sorted({e for r in rects for e in (r[0], r[1])})
    area = 0.0
    for i in range(len(xs) - 1):
        left, right = xs[i], xs[i + 1]
        if right <= left:
            continue
        iv = sorted((r[2], r[3]) for r in rects if r[0] < right and r[1] > left)
        if not iv:
            continue
        cov = 0.0
        cs, ce = iv[0]
        for s, e in iv[1:]:
            if s <= ce:
                ce = max(ce, e)
            else:
                cov += ce - cs
                cs, ce = s, e
        cov += ce - cs
        area += (right - left) * cov
    return area


def center_in_hull(cx: float, cy: float, rects: Sequence[Tuple[float, float, float, float]]) -> bool:
    """HC-06: tâm hình chiếu nằm trong bao lồi các mặt tiếp xúc."""
    for x1, x2, y1, y2 in rects:  # đường tắt: nằm trong một mặt tiếp xúc
        if x1 - EPS <= cx <= x2 + EPS and y1 - EPS <= cy <= y2 + EPS:
            return True
    pts = sorted({(x, y) for x1, x2, y1, y2 in rects for x in (x1, x2) for y in (y1, y2)})
    if len(pts) < 3:
        return False

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    hull = lower[:-1] + upper[:-1]
    if len(hull) < 3:
        return False
    for i in range(len(hull)):
        if cross(hull[i], hull[(i + 1) % len(hull)], (cx, cy)) < -EPS:
            return False
    return True


@dataclass
class Candidate:
    """Một cách đặt hợp lệ: kiện (trong hàng chờ), hướng xoay, vị trí."""
    slot: int
    pkg_idx: int
    rot: int
    x: float
    y: float
    z: float
    l: float
    w: float
    h: float
    support_ratio: float
    supporters: List[Tuple[int, float]] = field(default_factory=list)


class LoadState:
    """Các kiện đã xếp + sổ sách tải chồng, trọng tâm, tải trục."""

    def __init__(self, problem: Problem):
        self.problem = problem
        self.veh: Vehicle = problem.vehicle
        n = max(1, len(problem.packages))
        self.cap = n
        self.n = 0
        self.box = np.zeros((n, 6), dtype=np.float64)  # x, y, z, l, w, h
        self.weight = np.zeros(n)
        self.limit = np.zeros(n)
        self.received = np.zeros(n)
        self.chain_fragile = np.zeros(n, dtype=bool)  # chính nó hoặc bên dưới có hàng dễ vỡ
        self.stop = np.zeros(n, dtype=np.int64)
        self.pkg_of = np.zeros(n, dtype=np.int64)
        self.rot_of = np.zeros(n, dtype=np.int64)
        self.supporters: List[List[Tuple[int, float]]] = []
        self.total_weight = 0.0
        self.total_volume = 0.0
        self.mx = 0.0  # Σ m·cx
        self.my = 0.0
        self.mz = 0.0
        # điểm đặt ứng viên (extreme points)
        self.eps: set = set()

    # ---------------------------------------------------------------- truy vấn
    def placed_boxes(self) -> np.ndarray:
        return self.box[: self.n]

    def cog(self) -> Tuple[float, float, float]:
        if self.total_weight <= 0:
            return self.veh.inner_l / 2, self.veh.inner_w / 2, 0.0
        return self.mx / self.total_weight, self.my / self.total_weight, self.mz / self.total_weight

    def cog_offsets(self, extra: Optional[Tuple[float, float, float, float]] = None) -> Tuple[float, float]:
        """Độ lệch trọng tâm X, Y so với tâm thùng (tỉ lệ kích thước thùng). extra = (m, cx, cy, cz) giả định thêm."""
        w, mx, my = self.total_weight, self.mx, self.my
        if extra is not None:
            m, cx, cy, _ = extra
            w, mx, my = w + m, mx + m * cx, my + m * cy
        if w <= 0:
            return 0.0, 0.0
        v = self.veh
        return abs(mx / w - v.inner_l / 2) / v.inner_l, abs(my / w - v.inner_w / 2) / v.inner_w

    def axle_loads(self, extra: Optional[Tuple[float, float]] = None) -> Optional[Tuple[float, float]]:
        """(tải trục trước, tải trục sau) gồm tải xe rỗng — mô hình đòn bẩy HC-12. extra = (m, cx)."""
        v = self.veh
        if not v.has_axles:
            return None
        w, mx = self.total_weight, self.mx
        if extra is not None:
            w, mx = w + extra[0], mx + extra[0] * extra[1]
        wheelbase = v.rear_axle_x - v.front_axle_x
        rear = (mx - w * v.front_axle_x) / wheelbase
        front = w - rear
        return v.empty_front_axle_load_kg + front, v.empty_rear_axle_load_kg + rear

    # ---------------------------------------------------------------- kiểm ràng buộc
    def _drop_z(self, x: float, y: float, z: float) -> float:
        """Hạ một điểm xuống mặt trên gần nhất bên dưới (hoặc sàn)."""
        if self.n == 0 or z <= EPS:
            return 0.0
        b = self.box[: self.n]
        tops = b[:, 2] + b[:, 5]
        inside = (b[:, 0] - EPS <= x) & (x < b[:, 0] + b[:, 3] - EPS) & (b[:, 1] - EPS <= y) & (y < b[:, 1] + b[:, 4] - EPS) & (tops <= z + EPS)
        return float(tops[inside].max()) if inside.any() else 0.0

    def feasible_positions(self, pkg: Package, rot: int, xlo: float, xhi: float, points: np.ndarray) -> List[Candidate]:
        """Kiểm mọi điểm trong `points` (M×3) cho một (kiện, hướng), kiện phải nằm trong [xlo, xhi] theo X.
        Vector hoá phần biên, va chạm, thứ tự dỡ (HC-09 từng cặp); phần đỡ/ổn định/tải chồng tính từng điểm còn lại."""
        v = self.veh
        l, w, h = pkg.dims(rot)
        # HC-10: lọt cửa; dỡ thẳng theo +X nên chiều cao kiện + z phải dưới mép cửa
        if w > v.door_w + EPS or h > v.door_h + EPS:
            return []
        if self.total_weight + pkg.weight > v.max_payload_kg + EPS:  # HC-04
            return []
        if points.size == 0:
            return []
        px, py, pz = points[:, 0], points[:, 1], points[:, 2]
        ymin, ymax = v.door_y_min, v.door_y_max
        ztop = min(v.inner_h, v.door_h)
        ok = (px >= xlo - EPS) & (px + l <= xhi + EPS) & (py >= ymin - EPS) & (py + w <= ymax + EPS) & (pz >= -EPS) & (pz + h <= ztop + EPS)
        if not ok.any():
            return []
        idx = np.nonzero(ok)[0]
        px, py, pz = px[idx], py[idx], pz[idx]
        if self.n:
            b = self.box[: self.n]
            bx, by, bz, bl, bw, bh = (b[:, k][None, :] for k in range(6))
            X, Y, Z = px[:, None], py[:, None], pz[:, None]
            sep = (X + l <= bx + EPS) | (bx + bl <= X + EPS) | (Y + w <= by + EPS) | (by + bw <= Y + EPS) | (Z + h <= bz + EPS) | (bz + bh <= Z + EPS)
            no_overlap = sep.all(axis=1)
            # luật chặt hơn: mặt trên kiện mới không được chạm đáy kiện đã có
            ovx = np.minimum(X + l, bx + bl) - np.maximum(X, bx)
            ovy = np.minimum(Y + w, by + bw) - np.maximum(Y, by)
            under = (np.abs(bz - (Z + h)) <= EPS) & (ovx > EPS) & (ovy > EPS)
            keep = no_overlap & ~under.any(axis=1)
            # HC-09 từng cặp: kiện giao sau không chắn lối ra (phía cửa, cùng hành lang YZ) của kiện giao trước
            # và không nằm đè lên kiện giao trước
            sb = self.stop[: self.n][None, :]
            s = pkg.stop_index
            ovz = np.minimum(Z + h, bz + bh) - np.maximum(Z, bz)
            yz = (ovy > EPS) & (ovz > EPS)
            placed_later = sb > s   # kiện đã xếp giao sau, kiện mới giao trước
            placed_earlier = sb < s
            bad = (placed_later & yz & (bx >= X + l - EPS)) | (placed_earlier & yz & (X >= bx + bl - EPS))
            bad |= placed_earlier & (ovx > EPS) & (ovy > EPS) & (np.abs(Z - (bz + bh)) <= EPS)
            keep &= ~bad.any(axis=1)
            # mặt đỡ trực tiếp
            touch = (np.abs((bz + bh) - Z) <= EPS) & (ovx > EPS) & (ovy > EPS)
        else:
            keep = np.ones(len(px), dtype=bool)
            touch = None
        out: List[Candidate] = []
        for k in np.nonzero(keep)[0]:
            x, y, z = float(px[k]), float(py[k]), float(pz[k])
            if z <= EPS:
                out.append(Candidate(-1, -1, rot, x, y, 0.0, l, w, h, 1.0, []))
                continue
            sup = np.nonzero(touch[k])[0]
            if sup.size == 0:
                continue
            rects, sups = [], []
            for j in sup:
                bj = self.box[j]
                x1, x2 = max(x, bj[0]), min(x + l, bj[0] + bj[3])
                y1, y2 = max(y, bj[1]), min(y + w, bj[1] + bj[4])
                rects.append((x1, x2, y1, y2))
                sups.append((int(j), (x2 - x1) * (y2 - y1)))
            ratio = min(1.0, union_area(rects) / (l * w))
            if ratio < SUPPORT_RATIO - 1e-9:  # HC-05
                continue
            if not center_in_hull(x + l / 2, y + w / 2, rects):  # HC-06
                continue
            if any(self.chain_fragile[j] for j, _ in sups):  # HC-08
                continue
            if not self._stack_ok(sups, pkg.weight):  # HC-07
                continue
            out.append(Candidate(-1, -1, rot, x, y, z, l, w, h, ratio, sups))
        return out

    def _stack_deltas(self, sups: List[Tuple[int, float]], load: float) -> Dict[int, float]:
        """Tải thêm truyền xuống từng kiện bên dưới, chia theo diện tích tiếp xúc (HC-07)."""
        delta: Dict[int, float] = {}
        stack = [(sups, load)]
        while stack:
            ss, ld = stack.pop()
            tot = sum(a for _, a in ss)
            if tot <= 0:
                continue
            for j, a in ss:
                share = ld * a / tot
                delta[j] = delta.get(j, 0.0) + share
                if self.supporters[j]:
                    stack.append((self.supporters[j], share))
        return delta

    def _stack_ok(self, sups: List[Tuple[int, float]], load: float) -> bool:
        for j, d in self._stack_deltas(sups, load).items():
            if self.received[j] + d > self.limit[j] + EPS:
                return False
        return True

    # ---------------------------------------------------------------- đặt kiện
    def place(self, pkg_idx: int, c: Candidate) -> None:
        pkg = self.problem.packages[pkg_idx]
        i = self.n
        self.box[i] = (c.x, c.y, c.z, c.l, c.w, c.h)
        self.weight[i] = pkg.weight
        self.limit[i] = pkg.stack_limit
        self.stop[i] = pkg.stop_index
        self.pkg_of[i] = pkg_idx
        self.rot_of[i] = c.rot
        self.supporters.append(list(c.supporters))
        self.chain_fragile[i] = pkg.fragile or any(self.chain_fragile[j] for j, _ in c.supporters)
        for j, d in self._stack_deltas(c.supporters, pkg.weight).items():
            self.received[j] += d
        self.n += 1
        self.total_weight += pkg.weight
        self.total_volume += c.l * c.w * c.h
        self.mx += pkg.weight * (c.x + c.l / 2)
        self.my += pkg.weight * (c.y + c.w / 2)
        self.mz += pkg.weight * (c.z + c.h / 2)
        # điểm mới (extreme points) + hạ xuống mặt đỡ
        for (x, y, z) in ((c.x + c.l, c.y, c.z), (c.x, c.y + c.w, c.z), (c.x, c.y, c.z + c.h)):
            self.eps.add((round(x, 4), round(y, 4), round(z, 4)))
            if z > EPS:
                dz = self._drop_z(x, y, z)
                if dz < z - EPS:
                    self.eps.add((round(x, 4), round(y, 4), round(dz, 4)))

    def placements(self) -> List[Placement]:
        out = []
        for i in range(self.n):
            x, y, z, l, w, h = (float(t) for t in self.box[i])
            pkg = self.problem.packages[int(self.pkg_of[i])]
            out.append(Placement(pkg.id, x, y, z, l, w, h, int(self.rot_of[i]), i + 1, str(pkg.stop_index)))
        return out
