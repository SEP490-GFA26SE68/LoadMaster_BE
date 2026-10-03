# Sprint 5b — Flow 3: Route-Aware 3D Loading Optimization (nâng cấp)

> 7 issues. Nâng cấp engine từ greedy đơn giản lên: Stop Zones + COG + Axle Load + Fragility + Stacking.
> Tất cả thay đổi ở OptimizeService (Python/FastAPI) và entity schema.

| Issue | Phạm vi |
|---|---|
| S5b-01 | Entity migration: LoadPlan thêm COG + axle load; PackagePlacement thêm stop_zone_id; PackageType thêm stacking + rotation |
| S5b-02 | StopZoneCalculator — tính dynamic zone sizing |
| S5b-03 | ConstraintEngine — validate COG, axle load, stacking, fragility, rotation, support area |
| S5b-04 | 3D Engine nâng cấp — tích hợp stop-zone aware packing + constraint engine |
| S5b-05 | Algorithm tier service — BASIC/PRO/ULTIMATE theo subscription |
| S5b-06 | TripValidationService nâng cấp — validate thêm handling_class, COG feasibility |
| S5b-07 | LoadPlan API nâng cấp — trả thêm COG, axle load, rehandling metrics |
