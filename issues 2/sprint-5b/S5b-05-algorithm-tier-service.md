# S5b-05 · AlgorithmTierService — Subscription-based Algorithm Selection

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Optimize / Subscription |
| **Priority** | 🔴 Must |
| **Label** | `PYTHON` `BE` |
| **PRD Ref** | FLOW-3, FLOW-8 |
| **Depends on** | S5b-04 |
| **Status** | 🔲 Todo |

## Mô tả
Chọn algorithm dựa trên subscription tier của company. Kiểm tra credit trước khi chạy job.

## Acceptance Criteria
- [ ] OptimizationJob request phải kèm `company_id` và `subscription_tier`
- [ ] `BASIC` → dùng EP + DBLF (in-process greedy nâng cấp)
- [ ] `PRO` → dùng EP + DBLF + GA (thêm bước local search sau greedy)
- [ ] `ULTIMATE` → dùng EP + DBLF + GA + (placeholder AI tier, có thể mock)
- [ ] Trước khi chạy: gọi LoadMasterService API `POST /api/credits/deduct` để trừ 1 credit
- [ ] Nếu credit = 0 → trả lỗi `INSUFFICIENT_CREDITS`, không chạy engine
- [ ] Nếu job FAILED → gọi `POST /api/credits/refund` để hoàn credit (fix B4 trong PRD)
- [ ] Lưu `OptimizationJob.algorithm_tier` để audit

## Files cần tạo/sửa (OptimizeService)
- `app/service/optimize/algorithm_tier_service.py`
- `app/client/loadmaster_credit_client.py` — gọi LoadMasterService API
- `app/service/optimize/async_optimization_runner.py` — thêm credit check
