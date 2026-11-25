"""Lỗi kiểm đầu vào từ validator tự viết (BeforeValidator) phải ra 422, không phải 500."""
from fastapi import FastAPI
from pydantic import BaseModel
from starlette.testclient import TestClient

from app.dto.optimization.engine.rotation_contract import RotationCode
from app.exception.global_exception_handler import register_exception_handlers


class _Body(BaseModel):
    rotation_type: RotationCode = 0


def _app() -> FastAPI:
    app = FastAPI()
    register_exception_handlers(app)

    @app.post("/echo")
    def echo(body: _Body):
        return {"rotation_type": body.rotation_type}

    return app


def test_custom_validator_error_returns_422_with_message():
    response = TestClient(_app(), raise_server_exceptions=False).post("/echo", json={"rotation_type": 9})

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "VALIDATION_ERROR"
    assert body["errors"][0]["loc"] == ["body", "rotation_type"]
    assert "0..5" in body["errors"][0]["msg"]


def test_valid_body_passes():
    response = TestClient(_app()).post("/echo", json={"rotation_type": "3"})

    assert response.status_code == 200
    assert response.json() == {"rotation_type": 3}
