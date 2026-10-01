# LoadMaster Issues 2 — Post Review 1

> Các issues triển khai 8 flows theo PRD v2.0 (Post Review 1).
> Tiếp nối từ `issues/` (Sprint 1–5). Issues 2 bao gồm Sprint 3b → Sprint 8.

## Quy ước

- Entity name chuẩn theo PRD v2.0: `Package` (không phải `CargoPackage`), `Order` (không phải `TransportOrder`).
- Kích thước dùng `Integer` mm; tải trọng dùng `BigDecimal DECIMAL(10,2)` kg; tiền VNĐ dùng `BigDecimal DECIMAL(15,2)`.
- Multi-tenancy: mọi query lọc theo `company_id`.
- QR token là `UUID` stable — không embed dữ liệu nhạy cảm.
- Mọi entity mới phải cập nhật migration trước khi implement service.

## Cấu trúc

| Sprint | Focus | Issues |
|---|---|---|
| **sprint-3b** | Flow 1 — QR & Package Import mới | S3b-01 → S3b-07 |
| **sprint-4b** | Flow 2 — Cargo Segregation, Route Opt | S4b-01 → S4b-08 |
| **sprint-5b** | Flow 3 — 3D Engine nâng cấp | S5b-01 → S5b-07 |
| **sprint-6** | Flow 4+5 nâng cấp + Flow 6 GPS | S6-01 → S6-10 |
| **sprint-7** | Flow 7 — En-route Pickup | S7-01 → S7-05 |
| **sprint-8** | Flow 8 — Subscription & Credit | S8-01 → S8-08 |

## Issues nghiệp vụ phát hiện trong PRD

Xem `ISSUES_PRD_REVIEW.md` để biết các lỗ hổng đã tìm thấy trong PRD và cách giải quyết.
