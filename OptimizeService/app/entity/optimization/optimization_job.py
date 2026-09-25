from datetime import datetime
from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, DateTime
from sqlalchemy.orm import relationship

from app.config.database import Base
from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective


class OptimizationJob(Base):
    __tablename__ = "optimization_jobs"

    id = Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)
    trip_id = Column(String(64), ForeignKey("trips.id"), nullable=False)
    job_uuid = Column(String(64), unique=True, nullable=False, index=True)
    algorithm_name = Column(String(50), nullable=True)
    objective = Column(String(50), nullable=True, default=OptimizationObjective.MAX_VOLUME.value)
    time_limit_sec = Column(Integer, default=60)
    status = Column(String(50), nullable=False, default=OptimizationJobStatus.PENDING.value)
    computation_ms = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    trip = relationship("Trip")
