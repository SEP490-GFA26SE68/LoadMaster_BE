"""M3 (KE-HOACH-TOI-UU; spec ràng buộc vật lý 8.1–8.2): số liệu vật lý mới được đọc từ DB và truyền tới bộ giải.

- σ / ρ: hồ sơ kiện > loại kiện > trống (không mặc định ngầm — Q-D2);
- must_load / priority / packing_group từ hồ sơ; tải sàn / lệch ngang từ loại xe;
- cảnh báo dữ liệu (kiện thiếu σ, kiện không có loại kiện) đi theo bài và được lưu vào job;
- ConstraintEngine CHƯA đọc trường mới: cùng bài, có hay không có số mới, phương án giống hệt.
"""
from types import SimpleNamespace as NS

import pytest

from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner as Runner, save_data_warnings


@pytest.fixture
def trip_db():
    from sqlalchemy import create_engine
    from sqlalchemy.orm import Session
    from app.config.database import Base
    from app.entity.optimization.optimization_job import OptimizationJob
    from app.entity.trip_model import (VehicleType, Vehicle, Trip, DeliveryStop, TransportOrder, PackageType,
                                       CargoPackage, DeliveryRequirement, DeliveryRequirementPackage,
                                       CargoPackagePackingProfile)
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add_all([
            VehicleType(id=1, inner_length=620, inner_width=240, inner_height=250, door_width=210, door_height=210,
                        max_payload_kg=5000, max_cog_offset_ratio=0.15, front_axle_x=100, rear_axle_x=350,
                        front_axle_limit_kg=2200, rear_axle_limit_kg=3300, empty_front_axle_load_kg=1500,
                        empty_rear_axle_load_kg=1800, max_cog_offset_ratio_y=0.1, floor_load_capacity_kg_m2=2500),
            Vehicle(id=1, vehicle_type_id=1, license_plate="SYNTHETIC"), Trip(id=12, vehicle_id=1),
            DeliveryRequirement(id=1, trip_id=12),
            DeliveryStop(id=1, trip_id=12, delivery_requirement_id=1, stop_sequence=1),
            TransportOrder(id=1, delivery_stop_id=1),
            PackageType(id=1, length=40, width=30, height=25, rotation_allowed=True, allow_rotate_x=False,
                        allow_rotate_y=False, allow_rotate_z=True, max_stack_weight_kg=60,
                        top_load_capacity_kg_m2=500, min_support_ratio=0.8),
            PackageType(id=2, length=50, width=50, height=40, rotation_allowed=True, allow_rotate_x=False,
                        allow_rotate_y=False, allow_rotate_z=True, max_stack_weight_kg=60),
            OptimizationJob(id=1, trip_id=12, job_uuid="job-m3"),
        ])
        # 1: σ, ρ của loại kiện; 2: hồ sơ ghi đè σ, ρ, có must_load / priority / packing_group;
        # 3: loại kiện thiếu σ → cảnh báo; 4: không có loại kiện → cảnh báo cả hai.
        rows = [(1, 1, None), (2, 1, dict(top_load_capacity_kg_m2=0, min_support_ratio=0.9, must_load=True, priority=5,
                                          packing_group="G1")), (3, 2, None), (4, None, None)]
        for pid, type_id, prof in rows:
            dims = {} if type_id else dict(actual_length=30, actual_width=30, actual_height=30)
            db.add(CargoPackage(id=pid, order_id=1, package_type_id=type_id, actual_weight_kg=10, **dims))
            db.add(DeliveryRequirementPackage(delivery_requirement_id=1, package_id=pid))
            if prof:
                db.add(CargoPackagePackingProfile(id=pid, package_id=pid, **prof))
        db.commit()
        runner = Runner.__new__(Runner)
        runner.job_service = NS(get_job=lambda *a: NS(trip_id=12, objective="MAX_VOLUME", time_limit_sec=5,
                                                      algorithm_name="EP_DBLF"))
        yield runner, db
    engine.dispose()


def test_profile_beats_type_and_missing_sigma_stays_empty(trip_db):
    runner, db = trip_db
    req = runner.build_problem_request("job-m3", db)
    by = {str(p.id): p for p in req.packages}
    assert (by["1"].top_load_capacity_kg_m2, by["1"].min_support_ratio) == (500.0, 0.8)
    assert (by["2"].top_load_capacity_kg_m2, by["2"].min_support_ratio) == (0.0, 0.9)
    assert (by["2"].must_load, by["2"].priority, by["2"].packing_group) == (True, 5, "G1")
    assert by["1"].must_load is False and by["1"].priority is None
    assert by["3"].top_load_capacity_kg_m2 is None and by["4"].top_load_capacity_kg_m2 is None
    assert by["3"].min_support_ratio is None
    assert req.vehicle.max_cog_offset_ratio_y == 0.1 and req.vehicle.floor_load_capacity_kg_m2 == 2500.0
    assert [(w.code, w.package_ids) for w in req.data_warnings] == [
        ("TOP_LOAD_CAPACITY_MISSING", ["3", "4"]), ("PACKAGE_TYPE_MISSING", ["4"])]
    # Kiện cũ vẫn giữ trường cũ (luật hiện hành chưa đổi).
    assert by["1"].max_stack_weight_kg == 60.0


def test_warnings_survive_json_dispatch_and_are_saved_on_the_job(trip_db):
    from app.dto.optimization.engine.problem_request import ProblemRequest
    from app.entity.optimization.optimization_job import OptimizationJob
    runner, db = trip_db
    req = ProblemRequest.model_validate_json(runner.build_problem_request("job-m3", db).model_dump_json())
    save_data_warnings("job-m3", req, db)
    db.commit()
    job = db.query(OptimizationJob).filter_by(job_uuid="job-m3").one()
    assert job.data_warnings == [{"code": "TOP_LOAD_CAPACITY_MISSING", "package_ids": ["3", "4"]},
                                 {"code": "PACKAGE_TYPE_MISSING", "package_ids": ["4"]}]


def test_solver_ignores_new_fields_for_now(trip_db):
    """M3 không đổi hành vi xếp: bỏ hết số mới → phương án giống hệt."""
    from app.service.optimize.optimization_client import OptimizationClient
    runner, db = trip_db
    req = runner.build_problem_request("job-m3", db)
    plain = req.model_copy(deep=True, update={"data_warnings": []})
    plain.vehicle = plain.vehicle.model_copy(update={"max_cog_offset_ratio_y": None, "floor_load_capacity_kg_m2": None})
    plain.packages = [p.model_copy(update={"top_load_capacity_kg_m2": None, "min_support_ratio": None,
                                           "must_load": False, "priority": None, "packing_group": None})
                      for p in plain.packages]
    a = OptimizationClient().solve_in_process(req, use_lumo_scorer=False)
    b = OptimizationClient().solve_in_process(plain, use_lumo_scorer=False)
    key = lambda r: [(str(p.package_id), p.x, p.y, p.z, p.rotation_type) for p in r.placements]
    assert key(a) == key(b) and len(a.placements) == 4
