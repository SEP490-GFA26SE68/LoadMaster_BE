"""
Token như Keycloak cấp, ký RS256 bằng khoá sinh trong test.

Các test API đi qua đúng lối kiểm token của dịch vụ (auth_dependency.decode_token: chữ ký RS256, iss, aud, exp, iat,
sub; client role ở resource_access) — chỉ bước tải JWKS từ Keycloak được thay bằng khoá công khai của test
(fixture keycloak_jwks ở conftest.py). Không cần Keycloak thật.
"""
import time
import uuid
from types import SimpleNamespace
from typing import Optional

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

from app.config.settings import settings

_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
KID = "loadmaster-test-key"


class FakeJwksClient:
    """Thay PyJWKClient: trả khoá công khai của test cho mọi token (token ký khoá khác vẫn sai chữ ký)."""

    def get_signing_key_from_jwt(self, _token):
        return SimpleNamespace(key=_PRIVATE_KEY.public_key())


def keycloak_token(
    client_roles: list[str],
    *,
    azp: str = "loadmaster-web",
    sub: Optional[str] = None,
    expires_in: int = 300,
    audience: Optional[str] = None,
    issuer: Optional[str] = None,
) -> str:
    now = int(time.time())
    aud = audience or settings.KEYCLOAK_AUDIENCE
    payload = {
        "iss": issuer or settings.KEYCLOAK_ISSUER,
        "aud": aud,
        "sub": sub or str(uuid.uuid4()),
        "azp": azp,
        "iat": now,
        "exp": now + expires_in,
        "resource_access": {aud: {"roles": list(client_roles)}},
    }
    return jwt.encode(payload, _PRIVATE_KEY, algorithm="RS256", headers={"kid": KID})


def user_headers(*client_roles: str, sub: Optional[str] = None) -> dict[str, str]:
    """Token người dùng của web (client loadmaster-web) có các client role của optimization-service."""
    return {"Authorization": f"Bearer {keycloak_token(list(client_roles), sub=sub)}"}


def service_headers() -> dict[str, str]:
    """Service token của LoadMasterService (client credentials): role OPTIMIZATION_EXECUTE, azp của backend."""
    token = keycloak_token([settings.KEYCLOAK_REQUIRED_ROLE], azp=settings.KEYCLOAK_ALLOWED_CLIENT_ID)
    return {"Authorization": f"Bearer {token}"}
