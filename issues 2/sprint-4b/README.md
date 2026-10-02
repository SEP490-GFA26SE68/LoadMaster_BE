# Sprint 4b — Flow 2: Delivery Demand, Cargo Segregation & Route Optimization

> 8 issues. Mục tiêu: CompanyManager tạo nhu cầu, Dispatcher lập trip, system phân nhóm hàng và tối ưu route dùng Goong Maps.
> FIX: DeliveryStop cần thêm lat/lng. Trip cần status flow đầy đủ.

| Issue | Phạm vi |
|---|---|
| S4b-01 | Entity migration: DeliveryStop thêm lat/lng, Trip thêm status flow + route_plan + handling_class_lock |
| S4b-02 | DeliveryRequirement CRUD — CompanyManager tạo nhu cầu giao hàng |
| S4b-03 | CargoSegregationService — tự động phân nhóm, detect conflict |
| S4b-04 | GoongMapsClient — tích hợp Goong Distance Matrix + Directions API |
| S4b-05 | StopSequenceOptimizer — Nearest Neighbor + deadline constraint |
| S4b-06 | RouteOptimizationService — orchestrate Goong + stop optimizer + ETA |
| S4b-07 | TripPlanningController — API cho Dispatcher lập trip, add packages, optimize route |
| S4b-08 | TripStatusService — quản lý chuyển trạng thái Trip |
