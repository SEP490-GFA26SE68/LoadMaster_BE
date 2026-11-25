"""Bounded, fail-closed improvement of a load plan; zones are search hints, not safety rules."""
from __future__ import annotations

import logging
import math
import time
from itertools import product

from app.config.settings import settings
from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import ProblemRequest
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator

logger = logging.getLogger(__name__)


# The same whitelist and dimensions are used by initial packing and final validation.
from app.dto.optimization.engine.rotation_contract import orientations, matches_rotation


def center_of_mass(placements, packages_map):
    weight = sum(float(packages_map[str(p.package_id)].weight) for p in placements)
    if not weight:
        return None
    return tuple(sum(float(packages_map[str(p.package_id)].weight) * (float(getattr(p, axis))
                    + float(getattr(p, size)) / 2) for p in placements) / weight
                 for axis, size in (("x", "packed_l"), ("y", "packed_w"), ("z", "packed_h")))


def validate_plan(placements, vehicle, packages_map, deadline, *, balance=True, unload_budget_sec=None):
    """Revalidate the entire geometry/stack graph, rotations, mass and actual unload sequence.

    unload_budget_sec caps this call's unload search; running out is a rejection (UNLOAD_TIME_LIMIT_REACHED),
    never an acceptance.
    """
    if time.monotonic() >= deadline:
        return ["OPTIMIZATION_TIME_LIMIT_REACHED"]
    ids = [str(p.package_id) for p in placements]
    if len(ids) != len(set(ids)) or any(key not in packages_map for key in ids):
        return ["INVALID_PACKAGE_PARTITION"]
    for i, placement in enumerate(placements):
        if time.monotonic() >= deadline:
            return ["OPTIMIZATION_TIME_LIMIT_REACHED"]
        package = packages_map[str(placement.package_id)]
        if not matches_rotation(package, placement, ConstraintEngine.EPSILON):
            return ["ORIENTATION_NOT_ALLOWED"]
        violations = ConstraintEngine.check_all(placement, {
            "vehicle": vehicle, "packages_map": packages_map,
            "existing_placements": placements[:i] + placements[i + 1:],
            "check_cog": False, "check_axle": False,
        })
        if violations:
            return list(dict.fromkeys(v.violation_code for v in violations))
    if balance and placements:
        for check in (ConstraintEngine.check_cog, ConstraintEngine.check_axle_load):
            if time.monotonic() >= deadline:
                return ["OPTIMIZATION_TIME_LIMIT_REACHED"]
            result = check(placements, vehicle, packages_map)
            if not result.passed:
                return [result.violation_code]
    unload_deadline = deadline
    if unload_budget_sec is not None:
        unload_deadline = min(deadline, time.monotonic() + max(0.0, float(unload_budget_sec)))
    result = UnloadFeasibilityValidator.validate(placements, packages_map, vehicle, deadline=unload_deadline)
    if time.monotonic() >= deadline:
        return ["OPTIMIZATION_TIME_LIMIT_REACHED"]
    return [] if result.passed else [result.violation_code or "MULTI_DROP_LIFO_VIOLATION"]


class LoadBalanceSearch:
    MAX_ATTEMPTS = 12
    MAX_CANDIDATES = 160
    MAX_GENERATED_CANDIDATES = 640

    def __init__(self, problem: ProblemRequest, deadline: float):
        self.problem = problem
        self.vehicle = problem.vehicle
        self.packages = {str(p.id): p for p in problem.packages}
        self.deadline = deadline
        self.iterations = 0
        self.best_cog = None
        self.best_plan = []
        self.best_rank = None
        self.last_violations = []

    def best_plan_copy(self):
        """Return an isolated snapshot; callers cannot mutate the validated incumbent."""
        return [p.model_copy(deep=True) for p in self.best_plan]

    def validated_copy(self, placements):
        """Reuse validation only for the exact unchanged incumbent in this fixed problem."""
        if self.best_plan and placements == self.best_plan:
            return self.best_plan_copy()
        return None

    @staticmethod
    def _bounded():
        return bool(settings.LOAD_BALANCE_BOUNDED_ENABLED)

    def _validate(self, placements, *, balance=True, unload_budget_sec=None):
        if unload_budget_sec is None and self._bounded():
            unload_budget_sec = float(settings.LOAD_BALANCE_UNLOAD_CHECK_SEC)
        return validate_plan(placements, self.vehicle, self.packages, self.deadline,
                             balance=balance, unload_budget_sec=unload_budget_sec)

    def _initial_unload_budget(self):
        """Bounded mode: the constructor's plan may use a share of the pass time left (never less than the per-check
        cap). A large valid plan can need tens of seconds to prove unloadable, but so can an invalid one to prove it
        is not — an uncapped check would leave no time to repack."""
        if not self._bounded():
            return None
        left = max(0.0, self.deadline - time.monotonic())
        return max(float(settings.LOAD_BALANCE_UNLOAD_CHECK_SEC), left * float(settings.LOAD_BALANCE_INITIAL_CHECK_RATIO))

    def _consider(self, placements, *, unload_budget_sec=None):
        """Only fully validated plans may be ranked or retained, including partial plans."""
        codes = self._validate(placements, unload_budget_sec=unload_budget_sec)
        if codes:
            self.last_violations = codes
            return False
        if not placements:
            return False
        self.last_violations = []
        cog = center_of_mass(placements, self.packages)
        lo, hi = self.target_interval(placements)
        target = min(max(self.vehicle.inner_l / 2, lo), hi)
        error = ((cog[0] - target) / self.vehicle.inner_l) ** 2
        error += ((cog[1] - self.vehicle.inner_w / 2) / self.vehicle.inner_w) ** 2
        volume = sum(p.packed_l * p.packed_w * p.packed_h for p in placements)
        rank = (len(placements), volume, -error)
        if self.best_rank is None or rank > self.best_rank:
            self.best_plan = [p.model_copy(deep=True) for p in placements]
            self.best_rank, self.best_cog = rank, cog
        return True

    def input_violations(self):
        v = self.vehicle
        if any(not math.isfinite(float(getattr(v, field))) or float(getattr(v, field)) <= 0
               for field in ("inner_l", "inner_w", "inner_h", "max_payload_kg")):
            return ["INVALID_VEHICLE_DATA"]
        if v.door_w is None or v.door_h is None:
            return ["DOOR_CONFIG_MISSING"]
        if any(not math.isfinite(float(value)) or value <= 0 for value in (v.door_w, v.door_h)):
            return ["DOOR_CONFIG_INVALID"]
        axle = ConstraintEngine.check_axle_load([], v, self.packages)
        # Cargo on an overhang can unload one axle. Only configuration errors are decidable before placing cargo.
        if not axle.passed and axle.violation_code in {"AXLE_CONFIG_MISSING", "AXLE_CONFIG_INVALID"}:
            return [axle.violation_code]
        for package in self.problem.packages:
            if any(not math.isfinite(float(value)) or value <= 0
                   for value in (package.l, package.w, package.h, package.weight)):
                return ["INVALID_PACKAGE_DATA"]
        # The existing heuristic does not implement pinned placement construction; never silently move a pin.
        if self.problem.pinned:
            return ["PINNED_PLACEMENT_UNSUPPORTED"]
        return []

    def target_interval(self, placements):
        """Intersect HC-11 with BOTH axle upper limits and non-negative reactions, for this cargo mass."""
        v = self.vehicle
        mass = sum(float(self.packages[str(p.package_id)].weight) for p in placements)
        lo = v.inner_l * (0.5 - v.max_cog_offset_ratio)
        hi = v.inner_l * (0.5 + v.max_cog_offset_ratio)
        if mass:
            wheelbase = v.rear_axle_x - v.front_axle_x
            lo = max(lo, v.front_axle_x + wheelbase * (v.empty_front_axle_load_kg + mass - v.front_axle_limit_kg) / mass,
                     v.front_axle_x - wheelbase * v.empty_rear_axle_load_kg / mass)
            hi = min(hi, v.front_axle_x + wheelbase * (v.rear_axle_limit_kg - v.empty_rear_axle_load_kg) / mass,
                     v.front_axle_x + wheelbase * (v.empty_front_axle_load_kg + mass) / mass)
        return lo, hi

    def improve(self, initial, zones):
        bounded = self._bounded()
        patience = max(1, int(settings.LOAD_BALANCE_PATIENCE)) if bounded else None
        if time.monotonic() >= self.deadline:
            self._log("time budget exhausted before improvement", ["OPTIMIZATION_TIME_LIMIT_REACHED"])
            return self.best_plan_copy()
        initial_valid = self._consider(initial, unload_budget_sec=self._initial_unload_budget())
        initial_codes = self.last_violations
        if initial_valid and len(initial) == len(self.packages):
            self._log("initial plan already satisfies all hard constraints", [])
            return initial
        if bounded:
            # Own share of the job for the search itself (starts after the initial check); pass deadline still applies.
            budget = max(float(self.problem.time_limit_sec or 60), 1) * float(settings.LOAD_BALANCE_BUDGET_RATIO)
            self.deadline = min(self.deadline, time.monotonic() + max(0.0, budget))

        # Rigid translation retains geometry, but the entire trial must still be validated.
        initial_cog = center_of_mass(initial, self.packages) if initial else None
        if initial_cog and time.monotonic() < self.deadline:
            lo, hi = self.target_interval(initial)
            if lo <= hi:
                target = min(max(self.vehicle.inner_l / 2, lo), hi)
                dx_min = -min(p.x for p in initial)
                dx_max = self.vehicle.inner_l - max(p.x + p.packed_l for p in initial)
                dy_min = -min(p.y for p in initial)
                dy_max = self.vehicle.inner_w - max(p.y + p.packed_w for p in initial)
                dx = min(max(target - initial_cog[0], dx_min), dx_max)
                dy = min(max(self.vehicle.inner_w / 2 - initial_cog[1], dy_min), dy_max)
                for tx, ty in ((dx, dy), (dx, 0.0), (0.0, dy)):
                    if time.monotonic() >= self.deadline:
                        break
                    trial = [p.model_copy(update={"x": p.x + tx, "y": p.y + ty}) for p in initial]
                    self.iterations += 1
                    if self._consider(trial) and len(trial) == len(self.packages):
                        self._log("rigid translation accepted after full validation", [])
                        return self.best_plan_copy()
            else:
                logger.info("[IMPROVEMENT] No longitudinal COG satisfies HC-11/HC-12 for current cargo mass")

        packages = list(self.problem.packages)
        order_keys = [
            lambda p: (-p.stop_index, -p.weight, str(p.id)),
            lambda p: (p.stop_index, -p.weight, str(p.id)),
            lambda p: (-p.weight, -p.stop_index, str(p.id)),
            lambda p: (-p.stop_index, -(p.l * p.w * p.h), str(p.id)),
        ]
        stale = 0
        stopped_early = False
        for attempt in range(self.MAX_ATTEMPTS):
            if time.monotonic() >= self.deadline:
                break
            before = self.best_rank
            order = sorted(packages, key=order_keys[attempt % len(order_keys)])
            trial = self._repack(order, zones, attempt // len(order_keys))
            self.iterations += 1
            if time.monotonic() < self.deadline:
                self._consider(trial)
            if self.best_plan and len(self.best_plan) == len(packages):
                self._log("soft-zone repack accepted after full validation", [])
                return self.best_plan_copy()
            if patience is not None:
                stale = 0 if self.best_rank != before else stale + 1
                if stale >= patience:
                    stopped_early = True
                    break
        if stopped_early:
            reason = f"no better plan in {patience} consecutive repacks"
        else:
            reason = "time budget exhausted" if time.monotonic() >= self.deadline else "bounded candidate/order search exhausted"
        self._log(reason, [] if self.best_plan else self.last_violations or initial_codes)
        # An invalid initial plan never sets a minimum count or becomes the fallback.
        return self.best_plan_copy()

    def _repack(self, packages, zones, variant):
        placed = []
        mass = 0.0
        v = self.vehicle
        # Target the full requested mass, including empty-vehicle reactions. Invalid intersections remain rejectable.
        proxies = []
        for p in packages:
            if time.monotonic() >= self.deadline:
                return placed
            proxies.append(PlacementData(package_id=p.id, x=0, y=0, z=0, packed_l=p.l, packed_w=p.w, packed_h=p.h))
        lo, hi = self.target_interval(proxies)
        target = (lo + hi) / 2 if lo <= hi else v.inner_l / 2
        target = min(max(target, 0), v.inner_l)
        for package in packages:
            if time.monotonic() >= self.deadline:
                break
            if mass + package.weight > v.max_payload_kg + ConstraintEngine.EPSILON:
                continue
            candidates = []
            allowed = orientations(package)
            point_limit = max(1, self.MAX_GENERATED_CANDIDATES // max(1, len(allowed)))
            for l, w, h, rotation in allowed:
                if time.monotonic() >= self.deadline:
                    break
                if l > v.inner_l or w > v.inner_w or h > v.inner_h:
                    continue
                x0 = min(max(target - l / 2, 0), v.inner_l - l)
                y0 = (v.inner_w - w) / 2
                zone = next((z for z in zones.values() if z.sequence == package.stop_index), None)
                xs = {0.0, v.inner_l - l, x0}
                if zone:
                    xs.update((max(0.0, zone.zone_start_x), min(v.inner_l - l, zone.zone_end_x - l)))
                ys = {0.0, v.inner_w - w, y0, max(0.0, (v.inner_w - v.door_w) / 2)}
                points = list(dict.fromkeys((x, y, 0.0) for x, y in product(sorted(xs), sorted(ys))))
                seen = set(points)
                # Boundaries are candidate hints only; all obstacle checks remain mandatory.
                for obstacle in v.obstacles:
                    if time.monotonic() >= self.deadline or len(points) >= point_limit:
                        break
                    hints = [(obstacle.x - l, y0, 0.0), (obstacle.x + obstacle.length, y0, 0.0),
                             (x0, obstacle.y - w, 0.0), (x0, obstacle.y + obstacle.width, 0.0)]
                    if obstacle.load_bearing:
                        hints.append((obstacle.x, obstacle.y, obstacle.z + obstacle.height))
                    for point in hints:
                        if len(points) >= point_limit:
                            break
                        if point not in seen:
                            points.append(point)
                            seen.add(point)
                for p in placed:
                    if time.monotonic() >= self.deadline or len(points) >= point_limit:
                        break
                    for point in (
                        (p.x + p.packed_l, p.y, p.z), (p.x - l, p.y, p.z),
                        (p.x, p.y + p.packed_w, p.z), (p.x, p.y - w, p.z),
                        (p.x, p.y, p.z + p.packed_h), (p.x + p.packed_l, y0, 0.0),
                        (p.x - l, y0, 0.0), (x0, p.y + p.packed_w, 0.0), (x0, p.y - w, 0.0),
                    ):
                        if len(points) >= point_limit:
                            break
                        if point not in seen:
                            points.append(point)
                            seen.add(point)
                for x, y, z in points[:point_limit]:
                    if time.monotonic() >= self.deadline:
                        break
                    if not (0 <= x <= v.inner_l - l and 0 <= y <= v.inner_w - w and 0 <= z <= v.inner_h - h):
                        continue
                    candidate = PlacementData(package_id=package.id, x=x, y=y, z=z, packed_l=l, packed_w=w,
                                              packed_h=h, rotation_type=rotation, step_sequence=len(placed) + 1,
                                              stop_zone_id=zone.stop_id if zone else None)
                    if not ConstraintEngine.check_non_overlap(candidate, placed).passed:
                        continue
                    cog = center_of_mass([*placed, candidate], self.packages)
                    score = ((cog[0] - target) / v.inner_l) ** 2 + ((cog[1] - v.inner_w / 2) / v.inner_w) ** 2
                    score += 0.1 * z / v.inner_h
                    # Vary the soft hint strength, never the hard limits or stop extraction rule.
                    if zone and variant:
                        score += 0.01 * variant * abs(x + l / 2 - (zone.zone_start_x + zone.zone_end_x) / 2) / v.inner_l
                    candidates.append((score, x, y, z, rotation, candidate))
            if time.monotonic() >= self.deadline:
                break
            candidates.sort(key=lambda item: item[:5])
            for *_, candidate in candidates[:self.MAX_CANDIDATES]:
                if time.monotonic() >= self.deadline:
                    break
                # Partial-plan COG is a score, not a hard constraint. All physical/rotation/unload rules remain hard.
                if not self._validate([*placed, candidate], balance=False):
                    placed.append(candidate)
                    mass += package.weight
                    # Retain fully validated prefixes before spending time on the next package.
                    self._consider(placed)
                    break
        return placed

    def _log(self, reason, violations):
        logger.info("[IMPROVEMENT] Iterations=%s BestCOG_X=%s BestCOG_Y=%s Violations=%s Reason=%s",
                    self.iterations, self.best_cog[0] if self.best_cog else None,
                    self.best_cog[1] if self.best_cog else None, violations, reason)
