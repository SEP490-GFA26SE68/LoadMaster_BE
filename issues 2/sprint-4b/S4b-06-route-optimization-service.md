# S4b-06 · RouteOptimizationService

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Route |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2 |
| **Depends on** | S4b-04, S4b-05 |
| **Status** | ✅ Done |

## Mô tả
Orchestrate: gọi Goong để lấy distance matrix, chạy stop sequence optimizer, gọi Goong directions cho từng cặp stop, tính ETA tổng.

## Acceptance Criteria
- [x] `optimizeRoute(Long tripId)` → `RouteOptimizationResult`
  - Load trip + stops + packages từ DB
  - Gọi GoongMapsClient.getDistanceMatrix cho tất cả cặp stops
  - Chạy StopSequenceOptimizer → thứ tự tối ưu
  - Gọi GoongMapsClient.getDirections cho từng cặp stop kề nhau → chọn route tốt nhất (duration thấp nhất)
  - Tính `planned_arrival` mỗi stop = departure + cumulative_travel_time + service_time_at_stop (mặc định 15 phút)
  - Lưu `Trip.route_plan` = JSON toàn bộ result
  - Cập nhật `DeliveryStop.planned_arrival` và `stop_sequence` theo thứ tự mới
  - Ghi `AuditLog` action_type=`ROUTE_OPTIMIZED`
- [x] Nếu stop thiếu lat/lng → `MissingCoordinatesException`
- [x] Nếu Goong API unavailable → `RouteOptimizationUnavailableException`

## Files cần tạo
- `service/route/RouteOptimizationService.java`
- `dto/route/RouteOptimizationResult.java`
