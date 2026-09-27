# S1-15 · AuditService + AuditLogRepository

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Audit |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-LOG-01 |
| **Status** | 🔲 Todo |

## Mô tả
Service ghi audit log dùng chung cho toàn bộ hệ thống.

## Acceptance Criteria
- [ ] `AuditLogRepository` — insert-only JPA Repository
- [ ] `AuditService.log(action, entityName, entityId, userId, ipAddress)` đúng các cột của `audit_logs`
- [ ] `entityId` là `String` nullable; `userId` nullable và FK dùng `ON DELETE SET NULL`
- [ ] Không persistence `oldValues`/`newValues` vì schema v3.4 không có hai cột này
- [ ] `AuditService` không throw exception ra ngoài — luôn catch & log warning nếu fail
- [ ] Async insert (`@Async`) để không ảnh hưởng performance

## Files cần tạo
- `src/main/java/.../repository/AuditLogRepository.java`
- `src/main/java/.../service/AuditService.java`
