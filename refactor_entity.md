# LOADMASTER — PHYSICAL DATABASE SPECIFICATION & DATA DICTIONARY
**Phiên bản:** 3.4 (Production Single-Role Schema)  
**Mô hình phân quyền:** 1 User — 1 Role ($1 : N$)  
**Hệ quản trị CSDL mục tiêu:** PostgreSQL 
**Framework Backend:** Spring Boot  
**Quy ước đơn vị:** Kích thước = Milimét (`INT` - cm) | Trọng lượng & Tải trọng = Kilôgam (`DECIMAL(10,2)` - kg) | Tiền tệ = VNĐ (`DECIMAL(15,2)`)

---

## I. DANH MỤC THỰC THỂ & QUY CHUẨN TÊN BẢNG / ENTITY CODE

| Tên bảng trên sơ đồ | Tên bảng SQL vật lý | Tên Java Entity Class | Tên Java Repository | Phân hệ nghiệp vụ |
| :--- | :--- | :--- | :--- | :--- |
| `t_companies` | `companies` | `Company` | `CompanyRepository` | IAM & Multi-Tenancy |
| `t_roles` | `roles` | `Role` | `RoleRepository` | IAM & RBAC |
| `t_permissions` | `permissions` | `Permission` | `PermissionRepository` | IAM & RBAC |
| `t_role_permissions`| `role_permissions` | `RolePermission` | `RolePermissionRepository` | IAM & RBAC (Junction N:N) |
| `t_users` | `users` | `User` | `UserRepository` | IAM & Identity |
| `t_audit_logs` | `audit_logs` | `AuditLog` | `AuditLogRepository` | Logging & Compliance |
| `t_sub_plans` | `subscription_plans` | `SubscriptionPlan` | `SubscriptionPlanRepository` | Billing & Subscription |
| `t_subscriptions` | `subscriptions` | `Subscription` | `SubscriptionRepository` | Billing & Subscription |
| `t_invoices` | `invoices` | `Invoice` | `InvoiceRepository` | Billing & Subscription |
| `t_pay_trans` | `payment_transactions` | `PaymentTransaction`| `PaymentTransactionRepository`| Billing & Payment |
| `t_support_tickets` | `support_tickets` | `SupportTicket` | `SupportTicketRepository` | Customer Support |
| `t_contacts` | `contacts_by_guest` | `ContactByGuest` | `ContactByGuestRepository` | Leads & Marketing |
| `t_vehicle_types` | `vehicle_types` | `VehicleType` | `VehicleTypeRepository` | Fleet & Logistics Catalog |
| `t_vehicles` | `vehicles` | `Vehicle` | `VehicleRepository` | Fleet & Assets |
| `t_trips` | `trips` | `Trip` | `TripRepository` | Routing & Trips |
| `t_delivery_stops` | `delivery_stops` | `DeliveryStop` | `DeliveryStopRepository` | Routing & LIFO Stops |
| `t_customers` | `customers` | `Customer` | `CustomerRepository` | Partners & Receivers |
| `t_orders` | `orders` | `Order` | `OrderRepository` | Orders & Freight |
| `t_package_types` | `package_types` | `PackageType` | `PackageTypeRepository` | Cargo & Stacking Rules |
| `t_stacking_rules` | `stacking_rules` | `StackingRule` | `StackingRuleRepository` | Cargo & Stacking Rules |
| `t_packages` | `packages` | `Package` | `PackageRepository` | Cargo & Physical Items |
| `t_opt_jobs` | `optimization_jobs` | `OptimizationJob` | `OptimizationJobRepository` | 3D Algorithm Engine |
| `t_load_plans` | `load_plans` | `LoadPlan` | `LoadPlanRepository` | 3D Algorithm Engine |
| `t_pkg_placements` | `package_placements` | `PackagePlacement` | `PackagePlacementRepository` | 3D Placement Results |
| `t_unplaced_pkgs` | `unplaced_packages` | `UnplacedPackage` | `UnplacedPackageRepository` | 3D Rejection Results |
| `t_cog` | `center_of_gravity` | `CenterOfGravity` | `CenterOfGravityRepository` | Axle Load & Balance |
| `t_loading_exec` | `loading_executions` | `LoadingExecution` | `LoadingExecutionRepository` | Warehouse Operations |

---

## II. MA TRẬN MỐI QUAN HỆ & RÀNG BUỘC KHÓA NGOẠI (FOREIGN KEY CONSTRAINTS)

| Bảng chứa FK (Bảng con) | Tên cột Khóa ngoại | Bảng tham chiếu (Bảng cha) | Tên cột Khóa chính | Ràng buộc nghiệp vụ (On Delete) |
| :--- | :--- | :--- | :--- | :--- |
| `users` | `company_id` | `companies` | `id` | `ON DELETE RESTRICT` |
| `users` | `role_id` | `roles` | `id` | `ON DELETE RESTRICT` |
| `users` | `created_by_user_id` | `users` | `id` | `ON DELETE SET NULL` |
| `role_permissions` | `role_id` | `roles` | `id` | `ON DELETE CASCADE` |
| `role_permissions` | `permission_id` | `permissions` | `id` | `ON DELETE CASCADE` |
| `audit_logs` | `user_id` | `users` | `id` | `ON DELETE SET NULL` |
| `subscriptions` | `company_id` | `companies` | `id` | `ON DELETE RESTRICT` |
| `subscriptions` | `plan_id` | `subscription_plans`| `id` | `ON DELETE RESTRICT` |
| `invoices` | `subscription_id` | `subscriptions` | `id` | `ON DELETE CASCADE` |
| `payment_transactions` | `invoice_id` | `invoices` | `id` | `ON DELETE CASCADE` |
| `support_tickets` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `support_tickets` | `requester_user_id` | `users` | `id` | `ON DELETE RESTRICT` |
| `support_tickets` | `assigned_supporter_id` | `users` | `id` | `ON DELETE SET NULL` |
| `contacts_by_guest` | `assigned_supporter_id` | `users` | `id` | `ON DELETE SET NULL` |
| `vehicle_types` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `vehicles` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `vehicles` | `vehicle_type_id` | `vehicle_types` | `id` | `ON DELETE RESTRICT` |
| `vehicles` | `driver_user_id` | `users` | `id` | `ON DELETE SET NULL` |
| `trips` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `trips` | `vehicle_id` | `vehicles` | `id` | `ON DELETE RESTRICT` |
| `trips` | `created_by_dispatcher_id` | `users` | `id` | `ON DELETE RESTRICT` |
| `delivery_stops` | `trip_id` | `trips` | `id` | `ON DELETE CASCADE` |
| `customers` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `orders` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `orders` | `customer_id` | `customers` | `id` | `ON DELETE RESTRICT` |
| `orders` | `delivery_stop_id` | `delivery_stops` | `id` | `ON DELETE SET NULL` |
| `package_types` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `stacking_rules` | `company_id` | `companies` | `id` | `ON DELETE CASCADE` |
| `stacking_rules` | `bottom_package_type_id` | `package_types` | `id` | `ON DELETE CASCADE` |
| `stacking_rules` | `top_package_type_id` | `package_types` | `id` | `ON DELETE CASCADE` |
| `packages` | `order_id` | `orders` | `id` | `ON DELETE CASCADE` |
| `packages` | `package_type_id` | `package_types` | `id` | `ON DELETE RESTRICT` |
| `optimization_jobs` | `trip_id` | `trips` | `id` | `ON DELETE CASCADE` |
| `load_plans` | `job_id` | `optimization_jobs`| `id` | `ON DELETE CASCADE` |
| `load_plans` | `approved_by_user_id` | `users` | `id` | `ON DELETE SET NULL` |
| `package_placements` | `load_plan_id` | `load_plans` | `id` | `ON DELETE CASCADE` |
| `package_placements` | `package_id` | `packages` | `id` | `ON DELETE CASCADE` |
| `unplaced_packages` | `load_plan_id` | `load_plans` | `id` | `ON DELETE CASCADE` |
| `unplaced_packages` | `package_id` | `packages` | `id` | `ON DELETE CASCADE` |
| `center_of_gravity` | `load_plan_id` | `load_plans` | `id` | `ON DELETE CASCADE` |
| `loading_executions` | `load_plan_id` | `load_plans` | `id` | `ON DELETE RESTRICT` |
| `loading_executions` | `worker_user_id` | `users` | `id` | `ON DELETE RESTRICT` |

---

## III. ĐẶC TẢ CHI TIẾT TỪNG TRƯỜNG DỮ LIỆU (FIELD-BY-FIELD DICTIONARY)

### 1. Phân hệ Định danh & Phân quyền (IAM & Security)

#### Bảng: `companies`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ code : VARCHAR(50) [UK]` | `code` | `code` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ name : VARCHAR(200)` | `name` | `name` | `VARCHAR(200)` | `String` | Tối đa 200 ký tự | `NOT NULL` |
| `+ tax_code : VARCHAR(50) [UK]` | `tax_code` | `taxCode` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ phone_number : VARCHAR(20)` | `phone_number` | `phoneNumber` | `VARCHAR(20)` | `String` | Tối đa 20 ký tự | `NULL` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'ACTIVE'` |
| `+ created_at : TIMESTAMP` | `created_at` | `createdAt` | `TIMESTAMP` | `LocalDateTime` | Microsecond precision | `NOT NULL, DEFAULT CURRENT_TIMESTAMP` |

#### Bảng: `roles`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ name : VARCHAR(50) [UK]` | `name` | `name` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ description : VARCHAR(255)` | `description` | `description` | `VARCHAR(255)` | `String` | Tối đa 255 ký tự | `NULL` |

#### Bảng: `permissions`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ code : VARCHAR(100) [UK]` | `code` | `code` | `VARCHAR(100)` | `String` | Tối đa 100 ký tự | `NOT NULL, UNIQUE` |
| `+ description : VARCHAR(255)` | `description` | `description` | `VARCHAR(255)` | `String` | Tối đa 255 ký tự | `NULL` |

#### Bảng: `role_permissions`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ role_id : BIGINT [PK, FK]` | `role_id` | `roleId` | `BIGINT` | `Long` | 8 bytes | `PRIMARY KEY, REFERENCES roles(id)` |
| `+ permission_id : BIGINT [PK, FK]`| `permission_id`| `permissionId` | `BIGINT` | `Long` | 8 bytes | `PRIMARY KEY, REFERENCES permissions(id)`|

#### Bảng: `users`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK, NULL]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NULL, REFERENCES companies(id)` |
| `+ role_id : BIGINT [FK]` | `role_id` | `roleId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES roles(id)` |
| `+ email : VARCHAR(150) [UK]` | `email` | `email` | `VARCHAR(150)` | `String` | Tối đa 150 ký tự | `NOT NULL, UNIQUE` |
| `+ password_hash : VARCHAR(255)` | `password_hash` | `passwordHash` | `VARCHAR(255)` | `String` | Tối đa 255 ký tự | `NOT NULL` |
| `+ full_name : VARCHAR(150)` | `full_name` | `fullName` | `VARCHAR(150)` | `String` | Tối đa 150 ký tự | `NOT NULL` |
| `+ phone_number : VARCHAR(20)` | `phone_number` | `phoneNumber` | `VARCHAR(20)` | `String` | Tối đa 20 ký tự | `NULL` |
| `+ user_role_type : VARCHAR(30)` | `user_role_type`| `userRoleType` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL` |
| `+ dispatcher_code : VARCHAR(50) [NULL]`| `dispatcher_code`| `dispatcherCode`| `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NULL, UNIQUE` |
| `+ license_number : VARCHAR(50) [NULL]` | `license_number`| `licenseNumber` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NULL, UNIQUE` |
| `+ worker_badge_id : VARCHAR(50) [NULL]`| `worker_badge_id`| `workerBadgeId` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NULL, UNIQUE` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'ACTIVE'` |
| `+ created_by_user_id : BIGINT [FK]`| `created_by_user_id`| `createdByUserId`| `BIGINT`| `Long` | 8 bytes | `NULL, REFERENCES users(id)` |
| `+ created_at : TIMESTAMP` | `created_at` | `createdAt` | `TIMESTAMP` | `LocalDateTime` | Microsecond precision | `NOT NULL, DEFAULT CURRENT_TIMESTAMP` |

#### Bảng: `audit_logs`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ user_id : BIGINT [FK]` | `user_id` | `userId` | `BIGINT` | `Long` | 8 bytes | `NULL, REFERENCES users(id)` |
| `+ action : VARCHAR(100)` | `action` | `action` | `VARCHAR(100)` | `String` | Tối đa 100 ký tự | `NOT NULL` |
| `+ entity_name : VARCHAR(100)` | `entity_name` | `entityName` | `VARCHAR(100)` | `String` | Tối đa 100 ký tự | `NOT NULL` |
| `+ entity_id : VARCHAR(100)` | `entity_id` | `entityId` | `VARCHAR(100)` | `String` | Tối đa 100 ký tự | `NULL` |
| `+ ip_address : VARCHAR(50)` | `ip_address` | `ipAddress` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NULL` |
| `+ created_at : TIMESTAMP` | `created_at` | `createdAt` | `TIMESTAMP` | `LocalDateTime` | Microsecond precision | `NOT NULL, DEFAULT CURRENT_TIMESTAMP` |

---

### 2. Phân hệ Gói cước, Thanh toán & Chăm sóc Khách hàng

#### Bảng: `subscription_plans`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ code : VARCHAR(50) [UK]` | `code` | `code` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ name : VARCHAR(100)` | `name` | `name` | `VARCHAR(100)` | `String` | Tối đa 100 ký tự | `NOT NULL` |
| `+ price_vnd : DECIMAL(15,2)` | `price_vnd` | `priceVnd` | `DECIMAL(15,2)` | `BigDecimal` | 15 chữ số, 2 số thập phân | `NOT NULL` |
| `+ max_vehicles : INT` | `max_vehicles` | `maxVehicles` | `INT` | `Integer` | 4 bytes | `NOT NULL` |

#### Bảng: `subscriptions`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ plan_id : BIGINT [FK]` | `plan_id` | `planId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES subscription_plans(id)`|
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'ACTIVE'` |
| `+ start_date : DATE` | `start_date` | `startDate` | `DATE` | `LocalDate` | Định dạng YYYY-MM-DD | `NOT NULL` |
| `+ end_date : DATE` | `end_date` | `endDate` | `DATE` | `LocalDate` | Định dạng YYYY-MM-DD | `NOT NULL` |

#### Bảng: `invoices`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ invoice_number : VARCHAR(50) [UK]` | `invoice_number`| `invoiceNumber` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ subscription_id : BIGINT [FK]` | `subscription_id`| `subscriptionId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES subscriptions(id)` |
| `+ amount_vnd : DECIMAL(15,2)` | `amount_vnd` | `amountVnd` | `DECIMAL(15,2)` | `BigDecimal` | 15 chữ số, 2 số thập phân | `NOT NULL` |
| `+ payment_status : VARCHAR(30)` | `payment_status`| `paymentStatus` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'PENDING'` |

#### Bảng: `payment_transactions`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ invoice_id : BIGINT [FK]` | `invoice_id` | `invoiceId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES invoices(id)` |
| `+ gateway_provider : VARCHAR(50)`| `gateway_provider`| `gatewayProvider`| `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL` |
| `+ transaction_code : VARCHAR(100)`| `transaction_code`| `transactionCode`| `VARCHAR(100)` | `String` | Tối đa 100 ký tự | `NOT NULL, UNIQUE` |
| `+ amount_vnd : DECIMAL(15,2)` | `amount_vnd` | `amountVnd` | `DECIMAL(15,2)` | `BigDecimal` | 15 chữ số, 2 số thập phân | `NOT NULL` |

#### Bảng: `support_tickets`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ ticket_code : VARCHAR(50) [UK]` | `ticket_code` | `ticketCode` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ ticket_description: VARCHAR(500)`| `ticket_description`| `ticketDescription`| `VARCHAR(500)`| `String` | Tối đa 500 ký tự | `NOT NULL` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ requester_user_id : BIGINT [FK]` | `requester_user_id`| `requesterUserId`| `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES users(id)` |
| `+ assigned_supporter_id : BIGINT [FK]`| `assigned_supporter_id`| `assignedSupporterId`| `BIGINT`| `Long` | 8 bytes | `NULL, REFERENCES users(id)` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'OPEN'` |

#### Bảng: `contacts_by_guest`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ guest_name : VARCHAR(150)` | `guest_name` | `guestName` | `VARCHAR(150)` | `String` | Tối đa 150 ký tự | `NOT NULL` |
| `+ guest_email : VARCHAR(150)` | `guest_email` | `guestEmail` | `VARCHAR(150)` | `String` | Tối đa 150 ký tự | `NOT NULL` |
| `+ assigned_supporter_id : BIGINT [FK]`| `assigned_supporter_id`| `assignedSupporterId`| `BIGINT`| `Long` | 8 bytes | `NULL, REFERENCES users(id)` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'NEW'` |

---

### 3. Phân hệ Đội xe, Danh mục & Quy chuẩn Đóng gói

#### Bảng: `vehicle_types`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ name : VARCHAR(150)` | `name` | `name` | `VARCHAR(150)` | `String` | Tối đa 150 ký tự | `NOT NULL` |
| `+ inner_length : INT (mm)` | `inner_length` | `innerLength` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ inner_width : INT (mm)` | `inner_width` | `innerWidth` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ inner_height : INT (mm)` | `inner_height` | `innerHeight` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ max_payload_kg : DECIMAL(10,2)` | `max_payload_kg`| `maxPayloadKg` | `DECIMAL(10,2)` | `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL` |

#### Bảng: `vehicles`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ vehicle_type_id : BIGINT [FK]` | `vehicle_type_id`| `vehicleTypeId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES vehicle_types(id)` |
| `+ driver_user_id : BIGINT [FK, UK, NULL]`| `driver_user_id`| `driverUserId` | `BIGINT` | `Long` | 8 bytes | `NULL, UNIQUE, REFERENCES users(id)` |
| `+ license_plate : VARCHAR(50) [UK]` | `license_plate` | `licensePlate` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ front_axle_limit_kg : DECIMAL(10,2)` | `front_axle_limit_kg`| `frontAxleLimitKg`| `DECIMAL(10,2)`| `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL` |
| `+ rear_axle_limit_kg : DECIMAL(10,2)` | `rear_axle_limit_kg`| `rearAxleLimitKg` | `DECIMAL(10,2)`| `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL` |

#### Bảng: `package_types`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ name : VARCHAR(150)` | `name` | `name` | `VARCHAR(150)` | `String` | Tối đa 150 ký tự | `NOT NULL` |
| `+ length : INT (mm)` | `length` | `length` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ width : INT (mm)` | `width` | `width` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ height : INT (mm)` | `height` | `height` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ weight_kg : DECIMAL(10,2)` | `weight_kg` | `weightKg` | `DECIMAL(10,2)` | `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL` |
| `+ max_stacking_weight_kg : DECIMAL`| `max_stacking_weight_kg`| `maxStackingWeightKg`| `DECIMAL(10,2)`| `BigDecimal`| 10 chữ số, 2 số thập phân (kg) | `NOT NULL, DEFAULT 0.00` |
| `+ is_fragile : BOOLEAN` | `is_fragile` | `isFragile` | `BOOLEAN` | `Boolean` | 1 byte | `NOT NULL, DEFAULT FALSE` |

#### Bảng: `stacking_rules`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ bottom_package_type_id : BIGINT [FK]`| `bottom_package_type_id`| `bottomPackageTypeId`| `BIGINT`| `Long`| 8 bytes | `NOT NULL, REFERENCES package_types(id)`|
| `+ top_package_type_id : BIGINT [FK]` | `top_package_type_id`| `topPackageTypeId` | `BIGINT`| `Long`| 8 bytes | `NOT NULL, REFERENCES package_types(id)`|
| `+ is_allowed : BOOLEAN` | `is_allowed` | `isAllowed` | `BOOLEAN` | `Boolean` | 1 byte | `NOT NULL, DEFAULT TRUE` |

---

### 4. Phân hệ Chuyến xe, Khách hàng & Đơn hàng

#### Bảng: `customers`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ name : VARCHAR(200)` | `name` | `name` | `VARCHAR(200)` | `String` | Tối đa 200 ký tự | `NOT NULL` |
| `+ contact_phone : VARCHAR(20)` | `contact_phone` | `contactPhone` | `VARCHAR(20)` | `String` | Tối đa 20 ký tự | `NOT NULL` |
| `+ address : VARCHAR(300)` | `address` | `address` | `VARCHAR(300)` | `String` | Tối đa 300 ký tự | `NOT NULL` |

#### Bảng: `trips`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ trip_code : VARCHAR(50) [UK]` | `trip_code` | `tripCode` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ vehicle_id : BIGINT [FK]` | `vehicle_id` | `vehicleId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES vehicles(id)` |
| `+ created_by_dispatcher_id : BIGINT`| `created_by_dispatcher_id`| `createdByDispatcherId`| `BIGINT`| `Long`| 8 bytes | `NOT NULL, REFERENCES users(id)` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'DRAFT'` |

#### Bảng: `delivery_stops`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ trip_id : BIGINT [FK]` | `trip_id` | `tripId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES trips(id)` |
| `+ stop_sequence : INT` | `stop_sequence` | `stopSequence` | `INT` | `Integer` | 4 bytes | `NOT NULL` |
| `+ location_address : VARCHAR(300)` | `location_address`| `locationAddress` | `VARCHAR(300)` | `String` | Tối đa 300 ký tự | `NOT NULL` |

#### Bảng: `orders`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ company_id : BIGINT [FK]` | `company_id` | `companyId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES companies(id)` |
| `+ order_code : VARCHAR(50) [UK]` | `order_code` | `orderCode` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL, UNIQUE` |
| `+ customer_id : BIGINT [FK]` | `customer_id` | `customerId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES customers(id)` |
| `+ delivery_stop_id : BIGINT [FK, NULL]`| `delivery_stop_id`| `deliveryStopId` | `BIGINT` | `Long` | 8 bytes | `NULL, REFERENCES delivery_stops(id)` |
| `+ total_weight_kg : DECIMAL(10,2)` | `total_weight_kg`| `totalWeightKg` | `DECIMAL(10,2)` | `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL, DEFAULT 0.00` |

#### Bảng: `packages`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ order_id : BIGINT [FK]` | `order_id` | `orderId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES orders(id)` |
| `+ package_type_id : BIGINT [FK]` | `package_type_id`| `packageTypeId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES package_types(id)`|
| `+ tracking_barcode : VARCHAR(100) [UK]`| `tracking_barcode`| `trackingBarcode`| `VARCHAR(100)`| `String` | Tối đa 100 ký tự | `NOT NULL, UNIQUE` |
| `+ actual_length : INT (mm)` | `actual_length` | `actualLength` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ actual_weight_kg : DECIMAL(10,2)`| `actual_weight_kg`| `actualWeightKg` | `DECIMAL(10,2)`| `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL` |
| `+ is_pinned : BOOLEAN` | `is_pinned` | `isPinned` | `BOOLEAN` | `Boolean` | 1 byte | `NOT NULL, DEFAULT FALSE` |

---

### 5. Phân hệ Thuật toán 3D, Mô phỏng Không gian & Thực thi Kho

#### Bảng: `optimization_jobs`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ trip_id : BIGINT [FK]` | `trip_id` | `tripId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES trips(id)` |
| `+ algorithm_objective : VARCHAR(50)`| `algorithm_objective`| `algorithmObjective`| `VARCHAR(50)`| `String` | Tối đa 50 ký tự | `NOT NULL` |
| `+ execution_time_ms : INT` | `execution_time_ms`| `executionTimeMs`| `INT` | `Integer` | 4 bytes (ms) | `NULL` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'PENDING'` |

#### Bảng: `load_plans`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ job_id : BIGINT [FK]` | `job_id` | `jobId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES optimization_jobs(id)`|
| `+ plan_version : INT` | `plan_version` | `planVersion` | `INT` | `Integer` | 4 bytes | `NOT NULL, DEFAULT 1` |
| `+ volume_utilization_percent : DECIMAL`| `volume_utilization_percent`| `volumeUtilizationPercent`| `DECIMAL(5,2)`| `BigDecimal`| 5 chữ số, 2 số thập phân (%) | `NOT NULL` |
| `+ is_approved : BOOLEAN` | `is_approved` | `isApproved` | `BOOLEAN` | `Boolean` | 1 byte | `NOT NULL, DEFAULT FALSE` |
| `+ approved_at: TIMESTAMP` | `approved_at` | `approvedAt` | `TIMESTAMP` | `LocalDateTime` | Microsecond precision | `NULL` |
| `+ approved_by_user_id : BIGINT [FK, NULL]`| `approved_by_user_id`| `approvedByUserId`| `BIGINT`| `Long`| 8 bytes | `NULL, REFERENCES users(id)` |

#### Bảng: `package_placements`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ load_plan_id : BIGINT [FK]` | `load_plan_id` | `loadPlanId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES load_plans(id)` |
| `+ package_id : BIGINT [FK]` | `package_id` | `packageId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES packages(id)` |
| `+ pos_x : INT (mm)` | `pos_x` | `posX` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ pos_y : INT (mm)` | `pos_y` | `posY` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ pos_z : INT (mm)` | `pos_z` | `posZ` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ loading_sequence : INT` | `loading_sequence` | `loadingSequence` | `INT` | `Integer` | 4 bytes | `NOT NULL` |

#### Bảng: `unplaced_packages`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ load_plan_id : BIGINT [FK]` | `load_plan_id` | `loadPlanId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES load_plans(id)` |
| `+ package_id : BIGINT [FK]` | `package_id` | `packageId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES packages(id)` |
| `+ reason_code : VARCHAR(50)` | `reason_code` | `reasonCode` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NOT NULL` |

#### Bảng: `center_of_gravity`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ load_plan_id : BIGINT [FK, UK]` | `load_plan_id` | `loadPlanId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, UNIQUE, REFERENCES load_plans(id)`|
| `+ cog_x : INT (mm)` | `cog_x` | `cogX` | `INT` | `Integer` | 4 bytes (mm) | `NOT NULL` |
| `+ front_axle_load_kg : DECIMAL(10,2)`| `front_axle_load_kg`| `frontAxleLoadKg` | `DECIMAL(10,2)`| `BigDecimal` | 10 chữ số, 2 số thập phân (kg) | `NOT NULL` |
| `+ is_axle_overload : BOOLEAN` | `is_axle_overload`| `isAxleOverload` | `BOOLEAN` | `Boolean` | 1 byte | `NOT NULL, DEFAULT FALSE` |

#### Bảng: `loading_executions`
| Cột trên sơ đồ | Tên cột SQL (Physical) | Tên thuộc tính Java | Kiểu dữ liệu SQL | Kiểu dữ liệu Java | Kích thước / Giá trị | Ràng buộc toàn vẹn |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `+ id : BIGSERIAL [PK]` | `id` | `id` | `BIGSERIAL` | `Long` | 8 bytes | `PRIMARY KEY` |
| `+ load_plan_id : BIGINT [FK, UK]` | `load_plan_id` | `loadPlanId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, UNIQUE, REFERENCES load_plans(id)`|
| `+ worker_user_id : BIGINT [FK]` | `worker_user_id`| `workerUserId` | `BIGINT` | `Long` | 8 bytes | `NOT NULL, REFERENCES users(id)` |
| `+ seal_number : VARCHAR(50)` | `seal_number` | `sealNumber` | `VARCHAR(50)` | `String` | Tối đa 50 ký tự | `NULL` |
| `+ status : VARCHAR(30)` | `status` | `status` | `VARCHAR(30)` | `String` | Tối đa 30 ký tự | `NOT NULL, DEFAULT 'IN_PROGRESS'` |