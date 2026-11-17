from fastapi import APIRouter, Depends, BackgroundTasks, HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session
from sqlalchemy.exc import DBAPIError
from app.config.database import get_db
from app.config.diagnostics import mark
from app.config.settings import settings
from app.dto.request.optimization_request import OptimizationJobRequest
from app.service.auth.auth_dependency import require_optimization_service
from app.controller.optimization.optimization_router import get_async_runner
from app.service.optimize.job_execution_protocol import JobExecutionProtocol

router = APIRouter(prefix="/api/v2/optimization", tags=["Optimization V2"])

@router.post("/jobs", status_code=202)
def submit_owned(request: OptimizationJobRequest, tasks: BackgroundTasks,
                 claims=Depends(require_optimization_service), db: Session=Depends(get_db),
                 runner=Depends(get_async_runner)):
    if not settings.OPTIMIZATION_LIFECYCLE_V2_ENABLED:
        raise HTTPException(503, "Lifecycle V2 is not enabled")
    if not request.job_uuid or request.pinned_placements:
        raise HTTPException(422, "Reserved INITIAL job_uuid is required")
    mark("ownership_prepare_started", job_uuid=request.job_uuid, trip_id=request.trip_id)
    protocol = JobExecutionProtocol(runner.session_factory, settings.OPTIMIZATION_LEASE_SECONDS,
        settings.OPTIMIZATION_HEARTBEAT_SECONDS, settings.OPTIMIZATION_EXECUTION_GRACE_SECONDS)
    for attempt in range(3):
        try:
            prepared = protocol.prepare(request.job_uuid, request.trip_id, runner.build_problem_request, db)
            break
        except DBAPIError as exc:
            db.rollback()
            if getattr(exc.orig, "pgcode", None) != "40001" or attempt == 2:
                raise
    mark("ownership_prepare_committed")
    if prepared.rejected:
        return JSONResponse(status_code=400, content={"success":False,
            "code":"PACKING_PROFILE_ORIENTATION_INVALID", "message":"Packing profile orientation is invalid",
            "errors":{"job_id":prepared.job_uuid,"job_status":prepared.status,"protocol_version":2,
                      "execution_generation":prepared.generation}})
    if prepared.problem_json is not None:
        tasks.add_task(runner.run_owned, prepared.job_uuid, prepared.generation, prepared.problem_json)
    mark("ack_202_prepared")
    return {"success":True,"message":"Reserved job acknowledged", "data":{
        "job_id":prepared.job_uuid, "status":prepared.status,
        "dispatch_state":"ALREADY_RUNNING" if prepared.status == "RUNNING" else "ALREADY_TERMINAL",
        "protocol_version":2,"execution_generation":prepared.generation}}
