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


# Job còn chờ / đang chạy: chỉ từ hai trạng thái này mới đổi được trạng thái. Mọi trạng thái khác là trạng thái
# cuối — kể cả FAILED JOB_INTERRUPTED do LoadMasterService ghi khi job quá hạn —, kết quả đến muộn không ghi đè.
UNFINISHED_JOB_STATUSES = (OptimizationJobStatus.PENDING.value, OptimizationJobStatus.RUNNING.value)
