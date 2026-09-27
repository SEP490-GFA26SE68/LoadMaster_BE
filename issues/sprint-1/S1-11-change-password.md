# S1-11 · API Đổi mật khẩu

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Auth |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Mô tả
`PUT /api/auth/change-password` — xác thực mật khẩu cũ, hash mật khẩu mới bằng BCrypt.

## Acceptance Criteria
- [ ] Endpoint `PUT /api/auth/change-password`
- [ ] Request: `{ oldPassword, newPassword, confirmPassword }`
- [ ] Validate: `newPassword == confirmPassword`, `newPassword != oldPassword`
- [ ] Validate: `oldPassword` match với DB (BCrypt)
- [ ] Hash `newPassword` → update DB
- [ ] Trả 200 OK

## Dependencies
- S1-06 (Login / AuthService)
