# Chép từ LoadMaster_AI/lmai/env.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Môi trường học tăng cường (giao diện kiểu gymnasium: reset / step).

Mỗi bước mô hình chọn 1 trong các ứng viên hợp lệ (kiện trong hàng chờ × hướng xoay × điểm đặt).
Quan sát gồm 3 phần, độ dài cố định để ghép lô:
- placed (P×FP): các kiện đã xếp gần vùng đang xếp nhất
- cand   (C×FC): các ứng viên (đã lọc ràng buộc — mô hình không bao giờ chọn được nước sai)
- glob   (FG):   tình trạng chung (vùng, lấp đầy, trọng tâm, tải trục)
Điểm thưởng: mỗi kiện đặt được cộng thể tích kiện / tổng thể tích hàng. Kết thúc mà trọng tâm, tải trục hoặc thứ tự
dỡ sai (OptimizeService sẽ từ chối cả phương án) thì trừ lại toàn bộ và phạt thêm theo mức vi phạm.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

import numpy as np

from .packer import PackConfig, Packer
from .problem import Problem
from .result import PlanResult, finish

FP, FC, FG = 10, 22, 14
# CP2 (đầu vào v2): thêm cột cho ứng viên / toàn cục + token hàng còn lại + bản đồ độ cao. Cột cũ giữ nguyên vị trí
# nên mô hình v1 (Lumo-R1.x) vẫn chạy được trên quan sát v2 (chỉ đọc phần đầu).
FC2, FG2, FR = 4, 8, 10


@dataclass
class EnvConfig:
    max_placed: int = 80
    max_candidates: int = 128
    buffer_k: int = 5
    per_rot: int = 6
    out_zone_penalty: float = 0.02
    invalid_penalty: float = 0.5
    obs_v2: bool = False      # CP2: thêm thông tin hàng còn lại, bản đồ độ cao, độ khít của ứng viên
    n_rem: int = 16           # số kiện còn lại đưa vào quan sát
    hmap_x: int = 16          # lưới bản đồ độ cao (dọc thùng × ngang khung cửa)
    hmap_y: int = 8


def obs_spec(cfg: EnvConfig) -> Dict[str, Tuple[int, ...]]:
    if not cfg.obs_v2:
        return {"placed": (cfg.max_placed, FP), "placed_mask": (cfg.max_placed,), "cand": (cfg.max_candidates, FC),
                "cand_mask": (cfg.max_candidates,), "glob": (FG,)}
    return {"placed": (cfg.max_placed, FP), "placed_mask": (cfg.max_placed,), "cand": (cfg.max_candidates, FC + FC2),
            "cand_mask": (cfg.max_candidates,), "glob": (FG + FG2,), "rem": (cfg.n_rem, FR), "rem_mask": (cfg.n_rem,),
            "hmap": (cfg.hmap_x * cfg.hmap_y,)}


class PackingEnv:
    def __init__(self, cfg: EnvConfig = EnvConfig(), problem_fn: Optional[Callable[[], Problem]] = None):
        self.cfg = cfg
        self.problem_fn = problem_fn
        self.pack_cfg = PackConfig(buffer_k=cfg.buffer_k, per_rot=cfg.per_rot, max_candidates=cfg.max_candidates)
        self.packer: Optional[Packer] = None
        self.problem: Optional[Problem] = None
        self.t0 = 0.0

    # ---------------------------------------------------------------- vòng đời
    def reset(self, problem: Optional[Problem] = None):
        self.problem = problem if problem is not None else self.problem_fn()
        self.t0 = time.perf_counter()
        self.packer = Packer(self.problem, self.pack_cfg)
        v = self.problem.vehicle
        self.wscale = max(1.0, max((p.weight for p in self.problem.packages), default=1.0))
        self.cargo = max(1e-9, self.problem.cargo_volume)
        self.zone_cargo = {}
        for p in self.problem.packages:
            self.zone_cargo[p.stop_index] = self.zone_cargo.get(p.stop_index, 0.0) + p.volume
        self.ret = 0.0
        return self.observe()

    @property
    def done(self) -> bool:
        return self.packer.done or not self.packer.candidates

    def step(self, action: int):
        c, oz = self.packer.apply(int(action))
        r = c.l * c.w * c.h / self.cargo - (self.cfg.out_zone_penalty if oz else 0.0)
        info: Dict = {}
        if self.done:
            res = self.result("AI")
            info["result"] = res
            if not res.verdict.valid:
                ver = res.verdict
                v = self.problem.vehicle
                excess = max(0.0, ver.cog_x - v.max_cog_offset_ratio) / v.max_cog_offset_ratio + \
                    max(0.0, ver.cog_y - v.max_cog_offset_ratio) / v.max_cog_offset_ratio
                if not ver.axle_ok:
                    excess += 0.5
                if not ver.unload_ok:
                    excess += 1.0
                r -= (self.ret + r) + self.cfg.invalid_penalty * min(1.0, 0.25 + excess)
        self.ret += r
        obs = None if self.done else self.observe()
        return obs, float(r), self.done, info

    def result(self, name: str = "AI") -> PlanResult:
        return finish(self.problem, self.packer, name, time.perf_counter() - self.t0)

    # ---------------------------------------------------------------- quan sát
    def observe(self) -> Dict[str, np.ndarray]:
        cfg, pk, st = self.cfg, self.packer, self.packer.state
        v = self.problem.vehicle
        L, W, H = v.inner_l, v.inner_w, v.inner_h
        zone = pk.zone
        zx0, zx1 = (zone.x0, zone.x1) if zone else (0.0, L)
        zlen = max(1.0, zx1 - zx0)
        ratio = max(1e-6, v.max_cog_offset_ratio)

        placed = np.zeros((cfg.max_placed, FP), np.float32)
        pmask = np.zeros(cfg.max_placed, bool)
        n = st.n
        if n:
            b = st.box[:n]
            centre = (zx0 + zx1) / 2
            dist = np.abs(b[:, 0] + b[:, 3] / 2 - centre)
            sel = np.argsort(dist, kind="stable")[: cfg.max_placed]
            lim = st.limit[sel]
            cap = np.where(lim > 0, np.clip((lim - st.received[sel]) / np.maximum(lim, 1e-6), 0, 1), 0)
            cur = zone.stop_index if zone else -1
            placed[: len(sel)] = np.stack([
                b[sel, 0] / L, b[sel, 1] / W, b[sel, 2] / H, b[sel, 3] / L, b[sel, 4] / W, b[sel, 5] / H,
                st.weight[sel] / self.wscale, st.chain_fragile[sel].astype(float), cap, (st.stop[sel] == cur).astype(float),
            ], axis=1)
            pmask[: len(sel)] = True

        cand = np.zeros((cfg.max_candidates, FC), np.float32)
        cmask = np.zeros(cfg.max_candidates, bool)
        has_ax = v.has_axles
        zcargo = self.zone_cargo.get(zone.stop_index, 1.0) if zone else 1.0
        for i, c in enumerate(pk.candidates[: cfg.max_candidates]):
            pkg = self.problem.packages[c.pkg_idx]
            m = pkg.weight
            ex = (m, c.x + c.l / 2, c.y + c.w / 2, c.z + c.h / 2)
            w2 = st.total_weight + m
            cxs = ((st.mx + m * ex[1]) / w2 - L / 2) / L / ratio
            cys = ((st.my + m * ex[2]) / w2 - W / 2) / W / ratio
            if has_ax:
                fa, ra = st.axle_loads((m, ex[1]))
                fa, ra = fa / v.front_axle_limit_kg, ra / v.rear_axle_limit_kg
            else:
                fa = ra = 0.0
            cand[i] = (c.l / L, c.w / W, c.h / H, c.x / L, c.y / W, c.z / H, (c.x - zx0) / zlen, (c.x + c.l - zx0) / zlen,
                       (c.z + c.h) / H, c.support_ratio, m / self.wscale, float(pkg.fragile), float(pkg.stack_limit > 0),
                       c.l * c.w * c.h / zcargo, c.slot / max(1, cfg.buffer_k), float(c.rot in (0, 1)),
                       np.clip(cxs, -3, 3), np.clip(cys, -3, 3), fa, ra, float(pk.cand_oz[i]),
                       float(abs(c.y - pk.dy0) < 1e-3 or abs(c.y + c.w - pk.dy1) < 1e-3))
            cmask[i] = True

        cxn, cyn, _ = st.cog()
        if has_ax and st.total_weight > 0:
            fa, ra = st.axle_loads()
            fa, ra = fa / v.front_axle_limit_kg, ra / v.rear_axle_limit_kg
        else:
            fa = ra = 0.0
        zplaced = 0.0
        if zone is not None and n:
            sel = st.stop[:n] == zone.stop_index
            bb = st.box[:n][sel]
            zplaced = float((bb[:, 3] * bb[:, 4] * bb[:, 5]).sum()) / zcargo
        glob = np.array([zx0 / L, zx1 / L, pk.zone_i / max(1, len(pk.zones)), len(pk.zones) / 4.0,
                         st.total_volume / self.cargo, zplaced, ((cxn - L / 2) / L / ratio) if n else 0.0,
                         ((cyn - W / 2) / W / ratio) if n else 0.0, fa, ra,
                         (len(pk.queue) + len(pk.buffer)) / max(1, len(self.problem.packages)),
                         st.total_weight / v.max_payload_kg, st.total_volume / v.volume, float(has_ax)], np.float32)
        obs = {"placed": placed, "placed_mask": pmask, "cand": cand, "cand_mask": cmask, "glob": glob}
        if cfg.obs_v2:
            self._observe_v2(obs)
        return obs

    # ---------------------------------------------------------------- CP2: đầu vào v2
    def _observe_v2(self, obs: Dict[str, np.ndarray]) -> None:
        from .problem import UPRIGHT
        cfg, pk, st = self.cfg, self.packer, self.packer.state
        prob = self.problem
        v = prob.vehicle
        L, W, H = v.inner_l, v.inner_w, v.inner_h
        heff = min(H, v.door_h)
        dy0, dy1 = pk.dy0, pk.dy1
        zone = pk.zone
        zx0, zx1 = (zone.x0, zone.x1) if zone else (0.0, L)
        n = st.n
        B = st.box[:n]
        # --- thể tích còn trống trong vùng / cả thùng
        if zone is not None and n:
            inz = st.stop[:n] == zone.stop_index
            zplaced = float((B[inz, 3] * B[inz, 4] * B[inz, 5]).sum())
        else:
            zplaced = 0.0
        zfree = max(1.0, (zx1 - zx0) * (dy1 - dy0) * heff - zplaced)
        tfree = max(1.0, L * (dy1 - dy0) * heff - st.total_volume)
        # --- hàng còn lại: vùng hiện tại (hàng chờ + phần sau) rồi các vùng sau
        cur = [i for i in list(pk.buffer) + list(pk.queue)]
        later = []
        for z in pk.zones[pk.zone_i + 1:]:
            idx = [i for i, p in enumerate(prob.packages) if p.stop_index == z.stop_index]
            idx.sort(key=lambda i: prob.packages[i].volume, reverse=True)
            later += idx
        pkgs = prob.packages
        cur_vol = sum(pkgs[i].volume for i in cur)
        rem_vol = cur_vol + sum(pkgs[i].volume for i in later)
        rem_w = sum(pkgs[i].weight for i in cur + later)
        nc = max(1, len(cur))
        g2 = np.array([
            min(3.0, cur_vol / zfree), min(3.0, rem_vol / tfree),
            min(3.0, rem_w / max(1.0, v.max_payload_kg - st.total_weight)), len(cur) / 50.0,
            sum(pkgs[i].fragile for i in cur) / nc, sum(pkgs[i].stack_limit <= 0 for i in cur) / nc,
            sum(set(pkgs[i].rotations()) <= set(UPRIGHT) for i in cur) / nc,
            max(0, len(pk.zones) - pk.zone_i - 1) / 4.0], np.float32)
        obs["glob"] = np.concatenate([obs["glob"], g2]).astype(np.float32)
        # --- token hàng còn lại (sau hàng chờ — hàng chờ đã có trong ứng viên)
        rem = np.zeros((cfg.n_rem, FR), np.float32)
        rmask = np.zeros(cfg.n_rem, bool)
        seq = [(i, 1.0) for i in pk.queue] + [(i, 0.0) for i in later]
        zc = max(1e-9, self.zone_cargo.get(zone.stop_index, 1.0) if zone else 1.0)
        for k, (i, is_cur) in enumerate(seq[: cfg.n_rem]):
            p = pkgs[i]
            a, b, c = sorted((p.l, p.w, p.h), reverse=True)
            rem[k] = (a / L, b / W, c / H, min(3.0, p.volume / zc), p.weight / self.wscale, float(p.fragile),
                      float(p.stack_limit > 0), float(set(p.rotations()) <= set(UPRIGHT)), float(not p.rotation_allowed),
                      is_cur)
            rmask[k] = True
        obs["rem"], obs["rem_mask"] = rem, rmask
        # --- bản đồ độ cao (đỉnh cao nhất trên mỗi ô sàn trong khung cửa)
        gx = (np.arange(cfg.hmap_x) + 0.5) / cfg.hmap_x * L
        gy = dy0 + (np.arange(cfg.hmap_y) + 0.5) / cfg.hmap_y * (dy1 - dy0)
        if n:
            inx = (B[:, 0, None] <= gx[None]) & (gx[None] < B[:, 0, None] + B[:, 3, None])      # n × X
            iny = (B[:, 1, None] <= gy[None]) & (gy[None] < B[:, 1, None] + B[:, 4, None])      # n × Y
            top = (B[:, 2] + B[:, 5])[:, None, None] * (inx[:, :, None] & iny[:, None, :])
            hm = top.max(0) / H
        else:
            hm = np.zeros((cfg.hmap_x, cfg.hmap_y))
        obs["hmap"] = hm.reshape(-1).astype(np.float32)
        # --- cột thêm cho ứng viên: độ khít 3 mặt (sâu / trái / phải) + thể tích so với chỗ trống còn lại của vùng
        C = obs["cand"].shape[0]
        extra = np.zeros((C, FC2), np.float32)
        cands = pk.candidates[: C]
        if cands:
            cx = np.array([[c.x, c.y, c.z, c.l, c.w, c.h] for c in cands])
            e = 1e-3
            back = (cx[:, 0] <= e).astype(float)
            left = (cx[:, 1] <= dy0 + e).astype(float)
            right = (cx[:, 1] + cx[:, 4] >= dy1 - e).astype(float)
            if n:
                def ov(a0, a1, b0, b1):
                    return np.clip(np.minimum(a1, b1) - np.maximum(a0, b0), 0, None)
                oy = ov(cx[:, None, 1], cx[:, None, 1] + cx[:, None, 4], B[None, :, 1], B[None, :, 1] + B[None, :, 4])
                ozz = ov(cx[:, None, 2], cx[:, None, 2] + cx[:, None, 5], B[None, :, 2], B[None, :, 2] + B[None, :, 5])
                oxx = ov(cx[:, None, 0], cx[:, None, 0] + cx[:, None, 3], B[None, :, 0], B[None, :, 0] + B[None, :, 3])
                tb = np.abs(B[None, :, 0] + B[None, :, 3] - cx[:, None, 0]) <= e
                tl = np.abs(B[None, :, 1] + B[None, :, 4] - cx[:, None, 1]) <= e
                tr = np.abs(B[None, :, 1] - (cx[:, None, 1] + cx[:, None, 4])) <= e
                back = np.maximum(back, ((oy * ozz) * tb).sum(1) / (cx[:, 4] * cx[:, 5]))
                left = np.maximum(left, ((oxx * ozz) * tl).sum(1) / (cx[:, 3] * cx[:, 5]))
                right = np.maximum(right, ((oxx * ozz) * tr).sum(1) / (cx[:, 3] * cx[:, 5]))
            extra[: len(cands)] = np.stack([np.clip(back, 0, 1), np.clip(left, 0, 1), np.clip(right, 0, 1),
                                            np.clip(cx[:, 3] * cx[:, 4] * cx[:, 5] / zfree, 0, 3)], 1)
        obs["cand"] = np.concatenate([obs["cand"], extra], 1).astype(np.float32)
