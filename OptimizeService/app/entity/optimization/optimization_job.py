from datetime import datetime
from typing import Optional
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
    algorithm_objective = Column(String(50), nullable=False, default="MAX_VOLUME")
    execution_time_ms = Column(Integer, nullable=True)
    status = Column(
        String(30),
        nullable=False,
        default=OptimizationJobStatus.PENDING.value,
    )

    trip = relationship("Trip")

    def __init__(self, **kwargs):
        if "algorithm_objective" not in kwargs and "objective" not in kwargs:
            kwargs["algorithm_objective"] = "MAX_VOLUME"
        elif "objective" in kwargs and "algorithm_objective" not in kwargs:
            kwargs["algorithm_objective"] = str(kwargs["objective"])
        super().__init__(**kwargs)

    @property
    def tripId(self) -> int:
        return self.trip_id

    @tripId.setter
    def tripId(self, value: int) -> None:
        self.trip_id = value

    @property
    def algorithmObjective(self) -> str:
        return self.algorithm_objective

    @algorithmObjective.setter
    def algorithmObjective(self, value: str) -> None:
        self.algorithm_objective = value

    @property
    def executionTimeMs(self) -> Optional[int]:
        return self.execution_time_ms

    @executionTimeMs.setter
    def executionTimeMs(self, value: Optional[int]) -> None:
        self.execution_time_ms = value

    _uuid_to_id_map: dict[str, int] = {}

    # Compatibility aliases for transitional services
    @property
    def job_uuid(self) -> str:
        return getattr(self, "_job_uuid", None) or (str(self.id) if self.id is not None else "")

    @job_uuid.setter
    def job_uuid(self, value: str) -> None:
        str_val = str(value)
        self._job_uuid = str_val
        if getattr(self, "id", None) is not None:
            OptimizationJob._uuid_to_id_map[str_val] = self.id

    @property
    def algorithm_name(self) -> Optional[str]:
        return getattr(self, "_algorithm_name", None)

    @algorithm_name.setter
    def algorithm_name(self, value: Optional[str]) -> None:
        self._algorithm_name = value

    @property
    def time_limit_sec(self) -> Optional[int]:
        return getattr(self, "_time_limit_sec", 60)

    @time_limit_sec.setter
    def time_limit_sec(self, value: Optional[int]) -> None:
        self._time_limit_sec = value

    @property
    def objective(self) -> str:
        return self.algorithm_objective

    @objective.setter
    def objective(self, value: str) -> None:
        self.algorithm_objective = value

    @property
    def computation_ms(self) -> Optional[int]:
        return self.execution_time_ms

    @computation_ms.setter
    def computation_ms(self, value: Optional[int]) -> None:
        self.execution_time_ms = value

    @property
    def created_at(self) -> Optional[datetime]:
        return getattr(self, "_created_at", None)

    @created_at.setter
    def created_at(self, value: Optional[datetime]) -> None:
        self._created_at = value

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "tripId": self.trip_id,
            "algorithmObjective": self.algorithm_objective,
            "executionTimeMs": self.execution_time_ms,
            "status": self.status,
        }
