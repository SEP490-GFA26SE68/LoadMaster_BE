# S2-08 · StackingRule CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Order & Package |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Mô tả
CRUD StackingRule — quy tắc chồng hàng giữa các PackageType.

## Acceptance Criteria
- [ ] `StackingRuleRepository` — `findByCompanyId()`
- [ ] `StackingRuleService` — CRUD
- [ ] `StackingRuleController` — `GET/POST/PUT/DELETE /api/stacking-rules`
- [ ] Fields: `companyId`, `bottomPackageTypeId`, `topPackageTypeId`, `isAllowed`
- [ ] Validate cả hai PackageType thuộc cùng `companyId`; không lưu trọng lượng tối đa ở rule (dùng `PackageType.maxStackingWeightKg`)

## Dependencies
- S2-05 (PackageType)
