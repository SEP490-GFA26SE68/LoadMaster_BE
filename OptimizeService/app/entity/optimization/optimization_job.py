from sqlalchemy import BigInteger, Column, ForeignKey, Integer, String
from sqlalchemy.orm import relationship

from app.config.database import Base
from app.constant.optimization.job_status import OptimizationJobStatus


class OptimizationJob(Base):
    __tablename__ = "optimization_jobs"

    id = Column(
        Integer().with_variant(BigInteger, "postgresql"),
        primary_key=True,
        autoincrement=True,
    )
    trip_id = Column(
        Integer().with_variant(BigInteger, "postgresql"),
        ForeignKey("trips.id"),
        nullable=False,
    )
    algorithm_objective = Column(String(50), nullable=False)
    execution_time_ms = Column(Integer, nullable=True)
    status = Column(
        String(30),
        nullable=False,
        default=OptimizationJobStatus.PENDING.value,
    )

    trip = relationship("Trip")
