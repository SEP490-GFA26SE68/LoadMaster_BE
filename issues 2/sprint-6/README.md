# Sprint 6 — Flow 4+5 Upgrade + Flow 6: GPS Monitoring

> 10 issues. Flow 4: approve nâng cấp (deadline check, COG). Flow 5: QR scan khi bốc xếp. Flow 6: GPS tracking, ETA real-time, exception handling.

| Issue | Phạm vi |
|---|---|
| S6-01 | Flow 4 upgrade: deadline feasibility check khi approve + change vehicle |
| S6-02 | Flow 5 upgrade: QR scan verification khi warehouse confirm |
| S6-03 | Entity migration: GpsTracking + TripException |
| S6-04 | GpsTrackingService — nhận và lưu GPS từ Driver App |
| S6-05 | EtaCalculationService — tính ETA real-time dùng Goong |
| S6-06 | TripMonitoringService — so sánh ETA vs deadline, phát alert |
| S6-07 | TripMonitoringController — Dashboard API cho Dispatcher |
| S6-08 | TripExceptionService — ghi nhận exception, update deadline |
| S6-09 | RerouteService — gọi Goong khi có road incident |
| S6-10 | WebSocket GPS broadcast — real-time location update |
