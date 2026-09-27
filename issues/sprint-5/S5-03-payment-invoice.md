# S5-03 · Payment + Invoice

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Payment |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-SUB-03 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/payments` — ghi nhận giao dịch thanh toán
- [ ] `GET /api/payments` — xem lịch sử thanh toán
- [ ] `GET /api/invoices` — xem hóa đơn
- [ ] Auto-gen `invoiceNumber`
- [ ] `Invoice`: `subscriptionId`, `invoiceNumber`, `amountVnd`, `paymentStatus` (mặc định `PENDING`)
- [ ] `PaymentTransaction`: `invoiceId`, `gatewayProvider`, `transactionCode`, `amountVnd`
- [ ] Validate unique `invoiceNumber` và `transactionCode`; payment amount khớp invoice theo rule nghiệp vụ

## Files cần tạo
- `repository/PaymentTransactionRepository.java`
- `repository/InvoiceRepository.java`
- `service/PaymentService.java`
- `controller/PaymentController.java`
