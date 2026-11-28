"""Tiến độ sống của một lần chạy tối ưu (optimization_jobs.progress, V58).

Bộ giải (thread worker) báo qua các hàm của module này; thread ghi tiến độ của runner đọc snapshot
và ghi xuống DB khoảng mỗi giây. Báo tiến độ không bao giờ làm hỏng, làm chậm đáng kể hay làm đổi kết
quả của job: mỗi lời gọi O(1), không đọc / ghi trạng thái của bộ giải, nuốt mọi ngoại lệ. Không có bộ
báo đang bật (test, engine từ xa, đường chạy cũ) thì mọi hàm là no-op.

Bộ báo đi theo `contextvars`: runner đặt nó trong thread worker trước `asyncio.run(...)`, task của
asyncio sao chép ngữ cảnh đó nên `solve_candidates` (chạy đồng bộ trong task) thấy cùng bộ báo.

JSON ghi xuống DB (Java đổi sang camelCase của OptimizationJobStatusResponse.progress):
  {"stage": "BUILDING", "algorithm": "EP_DBLF", "layouts_tried": 3, "total_packages": 97,
   "best_placed_count": 87, "best_volume_percent": 54.5, "updated_at": "2026-11-27T01:15:02Z"}
"""
from __future__ import annotations

import logging
import threading
from contextvars import ContextVar, Token
from datetime import datetime, timezone
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)

BUILDING = "BUILDING"      # các lượt EP + DBLF đầu
IMPROVING = "IMPROVING"    # mô hình Lumo, EP + Lumo-R1.1, GA, SA
SELECTING = "SELECTING"    # chọn ba phương án A · B · C và chấm điểm
SAVING = "SAVING"          # kiểm lại và lưu kết quả
STAGES = (BUILDING, IMPROVING, SELECTING, SAVING)

# Nhịp ghi tiến độ xuống DB (giây). Riêng với nhịp heartbeat của lease.
PROGRESS_WRITE_SECONDS = 1.0


class ProgressReporter:
    """Snapshot tiến độ, an toàn giữa thread bộ giải (ghi) và thread ghi DB (đọc)."""

    def __init__(self, total_packages: Optional[int] = None):
        self._lock = threading.Lock()
        self._stage: Optional[str] = None
        self._algorithm: Optional[str] = None
        self._layouts_tried = 0
        self._total_packages = total_packages
        self._best_placed: Optional[int] = None
        self._best_volume: Optional[float] = None

    def stage(self, stage: str, algorithm: Optional[str] = None) -> None:
        if stage not in STAGES:
            return
        with self._lock:
            self._stage = stage
            self._algorithm = algorithm if stage in (BUILDING, IMPROVING) else None

    def layout_done(self, layout: Any) -> None:
        """Một cách xếp đã dựng xong: đếm, và giữ max số kiện xếp được / lấp đầy (hai max độc lập)."""
        placed, volume = _layout_numbers(layout)
        with self._lock:
            self._layouts_tried += 1
            if placed is not None and (self._best_placed is None or placed > self._best_placed):
                self._best_placed = placed
            if volume is not None and (self._best_volume is None or volume > self._best_volume):
                self._best_volume = volume

    def snapshot(self) -> Optional[dict]:
        """Tiến độ hiện tại (chưa có updated_at); None khi bộ giải chưa báo gì."""
        with self._lock:
            if self._stage is None:
                return None
            return {
                "stage": self._stage,
                "algorithm": self._algorithm,
                "layouts_tried": self._layouts_tried,
                "total_packages": self._total_packages,
                "best_placed_count": self._best_placed,
                "best_volume_percent": self._best_volume,
            }


def _layout_numbers(layout: Any) -> tuple[Optional[int], Optional[float]]:
    metrics = getattr(layout, "metrics", None)
    placed = getattr(metrics, "packed_count", None) if metrics is not None else None
    if placed is None:
        placements = getattr(layout, "placements", None)
        placed = len(placements) if placements is not None else None
    ratio = getattr(metrics, "volume_utilization", None) if metrics is not None else None
    # volume_utilization là tỷ lệ 0–1 (như load_plans.volume_utilization); web đọc phần trăm 0–100.
    volume = round(float(ratio) * 100.0, 1) if ratio is not None else None
    return (int(placed) if placed is not None else None), volume


_CURRENT: ContextVar[Optional[ProgressReporter]] = ContextVar("optimization_job_progress", default=None)


def activate(reporter: ProgressReporter) -> Token:
    return _CURRENT.set(reporter)


def deactivate(token: Token) -> None:
    try:
        _CURRENT.reset(token)
    except Exception:  # token của ngữ cảnh khác: chỉ cần không làm hỏng job
        _CURRENT.set(None)


def current() -> Optional[ProgressReporter]:
    return _CURRENT.get()


def stage(name: str, algorithm: Optional[str] = None) -> None:
    """Bộ giải chuyển sang một giai đoạn (không có bộ báo thì không làm gì)."""
    try:
        reporter = _CURRENT.get()
        if reporter is not None:
            reporter.stage(name, algorithm)
    except Exception:
        logger.debug("job_progress.stage ignored", exc_info=True)


def layout_done(layout: Any) -> None:
    """Bộ giải vừa dựng xong một cách xếp (không có bộ báo thì không làm gì)."""
    try:
        reporter = _CURRENT.get()
        if reporter is not None:
            reporter.layout_done(layout)
    except Exception:
        logger.debug("job_progress.layout_done ignored", exc_info=True)


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class ProgressWriter:
    """Ghi snapshot xuống DB khi nó đổi. `write(progress) -> bool` trả True khi đã ghi được.

    Mọi lỗi ghi chỉ được log: không đụng lease, không đặt `lost`, không làm hỏng job.
    """

    def __init__(self, reporter: ProgressReporter, write: Callable[[dict], Any],
                 interval: float = PROGRESS_WRITE_SECONDS, clock: Callable[[], str] = _utc_now_iso):
        self.reporter = reporter
        self._write = write
        self.interval = interval
        self._clock = clock
        self._lock = threading.Lock()
        self._last_written: Optional[dict] = None
        self._closed = False

    def flush(self) -> bool:
        """Ghi snapshot nếu nó khác lần đã ghi. True khi có ghi."""
        try:
            with self._lock:
                if self._closed:
                    return False
                snap = self.reporter.snapshot()
                if snap is None or snap == self._last_written:
                    return False
                payload = dict(snap, updated_at=self._clock())
                if self._write(payload) is False:
                    return False  # hàng bị khoá / job không còn RUNNING: thử lại ở nhịp sau
                self._last_written = snap
                return True
        except Exception as exc:
            logger.warning("optimization_progress stage=write_failed exception_type=%s", type(exc).__name__)
            return False

    def close(self) -> None:
        """Sau close() không ghi gì nữa (job đã / sắp ở trạng thái cuối)."""
        with self._lock:
            self._closed = True

    def loop(self, stop: threading.Event) -> None:
        while not stop.wait(self.interval):
            self.flush()
