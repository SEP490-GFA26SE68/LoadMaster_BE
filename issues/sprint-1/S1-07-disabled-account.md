# S1-07 · Xử lý tài khoản bị vô hiệu hóa

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Auth |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-AUTH-04 |
| **Status** | 🔲 Todo |

## Mô tả
Khi đăng nhập, nếu `User.status != "ACTIVE"` → từ chối, trả 403 Forbidden.

## Acceptance Criteria
- [ ] Trong `AuthService.authenticate()`: kiểm tra `user.status` bằng `ACTIVE`
- [ ] Nếu disabled → throw `AppException(ErrorCode.ACCOUNT_DISABLED)`
- [ ] Response: `403 — Tài khoản đã bị vô hiệu hóa`

## Dependencies
- S1-06 (Login API)
