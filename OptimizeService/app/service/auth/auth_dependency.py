from typing import Optional, Any
import jwt
from fastapi import Depends
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode

security = HTTPBearer(auto_error=False)


def decode_token(token: str) -> dict[str, Any]:
    """
    Decode JWT token (hỗ trợ kiểm tra payload không cần verify signature nếu đang test/mock,
    hoặc decode payload từ Keycloak).
    """
    try:
        # Decode không verify signature để linh hoạt với mock token & Keycloak realm certs
        payload = jwt.decode(token, options={"verify_signature": False})
        return payload
    except Exception:
        raise AppException(ErrorCode.UNAUTHENTICATED)


def extract_roles(payload: dict[str, Any]) -> set[str]:
    """Trích xuất tất cả roles từ các cấu trúc Keycloak JWT phổ biến."""
    roles = set()

    # 1. realm_access.roles
    realm_access = payload.get("realm_access", {})
    if isinstance(realm_access, dict):
        for r in realm_access.get("roles", []):
            roles.add(str(r).upper())

    # 2. resource_access.*.roles
    resource_access = payload.get("resource_access", {})
    if isinstance(resource_access, dict):
        for client_data in resource_access.values():
            if isinstance(client_data, dict):
                for r in client_data.get("roles", []):
                    roles.add(str(r).upper())

    # 3. roles top-level
    top_roles = payload.get("roles", [])
    if isinstance(top_roles, list):
        for r in top_roles:
            roles.add(str(r).upper())

    # 4. user authorities / scope
    scope = payload.get("scope", "")
    if isinstance(scope, str):
        for s in scope.split():
            roles.add(s.upper())

    return roles


def require_role(*allowed_roles: str):
    """
    Dependency kiểm tra role của user từ JWT Bearer token.
    Chỉ cho phép các role trong `allowed_roles` (vd: "DISPATCHER", "ADMIN").
    """
    target_roles = {r.upper() for r in allowed_roles}

    def role_checker(credentials: Optional[HTTPAuthorizationCredentials] = Depends(security)):
        if not credentials or not credentials.credentials:
            raise AppException(ErrorCode.UNAUTHENTICATED)

        payload = decode_token(credentials.credentials)
        user_roles = extract_roles(payload)

        # Kiểm tra nếu user có ít nhất 1 role hợp lệ
        if not user_roles.intersection(target_roles):
            raise AppException(ErrorCode.UNAUTHORIZED)

        return payload

    return role_checker
