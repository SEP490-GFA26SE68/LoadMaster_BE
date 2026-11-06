from __future__ import annotations

from typing import Sequence

from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, VehicleData

# Đơn vị checkpoint: cm, kg. Vector cố định theo Lumo-R1.1 (emb_placed 10, emb_cand 22, emb_glob 14).


def _safe_div(num: float, den: float) -> float:
    return num / den if den > 1e-9 else 0.0


def global_features(
    vehicle: VehicleData,
    placed: Sequence[PlacementData],
    packages_map: dict[str, PackageData],
    n_candidates: int,
    n_remaining: int,
    n_total: int,
    max_placed: int,
    max_candidates: int,
) -> list[float]:
    vol = float(vehicle.inner_l * vehicle.inner_w * vehicle.inner_h)
    payload = float(vehicle.max_payload_kg) if vehicle.max_payload_kg > 0 else 1.0
    placed_w = 0.0
    placed_v = 0.0
    for item in placed:
        pkg = packages_map.get(str(item.package_id))
        if pkg is not None:
            placed_w += float(pkg.weight)
        placed_v += float(item.packed_l * item.packed_w * item.packed_h)
    door_w = float(vehicle.door_w) if vehicle.door_w else float(vehicle.inner_w)
    door_h = float(vehicle.door_h) if vehicle.door_h else float(vehicle.inner_h)
    return [
        _safe_div(float(vehicle.inner_l), 1000.0),
        _safe_div(float(vehicle.inner_w), 1000.0),
        _safe_div(float(vehicle.inner_h), 1000.0),
        _safe_div(payload, 10000.0),
        _safe_div(door_w, float(vehicle.inner_w)),
        _safe_div(door_h, float(vehicle.inner_h)),
        _safe_div(float(len(placed)), float(max_placed)),
        _safe_div(float(n_candidates), float(max_candidates)),
        _safe_div(placed_w, payload),
        _safe_div(placed_v, vol),
        _safe_div(float(n_remaining), float(max(n_total, 1))),
        _safe_div(payload - placed_w, payload),
        _safe_div(vol - placed_v, vol),
        _safe_div(placed_v, vol),
    ]


def placed_features(item: PlacementData, vehicle: VehicleData, pkg: PackageData | None) -> list[float]:
    payload = float(vehicle.max_payload_kg) if vehicle.max_payload_kg > 0 else 1.0
    weight = float(pkg.weight) if pkg is not None else 0.0
    rot = float(item.rotation_type) if isinstance(item.rotation_type, (int, float)) else 0.0
    stop = 0.0
    fragile = 0.0
    if pkg is not None:
        stop = float(pkg.stop_index)
        fragile = 1.0 if pkg.fragile else 0.0
    return [
        _safe_div(float(item.x), float(vehicle.inner_l)),
        _safe_div(float(item.y), float(vehicle.inner_w)),
        _safe_div(float(item.z), float(vehicle.inner_h)),
        _safe_div(float(item.packed_l), float(vehicle.inner_l)),
        _safe_div(float(item.packed_w), float(vehicle.inner_w)),
        _safe_div(float(item.packed_h), float(vehicle.inner_h)),
        _safe_div(weight, payload),
        _safe_div(rot, 5.0),
        min(stop / 20.0, 1.0),
        fragile,
    ]


def candidate_features(
    item: PlacementData,
    vehicle: VehicleData,
    pkg: PackageData,
    zone_start: float,
    zone_end: float,
    valid: bool,
) -> list[float]:
    payload = float(vehicle.max_payload_kg) if vehicle.max_payload_kg > 0 else 1.0
    rot = int(item.rotation_type) if isinstance(item.rotation_type, (int, float)) else 0
    onehot = [0.0] * 6
    if 0 <= rot < 6:
        onehot[rot] = 1.0
    in_zone = 1.0 if (float(item.x) + 1e-6 >= zone_start and float(item.x) + float(item.packed_l) <= zone_end + 1e-4) else 0.0
    on_floor = 1.0 if float(item.z) <= 1e-4 else 0.0
    vol = float(vehicle.inner_l * vehicle.inner_w * vehicle.inner_h)
    packed_vol = float(item.packed_l * item.packed_w * item.packed_h)
    return [
        _safe_div(float(item.x), float(vehicle.inner_l)),
        _safe_div(float(item.y), float(vehicle.inner_w)),
        _safe_div(float(item.z), float(vehicle.inner_h)),
        _safe_div(float(item.packed_l), float(vehicle.inner_l)),
        _safe_div(float(item.packed_w), float(vehicle.inner_w)),
        _safe_div(float(item.packed_h), float(vehicle.inner_h)),
        _safe_div(float(pkg.weight), payload),
        *onehot,
        in_zone,
        on_floor,
        1.0 if valid else 0.0,
        _safe_div(float(item.x) + float(item.packed_l) / 2.0 - float(vehicle.inner_l) / 2.0, float(vehicle.inner_l)),
        _safe_div(float(item.y) + float(item.packed_w) / 2.0 - float(vehicle.inner_w) / 2.0, float(vehicle.inner_w)),
        _safe_div(float(item.z) + float(item.packed_h) / 2.0 - float(vehicle.inner_h) / 2.0, float(vehicle.inner_h)),
        min(float(pkg.stop_index) / 20.0, 1.0),
        _safe_div(packed_vol, vol),
        0.0 if in_zone else 1.0,
    ]
