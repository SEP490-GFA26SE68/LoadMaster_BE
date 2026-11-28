import logging
from contextlib import asynccontextmanager
from app.config.diagnostics import DiagnosticMiddleware, mark, failure, startup_identity

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.config.database import get_db

from app.config.settings import settings
from app.controller.optimization.optimization_router import router as optimization_router
from app.controller.optimization.load_plan_router import router as load_plan_router
from app.controller.optimization.rerun_router import router as rerun_router
from app.exception.global_exception_handler import register_exception_handlers

logger = logging.getLogger(__name__)

# Tài liệu API chỉ mở ở máy dev hoặc khi API_DOCS_ENABLED=true.
_docs_enabled = settings.api_docs_enabled()

@asynccontextmanager
async def lifespan(app):
    import asyncio
    from app.controller.optimization.optimization_router import get_async_runner
    from app.service.optimize.pending_job_recovery import recovery_loop
    startup_identity(__file__)
    recover = asyncio.create_task(recovery_loop(get_async_runner()))
    try:
        yield
    finally:
        recover.cancel()


app = FastAPI(
    lifespan=lifespan,
    title=settings.APP_NAME,
    version="1.0.0",
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
)

# CORS: chỉ origin đã khai; xác thực bằng Bearer token nên không cần gửi cookie.
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins(),
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)

# Gói ULTIMATE cần torch (requirements-ai.txt): báo ngay lúc khởi động, không để job âm thầm chạy heuristic.
from app.ml.lumo_models import torch_available
from jwt.algorithms import has_crypto

if not has_crypto:
    logger.error(
        "Chưa cài cryptography: không xác minh được token Keycloak (RS256), mọi API sẽ 401. "
        "Cài bằng: pip install -r requirements.txt"
    )

import importlib.util

from app.config.settings import settings as _settings

if _settings.OPTIMIZATION_LMAI_BUILDER_ENABLED and importlib.util.find_spec("numpy") is None:
    logger.error(
        "OPTIMIZATION_LMAI_BUILDER_ENABLED bật nhưng chưa cài numpy: bước EP dùng bộ dựng cũ (M9 không có tác dụng). "
        "Cài bằng: pip install -r requirements.txt"
    )

if not torch_available():
    logger.warning(
        "Chưa cài torch / numpy: gói ULTIMATE (EP_DBLF_GA_AI) sẽ chạy heuristic, không có mô hình Lumo. "
        "Cài bằng: pip install -r requirements-ai.txt"
    )

app.add_middleware(DiagnosticMiddleware)

# Exception handlers
register_exception_handlers(app)

# Routers
from app.controller.optimization.lifecycle_router import router as lifecycle_router
app.include_router(lifecycle_router)
app.include_router(optimization_router)
app.include_router(load_plan_router)
app.include_router(rerun_router)


@app.get("/health")
def health_check(db: Session = Depends(get_db)):
    # Kiểm DB thật: DB không trả lời thì 503 để nơi theo dõi biết dịch vụ chưa dùng được.
    mark("health_execute_started")
    try:
        db.execute(text("SELECT 1"))
    except Exception as exc:
        failure(exc, event="health_execute_failed")
        return JSONResponse(
            status_code=503,
            content={"status": "DOWN", "service": settings.APP_NAME},
        )
    mark("health_execute_completed")
    return {"status": "UP", "service": settings.APP_NAME}


from fastapi import WebSocket, WebSocketDisconnect
from app.service.optimize.job_notification_service import get_job_notification_service


@app.websocket("/ws/jobs/{job_uuid}")
async def ws_job_status(websocket: WebSocket, job_uuid: str):
    """
    WebSocket endpoint thông báo trạng thái real-time của optimization job (S3-10).
    """
    notification_service = get_job_notification_service()
    await websocket.accept()
    await notification_service.connect(job_uuid, websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        await notification_service.disconnect(job_uuid, websocket)
