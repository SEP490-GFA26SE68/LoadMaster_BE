# S1-02 · Cấu hình Spring Security + JWT

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Infra & Config |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Assignee** | — |
| **Status** | 🔲 Todo |

## Mô tả
Tạo cấu hình Spring Security với JWT authentication filter. Mọi request phải có Bearer token hợp lệ, trừ `/api/auth/**`, Swagger/health và `POST /api/guest-contacts`.

## Acceptance Criteria
- [ ] `SecurityConfig.java` — disable CSRF, stateless session, permit đúng các public endpoint (`/api/auth/**`, Swagger/health, `POST /api/guest-contacts`)
- [ ] `JwtTokenProvider.java` — generate token (HS256/RS256), validate, extract claims (`userId`, `role`, `companyId`)
- [ ] `JwtAuthenticationFilter.java` — OncePerRequestFilter, đọc header `Authorization: Bearer <token>`
- [ ] Token chứa: `sub` (userId), `role` (một role), `companyId` (nullable với System Admin), `exp` (expiration)
- [ ] Token expiry cấu hình qua `application.yml` (default 24h)
- [ ] Thêm dependency `jjwt` hoặc `spring-security-oauth2-jose` vào `pom.xml`

## Files cần tạo
- `src/main/java/.../config/SecurityConfig.java`
- `src/main/java/.../config/JwtTokenProvider.java`
- `src/main/java/.../config/JwtAuthenticationFilter.java`

## Dependencies
- Không phụ thuộc issue khác
