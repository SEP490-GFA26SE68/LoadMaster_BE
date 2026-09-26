# S4-01 · API Pin Package

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Pin & Rerun |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-08 / SD06 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria
- [x] `POST /api/load-plans/{id}/pin` — body `{ "packageId": 10 }`
- [x] Validate package có placement thuộc load plan, sau đó set `Package.isPinned = true`
- [x] INSERT AuditLog `PIN_PLACEMENT`
- [x] Trả 200 + package/placement info
- [x] Không thêm field `pinned` vào `PackagePlacement`
