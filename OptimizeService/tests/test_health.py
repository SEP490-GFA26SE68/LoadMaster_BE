"""/health kiểm DB thật: DB trả lời thì UP, không thì 503."""
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient

from app.config.database import get_db
from app.main import app


class _BrokenSession:
    def execute(self, *args, **kwargs):
        raise RuntimeError("database unreachable")

    def close(self):
        pass


def _sqlite_db():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    session = sessionmaker(bind=engine)()
    try:
        yield session
    finally:
        session.close()


def _broken_db():
    yield _BrokenSession()


def test_health_is_up_when_database_answers():
    app.dependency_overrides[get_db] = _sqlite_db
    try:
        response = TestClient(app).get("/health")
    finally:
        app.dependency_overrides.pop(get_db, None)

    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_health_is_503_when_database_fails():
    app.dependency_overrides[get_db] = _broken_db
    try:
        response = TestClient(app).get("/health")
    finally:
        app.dependency_overrides.pop(get_db, None)

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
