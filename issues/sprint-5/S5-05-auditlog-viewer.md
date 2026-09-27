# S5-05 · AuditLog Viewer API

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Audit |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-LOG-02 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/audit-logs` — Company Admin / System Admin
- [ ] Filter: `userId`, `action`, `entityName`, `dateFrom`, `dateTo`
- [ ] Pagination: `page`, `size`, `sort`
- [ ] Company Admin chỉ xem logs của company mình
- [ ] Response đúng các field: `id`, `userId`, `action`, `entityName`, `entityId`, `ipAddress`, `createdAt`

## Files cần tạo
- `controller/AuditLogController.java`
- `dto/response/AuditLogResponse.java`
