"""Endpoint kiểm tra chuyến không xác thực đã bị gỡ: không ai ngoài dịch vụ Java được đọc DB qua nó."""
from fastapi.testclient import TestClient

from app.main import app


def test_validate_trip_endpoint_is_gone():
    client = TestClient(app)
    response = client.get("/api/v1/validate/trips/00000000-0000-0000-0000-000000000001")
    assert response.status_code == 404
