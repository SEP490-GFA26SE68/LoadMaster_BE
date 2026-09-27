# ❓ Open Questions & Unresolved Issues

> Tổng hợp những vấn đề chưa giải quyết và câu hỏi chưa trả lời cho dự án LoadMaster.
> Cập nhật file này khi có quyết định mới.
> Entity/field hiện hành phải theo `refactor_entity.md` v3.4; quyết định cần thêm persistence phải cập nhật data dictionary trước.

---

## 🔴 Quyết định kiến trúc chưa đưa ra (từ PRD §7)

| # | Chủ đề | Lựa chọn | Ảnh hưởng | Deadline đề xuất |
|---|--------|----------|-----------|-----------------|
| D1 | **Optimization Engine** | (a) FastAPI Python riêng · (b) Tích hợp trong Spring Boot (Java) | Quyết định trước Sprint 3. Nếu chọn (a) → cần thêm repo Python, deploy riêng, HTTP client. Nếu (b) → code Java thuần, không cần service riêng | Trước Sprint 3 |
| D3 | **Cổng thanh toán** | (a) VNPay · (b) MoMo · (c) Stripe · (d) Manual/offline | Sprint 5 mới cần. Nếu chọn (d) → chỉ ghi nhận giao dịch thủ công, không cần tích hợp gateway | Trước Sprint 5 |
| D4 | **Deployment platform** | (a) Aiven + Render · (b) AWS · (c) Docker + VPS | DB đã trên Aiven Cloud. Backend cần chọn nơi host. Ảnh hưởng CI/CD pipeline | Trước Sprint 5 |
| D5 | **Email notification** | (a) Có gửi email khi Job hoàn thành · (b) Chỉ WebSocket | Nếu (a) → cần SMTP config, email template, dependency thêm | Trước Sprint 3 |

---

## 🟡 Câu hỏi nghiệp vụ chưa trả lời

| # | Câu hỏi | Context | Ảnh hưởng |
|---|---------|---------|-----------|
| Q1 | **Forgot password flow?** | PRD chưa có FR cho quên mật khẩu. Cần email reset link hay OTP? | Sprint 1 — Auth module |
| Q4 | **DeliveryStop cần tọa độ (lat/lng) không?** | Entity hiện chỉ có `locationAddress` string. Nếu sau này cần bản đồ/route thì thiếu | Sprint 2 — Entity |
| Q5 | **Ai approve LoadPlan?** | Chỉ Dispatcher hay Company Admin cũng approve được? | Sprint 3 — Optimization |
| Q6 | **Warehouse Worker được assign task thế nào?** | Auto-assign hay manual? Hiện chưa rõ flow assign worker vào trip/plan | Sprint 4 — Warehouse |
| Q7 | **Có cần theo dõi confirmation/deviation từng placement không?** | Schema v3.4 chỉ có `LoadingExecution`; muốn lưu chi tiết phải bổ sung entity/field vào data dictionary | Sprint 4 — Warehouse |
| Q8 | **Có cần trạng thái từng stop/kiện dỡ không?** | Schema v3.4 không có stop status hoặc unload confirmation; hiện chỉ hoàn tất ở mức Trip | Sprint 4 — Driver |

---

## 🟠 Vấn đề kỹ thuật chưa giải quyết

| # | Vấn đề | Chi tiết | Sprint |
|---|--------|----------|--------|
| T1 | **Token revocation strategy** | Logout — dùng token blacklist (cần Redis) hay chỉ xóa client-side? | Sprint 1 |
| T2 | **File storage cho Import** | Lưu file CSV/Excel upload ở đâu? Local disk, S3, hay chỉ parse in-memory? | Sprint 2 |
| T3 | **Optimization Engine API contract** | Chưa có spec chính thức cho Engine input/output. DTO ở S3-05 là draft | Sprint 3 |
| T4 | **WebSocket auth** | WebSocket connection có cần JWT auth không? Nếu có → cần HandshakeInterceptor | Sprint 3 |
| T5 | **3D Viewer data format** | API trả dữ liệu 3D dạng nào? JSON placements hay format riêng (glTF)? | Sprint 4 |
| T6 | **Concurrent optimization jobs** | 1 trip có thể chạy nhiều job cùng lúc không? Cần lock mechanism? | Sprint 3 |

---

## ✅ Đã giải quyết

| # | Vấn đề | Quyết định | Ngày |
|---|--------|-----------|------|
| R1 | Spring AI / PGVector dependency | Đã comment out trong `pom.xml` — không cần cho dự án này | 21/09/2026 |
| R2 | Credential management | Đã chuyển sang `.env` file, xóa khỏi `application.yml` | 21/09/2026 |
| R3 | ErrorCode cleanup | Đã xóa các error code không liên quan (copy từ dự án cũ) | 21/09/2026 |
| R4 | Frontend framework | React + React Three Fiber (Three.js) cho 3D Viewer | 21/09/2026 |
| R5 | Target audience | Các công ty vận tải vừa và nhỏ tại Việt Nam (B2B SaaS) | 21/09/2026 |
| R6 | Multi-tenancy | Shared database, phân tách theo `company_id`; System Admin có thể có `users.company_id = null` | 25/09/2026 |
| R7 | User–Role | Mỗi User bắt buộc có đúng một Role qua `users.role_id`; không có `UserRole` | 25/09/2026 |
| R8 | Driver–Vehicle | `vehicles.driver_user_id` nullable + unique, nên một driver tối đa một vehicle tại một thời điểm | 25/09/2026 |
| R9 | Order–Stop | Một Order có tối đa một DeliveryStop qua nullable `orders.delivery_stop_id` | 25/09/2026 |
