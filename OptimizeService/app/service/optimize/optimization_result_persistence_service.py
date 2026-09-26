from __future__ import annotations

import logging
from typing import Optional, Union, List, Any
from uuid import UUID
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.optimization.unplaced_package import UnplacedPackage
from app.entity.optimization.center_of_gravity import CenterOfGravity
from app.repository.optimization.load_plan_repository import LoadPlanRepository
from app.repository.optimization.package_placement_repository import PackagePlacementRepository
from app.repository.optimization.unplaced_package_repository import UnplacedPackageRepository
from app.repository.optimization.center_of_gravity_repository import CenterOfGravityRepository
from app.service.optimize.optimization_job_service import OptimizationJobService

logger = logging.getLogger(__name__)


class OptimizationResultPersistenceService:
    """
    Service lưu kết quả tính toán tối ưu vào database PostgreSQL (S3-09).
    Lưu LoadPlan, PackagePlacements, UnplacedPackages, CenterOfGravity trong 1 transaction an toàn.
    """

    def __init__(
        self,
        job_service: Optional[OptimizationJobService] = None,
        load_plan_repository: Optional[LoadPlanRepository] = None,
        package_placement_repository: Optional[PackagePlacementRepository] = None,
        unplaced_package_repository: Optional[UnplacedPackageRepository] = None,
        center_of_gravity_repository: Optional[CenterOfGravityRepository] = None,
    ):
        self.job_service = job_service or OptimizationJobService()
        self.load_plan_repository = load_plan_repository or LoadPlanRepository()
        self.package_placement_repository = package_placement_repository or PackagePlacementRepository()
        self.unplaced_package_repository = unplaced_package_repository or UnplacedPackageRepository()
        self.center_of_gravity_repository = center_of_gravity_repository or CenterOfGravityRepository()

    def save(
        self,
        job_uuid: Optional[Union[str, UUID, int]] = None,
        result: Optional[EngineOptimizationResponse] = None,
        db: Optional[Session] = None,
        plan_name: Optional[str] = None,
        job_id: Optional[Union[int, str, UUID]] = None,
        cog_data: Optional[dict[str, Any]] = None,
    ) -> LoadPlan:
        """
        Lưu kết quả xếp hàng từ EngineOptimizationResponse:
          1. Tìm OptimizationJob tương ứng
          2. Thực hiện trong transaction / savepoint
          3. Insert LoadPlan
          4. Insert danh sách PackagePlacement
          5. Insert danh sách UnplacedPackage
          6. Upsert CenterOfGravity (nếu có)
          7. Cập nhật OptimizationJob status và execution_time_ms
        """
        target_id = job_id if job_id is not None else job_uuid
        if target_id is None:
            raise ValueError("Either job_id or job_uuid must be provided")

        target_id_str = str(target_id)
        job = self.job_service.get_job(target_id, db)

        vol_util = 0.0
        wt_util = 0.0
        packed_count = len(result.placements) if (result and result.placements) else 0

        if result and result.metrics:
            if result.metrics.volume_utilization is not None:
                vol_util = float(result.metrics.volume_utilization)
            if result.metrics.weight_utilization is not None:
                wt_util = float(result.metrics.weight_utilization)
            if result.metrics.packed_count is not None:
                packed_count = result.metrics.packed_count

        resolved_plan_name = plan_name or f"Plan_{str(job.job_uuid)[:8]}"

        savepoint = db.begin_nested()
        try:
            plan = LoadPlan(
                job_id=job.id,
                plan_name=resolved_plan_name,
                packed_items_count=packed_count,
                volume_utilization=vol_util,
                weight_utilization=wt_util,
                approved=False,
                version=1,
            )
            self.load_plan_repository.create(plan, db)

            # Placements
            placement_entities: List[PackagePlacement] = []
            if result and result.placements:
                for p in result.placements:
                    pkg_id_val = None
                    if p.package_id is not None:
                        if isinstance(p.package_id, int):
                            pkg_id_val = p.package_id
                        elif isinstance(p.package_id, str) and p.package_id.isdigit():
                            pkg_id_val = int(p.package_id)

                    seq = getattr(p, "loading_sequence", None) or getattr(p, "step_sequence", 1)

                    placement_entities.append(
                        PackagePlacement(
                            load_plan_id=plan.id,
                            package_id=pkg_id_val,
                            pos_x=p.x,
                            pos_y=p.y,
                            pos_z=p.z,
                            packed_length=getattr(p, "packed_l", 0.0),
                            packed_width=getattr(p, "packed_w", 0.0),
                            packed_height=getattr(p, "packed_h", 0.0),
                            rotation_type=getattr(p, "rotation_type", 0) or 0,
                            step_sequence=seq,
                            pinned=False,
                        )
                    )
                self.package_placement_repository.create_all(placement_entities, db)

            # Unplaced
            unplaced_entities: List[UnplacedPackage] = []
            if result and result.unplaced:
                for u in result.unplaced:
                    pkg_id_val = None
                    if u.package_id is not None:
                        if isinstance(u.package_id, int):
                            pkg_id_val = u.package_id
                        elif isinstance(u.package_id, str) and u.package_id.isdigit():
                            pkg_id_val = int(u.package_id)

                    reason = getattr(u, "reason_code", None) or getattr(u, "reason", None) or "OUT_OF_SPACE"

                    unplaced_entities.append(
                        UnplacedPackage(
                            load_plan_id=plan.id,
                            package_id=pkg_id_val,
                            rejection_reason=reason,
                        )
                    )
                self.unplaced_package_repository.create_all(unplaced_entities, db)

            # CenterOfGravity (nếu có cog_data)
            if cog_data is not None:
                cog = CenterOfGravity(
                    load_plan_id=plan.id,
                    cog_x=cog_data.get("cog_x"),
                    cog_y=cog_data.get("cog_y"),
                    cog_z=cog_data.get("cog_z"),
                    front_axle_load_kg=cog_data.get("front_axle_load_kg"),
                    rear_axle_load_kg=cog_data.get("rear_axle_load_kg"),
                    is_balanced=cog_data.get("is_balanced", True),
                )
                self.center_of_gravity_repository.upsert(cog, db)

            # Cập nhật OptimizationJob status và execution_time_ms
            if result and result.metrics and result.metrics.computation_ms is not None:
                job.execution_time_ms = result.metrics.computation_ms

            if job.status in (OptimizationJobStatus.RUNNING.value, OptimizationJobStatus.PENDING.value):
                if result and not result.placements:
                    job.status = OptimizationJobStatus.NO_SOLUTION.value
                elif result and result.unplaced:
                    job.status = OptimizationJobStatus.PARTIAL.value
                else:
                    job.status = OptimizationJobStatus.COMPLETED.value
            db.flush()

            savepoint.commit()
            logger.info(f"Successfully persisted LoadPlan {plan.id} for job {target_id_str}")
            return plan

        except Exception as exc:
            savepoint.rollback()
            logger.error(f"Failed to persist optimization result for job {target_id_str}: {exc}")
            raise exc
