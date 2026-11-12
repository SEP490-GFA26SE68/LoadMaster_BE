import logging

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

app = FastAPI(
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

# Exception handlers
register_exception_handlers(app)

# Routers
app.include_router(optimization_router)
app.include_router(load_plan_router)
app.include_router(rerun_router)


@app.get("/health")
def health_check(db: Session = Depends(get_db)):
    # Kiểm DB thật: DB không trả lời thì 503 để nơi theo dõi biết dịch vụ chưa dùng được.
    try:
        db.execute(text("SELECT 1"))
    except Exception:
        logger.exception("Health check: không truy vấn được DB")
        return JSONResponse(
            status_code=503,
            content={"status": "DOWN", "service": settings.APP_NAME},
        )
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
