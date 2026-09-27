# S4-08 · Driver: Get Trips + Stop Manifest

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Driver Unloading |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-DRV-01, FR-DRV-02 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria

### Get Driver Trips
- [x] `GET /api/driver/trips`
- [x] Filter: `Vehicle.driverUserId = currentUser.id`, `Trip.status = READY_FOR_DELIVERY`
- [x] Trả: `tripCode`, `vehicleLicensePlate`, `stopCount`, `status` (không có `departureTime`)

### Get Stop Manifest
- [x] `GET /api/driver/trips/{id}/stops`
- [x] Trả `DeliveryStop` theo `stopSequence` ASC; schema không có stop status nên không suy luận “current stop”
- [x] Load packages qua `Order.deliveryStopId`
- [x] Compute recommended unloading order từ `PackagePlacement.loadingSequence` theo thứ tự ngược
- [x] Trả: `stopSequence`, `locationAddress`, packages[] (`trackingBarcode`, `actualWeightKg`, `unloadOrder`)

## Files cần tạo
- `controller/DriverController.java`
- `service/DriverService.java`
