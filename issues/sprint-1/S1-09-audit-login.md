# S1-09 · Ghi AuditLog khi login

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Auth / Audit |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-AUTH-03 |
| **Status** | 🔲 Todo |

## Mô tả
Sau mỗi lần đăng nhập thành công → INSERT `AuditLog(LOGIN_SUCCESS)`.

## Acceptance Criteria
- [ ] Gọi `AuditService.log("LOGIN_SUCCESS", "User", userId, ipAddress)` trong AuthService
- [ ] Lưu IP address từ `HttpServletRequest`
- [ ] Không block login flow nếu audit log fail (catch exception, log warning)

## Dependencies
- S1-06 (Login), S1-15 (AuditService)
