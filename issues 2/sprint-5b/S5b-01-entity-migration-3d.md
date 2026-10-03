# S5b-01 · Entity Migration — 3D Engine Constraints

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Entity / Migration |
| **Priority** | 🔴 Must |
| **Label** | `BE` `DB` |
| **PRD Ref** | FLOW-3 |
| **Status** | 🔲 Todo |

## Acceptance Criteria — LoadPlan
- [ ] Thêm `cog_x`, `cog_y`, `cog_z` `DECIMAL(10,3)` — Center of Gravity tổng
- [ ] Thêm `front_axle_load`, `rear_axle_load` `DECIMAL(10,2)` — tải trục trước/sau (kg)
- [ ] Thêm `rehandling_count` `INT DEFAULT 0` — số lần phải di chuyển hàng khi dỡ

## Acceptance Criteria — PackagePlacement
- [ ] Thêm `stop_zone_id` `BIGINT REFERENCES delivery_stops(id)` — package thuộc zone nào

## Acceptance Criteria — PackageType
- [ ] Thêm `max_stack_weight_kg` `DECIMAL(10,2)` — tổng khối lượng tối đa có thể đặt lên
- [ ] Thêm `rotation_allowed` `BOOLEAN DEFAULT true`
- [ ] Thêm `fragile` `BOOLEAN DEFAULT false`

## Acceptance Criteria — VehicleType
- [ ] Thêm `front_axle_limit_kg` `DECIMAL(10,2)` — giới hạn tải trục trước
- [ ] Thêm `rear_axle_limit_kg` `DECIMAL(10,2)` — giới hạn tải trục sau
- [ ] Thêm `max_cog_offset_ratio` `DECIMAL(4,2) DEFAULT 0.15` — giới hạn lệch COG (15% chiều dài/rộng)

## Files cần sửa/tạo
- `src/main/resources/db/migration/V{next}__3d_engine_constraints.sql`
- `entity/LoadPlan.java`, `entity/PackagePlacement.java`
- `entity/PackageType.java`, `entity/VehicleType.java`
- OptimizeService: `entity/optimization/load_plan.py`, `entity/optimization/package_placement.py`
