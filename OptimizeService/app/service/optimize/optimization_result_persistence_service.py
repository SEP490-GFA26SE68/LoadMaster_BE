from __future__ import annotations

import logging
from typing import Optional, Union, List
from uuid import UUID
from sqlalchemy.orm import Session

from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.optimization.unplaced_package import UnplacedPackage
from app.repository.optimization.load_plan_repository import LoadPlanRepository
from app.repository.optimization.package_placement_repository import PackagePlacementRepository
from app.repository.optimization.unplaced_package_repository import UnplacedPackageRepository
from app.service.optimize.optimization_job_service import OptimizationJobService

logger = logging.getLogger(__name__)


class OptimizationResultPersistenceService:
    """
    Service lưu kết quả tính toán tối ưu vào database PostgreSQL (S3-09).
    Lưu LoadPlan, PackagePlacements, UnplacedPackages trong 1 transaction an toàn.
    """

    def __init__(
        self,
        job_service: Optional[OptimizationJobService] = None,
        load_plan_repository: Optional[LoadPlanRepository] = None,
        package_placement_repository: Optional[PackagePlacementRepository] = None,
        unplaced_package_repository: Optional[UnplacedPackageRepository] = None,
    ):
        self.job_service = job_service or OptimizationJobService()
        self.load_plan_repository = load_plan_repository or LoadPlanRepository()
        self.package_placement_repository = package_placement_repository or PackagePlacementRepository()
        self.unplaced_package_repository = unplaced_package_repository or UnplacedPackageRepository()

    def save(
        self,
        job_uuid: Union[str, UUID],
        result: EngineOptimizationResponse,
        db: Session,
        plan_name: Optional[str] = None,
    ) -> LoadPlan:
        """
        Lưu kết quả xếp hàng từ EngineOptimizationResponse trong một savepoint:
          - Kết quả có phương án ứng viên (A · B · C) và ít nhất một phương án xếp được kiện:
            lưu mỗi phương án một LoadPlan cùng job, theo thứ tự A, B, C, kèm mục tiêu và nhãn.
          - Ngược lại (engine ngoài, hoặc cả ba không xếp được kiện nào): một LoadPlan như cũ,
            không nhãn, giữ lý do của các kiện không xếp được.
        Trả LoadPlan đầu tiên đã lưu.
        """
        job = self.job_service.get_job(str(job_uuid), db)
        base_name = plan_name or f"Plan_{str(job.job_uuid)[:8]}"

        candidates = list(result.candidates or [])
        if candidates and any(c.result.placements for c in candidates):
            entries = [
                (c.result, f"{base_name}_{c.label}", c.objective, c.label)
                for c in candidates
            ]
        else:
            entries = [(result, base_name, None, None)]

        savepoint = db.begin_nested()
        try:
            plans: List[LoadPlan] = []
            for index, (plan_result, name, objective, label) in enumerate(entries):
                plans.append(
                    self._write_plan(
                        job,
                        plan_result,
                        db,
                        plan_name=name,
                        objective=objective,
                        candidate_label=label,
                        reuse_empty=index == 0,
                    )
                )
            savepoint.commit()
            logger.info(
                "Successfully persisted %d LoadPlan(s) %s for job %s",
                len(plans),
                [p.id for p in plans],
                job_uuid,
            )
            return plans[0]

        except Exception as exc:
            savepoint.rollback()
            logger.error(f"Failed to persist optimization result for job {job_uuid}: {exc}")
            raise exc

    def _write_plan(
        self,
        job,
        result: EngineOptimizationResponse,
        db: Session,
        plan_name: str,
        objective: Optional[str],
        candidate_label: Optional[str],
        reuse_empty: bool,
    ) -> LoadPlan:
        """Ghi một LoadPlan kèm kiện đã xếp và kiện không xếp được (trong savepoint của save)."""
        logger.info(
            "Optimization persistence: job=%s, plan=%s, placements=%d, unplaced=%d",
            job.job_uuid,
            candidate_label or "-",
            len(result.placements or []),
            len(result.unplaced or []),
        )

        for item in result.unplaced or []:
            logger.info(
                "Unplaced package: package_id=%s, reason=%s, violations=%s",
                item.package_id,
                item.reason,
                item.violated_constraints,
            )

        vol_util = 0.0
        wt_util = 0.0
        packed_count = len(result.placements) if result.placements else 0
        cog_x, cog_y, cog_z = None, None, None
        front_axle_load, rear_axle_load = None, None
        rehandling_count = 0

        if result.metrics:
            if result.metrics.volume_utilization is not None:
                vol_util = float(result.metrics.volume_utilization)
            if result.metrics.weight_utilization is not None:
                wt_util = float(result.metrics.weight_utilization)
            if result.metrics.packed_count is not None:
                packed_count = result.metrics.packed_count
            if result.metrics.cog_x is not None:
                cog_x = float(result.metrics.cog_x)
            if result.metrics.cog_y is not None:
                cog_y = float(result.metrics.cog_y)
            if result.metrics.cog_z is not None:
                cog_z = float(result.metrics.cog_z)
            if result.metrics.front_axle_load is not None:
                front_axle_load = float(result.metrics.front_axle_load)
            if result.metrics.rear_axle_load is not None:
                rear_axle_load = float(result.metrics.rear_axle_load)
            if result.metrics.rehandling_count is not None:
                rehandling_count = int(result.metrics.rehandling_count)

        plan = None

        # Chỉ tái sử dụng khi Job có đúng một LoadPlan rỗng, và chỉ cho plan đầu tiên.
        if reuse_empty:
            existing_plans = self.load_plan_repository.find_by_job_id(job.id, db)
            if len(existing_plans) == 1:
                existing = existing_plans[0]
                existing_placements = self.package_placement_repository.find_by_plan_id(existing.id, db)
                existing_unplaced = self.unplaced_package_repository.find_by_plan_id(existing.id, db)
                if (
                    existing.approved is False
                    and existing.packed_items_count == 0
                    and not existing_placements
                    and not existing_unplaced
                ):
                    plan = existing

        if plan is None:
            plan = LoadPlan(
                job_id=job.id,
                approved=False,
            )
            self.load_plan_repository.create(plan, db)

        # Đồng bộ thông tin với kết quả solver.
        plan.plan_name = plan_name
        plan.objective = objective
        plan.candidate_label = candidate_label
        plan.packed_items_count = packed_count
        plan.volume_utilization = vol_util
        plan.weight_utilization = wt_util
        plan.cog_x = cog_x
        plan.cog_y = cog_y
        plan.cog_z = cog_z
        plan.front_axle_load = front_axle_load
        plan.rear_axle_load = rear_axle_load
        plan.rehandling_count = rehandling_count

        self.load_plan_repository.update(plan, db)

        # Placements
        placement_entities: List[PackagePlacement] = []
        if result.placements:
            for p in result.placements:
                pkg_id_val = None
                if p.package_id is not None:
                    if isinstance(p.package_id, int):
                        pkg_id_val = p.package_id
                    elif isinstance(p.package_id, str) and p.package_id.isdigit():
                        pkg_id_val = int(p.package_id)

                zone_id_val = None
                if p.stop_zone_id is not None:
                    if isinstance(p.stop_zone_id, int):
                        zone_id_val = p.stop_zone_id
                    elif isinstance(p.stop_zone_id, str) and p.stop_zone_id.isdigit():
                        zone_id_val = int(p.stop_zone_id)

                placement_entities.append(
                    PackagePlacement(
                        load_plan_id=plan.id,
                        package_id=pkg_id_val,
                        pos_x=p.x,
                        pos_y=p.y,
                        pos_z=p.z,
                        packed_length=p.packed_l,
                        packed_width=p.packed_w,
                        packed_height=p.packed_h,
                        rotation_type=p.rotation_type or 0,
                        step_sequence=p.step_sequence or 1,
                        pinned=False,
                        stop_zone_id=zone_id_val,
                    )
                )
            self.package_placement_repository.create_all(placement_entities, db)

        # Unplaced
        unplaced_entities: List[UnplacedPackage] = []
        if result.unplaced:
            for u in result.unplaced:
                pkg_id_val = None
                if u.package_id is not None:
                    if isinstance(u.package_id, int):
                        pkg_id_val = u.package_id
                    elif isinstance(u.package_id, str) and u.package_id.isdigit():
                        pkg_id_val = int(u.package_id)

                unplaced_entities.append(
                    UnplacedPackage(
                        load_plan_id=plan.id,
                        package_id=pkg_id_val,
                        rejection_reason=u.reason or "OUT_OF_SPACE",
                    )
                )
            self.unplaced_package_repository.create_all(unplaced_entities, db)

        return plan
