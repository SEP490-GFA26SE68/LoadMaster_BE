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
# Lumo-R3 (đầu vào v3, HUONG-DAN-TRAIN-LUMO-R3 mục 2.5): +4 cột ứng viên (biên áp lực, biên tải sàn, must_load, priority
# cao), +8 cột toàn cục (trọng số hồ sơ). Cột v1 giữ nguyên vị trí → mô hình v1 chạy được trên quan sát v3.
FC3, FG3 = 4, 8
# CP3b (obs_dj, sau T5 27/11/2026): +2 cột ứng viên — thưởng tức thời của nước đi theo hồ sơ của lượt (dj) và phần phạt của
# nó (pen), đơn vị "một kiện" (× số kiện của bài), kẹp ±5. T5: R3 không phân biệt hồ sơ vì hồ sơ chỉ vào nút toàn cục;
# hai cột này đổi theo hồ sơ ngay trên từng ứng viên.
FC4, DJ_CLIP = 2, 5.0


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
    # --- Lumo-R3 (HUONG-DAN-TRAIN-LUMO-R3 2.5–2.7)
    obs_v3: bool = False      # đầu vào v3: hồ sơ + biên vật lý (không dùng cùng obs_v2)
    reward_j: bool = False    # thưởng = J_train / 100 theo hồ sơ của lượt (thay thưởng thể tích / hàng + out_zone_penalty)
    reward_w_n: float = 3.0   # w_n của J_train (chốt 26/11/2026); sản phẩm vẫn w_n của file hồ sơ + so số kiện trước
    profile: str = "default"  # hồ sơ cố định khi không lấy mẫu (kiểm định, chấm)
    profile_sample: bool = False   # train: mỗi lượt lấy mẫu một hồ sơ (profiles.sample_profile)
    p_preset: float = 0.4
    log_sigma: float = 0.5
    p_zero: float = 0.2
    # Thưởng J dày (CP3b, chủ dự án duyệt 27/11/2026): mỗi bước cộng thêm Φ(s') − Φ(s), Φ = −Σ w_k·U_k (k = S2…S6, U_k
    # = phần đóng góp của các kiện đã xếp, xem _JPotential) (potential-based shaping, Ng và cộng sự 1999: chính sách tối
    # ưu không đổi). Bước cuối vẫn bù để Σr = J_train / 100. Tắt: phạt S2–S6 chỉ đến ở bước cuối (như T4).
    reward_dense: bool = False
    # CP3b: thêm cột dj / pen cho ứng viên (cần obs_v3). Mô hình đọc cột dj thêm một đường tắt vào điểm chọn nước
    # (PCTPolicy(dj=True)).
    obs_dj: bool = False


def env_from_cfg(cfg: dict) -> EnvConfig:
    """EnvConfig từ một file config train: khối `env` + khối `profiles` (Lumo-R3, HUONG-DAN-TRAIN-LUMO-R3 2.6–2.7).
    Có `profiles` → mặc định bật thưởng J_train và lấy mẫu hồ sơ mỗi lượt; trọng số 4 hồ sơ mẫu luôn đọc từ
    lmai/plan_profiles.json, config chỉ đặt reward_w_n và vùng lấy mẫu."""
    d = dict(cfg.get("env") or {})
    pr = cfg.get("profiles")
    if pr:
        d.setdefault("reward_j", True)
        d.setdefault("profile_sample", True)
        for k in ("reward_w_n", "p_preset", "log_sigma", "p_zero", "reward_dense"):
            if k in pr:
                d[k] = pr[k]
    return EnvConfig(**d)


def obs_spec(cfg: EnvConfig) -> Dict[str, Tuple[int, ...]]:
    if cfg.obs_dj and not cfg.obs_v3:
        raise ValueError("obs_dj cần obs_v3")
    if cfg.obs_v3:
        if cfg.obs_v2:
            raise ValueError("obs_v3 không dùng cùng obs_v2 (CP2 không qua cửa)")
        fc = FC + FC3 + (FC4 if cfg.obs_dj else 0)
        return {"placed": (cfg.max_placed, FP), "placed_mask": (cfg.max_placed,), "cand": (cfg.max_candidates, fc),
                "cand_mask": (cfg.max_candidates,), "glob": (FG + FG3,)}
    if not cfg.obs_v2:
        return {"placed": (cfg.max_placed, FP), "placed_mask": (cfg.max_placed,), "cand": (cfg.max_candidates, FC),
                "cand_mask": (cfg.max_candidates,), "glob": (FG,)}
    return {"placed": (cfg.max_placed, FP), "placed_mask": (cfg.max_placed,), "cand": (cfg.max_candidates, FC + FC2),
            "cand_mask": (cfg.max_candidates,), "glob": (FG + FG2,), "rem": (cfg.n_rem, FR), "rem_mask": (cfg.n_rem,),
            "hmap": (cfg.hmap_x * cfg.hmap_y,)}


class PackingEnv:
    def __init__(self, cfg: EnvConfig = EnvConfig(), problem_fn: Optional[Callable[[], Problem]] = None):
        if cfg.obs_dj and not cfg.obs_v3:
            raise ValueError("obs_dj cần obs_v3 (cột dj / pen tính theo hồ sơ của lượt)")
        self.cfg = cfg
        self.problem_fn = problem_fn
        self.pack_cfg = PackConfig(buffer_k=cfg.buffer_k, per_rot=cfg.per_rot, max_candidates=cfg.max_candidates)
        self.packer: Optional[Packer] = None
        self.problem: Optional[Problem] = None
        self.t0 = 0.0

    # ---------------------------------------------------------------- vòng đời
    def reset(self, problem: Optional[Problem] = None, profile=None):
        """profile: mã hồ sơ hoặc PlanProfile (w_n của sản phẩm); None → lấy mẫu (profile_sample) hoặc cfg.profile."""
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
        self._set_profile(profile)
        need = (self.cfg.reward_j and self.cfg.reward_dense) or self.cfg.obs_dj
        self._pot = _JPotential(self.problem) if need else None
        self._phi = self._pot.phi(self.profile_train) if self._pot is not None else 0.0   # Φ(xe rỗng): S3 của xe không
        return self.observe()

    def _set_profile(self, profile) -> None:
        from . import profiles as PF
        cfg = self.cfg
        if not (cfg.reward_j or cfg.obs_v3) and profile is None:
            self.profile = self.profile_train = None
            return
        if isinstance(profile, str):
            profile = PF.get(profile)
        if profile is None:
            if cfg.profile_sample:
                rng = np.random.default_rng([int(self.problem.seed or 0), 7])
                profile = PF.sample_profile(rng, cfg.p_preset, cfg.log_sigma, cfg.p_zero)
            else:   # suy luận: hồ sơ của job (plan_score.set_profile — lookahead, AISolver, backend), không có thì cfg.profile
                from .plan_score import active_profile
                profile = active_profile() or PF.get(cfg.profile)
        self.profile = profile                                  # hồ sơ sản phẩm (w_n của file) — thầy / chọn phương án
        self.profile_train = PF.with_w_n(profile, cfg.reward_w_n)   # thưởng + quan sát
        self._v3_cache = None
        v = self.problem.vehicle
        self.v_cont = max(1e-9, float(v.inner_l * v.inner_w * v.inner_h))
        self.n_total = max(1, len(self.problem.packages))

    @property
    def done(self) -> bool:
        return self.packer.done or not self.packer.candidates

    def step(self, action: int):
        c, oz = self.packer.apply(int(action))
        if self.cfg.reward_j:   # mục 2.6: Σr của lượt hợp lệ = J_train / 100
            pt = self.profile_train
            r = (pt.w_vol * c.l * c.w * c.h / self.v_cont + pt.w_n / self.n_total
                 - (pt.weights[0] / self.n_total if oz else 0.0))
            if self._pot is not None:
                phi = self._pot.add(c, self.problem.packages[c.pkg_idx]).phi(pt)
                if self.cfg.reward_dense:
                    r += phi - self._phi
                self._phi = phi
        else:
            r = c.l * c.w * c.h / self.cargo - (self.cfg.out_zone_penalty if oz else 0.0)
        info: Dict = {}
        if self.done:
            res = self.result("AI")
            info["result"] = res
            if self.cfg.reward_j and res.verdict.valid:
                # bước cuối: trừ S2–S6 (tính trên phương án cuối) + phần lệch của S1 (nếu có) → tổng đúng J_train / 100
                from .plan_score import score
                j = score(self.problem, res.placements, self.profile_train).j if res.placements else 0.0
                info["j_train"] = j
                r += j / 100.0 - (self.ret + r)
            if not res.verdict.valid:
                ver = res.verdict
                v = self.problem.vehicle
                if self.problem.physics_v2:   # H11 chỉ trọng tâm ngang theo r_y
                    ry = max(1e-6, v.cog_limit_y)
                    excess = max(0.0, ver.cog_y - ry) / ry
                else:
                    excess = max(0.0, ver.cog_x - v.max_cog_offset_ratio) / v.max_cog_offset_ratio + \
                        max(0.0, ver.cog_y - v.max_cog_offset_ratio) / v.max_cog_offset_ratio
                if not ver.axle_ok:
                    excess += 0.5
                if not ver.unload_ok:
                    excess += 1.0
                if ver.must_load_missing:   # H13
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
            if self.problem.physics_v2:
                chain, cap = self._load_columns_v2(st)
                chain, cap = chain[sel], cap[sel]
            else:
                lim = st.limit[sel]
                cap = np.where(lim > 0, np.clip((lim - st.received[sel]) / np.maximum(lim, 1e-6), 0, 1), 0)
                chain = st.chain_fragile[sel].astype(float)
            cur = zone.stop_index if zone else -1
            placed[: len(sel)] = np.stack([
                b[sel, 0] / L, b[sel, 1] / W, b[sel, 2] / H, b[sel, 3] / L, b[sel, 4] / W, b[sel, 5] / H,
                st.weight[sel] / self.wscale, chain, cap, (st.stop[sel] == cur).astype(float),
            ], axis=1)
            pmask[: len(sel)] = True

        cand = np.zeros((cfg.max_candidates, FC), np.float32)
        cmask = np.zeros(cfg.max_candidates, bool)
        has_ax = v.has_axles
        v2 = self.problem.physics_v2
        zcargo = self.zone_cargo.get(zone.stop_index, 1.0) if zone else 1.0
        for i, c in enumerate(pk.candidates[: cfg.max_candidates]):
            pkg = self.problem.packages[c.pkg_idx]
            m = pkg.weight
            if v2:   # cột 11 / 12 đổi nghĩa (HUONG-DAN-TRAIN-LUMO-R3 mục 2.5): σ = 0; σ > 0
                sig = pkg.sigma
                f11, f12 = float(sig == 0.0), float(sig is not None and sig > 0)
            else:
                f11, f12 = float(pkg.fragile), float(pkg.stack_limit > 0)
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
                       (c.z + c.h) / H, c.support_ratio, m / self.wscale, f11, f12,
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
        if cfg.obs_v3:
            self._observe_v3(obs)
        if cfg.obs_dj:
            self._observe_dj(obs)
        return obs

    # ---------------------------------------------------------------- CP3b: thưởng tức thời của từng ứng viên
    def _observe_dj(self, obs: Dict[str, np.ndarray]) -> None:
        """2 cột: dj = n·(thưởng J dày của nước đi − w_n / n) và pen = phần phạt của nó (−w₁·ngoài vùng + n·ΔΦ), n = số
        kiện của bài. Đúng bằng thưởng `step` trả khi reward_dense (trừ phần w_n như nhau ở mọi ứng viên) — test_r3."""
        pk, prob, pt = self.packer, self.problem, self.profile_train
        C = obs["cand"].shape[0]
        extra = np.zeros((C, FC4), np.float32)
        cands = pk.candidates[:C]
        if cands:
            n = float(self.n_total)
            dphi = self._pot.cand_deltas(cands, prob.packages, pt.weights)
            vol = np.array([c.l * c.w * c.h for c in cands], float)
            oz = np.array(pk.cand_oz[: len(cands)], float)
            pen = -pt.weights[0] * oz + n * dphi
            dj = pt.w_vol * vol / self.v_cont * n + pen
            extra[: len(cands)] = np.stack([np.clip(dj, -DJ_CLIP, DJ_CLIP), np.clip(pen, -DJ_CLIP, DJ_CLIP)], 1)
        obs["cand"] = np.concatenate([obs["cand"], extra], 1).astype(np.float32)

    # ---------------------------------------------------------------- Lumo-R3: đầu vào v3
    def _observe_v3(self, obs: Dict[str, np.ndarray]) -> None:
        from . import profiles as PF
        pk, st, prob = self.packer, self.packer.state, self.problem
        if getattr(self, "_v3_cache", None) is None:   # hồ sơ cố định trong một lượt: tính một lần (_set_profile xoá)
            self._v3_cache = (np.asarray(PF.glob_features(self.profile_train), np.float32),
                              int(PF.config().get("high_priority_min", 2)))
        gfeat, hp = self._v3_cache
        obs["glob"] = np.concatenate([obs["glob"], gfeat])
        C = obs["cand"].shape[0]
        extra = np.zeros((C, FC3), np.float32)
        fq = st.floor_q if (prob.physics_v2 and not st.skip_h10) else None
        for i, c in enumerate(pk.candidates[:C]):
            pkg = prob.packages[c.pkg_idx]
            press, floor = 1.0, 1.0
            if c.z <= 1e-6:
                if fq:
                    floor = 1.0 - (pkg.weight / (c.l * c.w / 1e4)) / fq
            elif prob.physics_v2 and c.supporters:
                direct = dict(c.supporters)
                for j, d in st._stack_deltas(c.supporters, pkg.weight).items():
                    recv = st.received[j] + d
                    sig = st.sigma[j]
                    if not st.skip_h7:
                        area = (st.contact_top[j] + direct.get(j, 0.0)) / 1e4
                        m = 1.0 - (recv / area) / sig if (sig > 0 and area > 0) else 0.0
                        press = min(press, m)
                    if fq and st.box[j, 2] <= 1e-6:
                        floor = min(floor, 1.0 - ((st.weight[j] + recv) / (st.box[j, 3] * st.box[j, 4] / 1e4)) / fq)
            extra[i] = (min(max(press, 0.0), 1.0), min(max(floor, 0.0), 1.0), float(bool(pkg.must_load)),
                        float(pkg.priority is not None and int(pkg.priority) >= hp))
        obs["cand"] = np.concatenate([obs["cand"], extra], 1).astype(np.float32)

    @staticmethod
    def _load_columns_v2(st) -> Tuple[np.ndarray, np.ndarray]:
        """Cột 7 / 8 của kiện đã xếp dưới luật mới (mục 2.5), giữ cùng hướng nghĩa với cột cũ:
        - cột 7 "không nhận thêm tải" (cũ `chain_fragile`): σ = 0 / trống, hoặc đã kịch p = σ, hoặc một kiện bên dưới
          (trực tiếp / gián tiếp) đã như vậy;
        - cột 8 "phần tải còn lại" (cũ (K − tải) / K): 1 − p / σ, 0 khi σ = 0 / trống."""
        n = st.n
        sig = st.sigma[:n]
        p = np.array([st.pressure(i) for i in range(n)])
        ok = ~np.isnan(sig) & (sig > 0)
        cap = np.zeros(n)
        cap[ok] = np.clip(1.0 - p[ok] / sig[ok], 0.0, 1.0)
        full = ~ok | (cap <= 1e-9)
        chain = full.copy()
        for i in range(n):   # kiện đỡ luôn được xếp trước kiện nó đỡ
            if not chain[i] and any(chain[j] for j, _ in st.supporters[i]):
                chain[i] = True
        return chain.astype(float), cap

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


def _touch_matrix(A: np.ndarray, B: np.ndarray, gap: float, eps: float) -> np.ndarray:
    """a × b: hộp A[i] chạm hộp B[j] (cách ≤ gap trên một trục, chồng lấn > eps trên hai trục còn lại) — cùng điều kiện
    `plan_score._clusters` của backend."""
    a0, al = A[:, None, :3], A[:, None, 3:]
    b0, bl = B[None, :, :3], B[None, :, 3:]
    dist = np.maximum(b0 - (a0 + al), a0 - (b0 + bl))
    near = (dist <= gap + eps) & (dist >= -eps)
    pos = (np.minimum(a0 + al, b0 + bl) - np.maximum(a0, b0)) > eps
    return ((near[..., 0] & pos[..., 1] & pos[..., 2]) | (near[..., 1] & pos[..., 2] & pos[..., 0])
            | (near[..., 2] & pos[..., 0] & pos[..., 1]))


class _JPotential:
    """Thế năng Φ = −Σ w_k·U_k (k = S2…S6) cho thưởng J dày (`reward_dense`) và cột dj / pen (`obs_dj`), cập nhật tăng
    dần. S1 tính từng bước qua `out_zone`; S7 / S8 không tính.

    U_k là phần **đóng góp** của các kiện đã xếp vào V_k của phương án cuối, chia theo cả bài:
      U2 = |Σ m·(y_c − W/2)| / (M·r_y·W),  U4 = Σ m·z_c / (M·H),  U6 = Σ d / N_hp
    (M = tổng khối lượng hàng của bài, N_hp = số kiện ưu tiên cao của bài); U3, U5 = V3, V5 của phương án dở. Xếp hết
    mọi kiện thì U_k = V_k (`plan_score.components`). Bản 27/11 dùng V_k của phương án dở: V2, V4, V6 chia cho phần đã
    xếp nên vài kiện đầu làm V nhảy mạnh (kiện đầu sát vách → V2 = 1), tức phạt cách xếp sát vách vốn là cách tốt."""

    KEYS = ("S2", "S3", "S4", "S5", "S6")

    def __init__(self, problem: Problem):
        from . import profiles as PF
        from .plan_score import EPS
        cfg = PF.config()
        v = problem.vehicle
        self.L, self.W, self.H = float(v.inner_l), float(v.inner_w), float(v.inner_h)
        self.r_y = float(v.cog_limit_y)
        self.axle = None
        if v.has_axles and v.rear_axle_x > v.front_axle_x and v.front_axle_limit_kg > 0 and v.rear_axle_limit_kg > 0:
            self.axle = (float(v.front_axle_x), float(v.rear_axle_x), float(v.empty_front_axle_load_kg),
                         float(v.empty_rear_axle_load_kg), float(v.front_axle_limit_kg), float(v.rear_axle_limit_kg))
        self.n_total = max(len(problem.packages), 1)
        self.gap, self.eps = float(cfg.get("adjacent_gap_cm", 1.0)), EPS
        self.hp = int(cfg.get("high_priority_min", 2))
        self.m_ref = max(sum(float(p.weight) for p in problem.packages), 1e-9)
        self.n_hp = sum(1 for p in problem.packages if self._hp(p))
        self.mass = self.mx = self.mdy = self.mz = 0.0
        self.d_sum = 0.0
        self.garr: Dict[str, np.ndarray] = {}   # nhóm → hộp đã xếp (k × 6)
        self.gidx: Dict[str, list] = {}         # nhóm → chỉ số trong parent
        self.parent: list = []
        self.clusters = 0
        self.values = self._scalar(self._values(self.mass, self.mx, self.mdy, self.mz, self.d_sum, 0, 0))

    @staticmethod
    def _key(pkg) -> str:
        return f"g:{pkg.packing_group}" if pkg.packing_group else f"s:{pkg.stop_index}"

    def _hp(self, pkg) -> bool:
        return pkg.priority is not None and int(pkg.priority) >= self.hp

    @staticmethod
    def _scalar(vals):
        return {k: float(u) for k, u in vals.items()}

    def _values(self, mass, mx, mdy, mz, d_sum, clusters, groups):
        """U_k đã kẹp [0, 1]; nhận số hoặc mảng numpy (cùng phép tính → cùng kết quả cho bước thật và ứng viên)."""
        u2 = np.abs(mdy) / (self.m_ref * self.r_y * self.W) if self.r_y > 0 else 0.0 * mdy
        u4 = mz / (self.m_ref * self.H)
        if self.axle is not None:
            fx, rx, ef, er, gf, gr = self.axle
            rear = (mx - fx * mass) / (rx - fx)
            u3 = np.abs((ef + (mass - rear)) / gf - (er + rear) / gr)
        else:
            u3 = 0.0 * mass
        u5 = np.maximum(0, clusters - groups) / self.n_total
        u6 = d_sum / self.n_hp if self.n_hp else 0.0 * d_sum
        return {k: np.clip(u, 0.0, 1.0) for k, u in zip(self.KEYS, (u2, u3, u4, u5, u6))}

    def _find(self, i: int) -> int:
        par = self.parent
        while par[i] != i:
            par[i] = par[par[i]]
            i = par[i]
        return i

    def add(self, c, pkg) -> "_JPotential":
        x, y, z, l, w, h = float(c.x), float(c.y), float(c.z), float(c.l), float(c.w), float(c.h)
        m = float(pkg.weight)
        self.mass += m
        self.mx += m * (x + l / 2.0)
        self.mdy += m * ((y + w / 2.0) - self.W / 2.0)
        self.mz += m * (z + h / 2.0)
        key = self._key(pkg)
        box = np.array([[x, y, z, l, w, h]])
        i = len(self.parent)
        self.parent.append(i)
        self.clusters += 1
        if key in self.garr:
            hit = _touch_matrix(box, self.garr[key], self.gap, self.eps)[0]
            for j in np.flatnonzero(hit):
                ri, rj = self._find(i), self._find(self.gidx[key][j])
                if ri != rj:
                    self.parent[ri] = rj
                    self.clusters -= 1
            self.garr[key] = np.vstack([self.garr[key], box])
            self.gidx[key].append(i)
        else:
            self.garr[key], self.gidx[key] = box, [i]
        if self._hp(pkg):
            self.d_sum += (self.L - (x + l)) / self.L
        self.values = self._scalar(self._values(self.mass, self.mx, self.mdy, self.mz, self.d_sum, self.clusters,
                                                len(self.garr)))
        return self

    @staticmethod
    def _phi_of(val, w):
        """w theo thứ tự profiles.COMPONENTS (S1 … S8)."""
        return -(w[1] * val["S2"] + w[2] * val["S3"] + w[3] * val["S4"] + w[4] * val["S5"] + w[5] * val["S6"])

    def phi(self, profile) -> float:
        return self._phi_of(self.values, profile.weights)

    def cand_deltas(self, cands, packages, weights) -> np.ndarray:
        """ΔΦ nếu đặt từng ứng viên (mảng theo thứ tự `cands`), cùng phép tính với `add`."""
        n = len(cands)
        X = np.array([[c.x, c.y, c.z, c.l, c.w, c.h] for c in cands], float)
        pk = [packages[c.pkg_idx] for c in cands]
        m = np.array([float(p.weight) for p in pk])
        hp = np.array([self._hp(p) for p in pk], bool)
        d_new = self.d_sum + np.where(hp, (self.L - (X[:, 0] + X[:, 3])) / self.L, 0.0)
        dcl = np.ones(n)            # thêm một cụm, rồi trừ số cụm khác nhau mà ứng viên chạm
        dgr = np.zeros(n)           # ứng viên mở nhóm mới
        by_key: Dict[str, list] = {}
        for k, p in enumerate(pk):
            by_key.setdefault(self._key(p), []).append(k)
        for key, idx in by_key.items():
            if key not in self.garr:
                dgr[idx] = 1.0
                continue
            hit = _touch_matrix(X[idx], self.garr[key], self.gap, self.eps)
            cnt = hit.sum(1)
            if cnt.any():
                roots = np.array([self._find(j) for j in self.gidx[key]])
                for r, k in enumerate(idx):
                    if cnt[r]:
                        dcl[k] = 1.0 - (1 if cnt[r] == 1 else len(set(roots[hit[r]].tolist())))
        new = self._values(self.mass + m, self.mx + m * (X[:, 0] + X[:, 3] / 2.0),
                           self.mdy + m * ((X[:, 1] + X[:, 4] / 2.0) - self.W / 2.0),
                           self.mz + m * (X[:, 2] + X[:, 5] / 2.0),
                           d_new, self.clusters + dcl, len(self.garr) + dgr)
        return self._phi_of(new, weights) - self._phi_of(self.values, weights)
