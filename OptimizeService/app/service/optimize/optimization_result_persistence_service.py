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
        Lưu kết quả xếp hàng từ EngineOptimizationResponse:
          1. Tìm OptimizationJob tương ứng
          2. Thực hiện trong transaction / savepoint
          3. Insert LoadPlan
          4. Insert danh sách PackagePlacement
          5. Insert danh sách UnplacedPackage
        """
        job = self.job_service.get_job(str(job_uuid), db)

        vol_util = 0.0
        wt_util = 0.0
        packed_count = len(result.placements) if result.placements else 0

        if result.metrics:
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
            )
            self.load_plan_repository.create(plan, db)

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

            savepoint.commit()
            logger.info(f"Successfully persisted LoadPlan {plan.id} for job {job_uuid}")
            return plan

        except Exception as exc:
            savepoint.rollback()
            logger.error(f"Failed to persist optimization result for job {job_uuid}: {exc}")
            raise exc
