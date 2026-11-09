# Chép từ LoadMaster_AI/lmai/problem.py (E:/SEP490/LoadMaster_AI) để OptimizeService chạy suy luận Lumo — không sửa nội dung, sửa ở dự án gốc rồi chép lại.
"""Dữ liệu bài toán, cùng định dạng ProblemRequest / EngineOptimizationResponse của OptimizeService.

Quy ước (giống OptimizeService):
- Đơn vị cm, kg.
- X = 0 ở vách trước thùng (sau cabin), X tăng dần về cửa sau (X = inner_l).
- Y theo chiều rộng, Z theo chiều cao.
- stop_index nhỏ hơn = giao trước → nằm gần cửa.
- rotation_type 0..5 = LWH, WLH, LHW, WHL, HLW, HWL (kích thước sau xoay = packed_l, packed_w, packed_h).
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import List, Optional, Tuple

# chỉ số (l, w, h) của kiện ứng với từng rotation_type
ROTATIONS = {
    0: (0, 1, 2),  # LWH
    1: (1, 0, 2),  # WLH
    2: (0, 2, 1),  # LHW
    3: (1, 2, 0),  # WHL
    4: (2, 0, 1),  # HLW
    5: (2, 1, 0),  # HWL
}
ROTATION_NAMES = {0: "LWH", 1: "WLH", 2: "LHW", 3: "WHL", 4: "HLW", 5: "HWL"}
UPRIGHT = (0, 1)


@dataclass
class Vehicle:
    inner_l: float
    inner_w: float
    inner_h: float
    max_payload_kg: float
    door_w: float
    door_h: float
    front_axle_limit_kg: Optional[float] = None
    rear_axle_limit_kg: Optional[float] = None
    front_axle_x: Optional[float] = None
    rear_axle_x: Optional[float] = None
    empty_front_axle_load_kg: Optional[float] = None
    empty_rear_axle_load_kg: Optional[float] = None
    max_cog_offset_ratio: float = 0.15
    name: str = ""

    @property
    def volume(self) -> float:
        return self.inner_l * self.inner_w * self.inner_h

    @property
    def door_y_min(self) -> float:
        # cửa nằm giữa chiều rộng thùng (giả định của UnloadFeasibilityValidator)
        return (self.inner_w - self.door_w) / 2.0

    @property
    def door_y_max(self) -> float:
        return self.door_y_min + self.door_w

    @property
    def has_axles(self) -> bool:
        return None not in (self.front_axle_limit_kg, self.rear_axle_limit_kg, self.front_axle_x,
                            self.rear_axle_x, self.empty_front_axle_load_kg, self.empty_rear_axle_load_kg)


@dataclass
class Package:
    id: str
    l: float
    w: float
    h: float
    weight: float
    stop_index: int = 1
    allowed_rotations: List[int] = field(default_factory=list)
    max_stack_weight_kg: Optional[float] = None  # None = không được đặt gì lên (HC-07 phương án A)
    fragile: bool = False
    rotation_allowed: bool = True

    @property
    def volume(self) -> float:
        return self.l * self.w * self.h

    def rotations(self) -> List[int]:
        """Các hướng xoay hợp lệ theo HC-03 của ConstraintEngine."""
        if not self.rotation_allowed:
            return [0]
        if self.allowed_rotations:
            return sorted({int(r) for r in self.allowed_rotations if int(r) in ROTATIONS})
        return list(ROTATIONS)

    def dims(self, rot: int) -> Tuple[float, float, float]:
        d = (self.l, self.w, self.h)
        a, b, c = ROTATIONS[rot]
        return d[a], d[b], d[c]

    @property
    def stack_limit(self) -> float:
        """Tải tối đa được đặt lên kiện. Dễ vỡ hoặc chưa khai = 0."""
        if self.fragile or self.max_stack_weight_kg is None:
            return 0.0
        return max(0.0, float(self.max_stack_weight_kg))


@dataclass
class Stop:
    id: str
    sequence: int


@dataclass
class Problem:
    vehicle: Vehicle
    packages: List[Package]
    stops: List[Stop] = field(default_factory=list)
    objective: str = "MAX_VOLUME_UTIL"
    time_limit_sec: int = 60
    seed: Optional[int] = None

    @property
    def cargo_volume(self) -> float:
        return sum(p.volume for p in self.packages)

    def stop_sequences(self) -> List[int]:
        if self.stops:
            return sorted({s.sequence for s in self.stops})
        return sorted({p.stop_index for p in self.packages})

    # ---- chuyển đổi với JSON của OptimizeService ----
    @staticmethod
    def from_request(d: dict) -> "Problem":
        v = d["vehicle"]
        veh = Vehicle(**{k: v.get(k) for k in Vehicle.__dataclass_fields__ if k in v})
        if veh.door_w is None:
            veh.door_w = veh.inner_w
        if veh.door_h is None:
            veh.door_h = veh.inner_h
        if v.get("max_cog_offset_ratio") is None:
            veh.max_cog_offset_ratio = 0.15
        pkgs = [Package(id=str(p["id"]), l=float(p["l"]), w=float(p["w"]), h=float(p["h"]), weight=float(p["weight"]),
                        stop_index=int(p.get("stop_index") or 0),  # giữ nguyên như OptimizeService (mặc định 0)
                        allowed_rotations=[int(r) for r in (p.get("allowed_rotations") or [])],
                        max_stack_weight_kg=p.get("max_stack_weight_kg"), fragile=bool(p.get("fragile", False)),
                        rotation_allowed=bool(p.get("rotation_allowed", True))) for p in d["packages"]]
        stops = [Stop(id=str(s["id"]), sequence=int(s["sequence"])) for s in d.get("stops", [])]
        return Problem(veh, pkgs, stops, d.get("objective", "MAX_VOLUME_UTIL"), int(d.get("time_limit_sec", 60)), d.get("seed"))

    def to_request(self) -> dict:
        v = asdict(self.vehicle)
        v.pop("name", None)
        return {
            "vehicle": v,
            "packages": [asdict(p) for p in self.packages],
            "stops": [asdict(s) for s in self.stops],
            "pinned": [],
            "objective": self.objective,
            "time_limit_sec": self.time_limit_sec,
            "seed": self.seed,
        }


@dataclass
class Placement:
    package_id: str
    x: float
    y: float
    z: float
    packed_l: float
    packed_w: float
    packed_h: float
    rotation_type: int = 0
    step_sequence: int = 1
    stop_zone_id: Optional[str] = None
