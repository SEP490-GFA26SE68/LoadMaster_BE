# Sprint 7 — Flow 7: En-route Pickup & Dynamic Re-optimization

> 5 issues. Driver nhận thêm hàng dọc đường. System validate 10 rules và re-optimize vùng trống.
> FIX: Pickup package cần được tạo trong DB trước khi xếp lên xe (lỗ hổng B3 + B8).

| Issue | Phạm vi |
|---|---|
| S7-01 | Entity migration: PickupRequest + PickupPackage |
| S7-02 | PickupRequestService — tạo request, validate 10 rules |
| S7-03 | PickupQrService — sinh QR tạm cho pickup package |
| S7-04 | FreedZoneService — tính vùng trống sau khi giao stop |
| S7-05 | PickupReoptimizationService — re-optimize freed zone |
