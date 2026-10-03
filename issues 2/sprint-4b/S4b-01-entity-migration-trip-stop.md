# S4b-01 · Entity Migration — Trip Status Flow + DeliveryStop Coordinates

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Entity / Migration |
| **Priority** | 🔴 Must |
| **Label** | `BE` `DB` |
| **PRD Ref** | FLOW-2 |
| **Status** | ✅ Done |

## Mô tả
Cập nhật entity `Trip` và `DeliveryStop` để hỗ trợ Flow 2 (route optimization) và Flow 6 (GPS monitoring).

## Acceptance Criteria — DeliveryStop
- [ ] Thêm `latitude DECIMAL(10,7)` và `longitude DECIMAL(10,7)` (nullable ban đầu, required sau Flow 2)
- [ ] Thêm `status VARCHAR(20) DEFAULT ''PENDING''` — PENDING, ARRIVED, COMPLETED
- [ ] Thêm `planned_arrival TIMESTAMP` — ETA dự báo
- [ ] Thêm `actual_arrival TIMESTAMP` — thời gian thực tế

## Acceptance Criteria — Trip
- [ ] Thay đổi `status` flow: `DRAFT → PLANNED → LOADING → IN_TRANSIT → DELIVERED → CANCELLED`
- [ ] Thêm `route_plan JSON` — lưu response từ Goong Maps (null nếu chưa tối ưu)
- [ ] Thêm `handling_class_lock VARCHAR(20)` — lock handling class của trip (STANDARD/FRAGILE/...)
- [ ] Thêm `override_reason TEXT NULL` — lý do khi Dispatcher override cargo segregation rule
- [ ] Java entity `Trip` và `DeliveryStop` cập nhật tương ứng

## Lưu ý nghiệp vụ (Fix lỗ hổng B2 trong PRD)
- `lat/lng` là bắt buộc để Goong Maps tính route. FE phải cho phép nhập tọa độ hoặc geocode từ địa chỉ.

## Files cần sửa/tạo
- `src/main/resources/db/migration/V{next}__trip_status_stop_coordinates.sql`
- `entity/Trip.java`
- `entity/DeliveryStop.java`
- `constant/TripStatus.java` (enum mới)
- `constant/DeliveryStopStatus.java` (enum mới)
