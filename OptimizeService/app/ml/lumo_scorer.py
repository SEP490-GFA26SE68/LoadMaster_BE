from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional, Sequence

from app.config.settings import settings
from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, VehicleData
from app.ml.lumo_features import candidate_features, global_features, placed_features
from app.ml.pct_policy import PCTPolicy, pct_policy_available

logger = logging.getLogger(__name__)

AI_ALGORITHMS = {"EP_DBLF_GA_AI", "AI", "ULTIMATE"}

_scorer: Optional["LumoScorer"] = None


def uses_lumo(algorithm_name: Optional[str]) -> bool:
    if not algorithm_name:
        return False
    return algorithm_name.strip().upper() in AI_ALGORITHMS


class LumoScorer:
    def __init__(self, model_path: str):
        if not pct_policy_available():
            raise RuntimeError("torch is not installed")
        import torch

        path = Path(model_path)
        if not path.is_absolute():
            path = Path(__file__).resolve().parents[2] / path
        if not path.is_file():
            raise FileNotFoundError(str(path))
        ckpt = torch.load(path, map_location="cpu", weights_only=False)
        cfg = ckpt.get("model_cfg") or {}
        self.env_cfg = ckpt.get("env_cfg") or {}
        self.max_placed = int(self.env_cfg.get("max_placed", 80))
        self.max_candidates = int(self.env_cfg.get("max_candidates", 128))
        self.policy = PCTPolicy(
            d=int(cfg.get("d", 128)),
            heads=int(cfg.get("heads", 4)),
            layers=int(cfg.get("layers", 2)),
            ff=int(cfg.get("ff", 256)),
        )
        self.policy.load_state_dict(ckpt["model"])
        self.policy.eval()
        self._torch = torch

    def pick(
        self,
        vehicle: VehicleData,
        packages_map: dict[str, PackageData],
        placed: Sequence[PlacementData],
        candidates: Sequence[PlacementData],
        remaining_count: int,
        total_count: int,
        zone_start: float,
        zone_end: float,
    ) -> Optional[int]:
        if not candidates:
            return None
        torch = self._torch
        take = candidates[: self.max_candidates]
        placed_rows = [placed_features(item, vehicle, packages_map.get(str(item.package_id))) for item in placed[: self.max_placed]]
        while len(placed_rows) < self.max_placed:
            placed_rows.append([0.0] * 10)
        cand_rows = [
            candidate_features(item, vehicle, packages_map[str(item.package_id)], zone_start, zone_end, True) for item in take
        ]
        n_cand = len(cand_rows)
        while len(cand_rows) < self.max_candidates:
            cand_rows.append([0.0] * 22)
        glob = global_features(
            vehicle,
            placed,
            packages_map,
            n_cand,
            remaining_count,
            total_count,
            self.max_placed,
            self.max_candidates,
        )
        placed_pad = [i >= min(len(placed), self.max_placed) for i in range(self.max_placed)]
        cand_pad = [i >= n_cand for i in range(self.max_candidates)]
        with torch.no_grad():
            scores = self.policy(
                torch.tensor([placed_rows], dtype=torch.float32),
                torch.tensor([cand_rows], dtype=torch.float32),
                torch.tensor([glob], dtype=torch.float32),
                torch.tensor([placed_pad], dtype=torch.bool),
                torch.tensor([cand_pad], dtype=torch.bool),
            )
            index = int(scores[0, :n_cand].argmax().item())
        return index


def get_lumo_scorer() -> Optional[LumoScorer]:
    global _scorer
    if _scorer is not None:
        return _scorer
    if not pct_policy_available():
        logger.warning(
            "Lumo-R1.1 không chấm chỗ đặt: chưa cài PyTorch, gói ULTIMATE dùng heuristic. "
            "Cài bằng: pip install -r requirements-ai.txt"
        )
        return None
    try:
        _scorer = LumoScorer(settings.LUMO_MODEL_PATH)
        return _scorer
    except Exception as exc:
        logger.warning("Lumo-R1.1 not loaded (%s); using heuristic scoring", exc)
        return None
