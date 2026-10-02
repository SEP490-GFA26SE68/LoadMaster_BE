# S5b-03 · ConstraintEngine (OptimizeService)

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Optimize / Constraint |
| **Priority** | 🔴 Must |
| **Label** | `PYTHON` |
| **PRD Ref** | FLOW-3 |
| **Depends on** | S5b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Engine kiểm tra tất cả ràng buộc vật lý khi xếp package vào vị trí đề xuất.

## Acceptance Criteria
- [ ] `check_fragility(placement, existing_placements, packages)` — FRAGILE không bị xếp đè (không có package khác nằm trên)
- [ ] `check_stacking(placement, existing_placements, packages)` — tổng weight đặt lên ≤ `max_stack_weight_kg`
- [ ] `check_support_area(placement, existing_placements)` — diện tích đáy tiếp xúc ≥ 70% (không overhang quá 30%)
- [ ] `check_rotation_allowed(package)` — nếu `rotation_allowed=false` thì chỉ dùng rotation_type=0
- [ ] `check_cog(all_placements, vehicle)` — trọng tâm không lệch quá `max_cog_offset_ratio` theo cả X và Y
- [ ] `check_axle_load(all_placements, vehicle)` — tải trục trước/sau không vượt giới hạn
- [ ] Mỗi constraint trả về `ConstraintResult { passed: bool, violation_code: str, detail: str }`
- [ ] `check_all(placement, context)` — chạy tất cả constraints, trả về list violations
- [ ] Unit test cho mỗi constraint riêng biệt

## Files cần tạo (OptimizeService)
- `app/service/optimize/constraint_engine.py`
- `app/dto/optimization/engine/constraint_result.py`
- `tests/test_constraint_engine.py`
