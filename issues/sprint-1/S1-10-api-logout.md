# S1-10 · API Đăng xuất

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Auth |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Mô tả
`POST /api/auth/logout` — invalidate JWT token.

## Acceptance Criteria
- [ ] Endpoint `POST /api/auth/logout`
- [ ] Chiến lược: token blacklist (Redis/in-memory) hoặc client-side xóa token
- [ ] Trả 200 OK

## Dependencies
- S1-02 (JWT config)
