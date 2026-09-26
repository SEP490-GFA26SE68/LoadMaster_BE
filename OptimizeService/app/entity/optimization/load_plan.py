from datetime import datetime
from typing import Optional
from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, Numeric, Boolean, DateTime
from sqlalchemy.orm import relationship

from app.config.database import Base


class LoadPlan(Base):
    __tablename__ = "load_plans"

    id = Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)
    job_id = Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey("optimization_jobs.id"), nullable=False)
    plan_name = Column(String(100), nullable=True)
    packed_items_count = Column(Integer, nullable=True, default=0)
    volume_utilization = Column(Numeric(5, 2), nullable=True)
    weight_utilization = Column(Numeric(5, 2), nullable=True)
    approved = Column("is_approved", Boolean, default=False)
    approved_by_id = Column("approved_by", Integer().with_variant(BigInteger, "postgresql"), nullable=True)
    version = Column(Integer, default=1, nullable=False)
    approved_at = Column(DateTime, nullable=True)

    job = relationship("OptimizationJob")
    placements = relationship("PackagePlacement", back_populates="load_plan", cascade="all, delete-orphan")
    unplaced = relationship("UnplacedPackage", back_populates="load_plan", cascade="all, delete-orphan")

    @property
    def plan_version(self) -> int:
        return self.version or 1

    @plan_version.setter
    def plan_version(self, val: int) -> None:
        self.version = val

    @property
    def volume_utilization_percent(self) -> Optional[float]:
        return float(self.volume_utilization) if self.volume_utilization is not None else None

    @volume_utilization_percent.setter
    def volume_utilization_percent(self, val: Optional[float]) -> None:
        self.volume_utilization = val

    @property
    def is_approved(self) -> bool:
        return bool(self.approved)

    @is_approved.setter
    def is_approved(self, val: bool) -> None:
        self.approved = val

    @property
    def approved_by_user_id(self) -> Optional[int]:
        return self.approved_by_id

    @approved_by_user_id.setter
    def approved_by_user_id(self, val: Optional[int]) -> None:
        self.approved_by_id = val
