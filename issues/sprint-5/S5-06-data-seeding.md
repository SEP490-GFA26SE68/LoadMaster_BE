# S5-06 · Data Seeding

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Infra |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Mô tả
Script seed dữ liệu ban đầu cho hệ thống.

## Acceptance Criteria
- [ ] Seed mặc định: 5 Roles (SYSTEM_ADMIN, COMPANY_ADMIN, DISPATCHER, WAREHOUSE_WORKER, DRIVER)
- [ ] Seed Permissions cho mỗi Role
- [ ] Seed System Admin bằng `email` + BCrypt `passwordHash`, `roleId`, `userRoleType`, `fullName`, `status=ACTIVE`; `companyId` nullable
- [ ] Dùng `data.sql` hoặc `CommandLineRunner`
- [ ] Chỉ seed khi DB chưa có data (check exists trước khi insert)

## Files cần tạo
- `config/DataSeeder.java` hoặc `src/main/resources/data.sql`
