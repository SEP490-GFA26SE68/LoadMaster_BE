# S5-08 · ContactByGuest CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Leads & Support |
| **Priority** | 🟢 Could |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Mô tả
Tiếp nhận và quản lý liên hệ từ khách chưa có tài khoản theo bảng `contacts_by_guest`.

## Acceptance Criteria

- [ ] `POST /api/guest-contacts` là public endpoint, nhận `guestName`, `guestEmail`
- [ ] `GET/PUT /api/guest-contacts` dành cho System Admin/Supporter
- [ ] Persist đúng: `id`, `guestName`, `guestEmail`, nullable `assignedSupporterId`, `status` (mặc định `NEW`)
- [ ] Khi assign, validate supporter tồn tại; FK `assigned_supporter_id` dùng `ON DELETE SET NULL`
- [ ] Validate độ dài tên/email tối đa 150 ký tự và email hợp lệ

## Files cần tạo

- `repository/ContactByGuestRepository.java`
- `service/ContactByGuestService.java`
- `controller/ContactByGuestController.java`
- `dto/request/GuestContactRequest.java`
- `dto/response/GuestContactResponse.java`
