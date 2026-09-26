# Sprint 3 — Optimization Engine

> 12 issues hiện có (gồm S3-06b). OptimizeService dùng chung PostgreSQL schema v3.4; các khóa DB là `BIGSERIAL/BIGINT`, không phải UUID.

## Luồng chính

`Trip → OptimizationJob → LoadPlan → PackagePlacement / UnplacedPackage / CenterOfGravity`

- `OptimizationJob`: `tripId`, `algorithmObjective`, `executionTimeMs`, `status`.
- `LoadPlan`: `jobId`, `planVersion`, `volumeUtilizationPercent`, trạng thái duyệt và người duyệt.
- `PackagePlacement`: tọa độ mm và `loadingSequence`; không persist rotation/packed dimensions.
- `UnplacedPackage`: package + `reasonCode`.
- `CenterOfGravity`: một bản ghi duy nhất mỗi plan.

| Issue | Nội dung |
|---|---|
| S3-01–S3-02 | Validate Trip và API validation |
| S3-03–S3-04 | Tạo/quản lý job và endpoint async |
| S3-05 | DTO engine và mapping xuống schema |
| S3-06/S3-06b | Optimization client và Keycloak service token |
| S3-07–S3-08 | Exception/status và background execution |
| S3-09 | Persist đúng 5 bảng optimization |
| S3-10 | WebSocket notification bằng numeric job ID |
| S3-11 | Approve bằng `isApproved`, `approvedAt`, `approvedByUserId` |

Không tạo `OptimizationMetric`, `LoadPlan.status`, `planName`, count fields hoặc weight-utilization column.
