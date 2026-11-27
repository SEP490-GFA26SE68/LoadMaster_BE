"""
SA tối đa J trên bộ dựng lmai cho gói PRO / ULTIMATE (M10 — KE-HOACH-TOI-UU; spec R2).

Cờ OPTIMIZATION_LMAI_SA_ENABLED. Bật (và bước EP đã dựng bằng bộ dựng lmai của M9) thì thay GA + SA cũ — giải mã bằng
bộ dựng EP của service, vài giây một lần, đo M7: PRO không hơn BASIC — bằng SA của LoadMaster_AI (`app/ml/lmai/sa.py`):

  - lời giải = bộ trọng số chấm chỗ đặt kiểu Heuristic-4 (+ cột thưởng J tức thời theo hồ sơ) + nhiễu;
    giải mã = một lượt xếp lmai (cùng bộ dựng với M9);
  - cờ PLAN_SCORE_J bật: một chuỗi SA cho mỗi hồ sơ A / B / C (phương án A / B / C của job chọn theo đúng hồ sơ đó),
    xuất phát từ các cách Heuristic-4 và EP-DBLF M9 đã dựng (+ PRESET_J); tắt: một chuỗi theo cách chấm cũ của lmai
    (hàng lên xe);
  - lời giải có cả cấu hình bộ dựng (số kiện chờ, số điểm mỗi hướng): PRESET_DBLF (1 kiện, 21 điểm) cho ra đúng cách
    EP-DBLF của M9 trên bộ đo, nên SA tìm được quanh EP-DBLF — bài EP-DBLF hơn mọi Heuristic-4 thì SA vẫn vượt được;
  - phương án tốt nhất của mỗi chuỗi (khi hơn điểm xuất phát) được kiểm bằng luật của service
    (`evaluate_fixed_layout`, như M9); qua kiểm thì vào danh sách cách xếp với mã SA.

Ngân sách: min(LMAI_SA_BUDGET_SEC, thời gian còn lại tới hạn − phần giữ cho bước kiểm), chia đều cho các chuỗi.
GA không chạy khi cờ bật (D2, 26/11/2026: GA không cải thiện EP; code GA giữ cho thí nghiệm R9).
"""
from __future__ import annotations

import logging
import time

from app.config.settings import settings
from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.service.optimize import plan_score

logger = logging.getLogger(__name__)

MIN_CHAIN_SEC = 0.5        # chuỗi ngắn hơn thì không chạy
CHECK_MARGIN = 1.5         # phần giữ cho mỗi lần kiểm = CHECK_MARGIN × lần kiểm lâu nhất của M9


def _lmai_profile(code):
    """Hồ sơ của backend (app/config/plan_profiles.json) dưới dạng PlanProfile của lmai — cùng trọng số với cách chọn A/B/C."""
    from app.ml.lmai.profiles import PlanProfile as LmaiProfile

    p = plan_score.profiles()[code]
    return LmaiProfile(p.code, p.name, p.w_vol, p.w_n, tuple(p.weights), p.assumed)


def run_lmai_sa(problem, client, built, deadline: float):
    """`built`: LmaiBuild của M9 (lm_problem, lm_results, check_sec). Trả MetaResult (layouts mã SA, tried, evaluations)."""
    from app.service.optimize.lmai_builder import _to_placements, signature
    from app.service.optimize.metaheuristic import MetaResult

    out = MetaResult()
    if built is None or built.lm_problem is None:
        return out
    from app.ml.lmai.sa import PRESET_DBLF, PRESET_J, Sol, anneal, env_config
    from app.ml.lmai.teacher import PRESETS

    starts = [(Sol.of(PRESETS[int(name[-1])]), res) for name, res in built.lm_results.items() if name.startswith("H4-")]
    if not starts:   # bài lớn: chưa kịp dựng cách Heuristic-4 nào thì cũng không đủ giờ cho SA
        logger.info("lmai SA skipped: no Heuristic-4 start")
        return out
    # EP-DBLF đã dựng: dùng lại phương án, lời giải PRESET_DBLF (cùng cách xếp) để SA đi quanh nó; PRESET_J dựng trong SA
    dblf = built.lm_results.get("EP-DBLF")
    if dblf is not None:
        starts.append((Sol.of(PRESET_DBLF, buf=1, rot=21), dblf))
    starts.append((Sol.of(PRESET_J), None))
    codes = list(plan_score.CANDIDATE_PROFILES) if plan_score.enabled() else [None]
    reserve = len(codes) * CHECK_MARGIN * max(built.check_sec, 0.5)
    budget = min(float(settings.LMAI_SA_BUDGET_SEC), deadline - time.monotonic() - reserve)
    per = budget / len(codes)
    if per < MIN_CHAIN_SEC:
        logger.info("lmai SA skipped: budget %.1fs", budget)
        return out
    env_cfg = env_config()
    found = []
    evaluations = 0
    t_start = time.monotonic()
    for i, code in enumerate(codes):
        profile = _lmai_profile(code) if code else None
        chain_end = min(time.monotonic() + per, deadline - reserve)
        best, _, st = anneal(built.lm_problem, profile, chain_end, starts=starts, seed=i + 1, env_cfg=env_cfg)
        evaluations += st.evaluations
        logger.info("lmai SA[%s]: %d lần giải mã, %d lần hơn, khoá %.2f → %.2f, %.1fs",
                    code or "-", st.evaluations, st.improved, st.start_key, st.best_key, st.seconds)
        if best is not None and best.verdict.valid and best.placements and st.best_key > st.start_key + 1e-9:
            found.append((code, best, st.seconds))
    out.evaluations[PlanAlgorithm.SA.value] = evaluations
    if evaluations:
        out.tried.append(PlanAlgorithm.SA.value)
    seen = set(built.signatures)
    for code, res, sec in found:
        sig = signature(res)
        if sig in seen:
            continue
        seen.add(sig)
        if time.monotonic() >= deadline:
            logger.warning("lmai SA[%s]: hết giờ trước khi kiểm", code)
            break
        layout, reason = client.evaluate_fixed_layout(
            problem, _to_placements(res), PlanAlgorithm.SA.value, started_at=time.time() - sec, deadline=deadline,
        )
        if layout is None:
            logger.warning("lmai SA[%s]: bỏ cách xếp vì luật của service từ chối (%s)", code, reason)
            continue
        out.layouts.append(layout)
    logger.info("lmai SA: %d cách xếp mới qua kiểm, %d lần giải mã, %.1fs", len(out.layouts), evaluations,
                time.monotonic() - t_start)
    return out
