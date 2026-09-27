# Sprint 2 — Dữ liệu nghiệp vụ

> 16 issues. Entity/field/FK tuân theo `refactor_entity.md` v3.4; mọi ID persistence là `Long/BIGINT`.

| Issue | Phạm vi schema |
|---|---|
| S2-01 | `VehicleType`: company, name, inner dimensions, max payload |
| S2-02 | `Vehicle`: type, nullable unique driver, plate, axle limits |
| S2-03 | Gán `Vehicle.driverUserId` (một driver–một vehicle) |
| S2-04 | `Customer`: name, contact phone, address |
| S2-05 | `PackageType`: dimensions, weight, stacking weight, fragile |
| S2-06 | `Order`: customer, nullable delivery stop, total weight |
| S2-07 | `Package`: type, tracking barcode, actual length/weight, pinned |
| S2-08 | `StackingRule`: bottom/top type, allowed |
| S2-09–S2-12 | Import stateless: preview in-memory, confirm ghi `Order` + `Package` |
| S2-13 | Không tạo `ImportJob`/`ImportRowError` entity |
| S2-14 | `Trip`: vehicle, dispatcher, status mặc định `DRAFT` |
| S2-15 | `DeliveryStop`: sequence, location address |
| S2-16 | Gán `Order.deliveryStopId` nullable |

Các field cũ như door dimensions, type code, rotation flags, order time window/status và stop status không thuộc schema v3.4.
