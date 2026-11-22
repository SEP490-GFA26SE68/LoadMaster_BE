from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, JSON
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import relationship

from app.config.database import Base


class UnplacedPackage(Base):
    __tablename__ = "unplaced_packages"

    id = Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)
    load_plan_id = Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey("load_plans.id"), nullable=False)
    package_id = Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey("packages.id"), nullable=True)
    rejection_reason = Column(String(50), nullable=True)
    # Mã HC của solver (AXLE_CONFIG_MISSING, …); JSON list để SQLite test và jsonb Postgres cùng đọc được.
    violated_constraints = Column(JSON().with_variant(JSONB(), "postgresql"), nullable=True)

    load_plan = relationship("LoadPlan", back_populates="unplaced")
    cargo_package = relationship("CargoPackage")
