# S5b-02 · StopZoneCalculator (OptimizeService)

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Optimize / Zone |
| **Priority** | 🔴 Must |
| **Label** | `PYTHON` |
| **PRD Ref** | FLOW-3 |
| **Depends on** | S5b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Tính kích thước vùng (zone) trong xe cho từng delivery stop theo công thức dynamic sizing. Không hardcode 33%/33%.

## Acceptance Criteria
- [ ] Input: `{ vehicle: VehicleData, stops: [{stop_id, packages: [{volume, weight}]}] }`
- [ ] Output: `{ zones: [{stop_id, zone_start_x, zone_end_x, zone_volume, zone_weight_limit}] }`
- [ ] Formula:
  ```
  volume_ratio_i = sum_volume_stop_i / total_volume_all_stops
  zone_length_i  = vehicle.inner_l * volume_ratio_i + ACCESSIBILITY_BUFFER (0.1m)
  ```
- [ ] ACCESSIBILITY_BUFFER = 0.1m (10cm) giữa các zone
- [ ] Zone đầu tiên = gần cửa sau (rear door) — stop giao trước
- [ ] Zone cuối = sâu nhất — stop giao sau
- [ ] Tổng zone_length ≤ vehicle.inner_l (trừ sum of buffers)
- [ ] Unit test: 3 stops với volume ratio 20%/35%/45% → verify zone sizes

## Files cần tạo (OptimizeService)
- `app/service/optimize/stop_zone_calculator.py`
- `app/dto/optimization/engine/zone_data.py`
- `tests/test_stop_zone_calculator.py`
