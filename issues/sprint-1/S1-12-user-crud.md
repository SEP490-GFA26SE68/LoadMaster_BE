# S1-12 · User CRUD — Repository + Service + Controller + DTO

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | User |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-USER-01, FR-USER-02 |
| **Status** | 🔲 Todo |

## Mô tả
Tạo đầy đủ CRUD cho User: tạo, sửa, xem danh sách, xem chi tiết, vô hiệu hóa.

## Acceptance Criteria
- [ ] `UserRepository` — JPA, `findByEmail()`, `findByCompanyId()`, kiểm tra unique cho các mã nghiệp vụ
- [ ] `UserService` — create (hash password BCrypt), update, getById, getAll (paging), disable
- [ ] `UserController` — `GET/POST/PUT/DELETE /api/users`
- [ ] `UserRequest` DTO — `companyId` (nullable), `roleId`, `email`, `password`, `fullName`, `phoneNumber`, `userRoleType`, `dispatcherCode`, `licenseNumber`, `workerBadgeId`, `status`
- [ ] `createdByUserId` lấy từ current user phía server, không nhận từ client
- [ ] `UserResponse` DTO — `id`, `companyId`, `roleId`, `email`, `fullName`, `phoneNumber`, các mã nghiệp vụ, `status`, `createdByUserId`, `createdAt`; không trả password/hash
- [ ] Validation: unique `email`; unique nullable `dispatcherCode`, `licenseNumber`, `workerBadgeId`; `roleId` bắt buộc tồn tại
- [ ] Hash password vào `passwordHash`; không tạo cột `username`, `enabled` hoặc bảng `user_roles`
- [ ] Company Admin chỉ CRUD user thuộc company mình (`companyId` filter); System Admin có thể có `companyId = null`
- [ ] `@PreAuthorize("hasAuthority('USER_MANAGE')")` trên controller

## Files cần tạo
- `src/main/java/.../repository/UserRepository.java`
- `src/main/java/.../service/UserService.java`
- `src/main/java/.../controller/UserController.java`
- `src/main/java/.../dto/request/UserRequest.java`
- `src/main/java/.../dto/response/UserResponse.java`
- `src/main/java/.../mapper/UserMapper.java` (optional — MapStruct hoặc manual)
