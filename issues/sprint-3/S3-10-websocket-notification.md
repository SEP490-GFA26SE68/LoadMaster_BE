# S3-10 · WebSocket Notification

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-05 |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
WebSocket thông báo real-time khi job hoàn thành.

## Acceptance Criteria
- [x] WebSocket endpoint: `ws://host/ws/jobs/{job_id}` (`job_id` là BIGINT)
- [x] Client subscribe → nhận `{ job_id, status, plan_id, message }` khi job xong
- [x] Khi job completed/failed/timeout → broadcast tới tất cả client đang subscribe job đó
- [x] Dependency: `websockets` (đã có trong uvicorn[standard])

## Files cần tạo
- `app/service/optimize/job_notification_service.py`
- `app/dto/websocket/job_status_notification.py`
- WebSocket endpoint trong `app/main.py`

## Notes (Python equivalent)
```python
# Java: WebSocketConfig.java + JobNotificationServiceImpl.java (STOMP)
# Python: FastAPI native WebSocket (không dùng STOMP)

# app/main.py
from fastapi import WebSocket

active_connections: dict[int, list[WebSocket]] = {}

@app.websocket("/ws/jobs/{job_id}")
async def ws_job_status(websocket: WebSocket, job_id: int):
    await websocket.accept()
    active_connections.setdefault(job_id, []).append(websocket)
    try:
        while True:
            await websocket.receive_text()  # keep-alive
    except WebSocketDisconnect:
        active_connections[job_id].remove(websocket)

# app/service/optimize/job_notification_service.py
class JobNotificationService:
    async def notify(self, job_id: int, notification: JobStatusNotification):
        for ws in active_connections.get(job_id, []):
            await ws.send_json(notification.model_dump())
```

> **Khác biệt vs Java:** FastAPI dùng native WebSocket, không dùng STOMP broker.
> Client kết nối thẳng `ws://host/ws/jobs/{id}` thay vì subscribe STOMP topic.
