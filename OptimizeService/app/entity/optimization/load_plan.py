from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, Numeric, Boolean
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

    job = relationship("OptimizationJob")
    placements = relationship("PackagePlacement", back_populates="load_plan", cascade="all, delete-orphan")
    unplaced = relationship("UnplacedPackage", back_populates="load_plan", cascade="all, delete-orphan")
