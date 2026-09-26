from __future__ import annotations

import logging
from typing import Optional, Union, Any, Callable
from uuid import UUID
from sqlalchemy.orm import Session

from app.config.database import SessionLocal
from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.problem_request import ProblemRequest, VehicleData, PackageData
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.optimization_job_service import OptimizationJobService

logger = logging.getLogger(__name__)


class AsyncOptimizationRunner:
    """
    Runner chạy thuật toán tối ưu bất đồng bộ trong background task (S3-08).
    Tương đương với @Async trong Spring Boot.
    """

    def __init__(
        self,
        job_service: Optional[OptimizationJobService] = None,
        optimization_client: Optional[OptimizationClient] = None,
        engine_exception_handler: Optional[EngineExceptionHandler] = None,
        persistence_service: Optional[Any] = None,
        session_factory: Optional[Callable[[], Session]] = None,
    ):
        self.job_service = job_service or OptimizationJobService()
        self.optimization_client = optimization_client or OptimizationClient()
        self.engine_exception_handler = engine_exception_handler or EngineExceptionHandler(job_service=self.job_service)
        from app.service.optimize.optimization_result_persistence_service import OptimizationResultPersistenceService
        self.persistence_service = persistence_service or OptimizationResultPersistenceService(job_service=self.job_service)
        self.session_factory = session_factory or SessionLocal

    def build_problem_request(
        self,
        job_id: Union[int, str, UUID],
        db: Session,
        job_uuid: Optional[Union[str, UUID, int]] = None,
    ) -> ProblemRequest:
        """
        Xây dựng ProblemRequest từ Trip, Vehicle, Package lưu trong DB.
        """
        from app.entity.trip_model import Trip, CargoPackage, TransportOrder, DeliveryStop

        target_id = job_id if job_id is not None else job_uuid
        job = self.job_service.get_job(target_id, db)

        trip = getattr(job, "trip", None)
        if not trip:
            try:
                numeric_trip_id = int(job.trip_id)
                trip = db.query(Trip).filter(Trip.id == numeric_trip_id).first()
            except (ValueError, TypeError):
                trip = db.query(Trip).filter(Trip.id == str(job.trip_id)).first()

        if not trip:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        if not trip.vehicle or not trip.vehicle.vehicle_type:
            raise AppException(ErrorCode.VEHICLE_NOT_FOUND)

        vt = trip.vehicle.vehicle_type
        # Quy đổi đơn vị mm sang m nếu lớn hơn 50mm
        inner_l = float(vt.inner_length) / 1000.0 if (vt.inner_length and vt.inner_length > 50) else float(vt.inner_length or 0)
        inner_w = float(vt.inner_width) / 1000.0 if (vt.inner_width and vt.inner_width > 50) else float(vt.inner_width or 0)
        inner_h = float(vt.inner_height) / 1000.0 if (vt.inner_height and vt.inner_height > 50) else float(vt.inner_height or 0)
        payload = float(vt.max_payload_kg or 0)

        vehicle_data = VehicleData(
            inner_l=inner_l,
            inner_w=inner_w,
            inner_h=inner_h,
            max_payload_kg=payload,
        )

        try:
            numeric_trip_id = int(job.trip_id)
            stops_query = db.query(DeliveryStop).filter(DeliveryStop.trip_id == numeric_trip_id).all()
        except (ValueError, TypeError):
            stops_query = db.query(DeliveryStop).filter(DeliveryStop.trip_id == str(job.trip_id)).all()

        stop_ids = [s.id for s in stops_query]
        packages_query = (
            db.query(CargoPackage)
            .join(TransportOrder, CargoPackage.order_id == TransportOrder.id)
            .filter(TransportOrder.delivery_stop_id.in_(stop_ids))
            .all()
            if stop_ids else []
        )

        package_items: list[PackageData] = []
        for pkg in packages_query:
            w = float(pkg.actual_weight_kg or 0)
            if pkg.package_type:
                pt = pkg.package_type
                l = float(pt.length) / 1000.0 if (pt.length and pt.length > 50) else float(pt.length or 0)
                width = float(pt.width) / 1000.0 if (pt.width and pt.width > 50) else float(pt.width or 0)
                h = float(pt.height) / 1000.0 if (pt.height and pt.height > 50) else float(pt.height or 0)
            else:
                l, width, h = 0.0, 0.0, 0.0

            package_items.append(
                PackageData(
                    id=pkg.id,
                    l=l,
                    w=width,
                    h=h,
                    weight=w,
                )
            )

        return ProblemRequest(
            vehicle=vehicle_data,
            packages=package_items,
            objective=getattr(job, "algorithm_objective", None) or getattr(job, "objective", "MAX_VOLUME_UTIL"),
            time_limit_sec=getattr(job, "time_limit_sec", 60),
        )

    async def run(
        self,
        job_id: Union[int, str, UUID],
        problem: Optional[ProblemRequest] = None,
        db: Optional[Session] = None,
        job_uuid: Optional[Union[str, UUID, int]] = None,
    ) -> None:
        """
        Thực thi thuật toán tối ưu bất đồng bộ trong BackgroundTasks.
        Flow:
          1. Chuyển status sang RUNNING
          2. Xây dựng ProblemRequest (nếu chưa truyền vào)
          3. Gọi optimization_client.solve(problem)
          4. Lưu kết quả qua persistence_service (nếu có - S3-09)
          5. handle_result qua engine_exception_handler (COMPLETED / PARTIAL / NO_SOLUTION)
          6. Nếu có exception -> engine_exception_handler.handle(exc)
        """
        target_id = job_id if job_id is not None else job_uuid
        target_id_str = str(target_id)
        session = db
        should_close = False
        if session is None:
            session = self.session_factory()
            should_close = True

        try:
            logger.info(f"Starting async optimization job: {target_id_str}")
            self.job_service.update_status(target_id, OptimizationJobStatus.RUNNING, db=session)

            if problem is None:
                problem = self.build_problem_request(target_id, session)

            result = await self.optimization_client.solve(problem)

            saved_plan = None
            if self.persistence_service is not None and hasattr(self.persistence_service, "save"):
                saved_plan = self.persistence_service.save(target_id, result, session)

            plan_id = getattr(saved_plan, "id", None)
            self.engine_exception_handler.handle_result(result, target_id, session, plan_id=plan_id)
            logger.info(f"Finished async optimization job: {target_id_str}")

        except Exception as exc:
            logger.error(f"Error executing async optimization job {target_id_str}: {exc}")
            self.engine_exception_handler.handle(exc, target_id, session)
        finally:
            if should_close and session is not None:
                session.close()
