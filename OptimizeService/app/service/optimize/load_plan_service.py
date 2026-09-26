from datetime import datetime
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

        now = datetime.utcnow()
        user_id_int = None
        if current_user_id is not None and str(current_user_id).isdigit():
            user_id_int = int(current_user_id)

        plan.approved = True
        plan.is_approved = True
        plan.approved_by_id = user_id_int
        plan.approved_by_user_id = user_id_int
        plan.approved_at = now
        self.load_plan_repository.update(plan, db)

        audit = AuditLog(
            action_type="PLAN_APPROVED",
            entity_name="LOAD_PLAN",
            entity_id=str(plan.id),
            user_id=user_id_int,
            new_values={
                "approved": True,
                "is_approved": True,
                "approved_by": user_id_int,
                "approved_by_user_id": user_id_int,
                "approved_at": now.isoformat(),
            },
            created_at=now,
        )
        db.add(audit)
        db.flush()

        return plan

    @staticmethod
    def _validate_lifo(placements: List[PackagePlacement]) -> bool:
        """
        Kiểm tra tính hợp lệ của thứ tự xếp dỡ LIFO.
        Các loading_sequence / step_sequence phải duy nhất và không bị duplicate.
        """
        sequences = [
            getattr(p, "loading_sequence", None)
            if getattr(p, "loading_sequence", None) is not None
            else getattr(p, "step_sequence", None)
            for p in placements
        ]
        return len(sequences) == len(placements) and None not in sequences and len(sequences) == len(set(sequences))

    def to_response(self, plan: LoadPlan, db: Session) -> LoadPlanResponse:
        """
        Chuyển đổi LoadPlan entity sang LoadPlanResponse DTO kèm danh sách placements.
        """
        placements = self.package_placement_repository.find_by_plan_id(plan.id, db)
        placement_dtos = [
            PackagePlacementResponse(
                id=item.id,
                load_plan_id=item.load_plan_id,
                package_id=str(item.package_id) if item.package_id is not None else "",
                loading_sequence=getattr(item, "loading_sequence", getattr(item, "step_sequence", 1)),
                step_sequence=getattr(item, "step_sequence", getattr(item, "loading_sequence", 1)),
                pos_x=float(item.pos_x or 0),
                pos_y=float(item.pos_y or 0),
                pos_z=float(item.pos_z or 0),
                dim_x=float(item.packed_length or 0),
                dim_y=float(item.packed_width or 0),
                dim_z=float(item.packed_height or 0),
                rotation_type=item.rotation_type or 0,
            )
            for item in placements
        ]

        return LoadPlanResponse(
            id=plan.id,
            job_id=plan.job_id,
            plan_name=plan.plan_name,
            plan_version=getattr(plan, "plan_version", getattr(plan, "version", 1)),
            packed_items_count=plan.packed_items_count,
            volume_utilization=float(plan.volume_utilization) if plan.volume_utilization is not None else 0.0,
            volume_utilization_percent=float(plan.volume_utilization) if plan.volume_utilization is not None else 0.0,
            weight_utilization=float(plan.weight_utilization) if plan.weight_utilization is not None else 0.0,
            is_approved=bool(plan.approved),
            approved=bool(plan.approved),
            approved_by_id=plan.approved_by_id,
            approved_by_user_id=plan.approved_by_id,
            approved_at=plan.approved_at,
            placements=placement_dtos,
        )

