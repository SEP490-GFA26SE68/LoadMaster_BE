# Chép từ LoadMaster_AI/lmai/packer.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Vòng xếp hàng dùng chung cho AI và thuật toán mốc.

Thứ tự: xếp từng vùng, vùng sâu nhất (điểm giao cuối) trước. Trong vùng, kiện xếp theo thể tích giảm dần như
OptimizeService; AI được chọn trong K kiện kế tiếp (hàng chờ), mọi hướng xoay và mọi điểm đặt hợp lệ.
Không đặt được trong vùng thì thử cả thùng (vẫn giữ luật dỡ HC-09) — tính là một lần "dỡ-xếp lại" như bản gốc.
"""
from __future__ import annotations

import os
from collections import deque
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np

from .problem import Problem
from .state import EPS, Candidate, LoadState, OUTSIDE_DOOR_FRAME
from .zones import Zone, compute_zones

# Bộ dựng biết H13 (sau M5, 26/11/2026): luật mới + bài có kiện must_load → hai lượt: lượt 0 đặt kiện must_load của MỌI
# vùng (vùng sâu trước) khi thùng còn trống, lượt 1 xếp phần còn lại như cũ — để hàng tràn từ vùng sâu không chiếm chỗ của
# kiện bắt buộc ở vùng sát cửa. Bài không có must_load: y hệt trước. LMAI_H13_HAI_LUOT=0 để tắt (đo so sánh).
H13_TWO_PASS = os.environ.get("LMAI_H13_HAI_LUOT", "1") == "1"


def unique_rows(a: np.ndarray) -> np.ndarray:
    """= np.unique(a, axis=0) cho mảng N×3 số thực: các hàng khác nhau, sắp theo cột 0 → 1 → 2 (28/11/2026, nhanh hơn
    vài lần vì không đổi sang kiểu có trường). Hàng trùng là trùng từng bit nên giữ hàng nào cũng như nhau. Mảng có -0.0
    hay NaN thì dùng thẳng np.unique: ở đó "bằng nhau" không còn là trùng bit và hàng được giữ tuỳ thuật toán sắp."""
    if len(a) < 2 or np.isnan(a).any() or ((a == 0) & np.signbit(a)).any():
        return np.unique(a, axis=0)
    s = a[np.lexsort((a[:, 2], a[:, 1], a[:, 0]))]
    keep = np.empty(len(s), dtype=bool)
    keep[0] = True
    keep[1:] = (s[1:] != s[:-1]).any(axis=1)
    return s[keep]


@dataclass
class PackConfig:
    buffer_k: int = 5          # số kiện trong hàng chờ AI được chọn
    per_rot: int = 6           # tối đa ứng viên giữ lại cho mỗi (kiện, hướng)
    max_candidates: int = 128  # tối đa ứng viên đưa vào mô hình


class Packer:
    def __init__(self, problem: Problem, cfg: PackConfig = PackConfig()):
        self.problem = problem
        self.cfg = cfg
        self.state = LoadState(problem)
        self.zones: List[Zone] = compute_zones(problem)
        self.zone_of_stop: Dict[int, Zone] = {z.stop_index: z for z in self.zones}
        v = problem.vehicle
        # vùng Y được đặt: khung cửa, hoặc cả bề rộng thùng khi bật thí nghiệm P18 (state.OUTSIDE_DOOR_FRAME)
        self.dy0, self.dy1 = (0.0, v.inner_w) if OUTSIDE_DOOR_FRAME else (v.door_y_min, v.door_y_max)
        self.zone_i = 0
        # lượt 0 = chỉ kiện must_load (khi bật bộ dựng biết H13), lượt 1 = phần còn lại / mọi kiện
        self.phase = 0 if (H13_TWO_PASS and problem.physics_v2 and any(p.must_load for p in problem.packages)) else 1
        self.two_pass = self.phase == 0   # → result.finish đánh lại step_sequence theo thứ tự bốc (load_order)
        self.queue: deque = deque()
        self.buffer: List[int] = []
        self.unplaced: List[int] = []
        self.rehandling = 0
        self.candidates: List[Candidate] = []
        self.cand_oz: List[bool] = []  # ứng viên nằm ngoài vùng
        for z in self.zones:  # điểm khởi đầu mỗi vùng: góc sâu, giữa vùng
            self.state.eps.add((round(z.x0, 4), round(self.dy0, 4), 0.0))
            self.state.eps.add((round((z.x0 + z.x1) / 2, 4), round(self.dy0, 4), 0.0))
        self._load_zone()
        self.refresh()

    # ---------------------------------------------------------------- hàng chờ
    def _load_zone(self) -> None:
        if self.phase == 0 and self.zone_i >= len(self.zones):   # hết lượt kiện bắt buộc → lượt phần còn lại
            self.phase, self.zone_i = 1, 0
        while self.zone_i < len(self.zones):
            s = self.zones[self.zone_i].stop_index
            idx = [i for i, p in enumerate(self.problem.packages) if p.stop_index == s]
            if self.phase == 0:
                idx = [i for i in idx if self.problem.packages[i].must_load]
            elif self.problem.physics_v2 and H13_TWO_PASS:
                idx = [i for i in idx if not self.problem.packages[i].must_load]
            idx.sort(key=lambda i: (self.problem.packages[i].volume, self.problem.packages[i].weight), reverse=True)
            if self.problem.physics_v2:
                # H13 (như backend solve_in_process): kiện must_load xét trước, giữ thứ tự còn lại
                idx.sort(key=lambda i: not self.problem.packages[i].must_load)
            if idx:
                self.queue = deque(idx)
                return
            self.zone_i += 1
            if self.phase == 0 and self.zone_i >= len(self.zones):
                self.phase, self.zone_i = 1, 0
        self.queue = deque()

    def _fill_buffer(self) -> None:
        while len(self.buffer) < self.cfg.buffer_k and self.queue:
            self.buffer.append(self.queue.popleft())

    @property
    def zone(self) -> Optional[Zone]:
        return self.zones[self.zone_i] if self.zone_i < len(self.zones) else None

    @property
    def done(self) -> bool:
        return self.zone is None

    # ---------------------------------------------------------------- ứng viên
    def _points(self, xlo: float, xhi: float) -> np.ndarray:
        pts = [p for p in self.state.eps if xlo - EPS <= p[0] < xhi - EPS]
        return np.array(pts, dtype=np.float64).reshape(-1, 3)

    def _cands_for(self, slot: int, pkg_idx: int, xlo: float, xhi: float, base: np.ndarray) -> List[Candidate]:
        pkg = self.problem.packages[pkg_idx]
        out: List[Candidate] = []
        # điểm sát vách trái (→ thêm biến thể sát vách phải) / sát đầu vùng phía sâu (→ thêm biến thể sát đầu vùng phía
        # cửa). Vector hoá (28/11/2026); unique_rows (= np.unique axis 0) sắp lại nên thứ tự thêm không ảnh hưởng — cùng
        # các điểm, cùng thứ tự như vòng cũ.
        on_y = np.abs(base[:, 1] - self.dy0) <= EPS if base.size else None
        on_x = np.abs(base[:, 0] - xlo) <= EPS if base.size else None
        has_extra = base.size and (on_y.any() or on_x.any())
        for rot in pkg.rotations():
            l, w, h = pkg.dims(rot)
            if has_extra:
                ey = base[on_y]
                ey[:, 1] = self.dy1 - w
                ex = base[on_x]
                ex[:, 0] = xhi - l
                pts = unique_rows(np.vstack([base, ey, ex]))
            else:
                pts = base
            cs = self.state.feasible_positions(pkg, rot, xlo, xhi, pts, limit=self.cfg.per_rot)
            cs.sort(key=lambda c: (c.z, c.x, c.y))
            for c in cs[: self.cfg.per_rot]:
                c.slot, c.pkg_idx = slot, pkg_idx
                out.append(c)
        return out

    def refresh(self) -> None:
        """Tính danh sách ứng viên cho trạng thái hiện tại; bỏ kiện không đặt được, sang vùng kế khi hết kiện."""
        while not self.done:
            self._fill_buffer()
            if not self.buffer:
                self.zone_i += 1
                self._load_zone()
                continue
            z = self.zone
            base = self._points(z.x0, z.x1)
            cands: List[Candidate] = []
            oz: List[bool] = []
            stuck = []
            for slot, pi in enumerate(self.buffer):
                cs = self._cands_for(slot, pi, z.x0, z.x1, base)
                if not cs:
                    # thử cả thùng (ngoài vùng) — bản gốc cũng làm vậy và tính là dỡ-xếp lại
                    allpts = self._points(0.0, self.problem.vehicle.inner_l)
                    cs = self._cands_for(slot, pi, 0.0, self.problem.vehicle.inner_l, allpts)
                    if cs:
                        oz.extend([True] * len(cs))
                        cands.extend(cs)
                    else:
                        stuck.append(pi)
                    continue
                oz.extend([False] * len(cs))
                cands.extend(cs)
            if stuck:
                for pi in stuck:
                    self.buffer.remove(pi)
                    self.unplaced.append(pi)
                if not cands:
                    continue
                # chỉ số slot đã đổi sau khi bỏ kiện — tính lại cho chắc
                continue
            if cands:
                order = sorted(range(len(cands)), key=lambda i: (oz[i], cands[i].z, cands[i].x, cands[i].y))
                order = order[: self.cfg.max_candidates]
                self.candidates = [cands[i] for i in order]
                self.cand_oz = [oz[i] for i in order]
                return
        self.candidates, self.cand_oz = [], []

    # ---------------------------------------------------------------- đặt
    def apply(self, k: int) -> Tuple[Candidate, bool]:
        c = self.candidates[k]
        out_zone = self.cand_oz[k]
        self.state.place(c.pkg_idx, c)
        self.buffer.remove(c.pkg_idx)
        if out_zone:
            self.rehandling += 1
        self.refresh()
        return c, out_zone
