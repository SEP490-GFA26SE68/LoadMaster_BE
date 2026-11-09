# Chép từ LoadMaster_AI/lmai/model.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Mạng chính sách kiểu PCT (Packing Configuration Tree, Zhao và cộng sự, ICLR 2022).

Mỗi kiện đã xếp, mỗi ứng viên và một nút "toàn cục" là một nút đồ thị. Các lớp chú ý (graph attention trên đồ thị
đầy đủ, viết bằng TransformerEncoder) cho các nút trao đổi thông tin; đầu "con trỏ" chấm điểm từng ứng viên, đầu
giá trị ước lượng điểm thưởng còn lại. Ứng viên đều đã hợp lệ nên mặt nạ chỉ che phần đệm.
"""
from __future__ import annotations

from typing import Dict

import torch
from torch import nn

from .env import FC, FC2, FG, FG2, FP, FR


def _mlp(i: int, h: int, o: int) -> nn.Sequential:
    return nn.Sequential(nn.Linear(i, h), nn.ReLU(), nn.Linear(h, o))


class PCTPolicy(nn.Module):
    def __init__(self, d: int = 128, heads: int = 4, layers: int = 2, ff: int = 256, v2: bool = False, hmap: int = 128):
        super().__init__()
        self.cfg = dict(d=d, heads=heads, layers=layers, ff=ff)
        self.v2 = v2
        if v2:  # CP2: đọc thêm cột mới + token hàng còn lại + bản đồ độ cao
            self.cfg.update(v2=True, hmap=hmap)
        self.fc, self.fg = (FC + FC2, FG + FG2) if v2 else (FC, FG)
        self.emb_placed = _mlp(FP, d, d)
        self.emb_cand = _mlp(self.fc, d, d)
        self.emb_glob = _mlp(self.fg, d, d)
        self.type_emb = nn.Parameter(torch.zeros(5 if v2 else 3, d))
        if v2:
            self.emb_rem = _mlp(FR, d, d)
            self.emb_map = _mlp(hmap, d, d)
        layer = nn.TransformerEncoderLayer(d, heads, ff, dropout=0.0, batch_first=True, norm_first=True)
        self.encoder = nn.TransformerEncoder(layer, layers, enable_nested_tensor=False)
        self.norm = nn.LayerNorm(d)
        self.pointer = nn.Sequential(nn.Linear(2 * d, d), nn.ReLU(), nn.Linear(d, 1))
        self.value = nn.Sequential(nn.Linear(2 * d, d), nn.ReLU(), nn.Linear(d, 1))

    def forward(self, obs: Dict[str, torch.Tensor]):
        placed, pmask = obs["placed"], obs["placed_mask"].bool()
        cand, cmask = obs["cand"][..., : self.fc], obs["cand_mask"].bool()   # mô hình v1 chỉ đọc phần cột cũ
        glob = obs["glob"][..., : self.fg]
        B, P, C = placed.shape[0], placed.shape[1], cand.shape[1]
        g = self.emb_glob(glob).unsqueeze(1) + self.type_emb[0]
        p = self.emb_placed(placed) + self.type_emb[1]
        c = self.emb_cand(cand) + self.type_emb[2]
        parts, pads = [g, p, c], [torch.zeros(B, 1, dtype=torch.bool, device=g.device), ~pmask, ~cmask]
        if self.v2:
            parts += [self.emb_rem(obs["rem"]) + self.type_emb[3], (self.emb_map(obs["hmap"]) + self.type_emb[4]).unsqueeze(1)]
            pads += [~obs["rem_mask"].bool(), torch.zeros(B, 1, dtype=torch.bool, device=g.device)]
        x = torch.cat(parts, dim=1)
        pad = torch.cat(pads, dim=1)
        h = self.norm(self.encoder(x, src_key_padding_mask=pad))
        hg = h[:, 0]
        hc = h[:, 1 + P: 1 + P + C]
        # ngữ cảnh: nút toàn cục + trung bình các ứng viên
        cm = cmask.float().unsqueeze(-1)
        mean_c = (hc * cm).sum(1) / cm.sum(1).clamp(min=1.0)
        ctx = hg + mean_c
        logits = self.pointer(torch.cat([hc, ctx.unsqueeze(1).expand(-1, C, -1)], dim=-1)).squeeze(-1)
        logits = logits.masked_fill(~cmask, -1e9)
        value = self.value(torch.cat([hg, mean_c], dim=-1)).squeeze(-1)
        return logits, value


def obs_to_torch(obs: Dict, device) -> Dict[str, torch.Tensor]:
    out = {}
    for k, v in obs.items():
        t = torch.as_tensor(v, device=device)
        out[k] = t.float() if t.dtype not in (torch.bool,) else t
    return out
