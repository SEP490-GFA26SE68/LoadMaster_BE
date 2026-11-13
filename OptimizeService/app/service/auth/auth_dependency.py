
from typing import Any, Optional
import logging

import jwt
from jwt import PyJWKClient
from jwt.exceptions import PyJWTError
from fastapi import Depends
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

from app.config.settings import settings
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode

logger = logging.getLogger(__name__)
security = HTTPBearer(auto_error=False)

jwks_url = (
        settings.KEYCLOAK_JWKS_URL
        or f"{settings.KEYCLOAK_ISSUER}/protocol/openid-connect/certs"
)

jwks_client = PyJWKClient(
    jwks_url,
    cache_jwk_set=True,
    lifespan=300,
)


def decode_token(token: str) -> dict[str, Any]:
    """
    Xác minh access token do Keycloak cấp:
    - Chữ ký RS256 bằng JWKS
    - Issuer, audience, expiration
    - Các claim bắt buộc
    """
    try:
        signing_key = jwks_client.get_signing_key_from_jwt(token)

        payload = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],
            issuer=settings.KEYCLOAK_ISSUER,
            audience=settings.KEYCLOAK_AUDIENCE,
            options={
                "require": [
                    "exp",
                    "iat",
                    "iss",
                    "aud",
                    "sub",
                ],
            },
            leeway=30,
        )

        if not isinstance(payload, dict):
            raise AppException(ErrorCode.UNAUTHENTICATED)

        return payload

    except AppException:
        raise
    except (PyJWTError, ValueError, TypeError) as exc:
        logger.warning(
            "JWT validation failed: %s: %s",
            type(exc).__name__,
            exc,
        )
        raise AppException(ErrorCode.UNAUTHENTICATED) from exc
    except Exception as exc:
        logger.exception(
            "Unexpected authentication error"
        )
        raise AppException(ErrorCode.UNAUTHENTICATED) from exc


def extract_roles(payload: dict[str, Any]) -> set[str]:
    """
    Trích xuất role thuộc riêng client optimization-service.
    Không gom role từ các client khác.
    """
    resource_access = payload.get("resource_access")

    if not isinstance(resource_access, dict):
        return set()

    client_access = resource_access.get(
        settings.KEYCLOAK_AUDIENCE
    )

    if not isinstance(client_access, dict):
        return set()

    roles = client_access.get("roles")

    if not isinstance(roles, list):
        return set()

    return {
        role.upper()
        for role in roles
        if isinstance(role, str)
    }


def require_role(*allowed_roles: str):
    """
    Dependency kiểm tra các client role đã được xác minh.
    """
    target_roles = {role.upper() for role in allowed_roles}

    def role_checker(
            credentials: Optional[
                HTTPAuthorizationCredentials
            ] = Depends(security),
    ) -> dict[str, Any]:

        if (
                credentials is None
                or credentials.scheme.lower() != "bearer"
                or not credentials.credentials
        ):
            raise AppException(ErrorCode.UNAUTHENTICATED)

        payload = decode_token(credentials.credentials)

        if not extract_roles(payload).intersection(target_roles):
            raise AppException(ErrorCode.UNAUTHORIZED)

        return payload

    return role_checker


def require_optimization_service(
        payload: dict[str, Any] = Depends(
            require_role(settings.KEYCLOAK_REQUIRED_ROLE)
        ),
) -> dict[str, Any]:
    """
    Chỉ chấp nhận service token của Spring Boot.
    """
    if payload.get("azp") != settings.KEYCLOAK_ALLOWED_CLIENT_ID:
        raise AppException(ErrorCode.UNAUTHORIZED)

    return payload


def is_backend_service(payload: dict[str, Any]) -> bool:
    """Token là service token của Spring Boot (client role + azp đúng)."""
    return (
        settings.KEYCLOAK_REQUIRED_ROLE.upper() in extract_roles(payload)
        and payload.get("azp") == settings.KEYCLOAK_ALLOWED_CLIENT_ID
    )


def company_of_caller(payload: dict[str, Any], db) -> Optional[int]:
    """
    Công ty của người dùng gọi bằng token của chính họ: token Keycloak không mang công ty,
    nên tra bảng users theo keycloak_id (= claim sub). Không có tài khoản hay không thuộc công ty nào: None.
    """
    subject = payload.get("sub")
    if not subject or db is None:
        return None
    from app.entity.trip_model import AppUser

    company_id = (
        db.query(AppUser.company_id)
        .filter(AppUser.keycloak_id == str(subject))
        .scalar()
    )
    return int(company_id) if company_id is not None else None


def user_id_of_caller(payload: dict[str, Any], db) -> Optional[int]:
    """
    Mã người dùng (users.id) của người gọi bằng token của chính họ. Token Keycloak chỉ có sub (UUID), không có
    users.id, nên tra theo keycloak_id; không có tài khoản: None.
    """
    subject = payload.get("sub")
    if not subject or db is None:
        return None
    from app.entity.trip_model import AppUser

    user_id = db.query(AppUser.id).filter(AppUser.keycloak_id == str(subject)).scalar()
    return int(user_id) if user_id is not None else None


def require_plan_approver(
        credentials: Optional[
            HTTPAuthorizationCredentials
        ] = Depends(security),
) -> dict[str, Any]:
    """
    Người được duyệt phương án:
    - service token của Spring Boot (Java đã kiểm vai trò và công ty, người duyệt gửi trong thân), hoặc
    - token người dùng có client role DISPATCHER / ADMIN.
    """
    if (
            credentials is None
            or credentials.scheme.lower() != "bearer"
            or not credentials.credentials
    ):
        raise AppException(ErrorCode.UNAUTHENTICATED)

    payload = decode_token(credentials.credentials)

    if is_backend_service(payload):
        return payload

    if extract_roles(payload).intersection({"DISPATCHER", "ADMIN"}):
        return payload

    raise AppException(ErrorCode.UNAUTHORIZED)
