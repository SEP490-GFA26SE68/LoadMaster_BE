# S4b-05 · StopSequenceOptimizer

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Route |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2 |
| **Depends on** | S4b-04 |
| **Status** | ✅ Done |

## Mô tả
Thuật toán tối ưu thứ tự điểm dừng (TSP) với ràng buộc deadline. Dùng Nearest Neighbor + deadline heuristic.

## Acceptance Criteria
- [x] Input: `{ origin: LatLng, stops: [{id, lat, lng, deadline, packageCount}], distanceMatrix: [][] }`
- [x] Output: `{ orderedStops: [stopId, ...], estimatedTotalDuration: int }`
- [x] Thuật toán: Nearest Neighbor with Deadline Constraint
  - Bắt đầu từ origin
  - Ở mỗi bước: chọn stop gần nhất HOẶC stop có deadline sớm nhất (nếu deadline risk > 30 phút)
  - Tránh backtrack nếu có thể
- [x] Nếu không có route khả thi đáp ứng tất cả deadline → trả về best-effort + danh sách `deadlineMissedStops`
- [x] Unit test cho case: 3 stops, 1 stop deadline ngay mai, 2 stops deadline tuần sau → stop deadline gần ưu tiên trước
- [x] Pure Java, không dependency ngoài

## Files cần tạo
- `service/route/StopSequenceOptimizer.java`
- `dto/route/StopSequenceRequest.java`
- `dto/route/StopSequenceResult.java`
- `test/StopSequenceOptimizerTest.java`
