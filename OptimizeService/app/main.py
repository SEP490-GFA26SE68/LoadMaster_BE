from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config.settings import settings
from app.controller.optimization.trip_validation_router import router as trip_validation_router
from app.controller.optimization.optimization_router import router as optimization_router
from app.controller.optimization.load_plan_router import router as load_plan_router
from app.exception.global_exception_handler import register_exception_handlers

app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Exception handlers
register_exception_handlers(app)

# Routers
app.include_router(trip_validation_router)
app.include_router(optimization_router)
app.include_router(load_plan_router)


@app.get("/health")
def health_check():
    return {"status": "UP", "service": settings.APP_NAME}


from fastapi import WebSocket, WebSocketDisconnect
from app.service.optimize.job_notification_service import get_job_notification_service


@app.websocket("/ws/jobs/{job_id}")
async def ws_job_status(websocket: WebSocket, job_id: str):
    """
    WebSocket endpoint thông báo trạng thái real-time của optimization job (S3-10).
    Hỗ trợ job_id kiểu BIGINT (Schema v3.4) hoặc UUID/str.
    """
    notification_service = get_job_notification_service()
    await websocket.accept()
    await notification_service.connect(job_id=job_id, websocket=websocket)
    try:
        while True:
            await websocket.receive_text()
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        await notification_service.disconnect(job_id=job_id, websocket=websocket)
