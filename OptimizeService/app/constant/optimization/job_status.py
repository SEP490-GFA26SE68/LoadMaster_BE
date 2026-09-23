from enum import Enum


class OptimizationJobStatus(str, Enum):
    """Trạng thái của optimization job."""
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    TIMEOUT = "TIMEOUT"
    NO_SOLUTION = "NO_SOLUTION"
    PARTIAL = "PARTIAL"
