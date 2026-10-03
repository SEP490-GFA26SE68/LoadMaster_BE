# Kết quả review PRD v2.0 — Lỗ hổng & Tối ưu

> Phân tích `prd.md` phát hiện các vấn đề nghiệp vụ và kỹ thuật cần làm rõ trước khi implement.

---

## 🔴 Lỗ hổng nghiệp vụ nghiêm trọng

| # | Vấn đề | Vị trí trong PRD | Đề xuất sửa |
|---|---|---|---|
| B1 | **Trip status flow thiếu** | Flow 2 | Trip hiện chỉ có `DRAFT`. Cần thêm: `DRAFT → PLANNED → LOADING → IN_TRANSIT → DELIVERED`. Flow 6 (GPS) và Flow 7 (pickup) phụ thuộc vào việc trip đang ở trạng thái `IN_TRANSIT`. |
| B2 | **DeliveryStop thiếu lat/lng** | Flow 2 | `DeliveryStop` chỉ có `locationAddress` (string). Goong Maps API cần tọa độ `lat/lng` để tính route. Phải thêm `lat`, `lng` vào entity. |
| B3 | **Pickup package chưa có QR** | Flow 7 | Hàng pickup dọc đường không có QR (vì chưa qua Flow 1). Cần quyết định: (a) Driver nhập thủ công, (b) System sinh QR tạm thời tại chỗ. |
| B4 | **Credit deduction timing** | Flow 8 | PRD không rõ credit bị trừ khi nào: (a) khi submit job, (b) khi job COMPLETED, (c) khi approve plan. Nếu job FAILED → có hoàn credit không? |
| B5 | **Cargo Segregation override flow** | Flow 2 | PRD nói Dispatcher có thể override nhưng không có API/entity nào ghi lại lý do override. Cần `SegregationOverride` log hoặc field `override_reason` trong Trip. |
| B6 | **Subscription đã có trong Sprint 5** | Flow 8 | Issues/sprint-5 đã có `S5-01-subscription-plan-crud.md`. PRD v2.0 mở rộng nhiều hơn (credit, payment gateway). Cần align với schema cũ, không conflict. |
| B7 | **CompanyManager role chưa có trong hệ thống** | Flow 2, 6 | PRD đề cập `CompanyManager` nhưng schema v3.4 chỉ có các role cũ. Phải định nghĩa rõ role mới và quyền của nó. |
| B8 | **En-route pickup: ai tạo QR mới?** | Flow 7 | Package pickup mới cần `Package` record trong DB trước khi xếp lên xe. Flow này chưa định nghĩa ai và khi nào tạo record. |

---

## 🟡 Điểm mờ kỹ thuật

| # | Vấn đề | Ảnh hưởng |
|---|---|---|
| T1 | **RouteService là service mới hay module trong LoadMasterService?** | Ảnh hưởng deployment, repo structure. PRD đề xuất microservice riêng nhưng chưa xác nhận. |
| T2 | **Goong Maps API key management** | Key cần lưu trong `.env`, không commit. Cần rate limit và fallback khi Goong API unavailable. |
| T3 | **GPS data retention** | `GpsTracking` record mỗi 30 giây × nhiều trip = rất nhiều data. Cần strategy: TTL, aggregation hay archive sau X ngày. |
| T4 | **WebSocket cho GPS tracking** | PRD đề cập "polling hoặc WebSocket". Nên chọn WebSocket để giảm overhead. Cần xác nhận auth mechanism (giống /ws/jobs). |
| T5 | **Dynamic zone sizing formula** | Formula hiện dùng volume ratio. Chưa xét weight distribution và COG constraint. Có thể zone sizing cần chạy lần 2 sau constraint check. |
| T6 | **Payment webhook idempotency** | PRD đề cập unique `gateway_transaction_id` nhưng chưa có giải pháp khi webhook đến 2 lần do retry. Cần distributed lock hoặc DB unique constraint. |
| T7 | **Algorithm tier check** | Khi job chạy, OptimizeService cần gọi lại LoadMasterService để check tier/credit hay nhận thông tin trong request? Phải tránh circular dependency. |

---

## 🟢 Tối ưu PRD (đã áp dụng trong issues)

| # | Thay đổi | Lý do |
|---|---|---|
| O1 | Thêm `Trip.status` đầy đủ vào entity | Cần cho Flow 6 (chỉ track trip IN_TRANSIT), Flow 7 (pickup chỉ khi IN_TRANSIT) |
| O2 | Thêm `lat`, `lng` vào `DeliveryStop` | Bắt buộc cho Goong Maps routing |
| O3 | Tách `PickupPackage` entity riêng | Package pickup dọc đường cần record DB trước khi scan QR xếp lên xe |
| O4 | Thêm `CreditTransaction.refunded` flag | Handle trường hợp job FAILED → hoàn credit |
| O5 | Thêm `Trip.override_reason` | Ghi log khi Dispatcher override cargo segregation rule |
| O6 | Goong Maps làm module trong LoadMasterService | Không cần tách microservice riêng cho MVP, giảm phức tạp deploy |
| O7 | GPS WebSocket thay vì polling | Hiệu quả hơn cho real-time tracking |
