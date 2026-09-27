# Sprint 4 — Execution & 3D

> 9 issues hiện có, đã thu gọn theo schema v3.4.

## Pin, rerun và comparison

- Pin dùng `Package.isPinned`, không thêm field vào `PackagePlacement`.
- Rerun tạo `OptimizationJob` mới cùng Trip và tăng `LoadPlan.planVersion`; không có `parentPlanId`.
- Comparison dùng field tồn tại; count/weight utilization được tính runtime.

## Warehouse

- `LoadingExecution` chỉ gồm `loadPlanId` (unique), `workerUserId`, `sealNumber`, `status`.
- Start loading tạo execution `IN_PROGRESS`; complete cập nhật seal/status và ghi AuditLog.
- Không dùng `startedAt`, `completedAt`, `totalDeviations`, `PlacementConfirmation` hoặc `Deviation`.

## Driver

- Driver trip được xác định qua `Vehicle.driverUserId`.
- Manifest dỡ hàng đi theo `DeliveryStop → Order → Package`, sắp xếp bằng `stopSequence` và `loadingSequence`.
- Schema không lưu trạng thái từng stop/kiện và không có `UnloadConfirmation`; hoàn tất chuyến chỉ cập nhật `Trip.status` + AuditLog.
