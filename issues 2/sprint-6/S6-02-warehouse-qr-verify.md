# S6-02 · Flow 5 Upgrade — QR Scan Verification in Warehouse

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Warehouse |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-5 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/warehouse/placements/{id}/confirm` nhận thêm `scannedQrToken: String` (optional)
- [ ] Nếu `scannedQrToken` được cung cấp: verify token khớp với `package.qr_token` của placement
- [ ] Nếu token không khớp → `WRONG_PACKAGE_SCANNED` error (HTTP 422)
- [ ] Nếu token không được cung cấp → vẫn cho confirm (backward compatible)
- [ ] Tương tự cho `POST /api/warehouse/placements/{id}/deviation` — thêm `scannedQrToken` optional
- [ ] Response confirm thêm field `packageQrToken` và `stopZoneName` (worker biết đang xếp zone nào)
- [ ] Ghi AuditLog: thêm field `qr_verified: true/false`

## Files cần sửa
- `service/warehouse/Impl/WarehouseServiceImpl.java`
- `controller/warehouse/WarehouseController.java`
- `dto/request/ConfirmPlacementRequest.java` (thêm scannedQrToken)
