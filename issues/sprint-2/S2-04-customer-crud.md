# S2-04 · Customer CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Customer |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Mô tả
CRUD Customer — khách hàng thuộc Company.

## Acceptance Criteria
- [ ] `CustomerRepository` — `findByCompanyId()`
- [ ] `CustomerService` — create, update, getById, getAll (paging)
- [ ] `CustomerController` — `GET/POST/PUT /api/customers`
- [ ] Filter theo `companyId` tự động từ current user
- [ ] DTO/persistence chỉ gồm `id`, `companyId`, `name`, `contactPhone`, `address`; ba field nghiệp vụ đều bắt buộc
- [ ] `@PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")`

## Files cần tạo
- `repository/CustomerRepository.java`
- `service/CustomerService.java`
- `controller/CustomerController.java`
- `dto/request/CustomerRequest.java`
- `dto/response/CustomerResponse.java`
