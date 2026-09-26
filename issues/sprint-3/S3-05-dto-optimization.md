# S3-05 · DTO: ProblemRequest + OptimizationResult

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Pydantic schemas cho giao tiếp nội bộ của FastAPI.

## ProblemRequest
```python
# Java: ProblemRequest.java
# Python: app/dto/optimization/engine/problem_request.py

class VehicleData(BaseModel):
    inner_l: float
    inner_w: float
    inner_h: float
    max_payload_kg: float

class PackageData(BaseModel):
    id: int
    l: float
    w: float
    h: float
    weight: float
    stop_index: int

class ProblemRequest(BaseModel):
    vehicle: VehicleData
    packages: list[PackageData]
    stops: list[StopData]
    pinned_package_ids: list[int] = []  # packages.is_pinned
    objective: str           # "MAX_VOLUME_UTIL" | "MIN_HEIGHT"
    time_limit_sec: int
    seed: int | None = None
```

## EngineOptimizationResponse
```python
# Java: OptimizationResult.java
# Python: app/dto/optimization/engine/engine_response.py

class PlacementData(BaseModel):
    package_id: int
    x: float; y: float; z: float
    loading_sequence: int

class EngineOptimizationResponse(BaseModel):
    placements: list[PlacementData]
    unplaced: list[UnplacedData]
    metrics: MetricsData
```

## Mapping bắt buộc xuống schema v3.4
- `PackagePlacement` chỉ persist `loadPlanId`, `packageId`, `posX`, `posY`, `posZ`, `loadingSequence`.
- Rotation và packed dimensions có thể tồn tại trong DTO nội bộ khi tính toán nhưng không được giả định là cột database.
- Metrics persist vào `LoadPlan.volumeUtilizationPercent`, `OptimizationJob.executionTimeMs` và `CenterOfGravity`; không tạo `OptimizationMetric`.

## Files cần tạo
- [x] `app/dto/optimization/engine/problem_request.py`
- [x] `app/dto/optimization/engine/engine_response.py`
- [x] `app/dto/request/optimization_request.py`
- [x] `app/dto/response/optimization_job_response.py`
- [x] `app/dto/response/load_plan_response.py`
- [x] `app/dto/response/package_placement_response.py`
- [x] `app/dto/response/validation_response.py`
- [x] `app/dto/websocket/job_status_notification.py`
