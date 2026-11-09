"""
GA + SA của gói PRO (EP_DBLF_GA) — cũng chạy trong gói ULTIMATE.

Nhiễm sắc thể (Genome):
  groups    thứ tự kiện TRONG từng vùng điểm giao (hoán vị riêng mỗi vùng; vùng vẫn xếp sâu trước,
            kiện ghim không nằm trong nhiễm sắc thể và luôn được đặt trước đúng chỗ cũ);
  orient    mỗi kiện một chỉ số hướng ưu tiên trong các hướng được phép (-1: không ưu tiên);
  strategy  cách chấm điểm chỗ đặt của bộ dựng (một LayoutStrategy).
Giải mã bằng chính OptimizationClient.solve_in_process (EP + DBLF, đủ HC-01 → HC-12 và kiểm dỡ hàng),
nên mọi cách xếp GA / SA tìm ra hợp lệ như cách xếp của EP.

GA: quần thể nhỏ, chọn lọc giải đấu (k = 3), lai thứ tự OX theo từng vùng + lai đều cho hướng,
đột biến đổi chỗ / đảo đoạn trong một vùng + đổi hướng ưu tiên, giữ cá thể tốt nhất (elitism).
Quần thể đầu gồm các cách xếp EP đã chạy (không giải mã lại) và vài cá thể ngẫu nhiên.
SA: bắt đầu từ cá thể tốt nhất của GA, láng giềng = đổi chỗ / dời một kiện trong vùng / đổi hướng
ưu tiên, làm nguội theo cấp số nhân.

Độ thích nghi (cùng chiều với ba mục tiêu A · B · C): thể tích xếp được là chính, rồi số kiện,
ít dỡ-xếp lại, ít lệch tải trục. Mọi cá thể con đã giải mã được giữ lại; cuối cùng chọn bằng
pick_candidates như các cách xếp khác, nên GA / SA có thể thắng bất kỳ mục tiêu nào.

Bộ sinh số ngẫu nhiên lấy hạt giống từ bài toán (problem.seed, không có thì băm dữ liệu kiện):
cùng đầu vào thì cùng dãy cá thể; số cá thể kịp giải mã phụ thuộc ngân sách thời gian.
"""
from __future__ import annotations

import logging
import math
import random
import time
import zlib
from dataclasses import dataclass, field
from typing import Optional

from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
from app.dto.optimization.engine.problem_request import ProblemRequest
from app.service.optimize.candidate_plans import (
    LayoutStrategy,
    axle_imbalance,
    layout_strategies_for,
    pick_candidates,
)

logger = logging.getLogger(__name__)

POPULATION = 6
TOURNAMENT_K = 3
ELITES = 2
CROSSOVER_RATE = 0.9
MUTATION_RATE = 0.5
STRATEGY_MUTATION_RATE = 0.15
GA_SHARE = 0.65            # phần ngân sách (thời gian, số lần giải mã) cho GA; còn lại cho SA
SA_T0 = 2.0                # nhiệt độ đầu, theo đơn vị điểm (1 điểm ≈ 1 % thể tích thùng)
SA_ALPHA = 0.85            # hệ số làm nguội mỗi bước
SA_T_MIN = 0.01
MAX_STALE_ATTEMPTS = 40    # số lần liên tiếp chỉ sinh ra cá thể đã giải mã thì dừng


@dataclass
class Genome:
    groups: dict[str, list[str]]
    orient: dict[str, int]
    strategy: str

    def order(self) -> list[str]:
        return [pid for key in sorted(self.groups) for pid in self.groups[key]]

    def key(self) -> tuple:
        return (
            self.strategy,
            tuple((k, tuple(v)) for k, v in sorted(self.groups.items())),
            tuple(sorted((k, v) for k, v in self.orient.items() if v >= 0)),
        )

    def copy(self) -> "Genome":
        return Genome({k: list(v) for k, v in self.groups.items()}, dict(self.orient), self.strategy)


@dataclass
class MetaResult:
    layouts: list[EngineOptimizationResponse] = field(default_factory=list)
    tried: list[str] = field(default_factory=list)
    evaluations: dict[str, int] = field(default_factory=dict)


def problem_seed(problem: ProblemRequest) -> int:
    """Hạt giống tất định từ bài toán (problem.seed nếu có)."""
    if problem.seed is not None:
        return int(problem.seed)
    vt = problem.vehicle
    parts = [f"{vt.inner_l}:{vt.inner_w}:{vt.inner_h}:{vt.max_payload_kg}"]
    parts += sorted(f"{p.id}:{p.l}:{p.w}:{p.h}:{p.weight}:{p.stop_index}" for p in problem.packages)
    parts += sorted(f"pin:{p.package_id}:{p.pos_x}:{p.pos_y}:{p.pos_z}" for p in problem.pinned or [])
    return zlib.crc32("|".join(parts).encode("utf-8"))


def fitness(result: EngineOptimizationResponse, vehicle, total_packages: int) -> float:
    """Điểm vô hướng: 100 × tỉ lệ thể tích + 2 × tỉ lệ kiện xếp được − tỉ lệ dỡ-xếp lại − độ lệch tải trục."""
    metrics = result.metrics
    n = max(total_packages, 1)
    packed = len(result.placements or [])
    volume = float(metrics.volume_utilization or 0.0) if metrics else 0.0
    rehandling = int(metrics.rehandling_count or 0) if metrics else 0
    imbalance = axle_imbalance(result, vehicle) if packed else None
    return 100.0 * volume + 2.0 * packed / n - rehandling / n - (imbalance or 0.0)


class _Search:
    """Bộ giải mã có bộ nhớ đệm và ngân sách chung cho GA rồi SA."""

    def __init__(self, client, problem: ProblemRequest, seed: int, decode_estimate: float):
        self.client = client
        self.problem = problem
        self.rng = random.Random(seed)
        self.decode_estimate = decode_estimate
        self.cache: dict[tuple, EngineOptimizationResponse] = {}
        self.total = len(problem.packages)
        pinned = {str(p.package_id) for p in problem.pinned or []}
        stops_dict, _, _ = client._stop_groups(problem)
        self.groups: dict[str, list] = {}
        for stop_id, pkgs in stops_dict.items():
            free = [p for p in pkgs if str(p.id) not in pinned]
            if free:
                self.groups[str(stop_id)] = free
        self.package_ids = [str(p.id) for pkgs in self.groups.values() for p in pkgs]
        self.strategies = layout_strategies_for(problem.vehicle)

    # ------------------------------------------------------------------ giải mã
    def can_decode(self, deadline: float, used: int, limit: int) -> bool:
        return used < limit and time.monotonic() + self.decode_estimate <= deadline

    def decode(self, genome: Genome) -> tuple[EngineOptimizationResponse, bool]:
        """(kết quả, có phải lần giải mã mới không)."""
        key = genome.key()
        if key in self.cache:
            return self.cache[key], False
        t0 = time.monotonic()
        result = self.client.solve_in_process(
            self.problem,
            strategy=genome.strategy,
            order=genome.order(),
            orientation_pref=genome.orient,
            use_lumo_scorer=False,
            quiet=True,
        )
        # Ước lượng thời gian giải mã theo lần chậm nhất gần đây (không để vượt hạn chót).
        self.decode_estimate = max(self.decode_estimate * 0.8, time.monotonic() - t0)
        self.cache[key] = result
        return result, True

    def score(self, result: EngineOptimizationResponse) -> float:
        return fitness(result, self.problem.vehicle, self.total)

    # ------------------------------------------------------------------ cá thể
    def seed_genome(self, strategy: str) -> Genome:
        """Cá thể tái tạo đúng cách xếp EP của chiến lược (cùng thứ tự kiện, không ưu tiên hướng)."""
        groups = {}
        for key, pkgs in self.groups.items():
            if strategy == LayoutStrategy.HEAVY_FIRST:
                ordered = sorted(pkgs, key=lambda p: (p.weight, p.l * p.w * p.h), reverse=True)
            else:
                ordered = sorted(pkgs, key=lambda p: (p.l * p.w * p.h, p.weight), reverse=True)
            groups[key] = [str(p.id) for p in ordered]
        return Genome(groups, {pid: -1 for pid in self.package_ids}, strategy)

    def random_genome(self) -> Genome:
        groups = {}
        for key, pkgs in self.groups.items():
            ids = [str(p.id) for p in pkgs]
            self.rng.shuffle(ids)
            groups[key] = ids
        orient = {pid: (self.rng.randrange(6) if self.rng.random() < 0.5 else -1) for pid in self.package_ids}
        return Genome(groups, orient, self.rng.choice(self.strategies))

    def crossover(self, a: Genome, b: Genome) -> Genome:
        groups = {}
        for key in a.groups:
            groups[key] = _order_crossover(a.groups[key], b.groups[key], self.rng)
        orient = {pid: (a.orient[pid] if self.rng.random() < 0.5 else b.orient[pid]) for pid in self.package_ids}
        return Genome(groups, orient, a.strategy if self.rng.random() < 0.5 else b.strategy)

    def mutate(self, g: Genome) -> Genome:
        g = g.copy()
        movable = [k for k, v in g.groups.items() if len(v) >= 2]
        if movable and self.rng.random() < MUTATION_RATE:
            seq = g.groups[self.rng.choice(movable)]
            i, j = sorted(self.rng.sample(range(len(seq)), 2))
            if self.rng.random() < 0.5:
                seq[i], seq[j] = seq[j], seq[i]          # đổi chỗ
            else:
                seq[i:j + 1] = reversed(seq[i:j + 1])    # đảo đoạn
        rate = 2.0 / max(len(self.package_ids), 1)
        for pid in self.package_ids:
            if self.rng.random() < rate:
                g.orient[pid] = self.rng.randrange(-1, 6)
        if self.rng.random() < STRATEGY_MUTATION_RATE:
            g.strategy = self.rng.choice(self.strategies)
        return g

    def neighbour(self, g: Genome) -> Genome:
        g = g.copy()
        movable = [k for k, v in g.groups.items() if len(v) >= 2]
        roll = self.rng.random()
        if movable and roll < 0.7:
            seq = g.groups[self.rng.choice(movable)]
            i, j = self.rng.sample(range(len(seq)), 2)
            if roll < 0.4:
                seq[i], seq[j] = seq[j], seq[i]          # đổi chỗ hai kiện
            else:
                seq.insert(j, seq.pop(i))                # dời một kiện
        elif self.package_ids:
            pid = self.rng.choice(self.package_ids)
            g.orient[pid] = self.rng.randrange(-1, 6)    # đổi hướng ưu tiên
        return g

    def tournament(self, pop: list[tuple[Genome, float]]) -> Genome:
        picks = [pop[self.rng.randrange(len(pop))] for _ in range(min(TOURNAMENT_K, len(pop)))]
        return max(picks, key=lambda item: item[1])[0]


def _order_crossover(a: list[str], b: list[str], rng: random.Random) -> list[str]:
    """OX: giữ một đoạn của cha a, phần còn lại theo thứ tự xuất hiện trong mẹ b."""
    n = len(a)
    if n < 2:
        return list(a)
    i, j = sorted(rng.sample(range(n), 2))
    middle = a[i:j + 1]
    taken = set(middle)
    rest = [x for x in b if x not in taken]
    return rest[:i] + middle + rest[i:]


def _unique_picks(results: list[EngineOptimizationResponse], vehicle, code: str) -> list[EngineOptimizationResponse]:
    """Cách xếp tốt nhất theo từng mục tiêu A · B · C trong số đã tìm, mỗi cách một lần, gắn mã thuật toán."""
    if not results:
        return []
    picked: list[EngineOptimizationResponse] = []
    for candidate in pick_candidates(results, vehicle):
        if not any(candidate.result is p for p in picked):
            picked.append(candidate.result)
    return [r.model_copy(update={"algorithm_name": code}) for r in picked]


def run_ga_sa(
    client,
    problem: ProblemRequest,
    ep_runs: list[tuple[str, EngineOptimizationResponse]],
    budget_sec: float,
    max_evaluations: int,
    run_sa: bool = True,
    seed: Optional[int] = None,
) -> MetaResult:
    """Chạy GA rồi SA trong `budget_sec` giây và tối đa `max_evaluations` lần giải mã."""
    out = MetaResult()
    search_start = time.monotonic()
    end = search_start + max(budget_sec, 0.0)
    if not problem.packages:
        return out
    estimate = max((r.metrics.computation_ms or 0) / 1000.0 for _, r in ep_runs) if ep_runs else 0.0
    search = _Search(client, problem, problem_seed(problem) if seed is None else seed, estimate)
    if not search.package_ids:
        logger.info("GA/SA skipped: mọi kiện đều đã ghim")
        return out

    ga_end = search_start + budget_sec * (GA_SHARE if run_sa else 1.0)
    ga_limit = max(1, int(max_evaluations * (GA_SHARE if run_sa else 1.0)))
    sa_limit = max(0, max_evaluations - ga_limit) if run_sa else 0

    # ---------------------------------------------------------------- GA
    population: list[tuple[Genome, float]] = []
    for strategy, layout in ep_runs:
        genome = search.seed_genome(strategy)
        search.cache[genome.key()] = layout           # cách xếp EP đã có: không giải mã lại
        population.append((genome, search.score(layout)))
    offspring: list[EngineOptimizationResponse] = []
    used = 0
    stale = 0
    while len(population) < POPULATION and search.can_decode(ga_end, used, ga_limit) and stale < MAX_STALE_ATTEMPTS:
        genome = search.random_genome()
        result, fresh = search.decode(genome)
        if not fresh:
            stale += 1
            continue
        used += 1
        offspring.append(result)
        population.append((genome, search.score(result)))

    generation = 0
    while search.can_decode(ga_end, used, ga_limit) and stale < MAX_STALE_ATTEMPTS and len(population) >= 2:
        generation += 1
        ranked = sorted(population, key=lambda item: item[1], reverse=True)
        nxt = ranked[:ELITES]
        while len(nxt) < POPULATION and search.can_decode(ga_end, used, ga_limit) and stale < MAX_STALE_ATTEMPTS:
            mother = search.tournament(ranked)
            father = search.tournament(ranked)
            child = search.crossover(mother, father) if search.rng.random() < CROSSOVER_RATE else mother.copy()
            child = search.mutate(child)
            result, fresh = search.decode(child)
            if not fresh:
                stale += 1
                continue
            stale = 0
            used += 1
            offspring.append(result)
            nxt.append((child, search.score(result)))
        population = nxt

    out.evaluations[PlanAlgorithm.GA.value] = used
    if used:
        out.tried.append(PlanAlgorithm.GA.value)
        out.layouts += _unique_picks(offspring, problem.vehicle, PlanAlgorithm.GA.value)

    # ---------------------------------------------------------------- SA
    if run_sa and population:
        current, current_score = max(population, key=lambda item: item[1])
        temperature = SA_T0
        sa_used = 0
        sa_results: list[EngineOptimizationResponse] = []
        stale = 0
        while search.can_decode(end, sa_used, sa_limit) and temperature > SA_T_MIN and stale < MAX_STALE_ATTEMPTS:
            candidate = search.neighbour(current)
            result, fresh = search.decode(candidate)
            if not fresh:
                stale += 1
                continue
            stale = 0
            sa_used += 1
            sa_results.append(result)
            value = search.score(result)
            delta = value - current_score
            if delta >= 0 or search.rng.random() < math.exp(delta / temperature):
                current, current_score = candidate, value
            temperature *= SA_ALPHA
        out.evaluations[PlanAlgorithm.SA.value] = sa_used
        if sa_used:
            out.tried.append(PlanAlgorithm.SA.value)
            out.layouts += _unique_picks(sa_results, problem.vehicle, PlanAlgorithm.SA.value)

    logger.info(
        "GA/SA: %d generation(s), evaluations=%s, %.2fs, decode≈%.2fs",
        generation,
        out.evaluations,
        time.monotonic() - search_start,
        search.decode_estimate,
    )
    return out
