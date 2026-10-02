# S5b-04 · 3D Packing Engine Upgrade — Stop-Zone Aware + Full Constraints

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Optimize / Engine |
| **Priority** | 🔴 Must |
| **Label** | `PYTHON` |
| **PRD Ref** | FLOW-3 |
| **Depends on** | S5b-02, S5b-03 |
| **Status** | 🔲 Todo |

## Mô tả
Nâng cấp `OptimizationClient.solve_in_process()` từ greedy đơn giản thành zone-aware packing với full constraint checking.

## Acceptance Criteria
- [ ] Trước khi pack: tính stop zones bằng `StopZoneCalculator`
- [ ] Pack theo zone: ưu tiên xếp hàng của stop cuối cùng trước (sâu nhất), stop đầu tiên sau (gần cửa)
- [ ] Với mỗi package trong zone: thử các vị trí bằng EP (Extreme Points)
- [ ] Với mỗi vị trí thử: chạy `ConstraintEngine.check_all()` → chỉ chọn vị trí pass all constraints
- [ ] Nếu không có vị trí pass → `UnplacedPackage(reason=CONSTRAINT_VIOLATED, violated_constraints=[...])`
- [ ] Sau khi pack xong: tính COG tổng và axle load → lưu vào `LoadPlan.cog_x/y/z`, `front_axle_load`, `rear_axle_load`
- [ ] Tính `rehandling_count`: số packages không thuộc zone đúng của stop (do zone đầy)
- [ ] Gán `PackagePlacement.stop_zone_id` = ID của stop tương ứng

## Files cần sửa (OptimizeService)
- `app/service/optimize/optimization_client.py` — thay thế `solve_in_process`
- `app/service/optimize/async_optimization_runner.py` — pass zone data vào engine
