from __future__ import annotations

from typing import Any

try:
    import torch
    from torch import nn
except ImportError:  # pragma: no cover - môi trường không cài torch vẫn chạy heuristic
    torch = None  # type: ignore[assignment]
    nn = None  # type: ignore[assignment]


def pct_policy_available() -> bool:
    return torch is not None and nn is not None


if nn is not None:

    class PCTPolicy(nn.Module):
        """
        PCTPolicy khớp checkpoint Lumo-R1.1: embedding 3 loại token, Transformer 2 lớp,
        pointer chọn ứng viên, value (PPO) không dùng lúc xếp.
        """

        TYPE_PLACED = 0
        TYPE_CAND = 1
        TYPE_GLOB = 2

        def __init__(self, d: int = 128, heads: int = 4, layers: int = 2, ff: int = 256):
            super().__init__()
            self.d = d
            self.type_emb = nn.Parameter(torch.zeros(3, d))
            self.emb_placed = nn.Sequential(nn.Linear(10, d), nn.ReLU(), nn.Linear(d, d))
            self.emb_cand = nn.Sequential(nn.Linear(22, d), nn.ReLU(), nn.Linear(d, d))
            self.emb_glob = nn.Sequential(nn.Linear(14, d), nn.ReLU(), nn.Linear(d, d))
            encoder_layer = nn.TransformerEncoderLayer(
                d_model=d,
                nhead=heads,
                dim_feedforward=ff,
                batch_first=True,
                activation="relu",
            )
            self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=layers)
            self.norm = nn.LayerNorm(d)
            self.pointer = nn.Sequential(nn.Linear(2 * d, d), nn.ReLU(), nn.Linear(d, 1))
            self.value = nn.Sequential(nn.Linear(2 * d, d), nn.ReLU(), nn.Linear(d, 1))

        def forward(
            self,
            placed_x: Any,
            cand_x: Any,
            glob_x: Any,
            placed_pad: Any,
            cand_pad: Any,
        ) -> Any:
            batch = glob_x.size(0)
            glob_h = self.emb_glob(glob_x) + self.type_emb[self.TYPE_GLOB]
            placed_h = self.emb_placed(placed_x) + self.type_emb[self.TYPE_PLACED]
            cand_h = self.emb_cand(cand_x) + self.type_emb[self.TYPE_CAND]
            tokens = torch.cat([glob_h.unsqueeze(1), placed_h, cand_h], dim=1)
            pad = torch.cat(
                [
                    torch.zeros(batch, 1, dtype=torch.bool, device=tokens.device),
                    placed_pad,
                    cand_pad,
                ],
                dim=1,
            )
            encoded = self.norm(self.encoder(tokens, src_key_padding_mask=pad))
            glob_enc = encoded[:, 0]
            cand_enc = encoded[:, 1 + placed_x.size(1) :]
            pointer_in = torch.cat([cand_enc, glob_enc.unsqueeze(1).expand_as(cand_enc)], dim=-1)
            scores = self.pointer(pointer_in).squeeze(-1)
            return scores.masked_fill(cand_pad, -1e9)

else:  # pragma: no cover

    class PCTPolicy:  # type: ignore[no-redef]
        def __init__(self, *args: Any, **kwargs: Any):
            raise RuntimeError("PyTorch is required to load Lumo-R1.1")
