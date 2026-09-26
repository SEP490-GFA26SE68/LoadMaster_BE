# S3-06b · Keycloak Service Token Provider

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization / Auth |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | ✅ Done |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Class lấy **service access token** từ Keycloak bằng `client_credentials` grant.
FastAPI dùng token này khi gọi engine ngoài (nếu có). FastAPI cũng verify token từ frontend theo chuẩn:

1. JWT signature hợp lệ
2. Token chưa expired
3. `iss` = `http://localhost:8180/realms/loadmaster`
4. `aud` chứa `optimization-service`
5. `scope` chứa `optimization.execute`

Token mẫu:
```json
{
  "iss": "http://localhost:8180/realms/loadmaster",
  "aud": ["optimization-service"],
  "azp": "loadmaster-backend-service",
  "scope": "optimization.execute"
}
```

## Acceptance Criteria
- [x] Class `KeycloakTokenProvider`
- [x] Gọi Keycloak token endpoint: `POST {KEYCLOAK_AUTH_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token`
- [x] Body: `grant_type=client_credentials`, `client_id`, `client_secret`
- [x] Parse response lấy `access_token` + `expires_in`
- [x] **Cache token** — không gọi Keycloak mỗi lần request
- [x] **Auto-refresh** — refresh khi còn < 30s
- [x] Method public: `get_service_token() → str`
- [x] Handle error: Keycloak không khả dụng → raise `ServiceAuthenticationException`

## Cấu hình `.env`
```env
KEYCLOAK_AUTH_URL=http://localhost:8180
KEYCLOAK_REALM=loadmaster
KEYCLOAK_CLIENT_ID=loadmaster-backend-service
KEYCLOAK_CLIENT_SECRET=your-secret-here
```

## Files cần tạo
- `app/service/auth/keycloak_token_provider.py`
- `app/exception/app_exception.py` (ServiceAuthenticationException)
- `app/config/settings.py` (Keycloak settings)

## Notes (Python equivalent)
```python
# Java: KeycloakServiceTokenProviderImpl.java
# Python: app/service/auth/keycloak_token_provider.py

class KeycloakTokenProvider:
    _cached_token: str | None = None
    _expires_at: float = 0

    async def get_service_token(self) -> str:
        if self._cached_token and time.time() < self._expires_at - 30:
            return self._cached_token
        # fetch new token from Keycloak
        ...
```

## Dependencies
- Không có dependency nội bộ (S3-06 phụ thuộc vào issue này)
