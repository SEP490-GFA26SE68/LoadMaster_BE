"""Synthetic safety regressions, NOT a reproduction of the unavailable 18-package production payload."""
import logging
import random
import time

import pytest

from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest, StopData, VehicleData
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.load_balance_search import LoadBalanceSearch, center_of_mass, validate_plan
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.stop_zone_calculator import StopZoneCalculator
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator


def vehicle(**overrides):
    data = dict(inner_l=620, inner_w=240, inner_h=250, door_w=210, door_h=210,
                max_payload_kg=5000, max_cog_offset_ratio=0.15, front_axle_x=100, rear_axle_x=350,
                front_axle_limit_kg=2200, rear_axle_limit_kg=3300,
                empty_front_axle_load_kg=1500, empty_rear_axle_load_kg=1800)
    data.update(overrides)
    return VehicleData(**data)


def package(id, stop=1, weight=10, l=40, w=30, h=25, **overrides):
    data = dict(id=str(id), stop_index=stop, weight=weight, l=l, w=w, h=h,
                rotation_allowed=False, allowed_rotations=[0])
    data.update(overrides)
    return PackageData(**data)


def problem(packages, **kwargs):
    return ProblemRequest(vehicle=kwargs.pop("vehicle", vehicle()), packages=packages,
                          stops=[StopData(id=f"S{s}", sequence=s) for s in sorted({p.stop_index for p in packages})],
                          **kwargs)


def placement(p, x, y=100, z=0):
    return PlacementData(package_id=p.id, x=x, y=y, z=z, packed_l=p.l, packed_w=p.w, packed_h=p.h, rotation_type=0)


def assert_partition(result, request):
    packed = [str(p.package_id) for p in result.placements]
    unplaced = [str(p.package_id) for p in result.unplaced]
    expected = {str(p.id) for p in request.packages}
    assert len(packed) == len(set(packed))
    assert len(unplaced) == len(set(unplaced))
    assert not set(packed) & set(unplaced)
    assert set(packed) | set(unplaced) == expected
    assert len(packed) + len(unplaced) == len(request.packages)
    assert result.metrics.packed_count == len(packed)
    assert {u.reason for u in result.unplaced} <= {
        "OVER_WEIGHT", "OUT_OF_SPACE", "STACKING_CONFLICT", "LIFO_BLOCK", "CONSTRAINT_VIOLATED"}


def assert_safe(result, request):
    assert_partition(result, request)
    if result.placements:
        metadata = {str(p.id): p for p in request.packages}
        assert validate_plan(result.placements, request.vehicle, metadata, time.monotonic() + 5) == []
        assert ConstraintEngine.check_cog(result.placements, request.vehicle, metadata).passed
        assert ConstraintEngine.check_axle_load(result.placements, request.vehicle, metadata).passed
        unload = UnloadFeasibilityValidator.validate(result.placements, metadata, request.vehicle)
        assert unload.passed, unload.detail
        assert [metadata[id].stop_index for id in unload.unload_sequence] == sorted(
            metadata[str(p.package_id)].stop_index for p in result.placements)


def test_mass_aware_zones_expand_heavy_small_volume_stop_without_changing_order():
    stops = [dict(stop_id="early", sequence=1, packages=[dict(volume=10, weight=90)]),
             dict(stop_id="late", sequence=2, packages=[dict(volume=90, weight=10)])]
    volume = StopZoneCalculator.calculate_zones(vehicle(), stops)
    mixed = StopZoneCalculator.calculate_zones(vehicle(), stops, weight_blend=0.5)
    assert mixed.zones[0].zone_length > volume.zones[0].zone_length
    assert mixed.zones[1].zone_end_x < mixed.zones[0].zone_start_x
    assert mixed.zones[0].zone_end_x == 620
    assert mixed.zones[0].volume_ratio == volume.zones[0].volume_ratio  # actual volume share, not allocation share


def test_rigid_translation_repairs_cog_before_rejecting():
    request = problem([package("A")])
    initial = [placement(request.packages[0], 550)]
    metadata = {p.id: p for p in request.packages}
    assert not ConstraintEngine.check_cog(initial, request.vehicle, metadata).passed
    repaired = LoadBalanceSearch(request, time.monotonic() + 2).improve(initial, {})
    assert len(repaired) == 1
    assert center_of_mass(repaired, metadata)[0] == pytest.approx(310)
    assert validate_plan(repaired, request.vehicle, metadata, time.monotonic() + 2) == []
    assert initial[0].x == 550  # copy, not an unvalidated edit of the original plan


def test_repack_recovers_when_full_span_prevents_rigid_translation():
    packages = [package("A", 1, 40, l=100, w=100, h=50), package("B", 1, 40, l=100, w=100, h=50),
                package("C", 2, 5, l=100, w=100, h=50), package("D", 3, 5, l=100, w=100, h=50)]
    request = problem(packages, vehicle=vehicle(door_w=240, door_h=250))
    initial = [placement(packages[0], 520, 0), placement(packages[1], 520, 120),
               placement(packages[2], 250, 0), placement(packages[3], 0, 0)]
    metadata = {p.id: p for p in packages}
    assert validate_plan(initial, request.vehicle, metadata, time.monotonic() + 3, balance=False) == []
    assert not ConstraintEngine.check_cog(initial, request.vehicle, metadata).passed
    repaired = LoadBalanceSearch(request, time.monotonic() + 4).improve(initial, {})
    assert {p.package_id for p in repaired} == {p.id for p in packages}
    assert validate_plan(repaired, request.vehicle, metadata, time.monotonic() + 3) == []
    assert any(p.x != initial[i].x for i, p in enumerate(repaired))


@pytest.mark.parametrize("heavy_stop", [1, 2, 3])
def test_one_stop_holds_most_mass_and_all_stops_unload_safely(heavy_stop):
    packages = [package(f"{s}-{i}", s, 80 if s == heavy_stop else 2) for s in (1, 2, 3) for i in range(3)]
    request = problem(packages)
    result = OptimizationClient().solve_in_process(request)
    assert len(result.placements) == 9
    assert_safe(result, request)


def test_synthetic_eighteen_packages_with_uneven_mass_is_safe():
    # Only the five masses are from the report. All dimensions, other masses, stops and axle settings are synthetic.
    packages = [package(f"heavy-{i}", 1, weight) for i, weight in enumerate([9, 8, 7, 6.5, 4])]
    packages += [package(f"light-{i}", 2 if i < 6 else 3, 1) for i in range(13)]
    request = problem(packages)
    result = OptimizationClient().solve_in_process(request)
    assert len(result.placements) == 18
    assert_safe(result, request)


def test_axle_limits_steer_cog_instead_of_always_targeting_geometric_center():
    request = problem([package("A", weight=1000, l=100, w=100)],
                      vehicle=vehicle(inner_l=600, front_axle_x=0, rear_axle_x=400,
                                      max_cog_offset_ratio=0.3, empty_front_axle_load_kg=2000,
                                      empty_rear_axle_load_kg=2000, front_axle_limit_kg=2600, rear_axle_limit_kg=2500))
    result = OptimizationClient().solve_in_process(request)
    assert len(result.placements) == 1
    assert 160 <= result.metrics.cog_x <= 200.001
    assert result.metrics.rear_axle_load <= 2500
    assert_safe(result, request)


def test_balanced_cog_never_bypasses_unload_failure(monkeypatch):
    request = problem([package("early", 1, l=100, w=240, h=50), package("late", 2, l=100, w=240, h=50)],
                      vehicle=vehicle(inner_h=50, door_w=240, door_h=50))
    blocked = [placement(request.packages[0], 100, 0), placement(request.packages[1], 300, 0)]
    metadata = {p.id: p for p in request.packages}
    assert ConstraintEngine.check_cog(blocked, request.vehicle, metadata).passed
    assert not UnloadFeasibilityValidator.validate(blocked, metadata, request.vehicle).passed
    monkeypatch.setattr(LoadBalanceSearch, "improve", lambda self, initial, zones: blocked)
    result = OptimizationClient().solve_in_process(request)
    assert result.placements == []
    assert any("MULTI_DROP_LIFO_VIOLATION" in u.violated_constraints for u in result.unplaced)
    assert_safe(result, request)


def test_genuinely_impossible_axle_capacity_keeps_fail_safe():
    request = problem([package("A", weight=1000)], vehicle=vehicle(
        front_axle_limit_kg=1500, rear_axle_limit_kg=1800))  # no capacity beyond the empty truck
    result = OptimizationClient().solve_in_process(request)
    assert result.placements == []
    assert any("AXLE_LOAD_EXCEEDED" in u.violated_constraints for u in result.unplaced)
    assert_safe(result, request)


def test_cargo_counterbalance_can_reduce_an_empty_axle_overload():
    request = problem([package("counterweight", weight=500)], vehicle=vehicle(
        max_cog_offset_ratio=0.4, empty_front_axle_load_kg=2200, empty_rear_axle_load_kg=1000,
        front_axle_limit_kg=2000, rear_axle_limit_kg=5000))
    # The existing static model permits the rear overhang to unload the front axle. Check the loaded plan, not just tare.
    result = OptimizationClient().solve_in_process(request)
    assert len(result.placements) == 1
    assert result.metrics.front_axle_load <= 2000
    assert_safe(result, request)


def test_duplicate_package_or_stop_identity_is_rejected():
    with pytest.raises(ValueError, match="Duplicate package"):
        OptimizationClient().solve_in_process(problem([package("A"), package("A")]))
    request = problem([package("A")])
    request.stops.append(StopData(id="another", sequence=1))
    with pytest.raises(ValueError, match="unique"):
        OptimizationClient().solve_in_process(request)


@pytest.mark.parametrize("seed", range(4))
def test_random_small_loads_never_duplicate_lose_or_accept_unsafe_packages(seed):
    rng = random.Random(seed)
    request = problem([package(i, i % 3 + 1, rng.uniform(1, 20), l=rng.choice([30, 50, 70]),
                               w=rng.choice([25, 40]), h=30, fragile=(i == 0)) for i in range(8)])
    assert_safe(OptimizationClient().solve_in_process(request), request)


def test_budget_is_cooperative_bounded_and_partition_survives_timeout(monkeypatch):
    class Clock:
        tick = 0.0
        def __call__(self):
            self.tick += 0.01
            return self.tick
    request = problem([package(i, i % 3 + 1) for i in range(60)], time_limit_sec=1)
    monkeypatch.setattr("app.service.optimize.optimization_client.time.monotonic", Clock())
    started = time.perf_counter()
    result = OptimizationClient().solve_in_process(request)
    assert time.perf_counter() - started < 2
    assert_partition(result, request)
    assert any("TIME_LIMIT" in code for u in result.unplaced for code in u.violated_constraints)


def test_unload_validation_checks_absolute_deadline():
    p = package("A")
    result = UnloadFeasibilityValidator.validate([placement(p, 290)], {p.id: p}, vehicle(), deadline=time.monotonic() - 1)
    assert not result.passed
    assert result.violation_code == "UNLOAD_TIME_LIMIT_REACHED"


def test_overweight_reason_is_compatible_with_existing_postgresql_enum():
    request = problem([package("A", weight=6000)])
    result = OptimizationClient().solve_in_process(request)
    assert result.placements == []
    assert result.unplaced[0].reason == "OVER_WEIGHT"
    assert_safe(result, request)


def test_full_revalidation_rejects_fragile_support_and_forged_rotation():
    base = package("base", l=100, w=100, h=50, fragile=True)
    top = package("top")
    request = problem([base, top])
    metadata = {p.id: p for p in request.packages}
    stacked = [placement(base, 260, 70), placement(top, 280, 100, 50)]
    assert "FRAGILITY_VIOLATION" in validate_plan(stacked, request.vehicle, metadata, time.monotonic() + 2)
    forged = placement(base, 260, 70).model_copy(update={"rotation_type": 1})
    assert validate_plan([forged], request.vehicle, metadata, time.monotonic() + 2) == ["ORIENTATION_NOT_ALLOWED"]


def test_logs_include_stop_mass_volume_zones_and_improvement_reason(caplog):
    caplog.set_level(logging.INFO)
    request = problem([package("A", 1, 90), package("B", 2, 1)])
    OptimizationClient().solve_in_process(request)
    for marker in ("[INPUT CHECK]", "[ZONE]", "ExpectedCOG_X", "Weight=", "Volume=", "[INITIAL PLAN]",
                   "[IMPROVEMENT]", "Reason=", "[FINAL VALIDATION]", "[OPTIMIZATION SUMMARY]"):
        assert marker in caplog.text


def partial_eighteen_fixture():
    """Synthetic control case: 18 invalid initial placements, safe 17-package trial."""
    packages = [package(i, 1, 1, l=20, w=20, h=20) for i in range(17)]
    packages.append(package("heavy", 1, 500, l=20, w=20, h=20))
    request = problem(packages)
    safe = [placement(p, 240 + (i % 6) * 20, 90 + (i // 6) * 20) for i, p in enumerate(packages[:17])]
    initial = [p.model_copy(deep=True) for p in safe]
    initial[0].x, initial[0].y = 0, 0
    initial[1].x, initial[1].y = 600, 0
    initial.append(placement(packages[-1], 550, 180))
    return request, initial, safe


def test_invalid_initial_eighteen_accepts_valid_seventeen_as_partial(monkeypatch):
    from app.service.optimize.engine_exception_handler import EngineExceptionHandler
    request, initial, safe = partial_eighteen_fixture()
    metadata = {p.id: p for p in request.packages}
    assert validate_plan(safe, request.vehicle, metadata, time.monotonic() + 3) == []
    assert "CENTER_OF_GRAVITY_OUT_OF_RANGE" in validate_plan(initial, request.vehicle, metadata, time.monotonic() + 3)
    improve = LoadBalanceSearch.improve
    monkeypatch.setattr(LoadBalanceSearch, "_repack", lambda *args: safe)
    monkeypatch.setattr(LoadBalanceSearch, "improve", lambda self, ignored, zones: improve(self, initial, zones))
    result = OptimizationClient().solve_in_process(request)
    assert len(result.placements) == 17
    assert [p.package_id for p in result.unplaced] == ["heavy"]
    assert EngineExceptionHandler.classify_result(None, result).value == "PARTIAL"
    assert_safe(result, request)


def test_valid_initial_partial_survives_unsuccessful_improvement(monkeypatch):
    request = problem([package("A"), package("B")])
    initial = [placement(request.packages[0], 290, 105)]
    monkeypatch.setattr(LoadBalanceSearch, "_repack", lambda *args: [])
    search = LoadBalanceSearch(request, time.monotonic() + 3)
    result = search.improve(initial, {})
    assert [p.package_id for p in result] == ["A"]
    assert validate_plan(result, request.vehicle, search.packages, time.monotonic() + 3) == []


def test_timeout_preserves_validated_plan_through_final_response(monkeypatch):
    class Clock:
        now = 0.0
        def __call__(self):
            return self.now
    clock = Clock()
    request = problem([package("A"), package("B")], time_limit_sec=10)
    safe = [placement(request.packages[0], 290, 105)]
    improve = LoadBalanceSearch.improve
    def repack(self, *args):
        clock.now = 100.0
        return [placement(request.packages[1], 550)]  # Unvalidated: must never replace A.
    def improve_until_timeout(self, ignored, zones):
        result = improve(self, safe, zones)
        clock.now = 100.0
        return result
    monkeypatch.setattr("app.service.optimize.optimization_client.time.monotonic", clock)
    monkeypatch.setattr(LoadBalanceSearch, "_repack", repack)
    monkeypatch.setattr(LoadBalanceSearch, "improve", improve_until_timeout)
    result = OptimizationClient().solve_in_process(request)
    assert [p.package_id for p in result.placements] == ["A"]
    assert [p.package_id for p in result.unplaced] == ["B"]
    assert_safe(result, request)


def test_timeout_during_trial_validation_keeps_previous_incumbent(monkeypatch):
    request = problem([package("A"), package("B")])
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    assert search._consider([placement(request.packages[0], 290, 105)])
    real_validate = validate_plan
    monkeypatch.setattr("app.service.optimize.load_balance_search.validate_plan",
                        lambda *args, **kwargs: ["UNLOAD_TIME_LIMIT_REACHED"])
    assert not search._consider([placement(request.packages[1], 290, 105)])
    result = search.best_plan_copy()
    assert [p.package_id for p in result] == ["A"]
    assert real_validate(result, request.vehicle, search.packages, time.monotonic() + 5) == []


def test_ranking_prioritizes_count_then_volume_then_balance():
    packages = [package("small", l=20, w=20), package("large", l=60, w=40), package("extra", l=20, w=20)]
    request = problem(packages)
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    assert search._consider([placement(packages[0], 300, 110)])
    assert search._consider([placement(packages[1], 240, 100)])
    assert search.best_plan[0].package_id == "large"
    assert search._consider([placement(packages[1], 280, 100)])
    assert search.best_plan[0].x == 280
    assert search._consider([placement(packages[0], 280, 110), placement(packages[2], 320, 110)])
    assert len(search.best_plan) == 2
    assert search._consider([placement(packages[1], 240, 100)])
    assert len(search.best_plan) == 2


def test_validated_snapshot_cannot_be_changed_by_trial_or_response_mutation():
    request = problem([package("A")])
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    initial = [placement(request.packages[0], 290, 105)]
    assert search._consider(initial)
    initial[0].x = 550
    assert search.validated_copy(initial) is None
    result = search.best_plan_copy()
    result[0].x = 500
    assert search.best_plan_copy()[0].x == 290


@pytest.mark.parametrize("violation", ["collision", "stacking", "orientation", "cog", "axle", "unload"])
def test_invalid_trials_never_replace_best_validated_plan(violation):
    height = 25 if violation == "unload" else 50
    v = vehicle(inner_h=height, door_w=240, door_h=height, rear_axle_limit_kg=1850)
    packages = [package("A", 1, l=100, w=240, h=25, max_stack_weight_kg=0),
                package("B", 2, l=100, w=240, h=25), package("heavy", 2, weight=1000)]
    request = problem(packages, vehicle=v)
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    safe = [placement(packages[0], 260, 0)]
    assert search._consider(safe)
    if violation == "collision":
        trial = [*safe, placement(packages[1], 270, 0)]
    elif violation == "stacking":
        trial = [*safe, placement(packages[1], 260, 0, 25)]
    elif violation == "orientation":
        trial = [safe[0].model_copy(update={"rotation_type": 1})]
    elif violation == "cog":
        trial = [placement(packages[0], 500, 0)]
    elif violation == "axle":
        trial = [placement(packages[2], 290, 105)]
    else:
        trial = [placement(packages[0], 100, 0), placement(packages[1], 300, 0)]
    assert not search._consider(trial)
    expected = {"collision": "NON_OVERLAP_VIOLATION", "stacking": "STACKING_WEIGHT_EXCEEDED",
                "orientation": "ORIENTATION_NOT_ALLOWED", "cog": "CENTER_OF_GRAVITY_OUT_OF_RANGE",
                "axle": "AXLE_LOAD_EXCEEDED", "unload": "MULTI_DROP_LIFO_VIOLATION"}
    assert expected[violation] in search.last_violations
    assert search.best_plan_copy() == safe


def test_full_valid_initial_is_unchanged_and_skips_repack(monkeypatch):
    request = problem([package("A"), package("B")])
    initial = [placement(request.packages[0], 260, 105), placement(request.packages[1], 320, 105)]
    def unexpected_repack(*args):
        pytest.fail("An already complete valid plan must not be repacked")
    monkeypatch.setattr(LoadBalanceSearch, "_repack", unexpected_repack)
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    result = search.improve(initial, {})
    assert result == initial
    assert search.validated_copy(result) == initial


def test_repack_retains_validated_prefix_when_next_package_times_out(monkeypatch):
    request = problem([package("A"), package("B")])
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    consider = search._consider
    def remember_then_expire(placements):
        valid = consider(placements)
        if valid:
            search.deadline = time.monotonic() - 1
        return valid
    monkeypatch.setattr(search, "_consider", remember_then_expire)
    search._repack(request.packages, {}, 0)
    assert len(search.best_plan) == 1
    assert validate_plan(search.best_plan_copy(), request.vehicle, search.packages, time.monotonic() + 3) == []


def test_repack_generation_and_validation_are_bounded(monkeypatch):
    import app.service.optimize.load_balance_search as module
    request = problem([package("A", rotation_allowed=True, allowed_rotations=list(range(6)))])
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    search.MAX_GENERATED_CANDIDATES = 24
    search.MAX_CANDIDATES = 3
    generated, validated = [], []
    real_placement = module.PlacementData
    def track_placement(*args, **kwargs):
        if "rotation_type" in kwargs:
            generated.append(kwargs)
        return real_placement(*args, **kwargs)
    def reject(*args, **kwargs):
        validated.append(1)
        return ["TEST_REJECTION"]
    monkeypatch.setattr(module, "PlacementData", track_placement)
    monkeypatch.setattr(module, "validate_plan", reject)
    assert search._repack(request.packages, {}, 0) == []
    assert 0 < len(generated) <= 24
    assert len(validated) <= 3
