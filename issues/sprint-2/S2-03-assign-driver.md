# S2-03 · Gán tài xế cho xe

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Vehicle |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-VEH-03 |
| **Status** | 🔲 Todo |

## Mô tả
API gán/gỡ Driver cho Vehicle.

## Acceptance Criteria
- [ ] `PUT /api/vehicles/{id}/driver` — body `{ "driverUserId": 5 }` hoặc `{ "driverUserId": null }` để gỡ
- [ ] Validate: driver có role `DRIVER`
- [ ] Validate: `vehicles.driver_user_id` unique — driver chưa gán xe khác (1 driver = 1 vehicle)
- [ ] Validate: driver thuộc cùng company

## Dependencies
- S2-02 (Vehicle CRUD), S1-12 (User CRUD)
