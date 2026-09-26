# S1-01 · Cấu hình PostgreSQL + Hibernate

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Infra & Config |
| **Priority** | 🔴 Must |
| **Label** | `INFRA` |
| **Assignee** | — |
| **Status** | ✅ Done |

## Mô tả
Setup kết nối PostgreSQL, HikariCP connection pool, JPA auto-ddl, import `.env` credentials.

## Acceptance Criteria
- [ ] `application.yml` dùng `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` từ `.env`
- [ ] `spring.config.import: optional:file:.env[.properties]`
- [ ] HikariCP pool size phù hợp (max 10)
- [ ] `ddl-auto: update` cho dev, `validate` cho prod
- [ ] SSL mode `require` cho Aiven Cloud
- [ ] `.env` nằm trong `.gitignore`

## Files liên quan
- `src/main/resources/application.yml`
- `.env` / `.env.example`
- `.gitignore`
