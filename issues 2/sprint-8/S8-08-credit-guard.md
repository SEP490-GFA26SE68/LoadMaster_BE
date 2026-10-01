# S8-08 · CreditGuard — Middleware kiểm tra credit trước optimization

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Credit / Optimize |
| **Priority** | 🔴 Must |
| **Label** | `BE` `PYTHON` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-04, S5b-05 |
| **Status** | 🔲 Todo |

## Mô tả
Tích hợp credit check vào optimization flow. LoadMasterService gọi credit API trước khi submit job. OptimizeService refund nếu job fail.

## Acceptance Criteria (LoadMasterService — Java)
- [ ] Trước khi gọi OptimizeService: `CreditService.deductCredit(companyId, jobUuid)`
- [ ] Nếu `InsufficientCreditsException` → trả HTTP 402 với message `INSUFFICIENT_CREDITS`
- [ ] ULTIMATE subscription: skip credit check
- [ ] `POST /api/credits/deduct` — internal API (chỉ gọi từ OptimizeService, dùng service token Keycloak)
- [ ] `POST /api/credits/refund` — internal API khi job FAILED

## Acceptance Criteria (OptimizeService — Python)
- [ ] Sau khi job FAILED: gọi `POST /api/credits/refund` với job_uuid → hoàn credit
- [ ] Retry refund 3 lần nếu LoadMasterService unavailable
- [ ] Log nếu refund thất bại → cần manual review

## Acceptance Criteria — Avoid Circular Dependency
- [ ] OptimizeService gọi LoadMasterService (không ngược lại)
- [ ] LoadMasterService expose internal API `/api/internal/credits/*` riêng biệt
- [ ] Internal API auth: service-to-service Keycloak token (tái dùng KeycloakTokenProvider)

## Files cần sửa
- `service/optimization/Impl/LoadPlanServiceImpl.java` — thêm credit check
- `controller/internal/CreditInternalController.java` — endpoint nội bộ
- OptimizeService: `app/service/optimize/engine_exception_handler.py` — thêm refund call
- OptimizeService: `app/client/loadmaster_credit_client.py` (tạo mới)
