from datetime import datetime
from sqlalchemy import Column, Integer, BigInteger, String, DateTime, JSON

from app.config.database import Base


class AuditLog(Base):
    __tablename__ = "audit_logs"

    id = Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)
    user_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=True)
    action_type = Column(String(50), nullable=False)
    entity_name = Column(String(50), nullable=True)
    entity_id = Column(String(50), nullable=True)
    old_values = Column(JSON, nullable=True)
    new_values = Column(JSON, nullable=True)
    ip_address = Column(String(50), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
