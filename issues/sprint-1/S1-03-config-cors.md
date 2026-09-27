# S1-03 · Cấu hình CORS

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Infra & Config |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Assignee** | — |
| **Status** | 🔲 Todo |

## Mô tả
Cho phép React frontend (localhost:3000 dev + production domain) gọi API cross-origin.

## Acceptance Criteria
- [ ] `CorsConfig.java` hoặc cấu hình trong `SecurityConfig`
- [ ] Allow origins: `http://localhost:3000`, production URL
- [ ] Allow methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
- [ ] Allow headers: Authorization, Content-Type
- [ ] Allow credentials: true

## Files cần tạo
- `src/main/java/.../config/CorsConfig.java`
