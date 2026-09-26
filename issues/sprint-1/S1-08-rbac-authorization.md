# S1-08 · Phân quyền RBAC

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Auth |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-AUTH-02 |
| **Status** | 🔲 Todo |

## Mô tả
Load Role + Permission từ DB, gán vào SecurityContext. Dùng `@PreAuthorize` trên controller.

## Acceptance Criteria
- [ ] `CustomUserDetailsService` implements `UserDetailsService`
- [ ] Load User → `User.roleId` → `Role` → `RolePermission` → `Permission`
- [ ] Map permissions thành `GrantedAuthority`
- [ ] Controller dùng `@PreAuthorize("hasAuthority('PERMISSION_NAME')")`
- [ ] Seed data: tạo các Role mặc định (SYSTEM_ADMIN, COMPANY_ADMIN, DISPATCHER, WAREHOUSE_WORKER, DRIVER)

## Files cần tạo
- `src/main/java/.../service/CustomUserDetailsService.java`
- `src/main/java/.../repository/UserRepository.java` (method `findByEmail`)
- `src/main/java/.../repository/RoleRepository.java`
- `src/main/java/.../repository/PermissionRepository.java`

## Dependencies
- S1-02 (Security config)
