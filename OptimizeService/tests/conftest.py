import pytest

from app.service.auth import auth_dependency
from tests.keycloak_tokens import FakeJwksClient


@pytest.fixture
def keycloak_jwks(monkeypatch):
    """Khoá công khai của test thay JWKS của Keycloak: token ký bằng tests.keycloak_tokens qua được decode_token."""
    monkeypatch.setattr(auth_dependency, "jwks_client", FakeJwksClient())
