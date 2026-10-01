# 🗺️ LoadMaster – Tài liệu Luồng Nghiệp vụ & Kiến trúc Hệ thống (Issues 2)

> **Tài liệu trực quan hóa toàn bộ 8 Luồng Nghiệp vụ & 45 Issues (Sprint 3b → Sprint 8)**  
> **Cơ sở đối chiếu:** `prd.md` (PRD v2.0) & thư mục `issues 2/`  
> **Hệ thống:** LoadMaster Backend (`LoadMasterService` - Spring Boot 3 & `OptimizeService` - FastAPI)

---

## 📑 Mục lục

1. [Kiến trúc Tổng thể Hệ thống (System Architecture)](#1-kiến-trúc-tổng-thể-hệ-thống)
2. [Sơ đồ Thực thể Dữ liệu Mở rộng (ERD Diagram)](#2-sơ-đồ-thực-thể-dữ-liệu-mở-rộng-erd)
3. [Vòng đời Trạng thái Cốt lõi (Core State Machines)](#3-vòng-đời-trạng-thái-cốt-lõi)
   - 3.1 Vòng đời Chuyến xe (Trip Lifecycle)
   - 3.2 Vòng đời Kiện hàng (Package Lifecycle)
   - 3.3 Vòng đời Yêu cầu Pickup (Pickup Request Lifecycle)
   - 3.4 Vòng đời Giao dịch Credit (Credit Lifecycle)
4. [Flow 1 (Sprint 3b) — Import Đơn hàng, Parse File, Sinh QR Code & In nhãn PDF](#4-flow-1-sprint-3b--import-đơn-hàng-sinh-qr-code--in-nhãn-pdf)
5. [Flow 2 (Sprint 4b) — Điều kiện Giao nhận, Phân tách Hàng & Tối ưu Lộ trình (Goong Maps)](#5-flow-2-sprint-4b--điều-kiện-giao-nhận-phân-tách-hàng--tối-ưu-lộ-trình)
6. [Flow 3 (Sprint 5b) — Tối ưu Xếp dỡ 3D Đa điểm (Stop Zones, COG & Axle Load)](#6-flow-3-sprint-5b--tối-ưu-xếp-dỡ-3d-đa-điểm-stop-zones-cog--axle-load)
7. [Flow 4 & 5 (Sprint 6) — Duyệt Kế hoạch, Xuất kho & Quét QR Đối soát Tải xe](#7-flow-4--5-sprint-6--duyệt-kế-hoạch-xuất-kho--quét-qr-đối-soát)
8. [Flow 6 (Sprint 6) — Giám sát GPS Real-time, Tính ETA & Xử lý Ngoại lệ Chuyến đi](#8-flow-6-sprint-6--giám-sát-gps-real-time-tính-eta--xử-lý-ngoại-lệ)
9. [Flow 7 (Sprint 7) — Nhận thêm Hàng Dọc đường & Tái Tối ưu Không gian Giải phóng](#9-flow-7-sprint-7--nhận-thêm-hàng-dọc-đường--tái-tối-ưu-freed-zone)
10. [Flow 8 (Sprint 8) — Gói cước (Subscription), Số dư Credit & Cổng Thanh toán VNPay](#10-flow-8-sprint-8--gói-cước-subscription-credit--cổng-vnpay)
11. [Ma trận Ánh xạ Issues 2 (Sprint 3b → Sprint 8)](#11-ma-trận-ánh-xạ-issues-2)

---

## 1. Kiến trúc Tổng thể Hệ thống

Hệ thống gồm 2 backend services chính dùng chung PostgreSQL DB, tích hợp các đối tác dịch vụ bên ngoài (Goong Maps, VNPay, Keycloak) và giao tiếp thời gian thực qua WebSocket STOMP.

```mermaid
flowchart TD
    subgraph Clients["Lớp Ứng dụng Phía Người dùng (Clients)"]
        WEB["Dispatcher Web Dashboard\n(React / Vite)"]
        MOB_W["Warehouse Staff App\n(Quét QR xuất kho)"]
        MOB_D["Driver Mobile App\n(GPS, QR dỡ hàng, Pickup)"]
        COMP_M["Company Manager Portal\n(Gói cước, Mua Credit)"]
    end

    subgraph Security["Xác thực & Ủy quyền"]
        KC["Keycloak IAM Server\n(OAuth2 / OIDC Bearer JWT)"]
    end

    subgraph CoreBackend["LoadMasterService (Spring Boot 3 - Port 8080)"]
        GW["API Controller Layer"]
        CG["CreditGuard AOP Interceptor"]
        PKG_SVC["Package & QR Code Service\n(ZXing + OpenPDF)"]
        ROUTE_SVC["Route Optimization Service\n(Goong Maps Client)"]
        SEGR_SVC["Cargo Segregation Service"]
        VERIF_SVC["Warehouse QR Verify Service"]
        GPS_SVC["GPS Tracking & ETA Service"]
        PICKUP_SVC["Pickup & Freed Zone Service"]
        SUB_SVC["Subscription & Credit Service"]
        WS_SVC["WebSocket STOMP Broker\n(/topic/trips/{id}/gps)"]
    end

    subgraph OptimizationEngine["OptimizeService (Python FastAPI - Port 8000)"]
        FAST_API["FastAPI Controller"]
        SZ_CALC["Stop Zone Calculator"]
        CONST_ENG["Constraint Engine\n(COG, Axle Load, LIFO)"]
        PACK_ENG["3D Packing Algorithm\n(EP-DBLF / GA / AI Tier)"]
        JOB_WORKER["Background Optimization Worker"]
    end

    subgraph ExternalServices["Dịch vụ Tích hợp Ngoại vi"]
        GOONG["Goong Maps API\n(Geocoding & Distance Matrix)"]
        VNPAY["Cổng Thanh toán VNPay\n(Payment URL & IPN Webhook)"]
    end

    subgraph Persistence["Lớp Dữ liệu (Persistence Layer)"]
        DB[("PostgreSQL Database\n(Shared Schema v3.4+\nMulti-tenant by company_id)")]
    end

    %% Client authentication
    Clients -->|1. Lấy Bearer Token| KC
    Clients -->|2. Gửi REST API Request mang JWT| GW

    %% Core routing
    GW --> CG
    CG --> PKG_SVC
    CG --> ROUTE_SVC
    CG --> SEGR_SVC
    CG --> VERIF_SVC
    CG --> GPS_SVC
    CG --> PICKUP_SVC
    CG --> SUB_SVC

    %% Integrations
    ROUTE_SVC -->|Tọa độ & Ma trận cự ly| GOONG
    SUB_SVC -->|Tạo giao dịch thanh toán| VNPAY
    VNPAY -->|IPN Webhook xác nhận| SUB_SVC

    %% Core to Optimization Service
    CG -->|3. Trigger tối ưu 3D kèm thông tin Tier/Credit| FAST_API
    FAST_API --> SZ_CALC
    SZ_CALC --> CONST_ENG
    CONST_ENG --> PACK_ENG
    PACK_ENG --> JOB_WORKER

    %% Database connections
    CoreBackend -->|JPA / Hibernate CRUD| DB
    OptimizationEngine -->|SQLAlchemy Read/Write Job & Placements| DB

    %% Realtime push
    GPS_SVC -->|Broadcast tọa độ & ETA| WS_SVC
    WS_SVC -.->|WebSocket Push| WEB
    JOB_WORKER -.->|WebSocket Job Status| WEB
```

---

## 2. Sơ đồ Thực thể Dữ liệu Mở rộng (ERD)

Sơ đồ thể hiện đầy đủ các trường và mối quan hệ giữa các bảng được thêm mới hoặc bổ sung qua các migration trong `issues 2`:

```mermaid
erDiagram
    COMPANIES ||--o{ USERS : "has"
    COMPANIES ||--o{ PACKAGES : "owns"
    COMPANIES ||--o{ TRIPS : "manages"
    COMPANIES ||--o| COMPANY_SUBSCRIPTIONS : "subscribes"
    COMPANIES ||--o| CREDIT_ACCOUNTS : "holds"

    SUBSCRIPTION_PLANS ||--o{ COMPANY_SUBSCRIPTIONS : "defines"
    CREDIT_ACCOUNTS ||--o{ CREDIT_TRANSACTIONS : "logs"
    COMPANIES ||--o{ PAYMENT_TRANSACTIONS : "executes"

    VEHICLE_TYPES ||--o{ VEHICLES : "specifies"
    VEHICLES ||--o{ TRIPS : "assigned_to"

    TRIPS ||--o{ DELIVERY_STOPS : "contains"
    TRIPS ||--o{ DELIVERY_REQUIREMENTS : "governed_by"
    TRIPS ||--o{ LOAD_PLANS : "has"
    TRIPS ||--o{ GPS_TRACKINGS : "tracked_by"
    TRIPS ||--o{ TRIP_EXCEPTIONS : "experiences"
    TRIPS ||--o{ PICKUP_REQUESTS : "handles"

    DELIVERY_STOPS ||--o{ STOP_ZONES : "mapped_to"
    DELIVERY_STOPS ||--o{ PACKAGES : "delivers"

    LOAD_PLANS ||--o{ PACKAGE_PLACEMENTS : "contains"
    PACKAGES ||--o| PACKAGE_PLACEMENTS : "positioned_as"
    PACKAGES }|--|| PACKAGE_TYPES : "categorized_by"

    PICKUP_REQUESTS ||--o{ PICKUP_PACKAGES : "includes"

    COMPANIES {
        bigint id PK
        string name
        string code
    }

    SUBSCRIPTION_PLANS {
        bigint id PK
        string code
        string tier "BASIC | PRO | ULTIMATE"
        int monthly_credits
        string algorithm_tier "EP_DBLF | EP_DBLF_GA | EP_DBLF_GA_AI"
        decimal price_vnd
        json features
    }

    COMPANY_SUBSCRIPTIONS {
        bigint id PK
        bigint company_id FK
        bigint plan_id FK
        string status "ACTIVE | EXPIRED | CANCELLED"
        timestamp started_at
        timestamp expires_at
    }

    CREDIT_ACCOUNTS {
        bigint id PK
        bigint company_id FK
        int balance "CHECK balance >= 0"
    }

    CREDIT_TRANSACTIONS {
        bigint id PK
        bigint credit_account_id FK
        int amount
        string type "MONTHLY_GRANT | PURCHASE | USAGE | REFUND"
        string reference
        boolean refunded
        timestamp created_at
    }

    PAYMENT_TRANSACTIONS {
        bigint id PK
        bigint company_id FK
        string gateway "VNPAY"
        string gateway_transaction_id UK "Idempotency key"
        decimal amount_vnd
        string status "PENDING | SUCCESS | FAILED"
        json payload
    }

    VEHICLE_TYPES {
        bigint id PK
        decimal front_axle_limit_kg
        decimal rear_axle_limit_kg
        decimal max_cog_offset_ratio "Default 0.15"
    }

    TRIPS {
        bigint id PK
        bigint company_id FK
        string status "DRAFT | PLANNED | LOADING | IN_TRANSIT | DELIVERED | CANCELLED"
        string handling_class_lock "STANDARD | FRAGILE | REFRIGERATED | HAZARDOUS"
        text override_reason "Nullable - ghi log khi override segregation"
        json route_plan "Lưu kết quả Goong Maps"
    }

    DELIVERY_STOPS {
        bigint id PK
        bigint trip_id FK
        int stop_order
        decimal latitude
        decimal longitude
        string status "PENDING | ARRIVED | COMPLETED"
        timestamp planned_arrival
        timestamp actual_arrival
    }

    PACKAGES {
        bigint id PK
        bigint company_id FK
        bigint stop_id FK
        string package_code "Mã nội bộ khách hàng"
        string qr_token UK "UUID bất biến"
        string handling_class "STANDARD | FRAGILE | REFRIGERATED | HAZARDOUS"
        int length_mm
        int width_mm
        int height_mm
        decimal weight_kg
    }

    LOAD_PLANS {
        bigint id PK
        bigint trip_id FK
        decimal cog_x
        decimal cog_y
        decimal cog_z
        decimal front_axle_load
        decimal rear_axle_load
        int rehandling_count
        boolean is_approved
    }

    GPS_TRACKINGS {
        bigint id PK
        bigint trip_id FK
        decimal latitude
        decimal longitude
        decimal speed_kmh
        int heading
        timestamp recorded_at
    }

    TRIP_EXCEPTIONS {
        bigint id PK
        bigint trip_id FK
        string exception_type "TRAFFIC | ACCIDENT | BREAKDOWN | OTHER"
        text description
        boolean resolved
        timestamp new_deadline
    }

    PICKUP_REQUESTS {
        bigint id PK
        bigint trip_id FK
        decimal pickup_lat
        decimal pickup_lng
        string status "PENDING | VALIDATED | APPROVED | REJECTED"
        json validation_errors
    }

    PICKUP_PACKAGES {
        bigint id PK
        bigint pickup_request_id FK
        string qr_token UK "UUID sinh ngay khi tạo"
        int length_mm
        int width_mm
        int height_mm
        decimal weight_kg
        string handling_class
    }
```

---

## 3. Vòng đời Trạng thái Cốt lõi

### 3.1 Vòng đời Chuyến xe (Trip Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> DRAFT: Tạo Trip mới & gán đơn hàng
    
    DRAFT --> PLANNED: Tối ưu lộ trình (Goong) & Xếp dỡ 3D hoàn tất
    DRAFT --> CANCELLED: Hủy trước khi lập kế hoạch

    PLANNED --> LOADING: Dispatcher duyệt kế hoạch & Bắt đầu quét QR xếp xe
    PLANNED --> DRAFT: Điều chỉnh lại lộ trình / Re-run tối ưu
    PLANNED --> CANCELLED: Hủy chuyến

    LOADING --> IN_TRANSIT: Quét đủ 100% QR đối soát & Tài xế xuất phát
    LOADING --> PLANNED: Phát hiện sai lệch hàng hóa nghiêm trọng cần xếp lại

    IN_TRANSIT --> IN_TRANSIT: Báo cáo vị trí GPS (mỗi 30s) / Gặp ngoại lệ & Re-route
    IN_TRANSIT --> IN_TRANSIT: Nhận thêm hàng dọc đường (En-route Pickup)
    IN_TRANSIT --> DELIVERED: Đã giao hết toàn bộ điểm dừng (All Stops COMPLETED)
    IN_TRANSIT --> CANCELLED: Sự cố nghiêm trọng không thể tiếp tục

    DELIVERED --> [*]
    CANCELLED --> [*]
```

### 3.2 Vòng đời Kiện hàng (Package Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> IMPORTED: Import từ Excel/CSV & sinh mã QR Code
    
    IMPORTED --> ASSIGNED: Gán vào Chuyến xe (Trip) & Điểm dỡ (DeliveryStop)
    ASSIGNED --> STAGED: Hàng đã chuyển ra khu vực chờ xuất kho
    
    STAGED --> LOADED: Kho quét QR xác nhận đã đưa lên xe (đúng thứ tự LIFO)
    LOADED --> IN_TRANSIT: Xe khởi hành rời kho
    
    IN_TRANSIT --> DELIVERED: Tài xế quét QR bàn giao tại điểm nhận
    IN_TRANSIT --> RETURNED: Khách từ chối nhận hàng (Exception ghi nhận)
    
    DELIVERED --> [*]
    RETURNED --> [*]
```

### 3.3 Vòng đời Yêu cầu Pickup (Pickup Request Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> PENDING: Khách gửi yêu cầu pickup dọc đường
    
    PENDING --> VALIDATED: FreedZoneService & ConstraintEngine xác nhận hợp lệ
    PENDING --> REJECTED: Vi phạm tải trọng / Không vừa Freed Zone / Sai loại hàng
    
    VALIDATED --> APPROVED: Dispatcher chấp thuận & hệ thống Re-optimize thành công
    APPROVED --> LOADED: Tài xế tới điểm đón & quét mã QR xác nhận nhận hàng
    LOADED --> DELIVERED: Giao hàng tới điểm đích quy định
    
    REJECTED --> [*]
    DELIVERED --> [*]
```

### 3.4 Vòng đời Giao dịch Credit (Credit Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE: Số dư Credit khả dụng trong CreditAccount
    
    AVAILABLE --> RESERVED: @CreditGuard tạm giữ khi bắt đầu chạy Optimization Job
    
    RESERVED --> DEDUCTED: Optimization Job COMPLETED thành công (Khấu trừ chính thức)
    RESERVED --> REFUNDED: Optimization Job FAILED hoặc TIMEOUT (Hoàn trả lại tài khoản)
    
    DEDUCTED --> [*]
    REFUNDED --> AVAILABLE: Khôi phục lại số dư khả dụng
```

---

## 4. Flow 1 (Sprint 3b) — Import Đơn hàng, Sinh QR Code & In nhãn PDF

> **Issues thực hiện:** `S3b-01` → `S3b-07`  
> **Mục tiêu:** Nhập dữ liệu hàng loạt từ file Excel/CSV, xác thực kích thước/loại hàng, sinh mã định danh UUID bất biến và xuất file PDF nhãn nhấc hàng chuẩn A6.

```mermaid
sequenceDiagram
    autonumber
    actor Dispatcher as Dispatcher
    participant Ctrl as PackageImportController
    participant Parser as PackageFileParser
    participant Valid as PackageImportValidator
    participant PkgSvc as PackageService
    participant QrSvc as QrCodeService
    participant PdfSvc as PdfLabelExportService
    participant DB as PostgreSQL

    Dispatcher->>Ctrl: POST /api/packages/import (Multipart File Excel/CSV)
    Ctrl->>Parser: parse(fileInputStream)
    Parser-->>Ctrl: List<RawPackageDto>
    
    Ctrl->>Valid: validate(rawList)
    alt Dữ liệu có dòng lỗi (kích thước <= 0, thiếu mã, sai định dạng)
        Valid-->>Ctrl: Danh sách lỗi theo từng số dòng (RowErrors)
        Ctrl-->>Dispatcher: 400 Bad Request kèm chi tiết từng dòng lỗi
    else Dữ liệu hợp lệ 100%
        Valid-->>Ctrl: List<ValidPackageDto>
        Ctrl->>PkgSvc: saveBatch(validList, companyId)
        
        loop Từng kiện hàng
            PkgSvc->>QrSvc: generateStableQrToken()
            QrSvc-->>PkgSvc: UUID Token (Ví dụ: "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            PkgSvc->>QrSvc: renderQrImageBase64(qrToken)
            QrSvc-->>PkgSvc: Base64 PNG image
        end
        
        PkgSvc->>DB: INSERT INTO packages (company_id, qr_token, handling_class, ...)
        DB-->>PkgSvc: Đã lưu thành công
        PkgSvc-->>Ctrl: List<PackageResponseDto>
        Ctrl-->>Dispatcher: 201 Created (Tổng số kiện đã import thành công)
    end

    opt Xuất nhãn in dán kiện hàng (PDF A6)
        Dispatcher->>Ctrl: GET /api/packages/labels/pdf?ids=1,2,3
        Ctrl->>PdfSvc: exportLabelsPdf(packageIds)
        PdfSvc->>DB: Lấy thông tin kiện hàng & QR Token
        DB-->>PdfSvc: Package entities
        PdfSvc->>PdfSvc: Render layout A6 (Mã đơn, Kích thước, Trọng lượng, Handling Class, QR Code)
        PdfSvc-->>Ctrl: byte[] (application/pdf)
        Ctrl-->>Dispatcher: Tải file nhãn `packages-label.pdf`
    end
```

---

## 5. Flow 2 (Sprint 4b) — Điều kiện Giao nhận, Phân tách Hàng & Tối ưu Lộ trình

> **Issues thực hiện:** `S4b-01` → `S4b-08`  
> **Mục tiêu:** Kiểm tra xung đột loại hàng (Cargo Segregation), gọi Goong Maps API tối ưu hóa thứ tự các điểm dừng dỡ hàng (Stop Sequence) theo quy tắc LIFO.

```mermaid
sequenceDiagram
    autonumber
    actor Dispatcher as Dispatcher
    participant Ctrl as TripPlanningController
    participant SegSvc as CargoSegregationService
    participant RouteSvc as RouteOptimizationService
    participant Goong as GoongMapsClient
    participant StopOpt as StopSequenceOptimizer
    participant TripSvc as TripStatusService
    participant DB as PostgreSQL

    Dispatcher->>Ctrl: POST /api/trips/{id}/plan-route
    Ctrl->>SegSvc: checkCompatibility(tripPackages)
    
    alt Phát hiện xung đột (Ví dụ: Hóa chất đi chung Thực phẩm)
        SegSvc-->>Ctrl: SegregationConflictException (Chi tiết loại hàng xung đột)
        Ctrl-->>Dispatcher: 409 Conflict (Yêu cầu tách chuyến hoặc gửi override_reason)
        
        opt Dispatcher chấp nhận Override rủi ro
            Dispatcher->>Ctrl: POST /api/trips/{id}/plan-route (kèm override_reason)
            Ctrl->>DB: UPDATE trips SET override_reason = :reason
        end
    end

    Ctrl->>RouteSvc: optimizeRoute(tripId)
    RouteSvc->>DB: Lấy tọa độ kho xuất phát và các DeliveryStop (lat, lng)
    DB-->>RouteSvc: Danh sách tọa độ các điểm dừng
    
    RouteSvc->>Goong: getDistanceMatrix(origins, destinations)
    Goong-->>RouteSvc: Ma trận khoảng cách & thời gian di chuyển (Distance Matrix)
    
    RouteSvc->>StopOpt: calculateOptimalSequence(matrix, constraints)
    Note over StopOpt: Tính thứ tự Stop tối ưu (giảm quãng đường + tôn trọng Time Window)
    StopOpt-->>RouteSvc: Thứ tự điểm dừng mới: [Stop 1, Stop 2, Stop 3]
    
    RouteSvc->>Goong: getDirections(orderedCoordinates)
    Goong-->>RouteSvc: Polyline chi tiết, tổng km, tổng thời gian dự kiến
    
    RouteSvc->>DB: Cập nhật stop_order, planned_arrival, lưu route_plan JSON vào trip
    RouteSvc->>TripSvc: transitionStatus(tripId, PLANNED)
    TripSvc->>DB: UPDATE trips SET status = 'PLANNED'
    
    Ctrl-->>Dispatcher: 200 OK (Chi tiết lộ trình tối ưu & thứ tự điểm dừng)
```

---

## 6. Flow 3 (Sprint 5b) — Tối ưu Xếp dỡ 3D Đa điểm (Stop Zones, COG & Axle Load)

> **Issues thực hiện:** `S5b-01` → `S5b-07`  
> **Mục tiêu:** Tính toán không gian khoang xe phân bổ theo điểm dừng dỡ hàng (Stop Zones theo thứ tự LIFO), đảm bảo trọng tâm xe (COG) và không vượt tải trọng trục (Axle Load).

```mermaid
sequenceDiagram
    autonumber
    actor Dispatcher as Dispatcher
    participant LoadMaster as LoadMasterService (:8080)
    participant Guard as @CreditGuard AOP
    participant FastApi as OptimizeService (:8000)
    participant ZoneCalc as StopZoneCalculator
    participant Constraint as ConstraintEngine
    participant Engine as 3D Packing Engine
    participant DB as PostgreSQL

    Dispatcher->>LoadMaster: POST /api/trips/{id}/optimize-3d
    LoadMaster->>Guard: Kiểm tra Subscription Tier & Số dư Credit
    Guard->>DB: SELECT balance FROM credit_accounts WHERE company_id = ?
    DB-->>Guard: balance >= required_credits
    Guard->>DB: Ghi nhận CreditTransaction (type = USAGE, status = RESERVED)

    LoadMaster->>FastApi: POST /api/v1/optimization/multi-stop (Trip Data, Vehicle, Packages, Stops)
    
    Note over FastApi,ZoneCalc: Bước 1: Phân bổ không gian thùng xe theo Stop (LIFO)
    FastApi->>ZoneCalc: calculateZones(vehicleDims, stopOrders, packageVolumes)
    ZoneCalc-->>FastApi: Stop Zones: [Zone Stop 3 (Sát cabin), Zone Stop 2 (Giữa), Zone Stop 1 (Cửa sau)]

    Note over FastApi,Constraint: Bước 2: Kiểm tra các ràng buộc vật lý
    FastApi->>Constraint: validateConstraints(vehicleSpec, packages)
    Constraint-->>FastApi: Giới hạn Axle Load, COG offset cho phép (<= 15%), Chiều xoay, Chồng tầng

    Note over FastApi,Engine: Bước 3: Chạy thuật toán xếp hàng 3D
    FastApi->>Engine: runPacking(algorithm_tier, zones, packages, constraints)
    Engine-->>FastApi: Kết quả tọa độ (x, y, z), COG thực tế, Tải trọng trục trước/sau, Rehandling count = 0

    FastApi->>DB: INSERT INTO load_plans (trip_id, cog_x, cog_y, cog_z, front_axle_load, rear_axle_load, ...)
    FastApi->>DB: INSERT INTO package_placements (load_plan_id, package_id, x, y, z, rotation)
    FastApi-->>LoadMaster: 200 OK (LoadPlan UUID, Metrics)
    
    LoadMaster->>Guard: Commit trừ Credit chính thức
    Guard->>DB: UPDATE credit_accounts SET balance = balance - required_credits
    LoadMaster-->>Dispatcher: Trả về kết quả xếp 3D (3D Viewer JSON)
```

```mermaid
flowchart LR
    subgraph VehicleLayout["Mô hình Phân vùng Khoang xe theo Thứ tự Dỡ hàng (LIFO Stop Zones)"]
        direction LR
        CABIN["[CABIN ĐẦU XE]\nTrọng tâm Động cơ"]
        ZONE3["STOP ZONE 3\n(Hàng dỡ cuối cùng)\nNằm sát vách cabin"]
        ZONE2["STOP ZONE 2\n(Hàng dỡ điểm thứ 2)\nNằm khoang giữa xe"]
        ZONE1["STOP ZONE 1\n(Hàng dỡ điểm đầu tiên)\nNằm ngay sát cửa đuôi xe"]
        DOOR["[CỬA THÙNG XE]\nKhu vực dỡ hàng trước"]
        
        CABIN --- ZONE3
        ZONE3 --- ZONE2
        ZONE2 --- ZONE1
        ZONE1 --- DOOR
    end
```

---

## 7. Flow 4 & 5 (Sprint 6) — Duyệt Kế hoạch, Xuất kho & Quét QR Đối soát

> **Issues thực hiện:** `S6-01`, `S6-02`, `S4b-08`  
> **Mục tiêu:** Quản trị viên duyệt kế hoạch xếp hàng, nhân viên kho dùng thiết bị cầm tay quét mã QR đối soát kiện hàng lên xe, đảm bảo xếp đúng thứ tự LIFO và không nhầm lẫn xe.

```mermaid
sequenceDiagram
    autonumber
    actor Dispatcher as Dispatcher
    actor Warehouse as Warehouse Staff
    participant Core as LoadMasterService
    participant VerifySvc as WarehouseVerificationService
    participant TripSvc as TripStatusService
    participant DB as PostgreSQL

    Dispatcher->>Core: POST /api/load-plans/{id}/approve
    Core->>DB: UPDATE load_plans SET is_approved = true
    Core->>TripSvc: setStatus(tripId, PLANNED)
    TripSvc->>DB: UPDATE trips SET status = 'PLANNED'
    Core-->>Dispatcher: Kế hoạch đã được phê duyệt

    Note over Warehouse,Core: Bắt đầu quy trình bốc xếp hàng tại kho
    Warehouse->>Core: POST /api/trips/{id}/start-loading
    Core->>TripSvc: setStatus(tripId, LOADING)
    TripSvc->>DB: UPDATE trips SET status = 'LOADING'

    loop Quét từng kiện hàng đưa lên xe
        Warehouse->>Core: POST /api/warehouse/verify-package\n{trip_id, qr_token: "f47ac10b-..."}
        Core->>VerifySvc: verifyPackageLoading(tripId, qrToken)
        VerifySvc->>DB: Kiểm tra qr_token có thuộc trip_id này không
        
        alt Sai kiện hàng (Kiện của xe khác)
            VerifySvc-->>Warehouse: ❌ CẢNH BÁO: Kiện hàng không thuộc chuyến xe này!
        else Sai thứ tự dỡ hàng LIFO
            VerifySvc-->>Warehouse: ⚠️ CẢNH BÁO: Đang xếp hàng của Stop 1 trước Stop 3 (Sai LIFO)!
        else Đúng kiện và đúng thứ tự
            VerifySvc->>DB: UPDATE packages SET status = 'LOADED'
            VerifySvc-->>Warehouse: ✅ HỢP LỆ: Đã xác nhận đưa lên xe (Tiến độ: 14/20 kiện)
        end
    end

    Warehouse->>Core: POST /api/trips/{id}/complete-loading
    Core->>VerifySvc: checkAllPackagesLoaded(tripId)
    VerifySvc-->>Core: 100% kiện hàng đã được scan xác nhận
    Core->>TripSvc: setStatus(tripId, IN_TRANSIT)
    TripSvc->>DB: UPDATE trips SET status = 'IN_TRANSIT'
    Core-->>Warehouse: Hoàn tất xuất kho, bàn giao Chuyến xe cho Tài xế
```

---

## 8. Flow 6 (Sprint 6) — Giám sát GPS Real-time, Tính ETA & Xử lý Ngoại lệ

> **Issues thực hiện:** `S6-03` → `S6-10`  
> **Mục tiêu:** Nhận tọa độ GPS liên tục từ ứng dụng Tài xế, tính toán lại thời gian đến dự kiến (ETA), phát hiện lệch lộ trình và xử lý ngoại lệ giao nhận (tắc đường, tai nạn).

```mermaid
sequenceDiagram
    autonumber
    actor Driver as Driver Mobile App
    actor Dispatcher as Dispatcher UI
    participant GpsCtrl as MonitoringController
    participant GpsSvc as GpsTrackingService
    participant EtaSvc as EtaCalculationService
    participant ExSvc as TripExceptionService
    participant WS as WebSocket STOMP (/topic/trips/{id}/gps)
    participant Goong as Goong Maps API
    participant DB as PostgreSQL

    loop Mỗi 30 giây khi xe đang chạy (IN_TRANSIT)
        Driver->>GpsCtrl: POST /api/monitoring/gps\n{tripId, latitude, longitude, speed, heading}
        GpsCtrl->>GpsSvc: recordGps(data)
        GpsSvc->>DB: INSERT INTO gps_tracking (...)
        
        GpsSvc->>EtaSvc: calculateLiveEta(tripId, currentLat, currentLng)
        EtaSvc->>Goong: getDistances(currentLocation, nextStopLocation)
        Goong-->>EtaSvc: Quãng đường còn lại & Thời gian dự kiến (duration)
        EtaSvc->>DB: Cập nhật actual/estimated arrival tại DeliveryStop
        
        GpsSvc->>WS: broadcastGpsUpdate(tripId, liveCoordinates, newEta)
        WS-->>Dispatcher: Nhận tọa độ thời gian thực & vị trí xe trên bản đồ
    end

    alt Tài xế báo cáo sự cố dọc đường (Exception)
        Driver->>GpsCtrl: POST /api/monitoring/exceptions\n{tripId, type: "TRAFFIC", description: "Cấm đường do sửa cầu"}
        GpsCtrl->>ExSvc: reportException(dto)
        ExSvc->>DB: INSERT INTO trip_exceptions (...)
        ExSvc->>Goong: calculateReroute(currentLocation, remainingStops, avoidAreas)
        Goong-->>ExSvc: Lộ trình thay thế mới (New Polyline & New ETA)
        ExSvc->>DB: UPDATE trips SET route_plan = :newRoutePlan
        ExSvc->>WS: broadcastExceptionAndReroute(tripId, exceptionDetails, newRoute)
        WS-->>Dispatcher: Cảnh báo đỏ trên bản đồ Dispatcher kèm lộ trình mới
        ExSvc-->>Driver: Cập nhật bản đồ chỉ đường mới trên ứng dụng Tài xế
    end
```

---

## 9. Flow 7 (Sprint 7) — Nhận thêm Hàng Dọc đường & Tái Tối ưu Freed Zone

> **Issues thực hiện:** `S7-01` → `S7-05`  
> **Mục tiêu:** Tận dụng không gian khoang xe đã được giải phóng (Freed Zone) sau khi dỡ hàng tại các Stop trước để nhận thêm hàng mới mà không làm xáo trộn hàng còn lại.

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Khách hàng / Dispatcher
    actor Driver as Tài xế xe đang chạy
    participant Core as LoadMasterService
    participant PickupSvc as PickupRequestService
    participant FreedSvc as FreedZoneService
    participant ReoptSvc as PickupReoptimizationService
    participant FastApi as OptimizeService (:8000)
    participant DB as PostgreSQL

    Customer->>Core: POST /api/pickup-requests\n{pickup_lat, pickup_lng, dest_lat, dest_lng, packages: [...]}
    Core->>PickupSvc: createRequest(dto)
    PickupSvc->>DB: Tìm các xe đang có status = 'IN_TRANSIT' ở gần điểm pickup
    DB-->>PickupSvc: Xe 29C-12345 (Đã hoàn thành Stop 1, đang tới gần điểm pickup)

    PickupSvc->>FreedSvc: calculateFreedZone(tripId)
    Note over FreedSvc: Tính thể tích và tải trọng đã dỡ ở Stop 1 (Freed Zone tại cửa đuôi xe)
    FreedSvc-->>PickupSvc: Freed Zone: Kích thước khả dụng & Tải trọng dư thừa cho phép

    PickupSvc->>ReoptSvc: validateSpatialAndWeight(freedZone, newPackages)
    alt Không đủ thể tích hoặc vượt tải trọng trục xe
        ReoptSvc-->>Customer: 400 Bad Request: Xe không đủ không gian khả dụng để nhận thêm
    else Thỏa mãn điều kiện xếp dỡ
        ReoptSvc->>FastApi: POST /api/v1/optimization/re-optimize-freed-zone
        FastApi-->>ReoptSvc: Kế hoạch xếp hàng mới (Không dịch chuyển hàng của Stop 2 & Stop 3)
        
        PickupSvc->>DB: INSERT INTO pickup_packages (sinh qr_token UUID tức thì)
        PickupSvc->>DB: UPDATE pickup_requests SET status = 'APPROVED'
        
        PickupSvc->>Core: Gửi Push Notification tới ứng dụng của Tài xế
        Core-->>Driver: Thông báo: "Chấp nhận đơn nhận hàng mới tại Stop 1B" kèm mã QR
        
        Driver->>Core: POST /api/driver/pickup-confirm (Quét mã QR kiện pickup)
        Core->>DB: Cập nhật kiện pickup đã xếp lên xe
        Core-->>Customer: Đơn pickup đã được lấy thành công
    end
```

---

## 10. Flow 8 (Sprint 8) — Gói cước (Subscription), Credit & Cổng VNPay

> **Issues thực hiện:** `S8-01` → `S8-08`  
> **Mục tiêu:** Quản lý gói cước công ty (Basic, Pro, Ultimate), nạp tiền mua credit qua VNPay có kiểm tra Idempotency, và áp dụng AOP `@CreditGuard` bảo vệ API tối ưu.

```mermaid
sequenceDiagram
    autonumber
    actor Manager as Company Manager
    participant Ctrl as SubscriptionController
    participant SubSvc as CompanySubscriptionService
    participant CreditSvc as CreditService
    participant VNPaySvc as VNPayIntegrationService
    participant Gateway as Cổng Thanh toán VNPay
    participant Webhook as PaymentWebhookController
    participant DB as PostgreSQL

    Manager->>Ctrl: POST /api/subscriptions/purchase-credits\n{amount_credits: 500, amount_vnd: 500000}
    Ctrl->>VNPaySvc: createPaymentUrl(companyId, amountVnd, returnUrl)
    VNPaySvc->>DB: INSERT INTO payment_transactions (status = 'PENDING', gateway_tx_id = ?)
    VNPaySvc-->>Ctrl: Payment URL (chứa chữ ký bảo mật HMAC-SHA512)
    Ctrl-->>Manager: Redirect URL sang cổng VNPay

    Manager->>Gateway: Tiến hành thanh toán qua App Ngân hàng / Quét VNPay-QR
    Gateway-->>Gateway: Xác nhận giao dịch thành công

    Note over Gateway,Webhook: VNPay gọi IPN Webhook Server-to-Server
    Gateway->>Webhook: POST /api/payments/vnpay-ipn (Checksum, TxId, Amount, ResponseCode: "00")
    Webhook->>VNPaySvc: verifyChecksum(payload)
    
    alt Chữ ký số không hợp lệ
        VNPaySvc-->>Webhook: Invalid Checksum
        Webhook-->>Gateway: {"RspCode": "97", "Message": "Invalid Signature"}
    else Chữ ký hợp lệ
        Webhook->>DB: SELECT * FROM payment_transactions WHERE gateway_transaction_id = ?
        
        alt Giao dịch đã xử lý trước đó (Idempotency check)
            Webhook-->>Gateway: {"RspCode": "02", "Message": "Order already confirmed"}
        else Giao dịch hợp lệ lần đầu
            Webhook->>DB: UPDATE payment_transactions SET status = 'SUCCESS'
            Webhook->>CreditSvc: addCredits(companyId, 500, type = 'PURCHASE')
            CreditSvc->>DB: UPDATE credit_accounts SET balance = balance + 500
            CreditSvc->>DB: INSERT INTO credit_transactions (amount = 500, type = 'PURCHASE')
            Webhook-->>Gateway: {"RspCode": "00", "Message": "Confirm Success"}
        end
    end
```

---

## 11. Ma trận Ánh xạ Issues 2

| Sprint | Mã Issue | Tên Issue & Mô tả | Flow Nghiệp vụ | Thành phần Chính (Files) |
|---|---|---|---|---|
| **3b** | `S3b-01` | Entity Migration Package QR & Handling Class | Flow 1 | `V{n}__package_qr.sql`, `Package.java` |
| **3b** | `S3b-02` | CSV/Excel File Parser Service | Flow 1 | `PackageFileParser.java`, `Apache POI` |
| **3b** | `S3b-03` | Package Import Validator | Flow 1 | `PackageImportValidator.java` |
| **3b** | `S3b-04` | QR Code Generation Service (UUID Stable) | Flow 1 | `QrCodeService.java`, `ZXing` |
| **3b** | `S3b-05` | Package Import Batch Service | Flow 1 | `PackageImportService.java` |
| **3b** | `S3b-06` | Package Import REST Controller | Flow 1 | `PackageImportController.java` |
| **3b** | `S3b-07` | PDF Label Export Service (A6 Print) | Flow 1 | `PdfLabelExportService.java`, `OpenPDF` |
| **4b** | `S4b-01` | Entity Migration Trip Status & Stop Lat/Lng | Flow 2, 6 | `V{n}__trip_stop.sql`, `Trip.java` |
| **4b** | `S4b-02` | Delivery Requirement CRUD | Flow 2 | `DeliveryRequirementService.java` |
| **4b** | `S4b-03` | Cargo Segregation Service & Override Log | Flow 2 | `CargoSegregationService.java` |
| **4b** | `S4b-04` | Goong Maps API Client (Geocode, Matrix) | Flow 2, 6 | `GoongMapsClient.java`, `RestTemplate` |
| **4b** | `S4b-05` | Stop Sequence Optimizer (TSP / LIFO) | Flow 2 | `StopSequenceOptimizer.java` |
| **4b** | `S4b-06` | Route Optimization Service | Flow 2 | `RouteOptimizationService.java` |
| **4b** | `S4b-07` | Trip Planning Controller | Flow 2 | `TripPlanningController.java` |
| **4b** | `S4b-08` | Trip Status State Machine Service | Flow 2, 4 | `TripStatusService.java` |
| **5b** | `S5b-01` | Entity Migration 3D Constraints (COG, Axle) | Flow 3 | `V{n}__3d_engine.sql`, `LoadPlan.java` |
| **5b** | `S5b-02` | Stop Zone Calculator (LIFO Partitioning) | Flow 3 | `StopZoneCalculator.py` (FastAPI) |
| **5b** | `S5b-03` | Physical Constraint Engine (COG, Stacking) | Flow 3 | `ConstraintEngine.py` (FastAPI) |
| **5b** | `S5b-04` | 3D Packing Engine Upgrade (Multi-stop) | Flow 3 | `packing_engine.py` (FastAPI) |
| **5b** | `S5b-05` | Algorithm Tier Service (Basic/GA/AI) | Flow 3, 8 | `AlgorithmTierService.java` |
| **5b** | `S5b-06` | Trip 3D Validation Upgrade | Flow 3 | `TripValidationService.java` |
| **5b** | `S5b-07` | Load Plan API Upgrade | Flow 3 | `LoadPlanController.java` |
| **6** | `S6-01` | Approve Trip & Load Plan Upgrade | Flow 4 | `LoadPlanApprovalService.java` |
| **6** | `S6-02` | Warehouse QR Verification Service | Flow 4, 5 | `WarehouseVerificationService.java` |
| **6** | `S6-03` | Entity Migration GpsTracking & TripException | Flow 6 | `V{n}__gps_exception.sql` |
| **6** | `S6-04` | GPS Tracking Ingestion Service | Flow 6 | `GpsTrackingService.java` |
| **6** | `S6-05` | Live ETA Calculation Service | Flow 6 | `EtaCalculationService.java` |
| **6** | `S6-06` | Trip Monitoring Dashboard Service | Flow 6 | `TripMonitoringService.java` |
| **6** | `S6-07` | Trip Monitoring REST Controller | Flow 6 | `MonitoringController.java` |
| **6** | `S6-08` | Trip Exception Handling Service | Flow 6 | `TripExceptionService.java` |
| **6** | `S6-09` | Dynamic Reroute Service | Flow 6 | `RerouteService.java` (Goong) |
| **6** | `S6-10` | WebSocket GPS Broadcast Handler | Flow 6 | `GpsWebSocketHandler.java` |
| **7** | `S7-01` | Entity Migration PickupRequest & Package | Flow 7 | `V{n}__pickup.sql`, `PickupRequest.java` |
| **7** | `S7-02` | Pickup Request Service & Eligibility Check | Flow 7 | `PickupRequestService.java` |
| **7** | `S7-03` | On-the-fly Pickup QR Code Service | Flow 7 | `PickupQrService.java` |
| **7** | `S7-04` | Freed Zone Calculation Service | Flow 7 | `FreedZoneService.java` |
| **7** | `S7-05` | En-route Pickup Re-optimization Service | Flow 7 | `PickupReoptimizationService.java` |
| **8** | `S8-01` | Entity Migration Subscription, Credit, VNPay | Flow 8 | `V{n}__subscription_credit.sql` |
| **8** | `S8-02` | Subscription Plan Management Service | Flow 8 | `SubscriptionPlanService.java` |
| **8** | `S8-03` | Company Subscription Service | Flow 8 | `CompanySubscriptionService.java` |
| **8** | `S8-04` | Credit Management Service (Deduct/Refund) | Flow 8 | `CreditService.java` |
| **8** | `S8-05` | VNPay Payment Integration Service | Flow 8 | `VNPayService.java` |
| **8** | `S8-06` | Payment Webhook Controller (Idempotency) | Flow 8 | `PaymentWebhookController.java` |
| **8** | `S8-07` | Subscription & Credit REST Controller | Flow 8 | `SubscriptionController.java` |
| **8** | `S8-08` | @CreditGuard AOP Interceptor | Flow 8 | `CreditGuardAspect.java` |

---

> **Lưu ý triển khai:**  
> - Toàn bộ các migration SQL phải đánh số version tăng dần theo `V{next}__...` nối tiếp Flyway schema hiện hữu của `LoadMasterService`.  
> - Các file Mermaid trong tài liệu này có thể render trực tiếp trên GitHub, GitLab, VS Code Markdown Preview hoặc [Mermaid Live Editor](https://mermaid.live).
