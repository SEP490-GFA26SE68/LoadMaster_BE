# Chép từ LoadMaster_AI/lmai/ppo.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""PPO có mặt nạ hành động + chạy nhiều môi trường song song (multiprocessing kiểu "spawn" — chạy được trên Windows)."""
from __future__ import annotations

import csv
import json
import math
import multiprocessing as mp
import os
import time
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional, Sequence

import numpy as np
import torch
from torch import nn
from torch.distributions import Categorical

from .env import EnvConfig, PackingEnv, obs_spec
from .generator import GenConfig, generate
from .model import PCTPolicy, obs_to_torch
from .problem import Problem
from .result import PlanResult

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_FIELDS = ["update", "stage", "steps", "fps", "lr", "bc_w", "pg", "vf", "ent", "kl", "clipfrac", "bc_ce",
              "ep_ret", "ep_valid", "ep_cargo_frac", "ep_raw", "episodes"]


# ======================================================================= môi trường song song
def _summary(env: PackingEnv, res: PlanResult) -> Dict:
    return {"ret": env.ret, "valid": float(res.verdict.valid), "cargo_frac": res.cargo_frac,
            "util": res.volume_util, "raw": res.packed_frac_raw, "n": len(env.problem.packages)}


class _EnvRunner:
    """Một môi trường + bộ sinh bài toán riêng (seed không trùng giữa các worker)."""

    def __init__(self, env_cfg: dict, gen_cfg: dict, seed: int):
        self.env = PackingEnv(EnvConfig(**env_cfg))
        self.gen = GenConfig(**gen_cfg)
        self.seed = seed

    def set_gen(self, gen_cfg: dict):
        self.gen = GenConfig(**gen_cfg)

    def reset(self):
        for _ in range(50):
            self.seed += 1
            obs = self.env.reset(generate(self.seed, self.gen))
            if not self.env.done:
                return obs
        raise RuntimeError("Không sinh được bài toán có ứng viên — kiểm tra GenConfig")

    def step(self, a: int):
        obs, r, done, info = self.env.step(a)
        out = {}
        if done:
            out = _summary(self.env, info["result"])
            obs = self.reset()
        return obs, r, done, out


def _worker(remote, env_cfg, gen_cfg, seed):
    torch.set_num_threads(1)
    runner = _EnvRunner(env_cfg, gen_cfg, seed)
    try:
        while True:
            cmd, data = remote.recv()
            if cmd == "step":
                remote.send(runner.step(data))
            elif cmd == "reset":
                remote.send(runner.reset())
            elif cmd == "set_gen":
                runner.set_gen(data)
                remote.send(True)
            elif cmd == "close":
                break
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        remote.close()


def _stack(obs_list: List[Dict[str, np.ndarray]]) -> Dict[str, np.ndarray]:
    return {k: np.stack([o[k] for o in obs_list]) for k in obs_list[0]}


class VecEnv:
    """n_envs môi trường; workers > 0 thì mỗi môi trường một tiến trình con, = 0 thì chạy tuần tự trong tiến trình chính."""

    def __init__(self, n_envs: int, env_cfg: EnvConfig, gen_cfg: GenConfig, seed: int, workers: bool = True):
        self.n = n_envs
        self.workers = workers
        ec, gc = asdict(env_cfg), asdict(gen_cfg)
        seeds = [seed * 1_000_003 + i * 10_000_000 for i in range(n_envs)]
        if workers:
            ctx = mp.get_context("spawn")
            self.remotes, self.procs = [], []
            for i in range(n_envs):
                parent, child = ctx.Pipe()
                p = ctx.Process(target=_worker, args=(child, ec, gc, seeds[i]), daemon=True)
                p.start()
                child.close()
                self.remotes.append(parent)
                self.procs.append(p)
        else:
            self.runners = [_EnvRunner(ec, gc, s) for s in seeds]

    def reset(self):
        if self.workers:
            for r in self.remotes:
                r.send(("reset", None))
            return _stack([r.recv() for r in self.remotes])
        return _stack([r.reset() for r in self.runners])

    def step(self, actions: Sequence[int]):
        if self.workers:
            for r, a in zip(self.remotes, actions):
                r.send(("step", int(a)))
            res = [r.recv() for r in self.remotes]
        else:
            res = [r.step(int(a)) for r, a in zip(self.runners, actions)]
        obs, rew, done, info = zip(*res)
        return _stack(list(obs)), np.array(rew, np.float32), np.array(done, bool), list(info)

    def set_gen(self, gen_cfg: GenConfig):
        if self.workers:
            for r in self.remotes:
                r.send(("set_gen", asdict(gen_cfg)))
            for r in self.remotes:
                r.recv()
        else:
            for r in self.runners:
                r.set_gen(asdict(gen_cfg))

    def close(self):
        if self.workers:
            for r in self.remotes:
                try:
                    r.send(("close", None))
                except (BrokenPipeError, EOFError):
                    pass
            for p in self.procs:
                p.join(timeout=5)


# ======================================================================= đánh giá
@torch.no_grad()
def evaluate(model: nn.Module, problems: Sequence[Problem], env_cfg: EnvConfig, device, samples: int = 0,
             seed: int = 0) -> List[PlanResult]:
    """Chạy mô hình trên danh sách bài toán. samples = 0: chọn nước tốt nhất (greedy);
    samples > 0: thêm `samples` lần chạy có lấy mẫu, giữ phương án hợp lệ chở được nhiều nhất."""
    model.eval()
    gen = torch.Generator(device="cpu").manual_seed(seed)
    best: List[Optional[PlanResult]] = [None] * len(problems)
    t_start = [0.0] * len(problems)
    runs = [("greedy", 0)] + [("sample", k) for k in range(samples)]
    for mode, _ in runs:
        envs = [PackingEnv(env_cfg) for _ in problems]
        obs = [e.reset(p) for e, p in zip(envs, problems)]
        active = [i for i, e in enumerate(envs) if not e.done]
        for i, e in enumerate(envs):
            if e.done:
                _keep(best, i, e.result("AI"))
        t0 = time.perf_counter()
        while active:
            batch = obs_to_torch(_stack([obs[i] for i in active]), device)
            logits, _ = model(batch)
            if mode == "greedy":
                acts = logits.argmax(-1).cpu().numpy()
            else:
                probs = torch.softmax(logits.float().cpu(), -1)
                acts = torch.multinomial(probs, 1, generator=gen).squeeze(-1).numpy()
            nxt = []
            for a, i in zip(acts, active):
                o, _, d, info = envs[i].step(int(a))
                if d:
                    res = info["result"]
                    res.seconds = time.perf_counter() - t0
                    _keep(best, i, res)
                else:
                    obs[i] = o
                    nxt.append(i)
            active = nxt
    model.train()
    return [b for b in best]


def _score(r: PlanResult) -> float:
    return (1.0 if r.verdict.valid else 0.0) * 10 + r.cargo_frac + 0.001 * r.packed_frac_raw


def _keep(best, i, res):
    if best[i] is None or _score(res) > _score(best[i]):
        best[i] = res


# ======================================================================= PPO
@dataclass
class PPOConfig:
    n_envs: int = 12
    workers: bool = True
    rollout: int = 64            # số bước mỗi môi trường trước một lần cập nhật
    epochs: int = 4
    minibatches: int = 4
    lr: float = 3e-4
    gamma: float = 1.0
    lam: float = 0.95
    clip: float = 0.2
    vf_coef: float = 0.5
    ent_coef: float = 0.01
    init_from: str = ""          # khởi tạo trọng số từ mô hình khác (vd. runs/bc/bc.pt của học bắt chước)
    bc_data: str = ""            # bộ mẫu của thầy (runs/bc/demos.npz) trộn vào lúc PPO
    bc_coef: float = 0.0         # trọng số loss bắt chước lúc đầu
    bc_decay_updates: int = 0    # giảm tuyến tính bc_coef về 0 sau từng ấy lần cập nhật
    lr_end: float = 0.3          # lr giảm tuyến tính trong mỗi giai đoạn từ lr xuống lr * lr_end
    max_grad_norm: float = 0.5
    target_kl: float = 0.03
    seed: int = 1
    eval_every: int = 20         # mỗi bao nhiêu lần cập nhật thì chấm bộ kiểm định
    save_every: int = 10
    val_size: int = 100
    val_seed: int = 900_000
    model: Dict = field(default_factory=lambda: dict(d=128, heads=4, layers=2, ff=256))


def _gen_from(d: dict) -> GenConfig:
    d = dict(d)
    for k in ("n_packages", "n_stops", "fill", "weight_frac", "skus_per_stop"):
        if k in d and isinstance(d[k], list):
            d[k] = tuple(d[k])
    return GenConfig(**d)


class Trainer:
    def __init__(self, run_dir: str, ppo: PPOConfig, env_cfg: EnvConfig, stages: List[Dict], device: str = "auto",
                 resume: bool = False, log=print):
        self.run_dir = run_dir
        os.makedirs(run_dir, exist_ok=True)
        self.ppo, self.env_cfg, self.stages, self.log = ppo, env_cfg, stages, log
        if device == "auto":
            device = "cuda" if torch.cuda.is_available() else "cpu"
        self.device = torch.device(device)
        torch.manual_seed(ppo.seed)
        np.random.seed(ppo.seed)
        self.model = PCTPolicy(**ppo.model).to(self.device)
        self.opt = torch.optim.Adam(self.model.parameters(), lr=ppo.lr, eps=1e-5)
        self.update = 0
        self.best = -1.0
        self.stage_i = 0
        self.stage_start = 0
        if resume and os.path.exists(os.path.join(run_dir, "last.pt")):
            ck = torch.load(os.path.join(run_dir, "last.pt"), map_location=self.device, weights_only=False)
            self.model.load_state_dict(ck["model"])
            self.opt.load_state_dict(ck["optimizer"])
            self.update, self.best = ck["update"], ck.get("best", -1.0)
            self.stage_i, self.stage_start = ck.get("stage_i", 0), ck.get("stage_start", 0)
            log(f"Tiếp tục từ lần cập nhật {self.update}, điểm kiểm định tốt nhất {self.best:.4f}")
        elif ppo.init_from:
            path = ppo.init_from if os.path.isabs(ppo.init_from) else os.path.join(ROOT, ppo.init_from)
            ck = torch.load(path, map_location=self.device, weights_only=False)
            self.model.load_state_dict(ck["model"])
            log(f"Khởi tạo trọng số từ {path}")
        self.demos = None
        if ppo.bc_data and ppo.bc_coef > 0:
            from .bc import DemoBatcher
            path = ppo.bc_data if os.path.isabs(ppo.bc_data) else os.path.join(ROOT, ppo.bc_data)
            self.demos = DemoBatcher(path, self.device)
            self.demo_rng = np.random.default_rng(ppo.seed)
            log(f"Trộn bộ mẫu học bắt chước: {self.demos.n} nước đi, hệ số {ppo.bc_coef} giảm về 0 sau {ppo.bc_decay_updates} lần cập nhật")
        self.writer = None
        try:
            from torch.utils.tensorboard import SummaryWriter
            self.writer = SummaryWriter(os.path.join(run_dir, "tb"))
        except Exception:
            log("Không có tensorboard — chỉ ghi log.csv")
        self.csv_path = os.path.join(run_dir, "log.csv")
        self.val_path = os.path.join(run_dir, "val.csv")

    # ------------------------------------------------------------------ tiện ích
    def _val_set(self, gen: GenConfig) -> List[Problem]:
        return [generate(self.ppo.val_seed + i, gen) for i in range(self.ppo.val_size)]

    def _save(self, name: str, extra: Dict):
        ck = {"model": self.model.state_dict(), "model_cfg": self.model.cfg, "env_cfg": asdict(self.env_cfg),
              "update": self.update, "best": self.best, "stage_i": self.stage_i, "stage_start": self.stage_start,
              "ppo": asdict(self.ppo), "stages": self.stages, **extra}
        if name == "last.pt":
            ck["optimizer"] = self.opt.state_dict()
        tmp = os.path.join(self.run_dir, name + ".tmp")
        torch.save(ck, tmp)
        os.replace(tmp, os.path.join(self.run_dir, name))

    def _append_csv(self, path: str, row: Dict):
        new = not os.path.exists(path)
        fields = LOG_FIELDS if path == self.csv_path else list(row)
        with open(path, "a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields, restval="", extrasaction="ignore")
            if new:
                w.writeheader()
            w.writerow(row)

    def _stage(self) -> Dict:
        return self.stages[min(self.stage_i, len(self.stages) - 1)]

    # ------------------------------------------------------------------ vòng train
    def run(self):
        P, E = self.ppo, self.env_cfg
        spec = obs_spec(E)
        stage = self._stage()
        gen = _gen_from(stage["gen"])
        vec = VecEnv(P.n_envs, E, gen, P.seed + self.update, P.workers)
        val = self._val_set(gen)
        obs = vec.reset()
        T, N = P.rollout, P.n_envs
        buf = {k: np.zeros((T, N) + s, np.float32 if k in ("placed", "cand", "glob") else bool) for k, s in spec.items()}
        acts = np.zeros((T, N), np.int64)
        logps = np.zeros((T, N), np.float32)
        vals = np.zeros((T, N), np.float32)
        rews = np.zeros((T, N), np.float32)
        dones = np.zeros((T, N), np.float32)
        ep_stats: List[Dict] = []
        total_updates = sum(s["updates"] for s in self.stages)
        t_last = time.perf_counter()
        try:
            while self.update < total_updates:
                # chuyển giai đoạn (tăng độ khó)
                if self.update - self.stage_start >= self._stage()["updates"] and self.stage_i < len(self.stages) - 1:
                    self.stage_i += 1
                    self.stage_start = self.update
                    stage = self._stage()
                    gen = _gen_from(stage["gen"])
                    vec.set_gen(gen)
                    val = self._val_set(gen)
                    self.best = -1.0  # bộ kiểm định mới → so lại từ đầu
                    self.log(f"== Giai đoạn {self.stage_i + 1}: {stage.get('name', '')} ==")
                    obs = vec.reset()
                # lr giảm dần trong giai đoạn cho ổn định về cuối
                frac = min(1.0, (self.update - self.stage_start) / max(1, self._stage()["updates"]))
                for g in self.opt.param_groups:
                    g["lr"] = P.lr * (1.0 - frac * (1.0 - P.lr_end))
                # 1. thu thập
                self.model.eval()
                with torch.no_grad():
                    for t in range(T):
                        for k in buf:
                            buf[k][t] = obs[k]
                        logits, v = self.model(obs_to_torch(obs, self.device))
                        dist = Categorical(logits=logits)
                        a = dist.sample()
                        acts[t] = a.cpu().numpy()
                        logps[t] = dist.log_prob(a).cpu().numpy()
                        vals[t] = v.cpu().numpy()
                        obs, r, d, infos = vec.step(acts[t])
                        rews[t], dones[t] = r, d
                        ep_stats.extend(i for i in infos if i)
                    _, last_v = self.model(obs_to_torch(obs, self.device))
                    last_v = last_v.cpu().numpy()
                # 2. GAE
                adv = np.zeros((T, N), np.float32)
                gae = np.zeros(N, np.float32)
                for t in reversed(range(T)):
                    nv = last_v if t == T - 1 else vals[t + 1]
                    nonterm = 1.0 - dones[t]
                    delta = rews[t] + P.gamma * nv * nonterm - vals[t]
                    gae = delta + P.gamma * P.lam * nonterm * gae
                    adv[t] = gae
                ret = adv + vals
                # 3. cập nhật
                self.model.train()
                flat = {k: torch.as_tensor(v.reshape((T * N,) + v.shape[2:]), device=self.device) for k, v in buf.items()}
                for k in ("placed", "cand", "glob"):
                    flat[k] = flat[k].float()
                fa = torch.as_tensor(acts.reshape(-1), device=self.device)
                flp = torch.as_tensor(logps.reshape(-1), device=self.device)
                fadv = torch.as_tensor(adv.reshape(-1), device=self.device)
                fret = torch.as_tensor(ret.reshape(-1), device=self.device)
                fval = torch.as_tensor(vals.reshape(-1), device=self.device)
                bs = T * N
                mb = max(1, bs // P.minibatches)
                bc_w = 0.0
                if self.demos is not None:
                    bc_w = P.bc_coef * max(0.0, 1.0 - self.update / max(1, P.bc_decay_updates)) if P.bc_decay_updates else P.bc_coef
                stats = {"pg": 0.0, "vf": 0.0, "ent": 0.0, "kl": 0.0, "clipfrac": 0.0}
                nmb = 0
                stop = False
                for _ in range(P.epochs):
                    perm = torch.randperm(bs, device=self.device)
                    for s0 in range(0, bs, mb):
                        idx = perm[s0:s0 + mb]
                        logits, v = self.model({k: t[idx] for k, t in flat.items()})
                        dist = Categorical(logits=logits)
                        lp = dist.log_prob(fa[idx])
                        ratio = torch.exp(lp - flp[idx])
                        a_ = fadv[idx]
                        a_ = (a_ - a_.mean()) / (a_.std() + 1e-8)
                        pg = torch.max(-a_ * ratio, -a_ * torch.clamp(ratio, 1 - P.clip, 1 + P.clip)).mean()
                        v_clip = fval[idx] + torch.clamp(v - fval[idx], -P.clip, P.clip)
                        vf = 0.5 * torch.max((v - fret[idx]) ** 2, (v_clip - fret[idx]) ** 2).mean()
                        ent = dist.entropy().mean()
                        loss = pg + P.vf_coef * vf - P.ent_coef * ent
                        if bc_w > 0:
                            from .bc import bc_loss
                            dobs, dact, dret = self.demos.sample(len(idx), self.demo_rng)
                            bl, bce, _, bacc = bc_loss(self.model, dobs, dact, dret)
                            loss = loss + bc_w * bl
                            stats["bc_ce"] = stats.get("bc_ce", 0.0) + bce.item()
                        self.opt.zero_grad()
                        loss.backward()
                        nn.utils.clip_grad_norm_(self.model.parameters(), P.max_grad_norm)
                        self.opt.step()
                        with torch.no_grad():
                            kl = ((ratio - 1) - torch.log(ratio)).mean().item()
                            stats["pg"] += pg.item(); stats["vf"] += vf.item(); stats["ent"] += ent.item()
                            stats["kl"] += kl; stats["clipfrac"] += ((ratio - 1).abs() > P.clip).float().mean().item()
                        nmb += 1
                        if P.target_kl and kl > 1.5 * P.target_kl:
                            stop = True
                            break
                    if stop:
                        break
                self.update += 1
                now = time.perf_counter()
                fps = T * N / max(1e-6, now - t_last)
                t_last = now
                row = {"update": self.update, "stage": self.stage_i + 1, "steps": self.update * T * N, "fps": round(fps, 1),
                       "lr": round(self.opt.param_groups[0]["lr"], 7), "bc_w": round(bc_w, 4),
                       **{k: round(v / max(1, nmb), 5) for k, v in stats.items()}}
                if ep_stats:
                    for key in ("ret", "valid", "cargo_frac", "raw"):
                        row["ep_" + key] = round(float(np.mean([e[key] for e in ep_stats])), 4)
                    row["episodes"] = len(ep_stats)
                    ep_stats = []
                self._append_csv(self.csv_path, row)
                if self.writer:
                    for k, v in row.items():
                        if k not in ("update",):
                            self.writer.add_scalar("train/" + k, v, self.update)
                self.log(" ".join(f"{k}={v}" for k, v in row.items()))
                # 4. kiểm định + lưu
                if self.update % P.eval_every == 0 or self.update == total_updates:
                    res = evaluate(self.model, val, E, self.device)
                    score = float(np.mean([r.cargo_frac for r in res]))
                    vrow = {"update": self.update, "stage": self.stage_i + 1, "val_cargo_frac": round(score, 4),
                            "val_valid": round(float(np.mean([r.verdict.valid for r in res])), 4),
                            "val_util": round(float(np.mean([r.volume_util for r in res])), 4)}
                    self._append_csv(self.val_path, vrow)
                    if self.writer:
                        for k, v in vrow.items():
                            if k != "update":
                                self.writer.add_scalar("val/" + k, v, self.update)
                    if score > self.best:
                        self.best = score
                        self._save("best.pt", {"val": vrow})
                        self._save(f"best_stage{self.stage_i + 1}.pt", {"val": vrow})
                    self.log("KIỂM ĐỊNH " + json.dumps(vrow, ensure_ascii=False) + f" | tốt nhất {self.best:.4f}")
                if self.update % P.save_every == 0:
                    self._save("last.pt", {})
        finally:
            self._save("last.pt", {})
            vec.close()
            if self.writer:
                self.writer.close()


def load_policy(path: str, device="cpu"):
    ck = torch.load(path, map_location=device, weights_only=False)
    model = PCTPolicy(**ck["model_cfg"]).to(device)
    model.load_state_dict(ck["model"])
    model.eval()
    return model, EnvConfig(**ck["env_cfg"]), ck
