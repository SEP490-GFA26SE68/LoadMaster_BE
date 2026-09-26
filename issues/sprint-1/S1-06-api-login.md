# S1-06 · API Đăng nhập

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Auth |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Assignee** | — |
| **Status** | 🔲 Todo |
| **PRD Ref** | FR-AUTH-01 |

## Mô tả
`POST /api/auth/login` — xác thực `email` + password → trả JWT token kèm role duy nhất của user.

## Acceptance Criteria
- [ ] `AuthController.java` với endpoint `POST /api/auth/login`
- [ ] `AuthService.java` — `authenticate(email, password)`
- [ ] Tìm User trong DB → so sánh BCrypt password
- [ ] Nếu hợp lệ → gọi `JwtTokenProvider.generateToken(user, authorities)`
- [ ] Trả `AuthResponse(accessToken, tokenType, role, userId, companyId)`
- [ ] Nếu sai → trả 401 `Bad credentials`

## Request/Response
```json
// Request
POST /api/auth/login
{ "email": "admin@loadmaster.vn", "password": "123456" }

// Response 200
{
  "code": 200,
  "result": {
    "accessToken": "eyJhbGci...",
    "tokenType": "Bearer",
    "role": "COMPANY_ADMIN",
    "userId": 1,
    "companyId": 1
  }
}
```

## Files cần tạo
- `src/main/java/.../controller/AuthController.java`
- `src/main/java/.../service/AuthService.java`
- `src/main/java/.../dto/request/LoginRequest.java`
- `src/main/java/.../dto/response/AuthResponse.java`

## Dependencies
- S1-02 (JWT config)
