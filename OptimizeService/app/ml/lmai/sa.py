# Chép từ LoadMaster_AI/lmai/sa.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""SA tối đa J trên bộ dựng lmai (M10 — KE-HOACH-TOI-UU; spec R2: SA là bước cải thiện chính của gói PRO / ULTIMATE).

Một lời giải = bộ trọng số chấm chỗ đặt kiểu Heuristic-4 (`teacher.PRESETS`) + mức nhiễu + hạt giống nhiễu + cấu hình
bộ dựng (số kiện trong hàng chờ, số điểm đặt giữ lại mỗi hướng). Giải mã = một lượt xếp của `PackingEnv` (cùng tập nước
đi với Lumo), mỗi bước chọn ứng viên có điểm thấp nhất. Bộ đặc trưng có thêm hai cột `dj` / `pen` của quan sát CP3b
(`EnvConfig.obs_dj`): thưởng J tức thời theo hồ sơ → cách xếp nghe theo hồ sơ.

Vì sao có cấu hình bộ dựng: Heuristic-4 giữ 6 điểm thấp nhất mỗi hướng (5 kiện chờ) nên có bài EP-DBLF (1 kiện, mọi điểm)
xếp được nhiều kiện hơn mọi bộ Heuristic-4; SA chỉ đi trong vùng Heuristic-4 thì không vượt được (đo 27/11, rb_vua bài 0).
PRESET_DBLF (1 kiện chờ, 21 điểm mỗi hướng, sâu → thấp → trái) là điểm xuất phát gần EP-DBLF.

Láng giềng: nhân một trọng số với e^{N(0; 0,5)}, thêm / bỏ một đặc trưng (dấu theo hướng tự nhiên của đặc trưng), đổi
nhiễu, hoặc đổi cấu hình bộ dựng. Nhận theo Metropolis trên khoá sản phẩm (hợp lệ → số kiện → J, như `plan_score.better`); nhiệt độ giảm
theo thời gian đã dùng, T0 → T_END (đơn vị điểm J). Luôn trả phương án tốt nhất đã thấy, nên không bao giờ kém
phương án xuất phát tốt nhất.

Tất định khi cùng hạt giống và cùng số lần giải mã (số lần giải mã phụ thuộc thời gian máy).
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass, field, replace
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .env import FC, FC3, EnvConfig, PackingEnv
from .problem import Problem
from .result import PlanResult
from .teacher import F_CX, F_CY, F_OZ, F_SUP, F_TOP, F_VOL, F_WALL, F_XZ0, F_Z, PRESETS, _score

F_DJ, F_PEN = FC + FC3, FC + FC3 + 1      # cột obs_dj: thưởng J tức thời / phần phạt (càng cao càng tốt → trọng số âm)
F_Y = 4                                   # y / W (trái → phải)
BUFS = (1, 2, 3, 4, 5)                    # số kiện trong hàng chờ
ROTS = (6, 12, 21)                        # số điểm đặt giữ lại mỗi (kiện, hướng); 1 kiện × 6 hướng × 21 ≤ 128 ứng viên

# đặc trưng SA được thêm vào, kèm (dấu, độ lớn khi thêm): điểm càng thấp càng tốt
ADDABLE: Dict[int, Tuple[float, float]] = {
    F_XZ0: (1.0, 0.6), F_Z: (1.0, 0.6), F_TOP: (1.0, 0.5), F_VOL: (-1.0, 0.4), F_CX: (1.0, 0.4), F_CY: (1.0, 0.4),
    F_SUP: (-1.0, 0.4), F_WALL: (-1.0, 0.3), F_DJ: (-1.0, 0.5), F_PEN: (-1.0, 0.5), F_Y: (1.0, 0.01),
}
# điểm xuất phát thêm cho SA: sâu → thấp + thưởng J tức thời theo hồ sơ
PRESET_J: Dict[int, float] = {F_OZ: 10, F_XZ0: 0.6, F_Z: 0.6, F_DJ: -0.5}
# gần EP-DBLF: 1 kiện chờ (thể tích giảm dần), nhiều điểm mỗi hướng, sâu nhất → thấp nhất → trái nhất
PRESET_DBLF: Dict[int, float] = {F_OZ: 10, F_XZ0: 1.0, F_Z: 1e-3, F_Y: 1e-5}

T0, T_END = 1.0, 0.02
NO_KEY = -1e18


def env_config(profile_code: Optional[str] = None) -> EnvConfig:
    """Môi trường cho giải mã: quan sát v3 + dj (hồ sơ do `anneal` truyền vào từng lượt)."""
    return EnvConfig(obs_v3=True, reward_j=True, obs_dj=True, profile=profile_code or "default")


@dataclass(frozen=True)
class Sol:
    w: Tuple[Tuple[int, float], ...]     # (đặc trưng, trọng số), sắp theo đặc trưng
    noise: float = 0.0
    seed: int = 0
    buf: int = 5                         # PackConfig.buffer_k (Heuristic-4: 5)
    rot: int = 6                         # PackConfig.per_rot (Heuristic-4: 6)

    @staticmethod
    def of(w: Dict[int, float], noise: float = 0.0, seed: int = 0, buf: int = 5, rot: int = 6) -> "Sol":
        return Sol(tuple(sorted((int(k), float(v)) for k, v in w.items())), float(noise), int(seed), int(buf), int(rot))

    def weights(self) -> Dict[int, float]:
        return dict(self.w)


@dataclass
class SAStats:
    evaluations: int = 0
    accepted: int = 0
    improved: int = 0          # số lần tìm được phương án tốt hơn mọi phương án trước
    start_key: float = NO_KEY
    best_key: float = NO_KEY
    seconds: float = 0.0
    trace: List[Tuple[int, float]] = field(default_factory=list)   # (lần giải mã, khoá tốt nhất) khi khoá tăng


def default_starts() -> List[Tuple[Sol, None]]:
    """4 bộ Heuristic-4 + PRESET_J + PRESET_DBLF (chưa dựng)."""
    return [(Sol.of(w), None) for w in list(PRESETS) + [PRESET_J]] + [(Sol.of(PRESET_DBLF, buf=1, rot=21), None)]


def decode(problem: Problem, sol: Sol, profile, env_cfg: Optional[EnvConfig] = None) -> PlanResult:
    env_cfg = env_cfg or env_config()
    if (env_cfg.buffer_k, env_cfg.per_rot) != (sol.buf, sol.rot):
        env_cfg = replace(env_cfg, buffer_k=sol.buf, per_rot=sol.rot)
    env = PackingEnv(env_cfg)
    obs = env.reset(problem, profile=profile)
    w = sol.weights()
    rng = np.random.default_rng(sol.seed) if sol.noise > 0 else None
    while not env.done:
        n = int(obs["cand_mask"].sum())
        s = _score(obs["cand"][:n], w)
        if rng is not None:
            s = s + sol.noise * rng.standard_normal(n)
        obs, _, _, _ = env.step(int(np.argmin(s)))
    return env.result("SA")


def key_of(res: PlanResult, profile) -> float:
    """Khoá sản phẩm thành một số (lớn hơn = tốt hơn): hợp lệ → số kiện (pack_count_first) → J của hồ sơ.
    profile None: cách chấm cũ của lmai (hợp lệ → hàng lên xe)."""
    if not res.verdict.valid or not res.placements:
        return NO_KEY
    if profile is None:
        return 1000.0 + 100.0 * res.cargo_frac + 0.1 * res.packed_frac_raw
    from . import profiles as PF
    from .plan_score import score
    j = score(res.problem, res.placements, profile).j
    return j + (1000.0 * len(res.placements) if PF.pack_count_first() else 0.0)


def neighbour(sol: Sol, rng: np.random.Generator) -> Sol:
    w = sol.weights()
    noise, seed, buf, rot = sol.noise, sol.seed, sol.buf, sol.rot
    active = [k for k in w if k != F_OZ]
    u = rng.random()
    if u < 0.1:
        if rng.random() < 0.5:
            buf = int(BUFS[int(rng.integers(len(BUFS)))])
        else:
            rot = int(ROTS[int(rng.integers(len(ROTS)))])
    elif u < 0.55 and active:
        k = active[int(rng.integers(len(active)))]
        w[k] *= math.exp(0.5 * rng.standard_normal())
    elif u < 0.85:
        absent = [k for k in ADDABLE if k not in w]
        if absent and (rng.random() < 0.6 or len(active) <= 1):
            k = absent[int(rng.integers(len(absent)))]
            sign, mag = ADDABLE[k]
            w[k] = sign * mag * math.exp(0.5 * rng.standard_normal())
        elif len(active) > 1:
            del w[active[int(rng.integers(len(active)))]]
    else:
        noise = float(min(0.2, max(0.0, noise + 0.04 * rng.standard_normal())))
        seed = int(rng.integers(1 << 30))
    w.setdefault(F_OZ, 10.0)
    return Sol.of(w, noise, seed, buf, rot)


def anneal(problem: Problem, profile, deadline: float, starts: Sequence[Tuple[Sol, Optional[PlanResult]]] = (),
           seed: int = 0, env_cfg: Optional[EnvConfig] = None, max_evals: Optional[int] = None,
           t0: float = T0, t_end: float = T_END) -> Tuple[Optional[PlanResult], Optional[Sol], SAStats]:
    """SA tới `deadline` (time.monotonic()). `starts`: (lời giải, phương án đã dựng hoặc None) — mặc định
    `default_starts()` (4 bộ Heuristic-4 + PRESET_J + PRESET_DBLF). Trả (phương án tốt nhất, lời giải của nó,
    thống kê). `profile`: PlanProfile hoặc mã; None = cách chấm cũ (khi backend tắt J)."""
    from . import profiles as PF
    prof = PF.get(profile) if isinstance(profile, str) else profile
    env_cfg = env_cfg or env_config()
    rng = np.random.default_rng([int(problem.seed or 0), int(seed), 11])
    st = SAStats()
    t_start = time.monotonic()
    if not starts:
        starts = default_starts()

    def budget_left() -> bool:
        return time.monotonic() < deadline and (max_evals is None or st.evaluations < max_evals)

    best = cur = None
    best_key = cur_key = NO_KEY
    for sol, res in starts:
        if res is None:
            if st.evaluations and not budget_left():
                break
            res = decode(problem, sol, prof, env_cfg)
            st.evaluations += 1
        k = key_of(res, prof)
        if best is None or k > best_key:
            best, best_key, cur, cur_key = (sol, res), k, (sol, res), k
    st.start_key = best_key
    if best is None:
        return None, None, st
    st.trace.append((st.evaluations, best_key))
    span = max(1e-6, deadline - time.monotonic())
    t_first = time.monotonic()
    seen = {best[0]}
    stale = 0
    while budget_left() and stale < 200:
        frac = min(1.0, (time.monotonic() - t_first) / span)
        temp = t0 * (t_end / t0) ** frac
        cand = neighbour(cur[0], rng)
        if cand in seen:
            stale += 1
            continue
        stale = 0
        seen.add(cand)
        res = decode(problem, cand, prof, env_cfg)
        st.evaluations += 1
        k = key_of(res, prof)
        if k >= cur_key or rng.random() < math.exp((k - cur_key) / temp):
            cur, cur_key = (cand, res), k
            st.accepted += 1
        if k > best_key + 1e-9:
            best, best_key = (cand, res), k
            st.improved += 1
            st.trace.append((st.evaluations, best_key))
    st.best_key = best_key
    st.seconds = time.monotonic() - t_start
    best[1].algorithm = "SA"
    return best[1], best[0], st
