# S5-04 · SupportTicket CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Support |
| **Priority** | 🟢 Could |
| **Label** | `BE` |
| **PRD Ref** | FR-TKT-01, FR-TKT-02 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET/POST/PUT /api/support-tickets`
- [ ] User tạo ticket với `ticketDescription`; service gán `companyId` và `requesterUserId`
- [ ] Auto-gen `ticketCode`
- [ ] System Admin: set nullable `assignedSupporterId`, update `status`
- [ ] Status flow: `OPEN` → `IN_PROGRESS` → `RESOLVED` → `CLOSED`
- [ ] Persist đúng: `ticketCode`, `ticketDescription`, `companyId`, `requesterUserId`, `assignedSupporterId`, `status`
- [ ] Không dùng `category`, `priority`, `subject` hoặc `jobId` vì không có trong schema v3.4

## Files cần tạo
- `repository/SupportTicketRepository.java`
- `service/SupportTicketService.java`
- `controller/SupportTicketController.java`
- `dto/request/SupportTicketRequest.java`
- `dto/response/SupportTicketResponse.java`
