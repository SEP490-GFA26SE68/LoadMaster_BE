# S4b-04 · GoongMapsClient

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Route |
| **Priority** | 🔴 Must |
| **Label** | `BE` `INTEGRATION` |
| **PRD Ref** | FLOW-2, FLOW-6 |
| **Status** | 🔲 Todo |

## Mô tả
HTTP client tích hợp Goong Maps API: Distance Matrix, Directions (road path), và ETA với traffic real-time.

## Acceptance Criteria
- [ ] Config `goong.api.key` từ `.env` (không hardcode, không commit)
- [ ] `getDistanceMatrix(List<LatLng> origins, List<LatLng> destinations)` → `DistanceMatrixResponse`
  - Gọi `https://rsapi.goong.io/distancematrix`
  - Trả về time (giây) và distance (mét) giữa các cặp điểm
- [ ] `getDirections(LatLng origin, LatLng destination)` → `DirectionsResponse`
  - Gọi `https://rsapi.goong.io/direction`
  - Trả về các route options với duration, distance, polyline
- [ ] Timeout: 10 giây; Retry: 2 lần nếu timeout
- [ ] Fallback: nếu Goong unavailable → log warning, trả `ServiceUnavailableException`
- [ ] `RestClient` hoặc `WebClient` (Spring Boot 3)
- [ ] API key trong header `Goong-Api-Key`

## Config
```yaml
goong:
  api:
    key: ${GOONG_API_KEY}
    base-url: https://rsapi.goong.io
    timeout-seconds: 10
```

## Files cần tạo
- `client/GoongMapsClient.java`
- `dto/goong/DistanceMatrixResponse.java`
- `dto/goong/DirectionsResponse.java`
- `src/main/resources/application.yml` (thêm goong config)
- `.env.example` (thêm GOONG_API_KEY)
