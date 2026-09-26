# S1-14 · Company CRUD — Repository + Service + Controller + DTO

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Company |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-COMP-01, FR-COMP-02 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD Company + quản lý trạng thái. Chỉ System Admin.

## Acceptance Criteria
- [ ] `CompanyRepository` — `findByCode()`, `findByTaxCode()`
- [ ] `CompanyService` — create, update, getById, getAll (paging), changeStatus
- [ ] `CompanyController` — `GET/POST/PUT /api/companies`
- [ ] `CompanyRequest` DTO — `code`, `name`, `taxCode`, `phoneNumber`, `status`
- [ ] `CompanyResponse` DTO — `id`, `code`, `name`, `taxCode`, `phoneNumber`, `status`, `createdAt`
- [ ] Validate: unique `code`, unique `taxCode`, độ dài đúng schema; không dùng `billingEmail`
- [ ] Status flow: `ACTIVE` ↔ `SUSPENDED` ↔ `TERMINATED`
- [ ] `PUT /api/companies/{id}/status` — body `{ status: "SUSPENDED" }`
- [ ] `@PreAuthorize("hasAuthority('COMPANY_MANAGE')")`

## Files cần tạo
- `src/main/java/.../repository/CompanyRepository.java`
- `src/main/java/.../service/CompanyService.java`
- `src/main/java/.../controller/CompanyController.java`
- `src/main/java/.../dto/request/CompanyRequest.java`
- `src/main/java/.../dto/response/CompanyResponse.java`
