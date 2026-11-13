from typing import Generator
from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker, Session

from app.config.database_url import sqlalchemy_database_url
from app.config.settings import settings

db_url = sqlalchemy_database_url(settings.DATABASE_URL, settings.POSTGRES_USER, settings.POSTGRES_PASSWORD)
connect_args = {"check_same_thread": False} if db_url.get_backend_name() == "sqlite" else {}

engine = create_engine(
    db_url,
    connect_args=connect_args,
    pool_pre_ping=True,
    pool_size=2,
    max_overflow=1,
    pool_recycle=600,
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
