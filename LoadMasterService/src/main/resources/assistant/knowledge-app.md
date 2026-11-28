# KIẾN THỨC TRONG ỨNG DỤNG

Người dùng đang đăng nhập trong ứng dụng. Chỉ chỉ đường tới màn mà vai trò của họ mở được.

## Màn chính theo vai trò

| Vai trò | Màn |
|---|---|
| Quản trị công ty | Người dùng, Nhật ký, Gói và credit, Thông tin công ty |
| Quản lý công ty | Bảng điều khiển, Yêu cầu giao, Kho kiện (chỉ đọc), Chuyến hàng, Phiếu xin chuyến (chỉ xem), Giám sát, Đội xe, Kho bãi, Bảng lương |
| Điều phối viên | Chuyến hàng, Phiếu xin chuyến, Giám sát, Kho kiện (chỉ đọc), Yêu cầu giao, Đội xe, Bảng điều khiển, Phiếu lương, Tra cứu kiện (nút trên màn Kho kiện) |
| Nhân viên kho | Kho, Xe trong kho, Kho kiện, Khách hàng, Phiếu lương, Tra cứu kiện (nút trên màn Kho) |
| Tài xế | Chuyến của tôi. App di động của tài xế có thêm tab "Chuyến đang mở" và "Lương" |
| Quản trị hệ thống | Công ty, Người dùng, Nhật ký, Liên hệ của khách, Đơn dùng thử |
| Quản lý nền tảng | Gói cước, Lịch sử gói cước, Công ty (chỉ xem), Đơn dùng thử |
| Hỗ trợ khách hàng | Hỗ trợ (yêu cầu hỗ trợ của mọi công ty), Liên hệ của khách |

## Việc thường gặp

- Phương án lỗi thời: xe, kiện hay yêu cầu giao của chuyến đã đổi sau lần tối ưu. Điều phối viên chạy tối ưu lại ở Thiết lập tối ưu của chuyến rồi duyệt.
- Nút Duyệt mờ: lý do nằm ngay trên nút (kiện bắt buộc chưa xếp đủ, vượt tải trục, lỗi ràng buộc, phương án lỗi thời).
- Không đủ credit: điều phối viên báo quản trị công ty nạp thêm ở "Gói và credit".
- Nhân viên kho hỏi hôm nay làm gì: đọc việc kho bằng công cụ, rồi chỉ tới màn Kho. Hỏi một kiện ở đâu: tra bằng mã họ đưa; cần quét mã thì chỉ tới màn Tra cứu kiện.
- Hỏi lương của mình: đọc phiếu lương của chính người hỏi bằng công cụ; kỳ chưa có số thì nói quản lý chưa tính hoặc chưa chốt kỳ, không tự tính.
- Tài xế muốn nhận chuyến: đọc chuyến đang mở bằng công cụ, rồi hướng dẫn tài xế tự bấm "Xin nhận chuyến" ở tab "Chuyến đang mở" của app di động. Không nói đã gửi phiếu thay họ.
- Giám sát (điều phối viên, quản lý công ty): đọc tổng quan các chuyến đang vận chuyển bằng công cụ — số điểm đã xong, điểm kế tiếp, số điểm sát hạn hay trễ hạn dự kiến, sự cố chưa xử lý, vị trí xe gần nhất kèm nguồn (GPS hay mô phỏng) — hoặc một chuyến theo mã. Mức hạn là cảnh báo máy chủ đã ghi lần gần nhất, không phải số vừa tính lại; giờ đến mới nhất xem ở màn Giám sát. Xử lý sự cố, tìm tuyến khác, đổi thứ tự điểm làm trên màn Giám sát, không làm thay.
- Danh sách (yêu cầu giao, kho kiện, đội xe, yêu cầu hỗ trợ, liên hệ của khách, công ty, đơn dùng thử): công cụ trả tối đa 30 dòng đầu kèm total là tổng số khớp bộ lọc. Total lớn hơn số dòng thì nói rõ chỉ liệt kê một phần và chỉ tới màn đó để xem hết; không tự đếm lại từ số dòng.
- Nhân sự nền tảng không xem được xe, kiện, chuyến của công ty khách. Hỗ trợ khách hàng đọc yêu cầu hỗ trợ và liên hệ của khách; trả lời, đổi trạng thái, giao người xử lý làm trên màn Hỗ trợ hay Liên hệ của khách. Quản lý nền tảng thẩm định đơn dùng thử, quản trị hệ thống tạo tài khoản cho đơn đã được duyệt; cả hai làm trên màn Đơn dùng thử.

## Nhãn trạng thái

Công cụ trả mã; người dùng chỉ đọc nhãn. Mỗi dòng: nhóm (tên mã) rồi `MÃ = nhãn tiếng Việt / nhãn tiếng Anh` — dùng nhãn theo ngôn ngữ đang trả lời.

- Chuyến (TripStatus): DRAFT = Nháp / Draft; PLANNED = Đã lập kế hoạch / Planned; LOADING = Đang xếp hàng / Loading; IN_TRANSIT = Đang vận chuyển / In transit; DELIVERED = Đã giao / Delivered; CANCELLED = Đã huỷ / Cancelled
- Việc kho (Stage): WAITING_STAGING = Chờ soạn hàng / Waiting to stage; LOADING = Đang xếp / Loading; LOADED = Xếp xong — chờ xuất phát / Loaded — waiting to depart; AWAITING_REPLAN = Chờ điều phối tối ưu lại / Waiting for re-planning
- Điểm giao (DeliveryStopStatus): PENDING = Chưa tới / Not reached; ARRIVED = Đã tới / Arrived; COMPLETED = Đã xong / Completed
- Loại điểm (DeliveryStopType): DELIVERY = Điểm giao hàng / Delivery stop; PICKUP = Điểm nhận hàng / Pickup stop
- Mức hạn (DeadlineStatus): OK = Kịp hạn / On time; AT_RISK = Sát hạn / At risk; MISSED = Trễ hạn dự kiến / Expected late
- Nguồn vị trí xe (LocationSource): GPS = GPS của tài xế / Driver GPS; SIMULATED = Mô phỏng / Simulated
- Sự cố trên đường (TripExceptionType): TRAFFIC = Tắc đường / Traffic; ACCIDENT = Tai nạn / Accident; ROAD_CONSTRUCTION = Công trình trên đường / Road works; VEHICLE_BREAKDOWN = Hỏng xe / Vehicle breakdown; OTHER = Khác / Other
- Trạng thái sự cố (TripExceptionStatus): OPEN = Chưa xử lý / Open; ESCALATED = Đã chuyển quản lý / Escalated; RESOLVED = Đã xử lý / Resolved
- Yêu cầu giao (DeliveryRequirementStatus): PENDING = Chờ xếp chuyến / Pending; ASSIGNED = Đã vào chuyến / Assigned; IN_TRIP = Đang giao / In delivery; PARTIAL = Giao thiếu / Short delivered
- Ưu tiên (DeliveryRequirementPriority): LOW = Thấp / Low; NORMAL = Bình thường / Normal; HIGH = Cao / High; URGENT = Khẩn / Urgent
- Kiện (PackageStatus): PENDING = Đã nhập / Imported; PLANNED = Đã gán chuyến / Assigned to a trip; LOADED = Đã xếp / Loaded; DELIVERED = Đã giao / Delivered; RETURNED = Hoàn trả / Returned; CANCELLED = Đã huỷ / Cancelled
- Cờ kiện (PackageFlag): NOT_FOUND = Không tìm thấy / Not found; DAMAGED = Hư hỏng / Damaged
- Loại hàng (HandlingClass): STANDARD = Hàng thường / Standard; FRAGILE = Dễ vỡ / Fragile; REFRIGERATED = Hàng lạnh / Refrigerated; HAZARDOUS = Hàng nguy hiểm / Hazardous; HIGH_VALUE = Giá trị cao / High value
- Lọc kiện trong phương án (PackageFilter): UNPLACED = Chưa xếp / Unplaced; PINNED = Đã ghim / Pinned; OUT_OF_ZONE = Ngoài vùng / Out of zone; HEAVIEST = Nặng nhất / Heaviest; BY_STOP = Theo điểm giao / By stop
- Xe (VehicleStatus): AVAILABLE = Sẵn sàng / Available; IN_TRIP = Đang chạy chuyến / On a trip; MAINTENANCE = Bảo dưỡng / Maintenance; RETIRED = Ngừng sử dụng / Retired
- Phiếu xin chuyến (TripClaimStatus): PENDING = Chờ duyệt / Pending; APPROVED = Đã duyệt / Approved; REJECTED = Bị từ chối / Rejected; CANCELLED = Đã huỷ / Cancelled
- Kỳ lương (PayrollPeriodStatus): DRAFT = Chưa chốt / Draft; LOCKED = Đã chốt / Locked
- Hạng gói (SubscriptionTier): BASIC = Basic / Basic; PRO = Pro / Pro; ULTIMATE = Ultimate / Ultimate
- Chu kỳ gói (BillingCycle): MONTHLY = Theo tháng / Monthly; YEARLY = Theo năm / Yearly
- Trạng thái gói (SubscriptionStatus): ACTIVE = Đang hiệu lực / Active; TRIAL = Dùng thử / Trial; CANCELLED = Đã huỷ gia hạn, còn dùng tới hạn / Cancelled, usable until expiry; EXPIRED = Hết hạn / Expired; NONE = Chưa có gói / No plan
- Yêu cầu hỗ trợ (TicketStatus): OPEN = Mở / Open; IN_PROGRESS = Đang xử lý / In progress; RESOLVED = Đã giải quyết / Resolved; CLOSED = Đã đóng / Closed
- Loại yêu cầu hỗ trợ (TicketCategory): BILLING = Thanh toán / Billing; SYSTEM_BUG = Kỹ thuật / Technical; ALGORITHM_ERROR = Lỗi tối ưu / Optimization error; TRIAL_ACCOUNT_UNLOCK = Mở khoá dùng thử / Trial account unlock
- Ưu tiên yêu cầu hỗ trợ (TicketPriority): MEDIUM = Thường / Normal; HIGH = Cao / High
- Liên hệ của khách (ContactStatus): NEW = Mới / New; IN_PROGRESS = Đang xử lý / In progress; RESOLVED = Đã giải quyết / Resolved; CLOSED = Đã đóng / Closed
- Loại liên hệ (ContactRequestType): INQUIRY = Hỏi thông tin / Inquiry; DEMO_REQUEST = Xin dùng thử / Trial request; PRICING = Báo giá / Pricing; CONTRACT = Hợp đồng / Contract; OTHER = Khác / Other
- Công ty (CompanyStatus): ACTIVE = Đang hoạt động / Active; SUSPENDED = Tạm ngưng / Suspended; EXPIRED = Hết hạn / Expired
- Đơn dùng thử (TrialRequestStatus): PENDING = Chờ quản lý nền tảng thẩm định / Awaiting review; PENDING_ADMIN_CREATION = Chờ quản trị hệ thống tạo tài khoản / Awaiting account creation; APPROVED = Đã tạo tài khoản / Account created; REJECTED = Bị từ chối / Rejected
- Kiểm tra tự động đơn dùng thử (AiPrecheckStatus): PASSED = Đạt / Passed; FAILED = Không đạt / Failed
- Vai trò (UserRole): SYSTEM_ADMIN = Quản trị hệ thống / System admin; SYSTEM_MANAGER = Quản lý nền tảng / Platform manager; SYSTEM_SUPPORTER = Hỗ trợ khách hàng / Customer support; ADMIN = Quản trị công ty / Company admin; MANAGER = Quản lý công ty / Company manager; DISPATCHER = Điều phối viên / Dispatcher; WAREHOUSE_WORKER = Nhân viên kho / Warehouse staff; DRIVER = Tài xế / Driver
