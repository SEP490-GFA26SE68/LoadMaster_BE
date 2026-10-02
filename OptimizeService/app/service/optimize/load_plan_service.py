from typing import List, Optional, Union
from sqlalchemy.orm import Session

from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.package_placement_response import PackagePlacementResponse
from app.entity.common.audit_log import AuditLog
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.repository.optimization.load_plan_repository import LoadPlanRepository
from app.repository.optimization.optimization_job_repository import OptimizationJobRepository
from app.repository.optimization.package_placement_repository import PackagePlacementRepository


class LoadPlanService:
    def __init__(
        self,
        job_repository: Optional[OptimizationJobRepository] = None,
        load_plan_repository: Optional[LoadPlanRepository] = None,
        package_placement_repository: Optional[PackagePlacementRepository] = None,
    ):
        self.job_repository = job_repository or OptimizationJobRepository()
        self.load_plan_repository = load_plan_repository or LoadPlanRepository()
        self.package_placement_repository = package_placement_repository or PackagePlacementRepository()

    def get_plans_for_job(self, job_uuid: str, db: Session) -> List[LoadPlanResponse]:
        """
        Lấy danh sách LoadPlan kèm placements của job.
        Kiểm tra job có tồn tại, nếu không có ném OPTIMIZATION_JOB_NOT_FOUND.
        """
        if db is None:
            return []

        job = self.job_repository.find_by_job_uuid(job_uuid, db)
        if not job and job_uuid.isdigit():
            job = self.job_repository.find_by_id(int(job_uuid), db)
        if not job:
            raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)

        plans = self.load_plan_repository.find_by_job_id(job.id, db)
        results: List[LoadPlanResponse] = []

        for p in plans:
            results.append(self.to_response(p, db))

        return results

    def approve_plan(
        self,
        plan_id: Union[int, str],
        current_user_id: Optional[Union[int, str]],
        db: Session,
    ) -> LoadPlan:
        """
        Duyệt kế hoạch xếp hàng (S3-11).
        Quy trình xác thực:
          1. Kế hoạch tồn tại trong DB (LOAD_PLAN_NOT_FOUND)
          2. Kế hoạch chưa được duyệt (PLAN_ALREADY_APPROVED)
          3. Kế hoạch có ít nhất 1 kiện hàng đã xếp (PLAN_HAS_NO_PLACEMENTS)
          4. Tuân thủ nguyên tắc LIFO (PLAN_LIFO_INVALID)
          5. Đánh dấu approved=True, approved_by_id=user_id
          6. Ghi AuditLog action_type="PLAN_APPROVED"
        """
        if db is None:
            raise AppException(ErrorCode.LOAD_PLAN_NOT_FOUND)

        plan = None
        if isinstance(plan_id, int) or (isinstance(plan_id, str) and plan_id.isdigit()):
            plan = self.load_plan_repository.find_by_id(int(plan_id), db)

        if not plan:
            raise AppException(ErrorCode.LOAD_PLAN_NOT_FOUND)

        if plan.approved:
            raise AppException(ErrorCode.PLAN_ALREADY_APPROVED)

        placements = self.package_placement_repository.find_by_plan_id(plan.id, db)
        if not placements or len(placements) == 0:
            raise AppException(ErrorCode.PLAN_HAS_NO_PLACEMENTS)

        if not self._validate_lifo(placements):
            raise AppException(ErrorCode.PLAN_LIFO_INVALID)

        user_id_int = None
        if current_user_id is not None and str(current_user_id).isdigit():
            user_id_int = int(current_user_id)

        plan.approved = True
        plan.approved_by_id = user_id_int
        self.load_plan_repository.update(plan, db)

        audit = AuditLog(
            action_type="PLAN_APPROVED",
            entity_name="LOAD_PLAN",
            entity_id=str(plan.id),
            user_id=user_id_int,
            new_values={"approved": True, "approved_by": user_id_int},
        )
        db.add(audit)
        db.flush()

        return plan

    @staticmethod
    def _validate_lifo(placements: List[PackagePlacement]) -> bool:
        """
        Kiểm tra tính hợp lệ của thứ tự xếp dỡ LIFO.
        Các step_sequence phải duy nhất và không bị duplicate.
        """
        sequences = [p.step_sequence for p in placements if p.step_sequence is not None]
        return len(sequences) == len(placements) and len(sequences) == len(set(sequences))

    def to_response(self, plan: LoadPlan, db: Session) -> LoadPlanResponse:
        """
        Chuyển đổi LoadPlan entity sang LoadPlanResponse DTO kèm danh sách placements và metrics 3D (S5b-07).
        """
        placements = self.package_placement_repository.find_by_plan_id(plan.id, db)

        # Lookup stop names if stop_zone_id is present
        stops_map = {}
        if db is not None:
            stop_ids = {item.stop_zone_id for item in placements if getattr(item, "stop_zone_id", None) is not None}
            if stop_ids:
                from app.entity.trip_model import DeliveryStop
                stops = db.query(DeliveryStop).filter(DeliveryStop.id.in_(stop_ids)).all()
                stops_map = {s.id: s.stop_name for s in stops}

        placement_dtos = [
            PackagePlacementResponse(
                id=item.id,
                package_id=str(item.package_id) if item.package_id is not None else "",
                step_sequence=item.step_sequence or 1,
                pos_x=float(item.pos_x or 0),
                pos_y=float(item.pos_y or 0),
                pos_z=float(item.pos_z or 0),
                dim_x=float(item.packed_length or 0),
                dim_y=float(item.packed_width or 0),
                dim_z=float(item.packed_height or 0),
                rotation_type=item.rotation_type or 0,
                stop_zone_id=getattr(item, "stop_zone_id", None),
                stop_zone_name=stops_map.get(getattr(item, "stop_zone_id", None)),
            )
            for item in placements
        ]

        return LoadPlanResponse(
            id=plan.id,
            plan_name=plan.plan_name,
            packed_items_count=plan.packed_items_count,
            volume_utilization=float(plan.volume_utilization) if plan.volume_utilization is not None else 0.0,
            weight_utilization=float(plan.weight_utilization) if plan.weight_utilization is not None else 0.0,
            approved=bool(plan.approved),
            approved_by_id=plan.approved_by_id,
            cog_x=float(plan.cog_x) if getattr(plan, "cog_x", None) is not None else None,
            cog_y=float(plan.cog_y) if getattr(plan, "cog_y", None) is not None else None,
            cog_z=float(plan.cog_z) if getattr(plan, "cog_z", None) is not None else None,
            front_axle_load=float(plan.front_axle_load) if getattr(plan, "front_axle_load", None) is not None else None,
            rear_axle_load=float(plan.rear_axle_load) if getattr(plan, "rear_axle_load", None) is not None else None,
            rehandling_count=int(plan.rehandling_count or 0) if getattr(plan, "rehandling_count", None) is not None else 0,
            placements=placement_dtos,
        )

    def compare_plans(self, plan_id_1: int, plan_id_2: int, db: Session):
        """
        So sánh hai LoadPlan (S5b-07):
          - COG diff, axle load diff, rehandling diff
          - Volume utilization diff, weight utilization diff
          - Deadline feasibility của các stop trong trip
        """
        from app.dto.response.load_plan_comparison_response import (
            LoadPlanComparisonResponse,
            MetricDiff,
            StopDeadlineFeasibility,
            DeadlineFeasibilityResponse,
        )
        from app.entity.trip_model import DeliveryStop
        from app.entity.optimization.optimization_job import OptimizationJob

        plan1 = self.load_plan_repository.find_by_id(plan_id_1, db)
        plan2 = self.load_plan_repository.find_by_id(plan_id_2, db)

        if not plan1 or not plan2:
            raise AppException(ErrorCode.LOAD_PLAN_NOT_FOUND)

        def make_diff(val1: Optional[float], val2: Optional[float], round_digits: int = 4) -> MetricDiff:
            v1 = float(val1) if val1 is not None else None
            v2 = float(val2) if val2 is not None else None
            d = round(v2 - v1, round_digits) if (v1 is not None and v2 is not None) else None
            return MetricDiff(plan1=v1, plan2=v2, diff=d)

        # Utilization & Counts
        vol_diff = make_diff(plan1.volume_utilization, plan2.volume_utilization, 4)
        wt_diff = make_diff(plan1.weight_utilization, plan2.weight_utilization, 4)
        count_diff = make_diff(plan1.packed_items_count, plan2.packed_items_count, 0)

        # 3D Metrics
        cog_x_diff = make_diff(plan1.cog_x, plan2.cog_x, 3)
        cog_y_diff = make_diff(plan1.cog_y, plan2.cog_y, 3)
        cog_z_diff = make_diff(plan1.cog_z, plan2.cog_z, 3)
        front_axle_diff = make_diff(plan1.front_axle_load, plan2.front_axle_load, 2)
        rear_axle_diff = make_diff(plan1.rear_axle_load, plan2.rear_axle_load, 2)
        rehandling_diff = make_diff(plan1.rehandling_count, plan2.rehandling_count, 0)

        # Deadline feasibility check for stops
        stops: List[DeliveryStop] = []
        job = db.query(OptimizationJob).filter(OptimizationJob.id == plan1.job_id).first()
        if job and job.trip_id:
            stops = (
                db.query(DeliveryStop)
                .filter(DeliveryStop.trip_id == str(job.trip_id))
                .order_by(DeliveryStop.stop_sequence)
                .all()
            )

        stop_feasibility_list: List[StopDeadlineFeasibility] = []
        overall_feasible = True

        for s in stops:
            arr = getattr(s, "planned_arrival", None)
            dead = getattr(s, "deadline", None)
            is_f = True
            delay_min = 0.0

            if arr is not None and dead is not None:
                if arr > dead:
                    is_f = False
                    overall_feasible = False
                    delay_min = round((arr - dead).total_seconds() / 60.0, 1)

            stop_feasibility_list.append(
                StopDeadlineFeasibility(
                    stop_id=s.id,
                    stop_name=s.stop_name,
                    planned_arrival=arr.isoformat() if arr else None,
                    deadline=dead.isoformat() if dead else None,
                    is_feasible=is_f,
                    delay_minutes=delay_min,
                )
            )

        df_response = DeadlineFeasibilityResponse(
            is_feasible=overall_feasible,
            stops=stop_feasibility_list,
        )

        return LoadPlanComparisonResponse(
            plan1_id=plan1.id,
            plan2_id=plan2.id,
            plan1_name=plan1.plan_name,
            plan2_name=plan2.plan_name,
            volume_utilization=vol_diff,
            weight_utilization=wt_diff,
            packed_items_count=count_diff,
            cog_x=cog_x_diff,
            cog_y=cog_y_diff,
            cog_z=cog_z_diff,
            front_axle_load=front_axle_diff,
            rear_axle_load=rear_axle_diff,
            rehandling_count=rehandling_diff,
            deadline_feasibility=df_response,
        )
