# Chép từ LoadMaster_AI/lmai/state.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Trạng thái thùng xe và bộ kiểm ràng buộc nhanh (numpy), khớp ConstraintEngine HC-01 → HC-12 của OptimizeService.

Bản này dùng để train nên phải nhanh. Kết quả cuối vẫn được chấm lại bằng `validate.py` (cùng luật) và,
nếu có mã nguồn OptimizeService, bằng chính ConstraintEngine (xem tests/test_parity.py).

Một luật chặt hơn bản gốc: không cho đặt kiện mới chui xuống dưới đáy kiện đã có (kiện mới sẽ phải đỡ tải
ngược lên). Luật này chỉ loại bớt vài chỗ đặt hiếm gặp, đổi lại tải chồng/dễ vỡ tính tăng dần được và luôn hợp lệ.

Luật mới (`Problem.physics_v2`, M5 — như backend PHYSICS_RULES_V2):
- H7: (received_j + d_j) / A_c,j ≤ σ_j, A_c,j = tổng diện tích tiếp xúc trên mặt trên của j SAU khi đặt (sổ
  `contact_top`): kiện đỡ trực tiếp có thêm mặt tiếp xúc, kiện sâu hơn chỉ thêm tải. σ trống = không nhận tải.
  Thay HC-07 (kg) và HC-08 (`chain_fragile` không dùng nữa).
- H10: kiện nằm sàn (gồm chính kiện mới): (mᵢ + receivedᵢ) / (lᵢwᵢ / 10⁴) ≤ q_sàn; xe không khai q_sàn → bỏ qua.
- H8: ρ theo kiện. Cùng sai số so sánh với backend (`_check_pressure`: limit·(1 + 1e-9) + 1e-6).
Nhờ "luật chặt hơn", tải và diện tích của mọi kiện cũ chỉ tăng → kiểm từng bước vẫn đúng cho cả phương án.
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .problem import Package, Placement, Problem, Vehicle

EPS = 1e-4
SUPPORT_RATIO = 0.70   # luật cũ (HC-05); luật mới: Package.support_ratio(True)
# P18 (KE-HOACH-TOI-UU M2 mục 3, thí nghiệm): cho đặt kiện ngoài khung cửa (theo Y). Dỡ được nhờ dịch ngang vào khung
# rồi kéo +X — như UnloadFeasibilityValidator của backend (b0f79a4). Tắt (mặc định) = chỉ đặt trong khung cửa như cũ.
OUTSIDE_DOOR_FRAME = os.environ.get("LMAI_NGOAI_KHUNG_CUA", "0") == "1"
# Bộ nhớ đệm phần kiểm từng cặp của `LoadState.feasible_positions` (28/11/2026, tăng tốc bộ dựng — ưu tiên 5). Cho cùng
# kết quả với cách tính lại từ đầu (xem `_PairCache`); LMAI_PAIR_CACHE=0 (hoặc gán state.PAIR_CACHE = False) để tắt, đối chiếu.
PAIR_CACHE = os.environ.get("LMAI_PAIR_CACHE", "1") == "1"


def over_limit(value: float, limit: float) -> bool:
    """So sánh vượt giới hạn H7 / H10, đúng như `ConstraintEngine._check_pressure` của backend."""
    return value > limit * (1 + 1e-9) + 1e-6


def exit_corridor(y, w, dy0: float, dy1: float):
    """Dải Y mà kiện [y, y + w] quét qua khi dỡ: tự nó nếu đã trong khung cửa; ngoài khung thì thêm đoạn dịch ngang tới
    vị trí gần nhất trong khung (một trong các đích Y của validator). Chạy được với số hoặc mảng numpy."""
    lo = np.where(y < dy0 - EPS, y, np.minimum(y, dy1 - w))
    hi = np.where(y < dy0 - EPS, np.maximum(y + w, dy0 + w), y + w)
    return lo, hi


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


class _PairEntry:
    """Kết quả kiểm từng cặp của một khoá (l, w, h, điểm giao), theo chỉ số điểm chung của `_PairCache.pid`."""

    __slots__ = ("keep", "done", "sup")

    def __init__(self):
        self.keep = np.ones(64, dtype=bool)          # còn đặt được với mọi kiện đã kiểm
        self.done = np.zeros(64, dtype=np.int32)     # đã kiểm với kiện 0 .. done-1
        self.sup: Dict[int, List[int]] = {}          # kiện đỡ trực tiếp (chỉ số tăng dần), chỉ cho điểm còn đặt được

    def grow(self, size: int) -> None:
        cap = len(self.keep)
        if size > cap:
            more = max(size, 2 * cap) - cap
            self.keep = np.concatenate([self.keep, np.ones(more, dtype=bool)])
            self.done = np.concatenate([self.done, np.zeros(more, dtype=np.int32)])


class _PairCache:
    """Nhớ phần kiểm TỪNG CẶP (điểm đặt, kiện đã xếp) của `feasible_positions` theo khoá (l, w, h, điểm giao) của kiện
    mới: mỗi lần gọi chỉ kiểm điểm mới với mọi kiện và điểm cũ với các kiện đặt sau lần kiểm trước.

    Vì sao cho cùng kết quả với cách tính lại từ đầu:
    - phần kiểm từng cặp (không chồng lấn, mặt trên không chạm đáy kiện cũ, HC-09 từng cặp, kiện đỡ trực tiếp) chỉ phụ
      thuộc toạ độ điểm, (l, w, h, điểm giao) của kiện mới và hộp + điểm giao của kiện đã xếp (`_pair_masks`, cùng một
      hàm cho cả hai cách → cùng phép tính số thực). Kiện đã xếp không đổi, không bị bỏ (`place` chỉ thêm vào cuối);
    - "còn đặt được" là AND trên mọi kiện → điểm đã bị chặn thì chặn mãi, không cần kiểm lại;
    - kiện đỡ được ghi theo chỉ số tăng dần, như `np.nonzero` của cách cũ → cùng thứ tự cộng diện tích / tải.
    Phần kiểm từng điểm (HC-05 / HC-06 / H7 / H10 / HC-07 / HC-08) vẫn tính lại mỗi lần như cũ.
    Chép trạng thái (deepcopy ở tìm kiếm / biết tính trước, pickle) → bộ nhớ đệm rỗng: rẻ và vẫn đúng.
    """

    def __init__(self):
        self.pid: Dict[Tuple[float, float, float], int] = {}
        self.entries: Dict[Tuple[float, float, float, int], _PairEntry] = {}

    def __deepcopy__(self, memo):
        return _PairCache()

    def __reduce__(self):
        return (_PairCache, ())

    def ids(self, px: np.ndarray, py: np.ndarray, pz: np.ndarray) -> np.ndarray:
        pid = self.pid
        out = np.empty(len(px), dtype=np.int64)
        for k, p in enumerate(zip(px.tolist(), py.tolist(), pz.tolist())):
            i = pid.get(p)
            if i is None:
                i = pid[p] = len(pid)
            out[k] = i
        return out


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
        self.chain_fragile = np.zeros(n, dtype=bool)  # chính nó hoặc bên dưới có hàng dễ vỡ (luật cũ)
        # luật mới: σ (nan = trống → không nhận tải) và diện tích tiếp xúc trên mặt trên (cm²)
        self.v2 = bool(problem.physics_v2)
        self.sigma = np.full(n, np.nan)
        self.contact_top = np.zeros(n)
        q = self.veh.floor_load_capacity_kg_m2
        self.floor_q: Optional[float] = float(q) if (self.v2 and q is not None) else None
        # chỉ để ĐO (benchmark.blocked_by_rules): tạm bỏ H7 / H10 xem kiện có chỗ đặt không. Không dùng khi xếp.
        self.skip_h7 = False
        self.skip_h10 = False
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
        self._pairs = _PairCache()

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

    def feasible_positions(self, pkg: Package, rot: int, xlo: float, xhi: float, points: np.ndarray,
                           limit: Optional[int] = None) -> List[Candidate]:
        """Kiểm mọi điểm trong `points` (M×3) cho một (kiện, hướng), kiện phải nằm trong [xlo, xhi] theo X.
        Vector hoá phần biên, va chạm, thứ tự dỡ (HC-09 từng cặp); phần đỡ/ổn định/tải chồng tính từng điểm còn lại.

        `limit` (28/11/2026, tăng tốc bộ dựng): chỉ cần `limit` ứng viên đầu theo (z, x, y) — như Packer giữ `per_rot`
        ứng viên sau khi sắp. Khi đó các điểm được xét theo đúng thứ tự (z, x, y), hoà thì theo thứ tự trong `points`
        (như sắp xếp ổn định), và dừng khi đủ `limit` ứng viên. Phép kiểm từng điểm độc lập với nhau nên kết quả đúng
        bằng `sorted(tất cả, key=(z, x, y))[:limit]`. Không có `limit`: mọi ứng viên, theo thứ tự trong `points`."""
        v = self.veh
        l, w, h = pkg.dims(rot)
        # HC-10: lọt cửa; dỡ thẳng theo +X nên chiều cao kiện + z phải dưới mép cửa
        if w > v.door_w + EPS or h > v.door_h + EPS:
            return []
        if self.total_weight + pkg.weight > v.max_payload_kg + EPS:  # HC-04
            return []
        if points.size == 0:
            return []
        rho = pkg.support_ratio(self.v2)
        # H10 cho chính kiện mới khi nằm sàn (chỉ phụ thuộc hướng xoay)
        floor_ok_self = (self.floor_q is None or self.skip_h10
                         or not over_limit(pkg.weight / (l * w / 1e4), self.floor_q))
        px, py, pz = points[:, 0], points[:, 1], points[:, 2]
        dy0, dy1 = v.door_y_min, v.door_y_max
        ymin, ymax = (0.0, v.inner_w) if OUTSIDE_DOOR_FRAME else (dy0, dy1)
        ztop = min(v.inner_h, v.door_h)
        ok = (px >= xlo - EPS) & (px + l <= xhi + EPS) & (py >= ymin - EPS) & (py + w <= ymax + EPS) & (pz >= -EPS) & (pz + h <= ztop + EPS)
        if not ok.any():
            return []
        idx = np.nonzero(ok)[0]
        px, py, pz = px[idx], py[idx], pz[idx]
        s = pkg.stop_index
        rows = entry = touch = None
        if self.n and PAIR_CACHE:
            keep, rows, entry = self._cached_pairs(l, w, h, s, px, py, pz)
        elif self.n:
            ok_pair, touch = self._pair_masks(px[:, None], py[:, None], pz[:, None], l, w, h, s, 0, self.n)
            keep = ok_pair.all(axis=1)
        else:
            keep = np.ones(len(px), dtype=bool)
        out: List[Candidate] = []
        order = np.nonzero(keep)[0]
        if limit is not None and len(order) > limit:
            # khoá sắp của Packer: (c.z, c.x, c.y), c.z = 0 khi nằm sàn; hoà → thứ tự điểm (lexsort ổn định)
            zc = np.where(pz[order] <= EPS, 0.0, pz[order])
            order = order[np.lexsort((order, py[order], px[order], zc))]
        else:
            limit = None
        for k in order:
            if limit is not None and len(out) >= limit:
                break
            x, y, z = float(px[k]), float(py[k]), float(pz[k])
            if z <= EPS:
                if floor_ok_self:
                    out.append(Candidate(-1, -1, rot, x, y, 0.0, l, w, h, 1.0, []))
                continue
            sup = entry.sup.get(int(rows[k]), ()) if entry is not None else np.nonzero(touch[k])[0]
            if len(sup) == 0:
                continue
            rects, sups = [], []
            for j in sup:
                bj = self.box[j]
                x1, x2 = max(x, bj[0]), min(x + l, bj[0] + bj[3])
                y1, y2 = max(y, bj[1]), min(y + w, bj[1] + bj[4])
                rects.append((x1, x2, y1, y2))
                sups.append((int(j), (x2 - x1) * (y2 - y1)))
            ratio = min(1.0, union_area(rects) / (l * w))
            if ratio < rho - 1e-9:  # HC-05 / H8
                continue
            if not center_in_hull(x + l / 2, y + w / 2, rects):  # HC-06
                continue
            if self.v2:
                if not self._pressure_ok(sups, pkg.weight):  # H7 + H10
                    continue
            else:
                if any(self.chain_fragile[j] for j, _ in sups):  # HC-08
                    continue
                if not self._stack_ok(sups, pkg.weight):  # HC-07
                    continue
            out.append(Candidate(-1, -1, rot, x, y, z, l, w, h, ratio, sups))
        return out

    def _pair_masks(self, X, Y, Z, l: float, w: float, h: float, s: int, j0: int, j1: int):
        """Kiểm từng cặp (điểm, kiện đã xếp j0 .. j1-1) cho kiện mới l×w×h giao ở điểm s; X, Y, Z là cột R×1.
        Trả (ok, touch), mỗi cái R×(j1-j0): ok = không chồng lấn, mặt trên không chạm đáy kiện cũ, không phạm HC-09 từng
        cặp; touch = kiện cũ đỡ trực tiếp (mặt trên ở cao độ Z, giao XY > EPS)."""
        v = self.veh
        dy0, dy1 = v.door_y_min, v.door_y_max
        b = self.box[j0:j1]
        bx, by, bz, bl, bw, bh = (b[:, k][None, :] for k in range(6))
        sb = self.stop[j0:j1][None, :]
        sep = (X + l <= bx + EPS) | (bx + bl <= X + EPS) | (Y + w <= by + EPS) | (by + bw <= Y + EPS) | (Z + h <= bz + EPS) | (bz + bh <= Z + EPS)
        # luật chặt hơn: mặt trên kiện mới không được chạm đáy kiện đã có
        ovx = np.minimum(X + l, bx + bl) - np.maximum(X, bx)
        ovy = np.minimum(Y + w, by + bw) - np.maximum(Y, by)
        under = (np.abs(bz - (Z + h)) <= EPS) & (ovx > EPS) & (ovy > EPS)
        # HC-09 từng cặp: kiện giao sau không chắn lối ra (phía cửa, cùng hành lang YZ) của kiện giao trước
        # và không nằm đè lên kiện giao trước
        ovz = np.minimum(Z + h, bz + bh) - np.maximum(Z, bz)
        yz = (ovy > EPS) & (ovz > EPS)
        placed_later = sb > s   # kiện đã xếp giao sau, kiện mới giao trước
        placed_earlier = sb < s
        if OUTSIDE_DOOR_FRAME:
            # hành lang dỡ của kiện giao trước (gồm đoạn dịch ngang vào khung cửa); kiện giao sau không được nằm
            # trong hành lang đó ở phía cửa, hay ngay cạnh nó (cùng đoạn X) trên đường dịch ngang
            nlo, nhi = exit_corridor(Y, w, dy0, dy1)          # kiện mới là kiện giao trước
            blo, bhi = exit_corridor(by, bw, dy0, dy1)        # kiện đã xếp là kiện giao trước
            in_new = (np.minimum(nhi, by + bw) - np.maximum(nlo, by) > EPS) & (ovz > EPS)
            in_old = (np.minimum(bhi, Y + w) - np.maximum(blo, Y) > EPS) & (ovz > EPS)
            bad = (placed_later & in_new & ((bx >= X + l - EPS) | (ovx > EPS))) |                       (placed_earlier & in_old & ((X >= bx + bl - EPS) | (ovx > EPS)))
        else:
            bad = (placed_later & yz & (bx >= X + l - EPS)) | (placed_earlier & yz & (X >= bx + bl - EPS))
        bad |= placed_earlier & (ovx > EPS) & (ovy > EPS) & (np.abs(Z - (bz + bh)) <= EPS)
        # mặt đỡ trực tiếp
        touch = (np.abs((bz + bh) - Z) <= EPS) & (ovx > EPS) & (ovy > EPS)
        return sep & ~under & ~bad, touch

    def _cached_pairs(self, l: float, w: float, h: float, s: int, px: np.ndarray, py: np.ndarray, pz: np.ndarray):
        """Như `_pair_masks(...).all(axis=1)` trên mọi kiện đã xếp, qua `_PairCache`. Trả (keep, rows, entry):
        kiện đỡ của điểm thứ k (khi keep[k]) là entry.sup.get(rows[k], ())."""
        cache = self._pairs
        key = (l, w, h, s)
        e = cache.entries.get(key)
        if e is None:
            e = cache.entries[key] = _PairEntry()
        rows = cache.ids(px, py, pz)
        e.grow(len(cache.pid))
        n = self.n
        todo = e.keep[rows] & (e.done[rows] < n)
        if todo.any():
            rk, first = np.unique(rows[todo], return_index=True)
            qk = np.nonzero(todo)[0][first]
            d = e.done[rk]
            j0 = int(d.min())
            ok, touch = self._pair_masks(px[qk][:, None], py[qk][:, None], pz[qk][:, None], l, w, h, s, j0, n)
            if j0 < int(d.max()):   # các điểm đã kiểm tới các mức khác nhau → bỏ những cặp đã kiểm rồi
                fresh = np.arange(j0, n)[None, :] >= d[:, None]
                ok |= ~fresh
                touch &= fresh
            alive = ok.all(axis=1)
            e.keep[rk] = alive
            e.done[rk] = n
            ri, ci = np.nonzero(touch & alive[:, None])
            sup = e.sup
            for r, j in zip(rk[ri].tolist(), (ci + j0).tolist()):
                lst = sup.get(r)
                if lst is None:
                    sup[r] = [j]
                else:
                    lst.append(j)
        return e.keep[rows], rows, e

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

    def _pressure_ok(self, sups: List[Tuple[int, float]], load: float) -> bool:
        """H7 + H10 sau khi đặt một kiện nặng `load` lên các kiện đỡ `sups` (luật mới)."""
        direct = dict(sups)
        for j, d in self._stack_deltas(sups, load).items():
            recv = self.received[j] + d
            if recv > EPS and not self.skip_h7:
                sig = self.sigma[j]
                if np.isnan(sig):
                    return False
                area = (self.contact_top[j] + direct.get(j, 0.0)) / 1e4
                if over_limit(recv / area, sig):
                    return False
            if self.floor_q is not None and not self.skip_h10 and self.box[j, 2] <= EPS:
                if over_limit((self.weight[j] + recv) / (self.box[j, 3] * self.box[j, 4] / 1e4), self.floor_q):
                    return False
        return True

    def pressure(self, i: int) -> float:
        """Áp lực hiện tại trên mặt trên kiện thứ i [kg/m²] (0 nếu chưa có gì đè)."""
        a = self.contact_top[i]
        return float(self.received[i] / (a / 1e4)) if a > 0 else 0.0

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
        sig = pkg.sigma
        self.sigma[i] = np.nan if sig is None else sig
        for j, a in c.supporters:
            self.contact_top[j] += a
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

    @classmethod
    def from_placements(cls, problem: Problem, pls: Sequence[Placement]) -> "LoadState":
        """Dựng lại sổ sách từ một phương án (theo thứ tự đặt; kiện đỡ phải đứng trước kiện nó đỡ, như bộ dựng lmai)."""
        st = cls(problem)
        idx = {p.id: i for i, p in enumerate(problem.packages)}
        for p in pls:
            sups = []
            for j in range(st.n):
                bj = st.box[j]
                if abs(bj[2] + bj[5] - p.z) > EPS:
                    continue
                ox = min(p.x + p.packed_l, bj[0] + bj[3]) - max(p.x, bj[0])
                oy = min(p.y + p.packed_w, bj[1] + bj[4]) - max(p.y, bj[1])
                if ox > EPS and oy > EPS:
                    sups.append((j, ox * oy))
            c = Candidate(-1, -1, int(p.rotation_type), p.x, p.y, p.z, p.packed_l, p.packed_w, p.packed_h, 1.0, sups)
            st.place(idx[p.package_id], c)
        return st

    def placements(self) -> List[Placement]:
        out = []
        for i in range(self.n):
            x, y, z, l, w, h = (float(t) for t in self.box[i])
            pkg = self.problem.packages[int(self.pkg_of[i])]
            out.append(Placement(pkg.id, x, y, z, l, w, h, int(self.rot_of[i]), i + 1, str(pkg.stop_index)))
        return out
