# LoadMaster Backend — 3D Cargo Loading & Logistics Optimization

> **Microservices Architecture:** Spring Boot (Core Business & Management) + FastAPI (3D Optimization Engine)

[🇻🇳 **Tiếng Việt**](#-tiếng-việt) | [🇬🇧 **English**](#-english)

---

## 🇻🇳 Tiếng Việt

### 1. Giới thiệu Dự án
**LoadMaster** là hệ thống quản lý logistics và tối ưu hóa xếp dỡ hàng hóa 3D (3D Bin Packing Optimization) dành cho xe tải và container. Hệ thống giúp doanh nghiệp vận tải:
- Tối ưu hóa không gian thùng xe và phân bổ tải trọng trục an toàn.
- Tự động sắp xếp hàng hóa tuân thủ nguyên tắc dỡ hàng **LIFO** (Last In, First Out) theo từng điểm dừng.
- Quản lý quy trình từ đặt hàng, tạo chuyến, kiểm thử thể tích đến thực thi xếp dỡ tại kho và giao hàng theo thời gian thực.

---

### 2. Kiến trúc & Công nghệ (Frameworks & Technologies)

Hệ thống được thiết kế theo kiến trúc **Microservices** phân tách rõ ràng giữa quản lý nghiệp vụ và tính toán chuyên sâu:

```
[ Frontend: React / Next.js ]
          │ (User Token: Bearer JWT)
          ▼
┌────────────────────────────────────────────────────────┐
│  LoadMasterService (Spring Boot 3 / Java 21)           │
│  - Nghiệp vụ chính: Xe, Kiện hàng, Đơn hàng, Chuyến    │
│  - Phân quyền RBAC, Quản lý Công ty, Thanh toán        │
│  - Database: PostgreSQL                                │
└───────────────────────┬────────────────────────────────┘
                        │ (Service Token: Keycloak client_credentials)
                        ▼
┌────────────────────────────────────────────────────────┐
│  OptimizeService (FastAPI / Python 3.11)               │
│  - Thuật toán xếp hàng 3D không gian (3D Bin Packing)  │
│  - Xử lý tác vụ tính toán bất đồng bộ (Async Runner)   │
│  - Thông báo tiến độ qua WebSocket                     │
│  - Kiểm định LIFO & Phê duyệt Kế hoạch (Approve Plan)  │
│  - Database: PostgreSQL / SQLAlchemy                   │
└────────────────────────────────────────────────────────┘
```

| Dịch vụ (Service) | Ngôn ngữ / Framework | Công nghệ & Thư viện chính | Vai trò chính |
|---|---|---|---|
| **`LoadMasterService`** | **Java 21**<br>`Spring Boot 3.x` | • Spring Data JPA / Hibernate<br>• Spring Security & Keycloak OIDC/OAuth2<br>• PostgreSQL Database<br>• SpringDoc OpenAPI / Swagger<br>• Lombok, Maven | Quản lý dữ liệu tổng thể (Master Data), xác thực người dùng, quản trị đơn hàng, chuyến xe, gói cước và bảo mật. |
| **`OptimizeService`** | **Python 3.11**<br>`FastAPI` | • SQLAlchemy ORM & Pydantic v2<br>• 3D Packing Heuristic Engine<br>• Native WebSockets (Starlette/Uvicorn)<br>• PyJWT & Keycloak Token Client<br>• Pytest & pytest-asyncio (91 unit/integration tests) | Động cơ toán học tính toán xếp hàng 3D, điều phối tác vụ async, phát thông báo tiến độ real-time và kiểm duyệt kế hoạch xếp hàng. |

---

### 3. Tóm tắt các Sprint (Full Sprint Summary)

Dự án được phân chia theo 5 Sprint trọng tâm:

#### 🔹 Sprint 1: Nền tảng & Bảo mật (Foundation & Identity)
- **Quản lý danh tính & Xác thực:** Tích hợp Keycloak OAuth2/OIDC, phát hành và kiểm định JWT Token, hỗ trợ đổi mật khẩu, vô hiệu hóa tài khoản.
- **Phân quyền (RBAC):** Định nghĩa vai trò (`ADMIN`, `DISPATCHER`, `WAREHOUSE_STAFF`, `DRIVER`, `CUSTOMER`).
- **Quản lý Công ty (Multi-tenant):** Khởi tạo và phân tách dữ liệu công ty logistics.
- **Audit Logging:** Ghi vết tự động các hành động đăng nhập, cập nhật dữ liệu quan trọng.

#### 🔹 Sprint 2: Dữ liệu Nghiệp vụ & Vận tải (Business Core & Transport Data)
- **Phương tiện (Vehicles):** Quản lý loại xe (`VehicleType`: kích thước thùng, tải trọng, giới hạn trục) và danh sách xe (`Vehicle`), gán tài xế.
- **Hàng hóa & Đơn hàng:** Quản lý quy chuẩn kiện hàng (`PackageType`), quy tắc xếp chồng (`StackingRule`), đơn vận chuyển (`TransportOrder`).
- **Tuyến vận tải (Trips & Stops):** Quản lý chuyến đi (`Trip`) và chuỗi các điểm dừng giao nhận (`DeliveryStop`).
- **Import dữ liệu:** Công cụ parser và validator nhập danh sách hàng hóa và đơn hàng hàng loạt từ Excel/CSV.

#### 🔹 Sprint 3: Động cơ Tối ưu hóa Xếp dỡ 3D (3D Optimization Engine)
- **Trip Validation (FR-OPT-01):** Tiền kiểm tra thể tích tổng, tải trọng xe và kích thước xoay của kiện trước khi xếp.
- **Async Job Execution (FR-OPT-02 → FR-OPT-04):** Spring Boot đẩy yêu cầu tối ưu sang FastAPI với service token; FastAPI trả về 202 Accepted và chạy nền.
- **3D Packing Engine:** Thuật toán tính toán vị trí tọa độ $(x, y, z)$, hướng xoay 6 bậc tự do, tính toán hệ số sử dụng thể tích và khối lượng.
- **Real-time Notifications (FR-OPT-05):** Kênh WebSocket (`/ws/jobs/{job_uuid}`) phát thông báo tiến độ khi hoàn tất, thất bại hoặc timeout.
- **Approve Plan & LIFO (FR-OPT-07):** Phê duyệt kế hoạch xếp dỡ (`POST /api/v1/load-plans/{id}/approve`), kiểm định nghiêm ngặt tính đơn điệu của chuỗi dỡ hàng LIFO và ghi vết `AuditLog`.

#### 🔹 Sprint 4: Thực thi Xếp dỡ & Vận hành Kho (Warehouse & Execution)
- **Tinh chỉnh Kế hoạch:** Cơ chế ghim vị trí kiện hàng (`Pin/Unpin placement`), chạy tối ưu hóa lại (`Rerun Optimization`) và so sánh nhiều phương án xếp hàng (`Plan Comparison`).
- **Thực thi Xếp hàng tại Kho (Warehouse Loading):** Công nhân kho nhận danh sách công việc, thực hiện quét mã và xác nhận xếp hàng tuần tự theo thứ tự hiển thị 3D.
- **Thực thi Dỡ hàng (Driver Unloading):** Tài xế kiểm tra và xác nhận dỡ từng kiện tại mỗi điểm dừng giao hàng.

#### 🔹 Sprint 5: Thương mại hóa & Quản trị Hệ thống (Monetization & Support)
- **Quản lý Gói cước (Subscriptions):** Quản lý bảng giá, gói dịch vụ theo tháng/năm, kiểm soát hạn mức (Quota: số lượng xe, số lượt tối ưu hóa/tháng).
- **Thanh toán & Hóa đơn:** Ghi nhận lịch sử giao dịch cổng thanh toán và xuất hóa đơn điện tử.
- **Hỗ trợ khách hàng (Support Ticket):** Tiếp nhận, phân loại và xử lý yêu cầu khiếu nại/hỗ trợ kỹ thuật.

---

### 4. Hướng dẫn Khởi chạy Nhanh (Quick Start)

#### Khởi động `OptimizeService` (Python FastAPI):
```bash
cd OptimizeService
python -m venv .venv
.venv\Scripts\activate       # Trên Windows (hoặc: source .venv/bin/activate trên Linux/macOS)
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
# Chạy bộ test suite (91 tests):
python -m pytest tests/ -v
```

#### Khởi động `LoadMasterService` (Spring Boot 3):
```bash
cd LoadMasterService
./mvnw clean spring-boot:run
```

---
---

## 🇬🇧 English

### 1. Project Overview
**LoadMaster** is a modern logistics and 3D cargo packing optimization platform engineered for freight forwarders, fleet operators, and warehouse facilities. The platform provides:
- Optimal volumetric container/truck space utilization and axle weight distribution.
- Automated sequence generation conforming to **LIFO (Last In, First Out)** unloading constraints across multiple delivery stops.
- End-to-end workflow management spanning order intake, route trip planning, async 3D computation, warehouse floor execution, and driver handoffs.

---

### 2. Architecture & Frameworks

The system employs a decoupled **Microservices Architecture**:

```
[ Frontend: React / Next.js ]
          │ (User Token: Bearer JWT)
          ▼
┌────────────────────────────────────────────────────────┐
│  LoadMasterService (Spring Boot 3 / Java 21)           │
│  - Master Data: Vehicles, Cargo Packages, Orders, Trips │
│  - RBAC, Multi-tenancy, Subscriptions & Billing        │
│  - Database: PostgreSQL                                │
└───────────────────────┬────────────────────────────────┘
                        │ (Service Token: Keycloak client_credentials)
                        ▼
┌────────────────────────────────────────────────────────┐
│  OptimizeService (FastAPI / Python 3.11)               │
│  - 3D Bin Packing & Heuristic Optimization Engine      │
│  - Asynchronous Job Processing & Background Workers    │
│  - Real-time Notifications via WebSockets              │
│  - LIFO Validation & Load Plan Approval Workflow       │
│  - Database: PostgreSQL / SQLAlchemy                   │
└────────────────────────────────────────────────────────┘
```

| Service | Language / Framework | Core Libraries & Stack | Primary Responsibility |
|---|---|---|---|
| **`LoadMasterService`** | **Java 21**<br>`Spring Boot 3.x` | • Spring Data JPA / Hibernate<br>• Spring Security & Keycloak OIDC/OAuth2<br>• PostgreSQL Database<br>• SpringDoc OpenAPI / Swagger<br>• Lombok, Maven | Master data management, user identity, order dispatching, multi-tenant billing, and core access control. |
| **`OptimizeService`** | **Python 3.11**<br>`FastAPI` | • SQLAlchemy ORM & Pydantic v2<br>• 3D Packing Heuristic Engine<br>• Native WebSockets (Starlette/Uvicorn)<br>• PyJWT & Keycloak Service Token Client<br>• Pytest & pytest-asyncio (91 unit/integration tests) | 3D spatial packing computations, async job execution, real-time WebSocket progress alerts, LIFO validation, and plan approval. |

---

### 3. Full Sprint Roadmap Summary

The system is structured across 5 progressive milestones:

#### 🔹 Sprint 1: Foundation & Identity
- **Authentication & Security:** Keycloak OAuth2/OIDC integration, JWT validation, password updates, account lifecycle management.
- **Role-Based Access Control (RBAC):** Fine-grained permissioning (`ADMIN`, `DISPATCHER`, `WAREHOUSE_STAFF`, `DRIVER`, `CUSTOMER`).
- **Multi-Tenant Setup:** Tenant segregation for logistics companies.
- **Audit Trails:** Centralized audit logs for sensitive transactions and user logins.

#### 🔹 Sprint 2: Core Logistics Master Data
- **Fleet Management:** Vehicle models (`VehicleType`: container dimensions, payload capacity, door apertures) and vehicles (`Vehicle`), driver binding.
- **Cargo Specifications:** Package catalog (`PackageType`), orientation permissions, and stacking constraints (`StackingRule`).
- **Trip Dispatching:** Transport orders (`TransportOrder`), multi-destination delivery sequences (`DeliveryStop`), and trip creation (`Trip`).
- **Batch Ingestion:** High-performance CSV/Excel bulk upload and schema validation.

#### 🔹 Sprint 3: 3D Cargo Optimization Engine
- **Pre-Optimization Validation:** Feasibility checks on payload limit, volumetric thresholds, and item dimension compatibility.
- **Async Execution Pipeline:** Spring Boot dispatches jobs via authenticated service tokens; FastAPI immediately yields `202 Accepted` and processes asynchronously.
- **3D Packing Heuristics:** Coordinates $(x, y, z)$ resolution, 6-direction rotation support, volume and weight density metrics.
- **Real-Time WebSockets:** Push notifications over `/ws/jobs/{job_uuid}` upon state transitions (`COMPLETED`, `PARTIAL`, `NO_SOLUTION`, `TIMEOUT`, `FAILED`).
- **Plan Approval & LIFO Compliance:** `POST /api/v1/load-plans/{id}/approve` ensuring non-overlapping step sequences and logging audit events.

#### 🔹 Sprint 4: Warehouse Operations & Execution
- **Interactive Fine-Tuning:** Placement pinning/unpinning, constrained re-optimization, and side-by-side plan comparisons.
- **Warehouse Loading Execution:** Mobile-ready step-by-step loading verification with physical checklist confirmations.
- **Driver Unloading:** Stop-by-stop delivery confirmation matching the reverse LIFO sequence.

#### 🔹 Sprint 5: Monetization, Billing & Support
- **SaaS Subscriptions:** Tiered plans (max vehicles, monthly optimization quota checks).
- **Payment & Invoicing:** Gateway transaction recording and automated billing generation.
- **Customer Support Ticketing:** Issue tracking, priority routing, and resolution workflows.

---

### 4. Quick Start Guide

#### Run `OptimizeService` (Python FastAPI):
```bash
cd OptimizeService
python -m venv .venv
.venv\Scripts\activate       # Windows (or: source .venv/bin/activate on Linux/macOS)
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# Execute Test Suite (91 tests):
python -m pytest tests/ -v
```

#### Run `LoadMasterService` (Spring Boot 3):
```bash
cd LoadMasterService
./mvnw clean spring-boot:run
```
