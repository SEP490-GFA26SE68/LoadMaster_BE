"""
Tiến độ sống của lần chạy tối ưu (optimization_jobs.progress, V58).

Seams: job_progress (bộ báo + bộ ghi), OptimizationClient.solve_candidates (các giai đoạn và số cách xếp),
JobExecutionProtocol.write_progress (chỉ ghi cho lần chạy đang RUNNING), AsyncOptimizationRunner._execute_owned
(ghi tiến độ không đụng lease, không ghi sau trạng thái cuối). Đơn vị: cm, kg.
"""
import threading
import uuid
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.config.database import Base
from app.config.settings import settings
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, MetricsData
from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest, StopData, VehicleData
from app.entity.optimization.optimization_job import OptimizationJob
from app.service.optimize import job_progress
from app.service.optimize.job_execution_protocol import JobExecutionProtocol
from app.service.optimize.optimization_client import OptimizationClient

UUID = "41858661-1c23-372e-9329-e09660e2648b"

VEHICLE = VehicleData(
    inner_l=600, inner_w=200, inner_h=200, max_payload_kg=5000,
    door_w=200, door_h=200,
    front_axle_x=-100, rear_axle_x=450,
    front_axle_limit_kg=6000, rear_axle_limit_kg=10000,
    empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
)
SIZES = [(60, 50, 40), (50, 50, 50), (80, 40, 40), (40, 40, 30), (60, 60, 50), (50, 40, 30)]


def _problem(algorithm_name=None, count=10) -> ProblemRequest:
    packages = [
        PackageData(id=str(i), l=SIZES[i % len(SIZES)][0], w=SIZES[i % len(SIZES)][1],
                    h=SIZES[i % len(SIZES)][2], weight=20 + 5 * (i % 4), stop_index=1 + i % 2,
                    allowed_rotations=[0, 1])
        for i in range(1, count + 1)
    ]
    return ProblemRequest(vehicle=VEHICLE, packages=packages,
                          stops=[StopData(id="1", sequence=1), StopData(id="2", sequence=2)],
                          time_limit_sec=60, algorithm_name=algorithm_name)


def _layout(placed, volume_ratio):
    return EngineOptimizationResponse(placements=[], metrics=MetricsData(
        packed_count=placed, volume_utilization=volume_ratio, weight_utilization=0, computation_ms=0))


class Recording(job_progress.ProgressReporter):
    """Bộ báo ghi lại từng lời gọi để kiểm thứ tự."""

    def __init__(self, total=None):
        super().__init__(total)
        self.events = []

    def stage(self, stage, algorithm=None):
        super().stage(stage, algorithm)
        self.events.append(("stage", stage, algorithm))

    def layout_done(self, layout):
        super().layout_done(layout)
        self.events.append(("layout", self.snapshot()["layouts_tried"] if self.snapshot() else None))


@pytest.fixture
def fast_meta(monkeypatch):
    # Đường GA + SA cũ (số lần giải mã cố định → kết quả lặp lại được). Từ 28/11 SA lmai (M10) bật mặc định và thay đường
    # này ở PRO; bước SA lmai có test riêng (test_pro_reports_lmai_sa_stage_when_flag_on).
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_SA_ENABLED", False)
    monkeypatch.setattr(settings, "METAHEURISTIC_TIME_BUDGET_SEC", 30.0)
    monkeypatch.setattr(settings, "METAHEURISTIC_MAX_EVALUATIONS", 8)


def _run_with(reporter, problem):
    token = job_progress.activate(reporter)
    try:
        return OptimizationClient(engine_url="").solve_candidates(problem)
    finally:
        job_progress.deactivate(token)


class TestReporter:
    def test_nothing_reported_means_no_progress(self):
        assert job_progress.ProgressReporter(total_packages=97).snapshot() is None

    def test_best_counts_are_independent_maxima(self):
        r = job_progress.ProgressReporter(total_packages=97)
        r.stage(job_progress.BUILDING, "EP_DBLF")
        assert r.snapshot()["best_placed_count"] is None and r.snapshot()["best_volume_percent"] is None
        r.layout_done(_layout(87, 0.40))
        r.layout_done(_layout(80, 0.5451))   # ít kiện hơn nhưng đầy hơn
        r.layout_done(_layout(60, 0.30))
        assert r.snapshot() == {"stage": "BUILDING", "algorithm": "EP_DBLF", "layouts_tried": 3,
                                "total_packages": 97, "best_placed_count": 87, "best_volume_percent": 54.5}

    def test_selecting_and_saving_have_no_algorithm_and_unknown_stage_is_ignored(self):
        r = job_progress.ProgressReporter()
        r.stage(job_progress.IMPROVING, "GA")
        r.stage(job_progress.SELECTING, "GA")
        assert r.snapshot()["algorithm"] is None
        r.stage("WHATEVER", "SA")
        assert r.snapshot()["stage"] == "SELECTING"

    def test_layout_without_metrics_counts_placements(self):
        r = job_progress.ProgressReporter()
        r.stage(job_progress.BUILDING)
        r.layout_done(SimpleNamespace(placements=[], metrics=None))
        assert r.snapshot()["layouts_tried"] == 1 and r.snapshot()["best_placed_count"] == 0
        assert r.snapshot()["best_volume_percent"] is None

    def test_module_hooks_are_noops_without_reporter_and_swallow_errors(self):
        job_progress.stage(job_progress.BUILDING, "EP_DBLF")
        job_progress.layout_done(object())
        broken = Mock(spec=job_progress.ProgressReporter)
        broken.stage.side_effect = RuntimeError("boom")
        broken.layout_done.side_effect = RuntimeError("boom")
        token = job_progress.activate(broken)
        try:
            job_progress.stage(job_progress.BUILDING)
            job_progress.layout_done(object())
        finally:
            job_progress.deactivate(token)
        assert job_progress.current() is None

    def test_concurrent_writer_and_reader_keep_a_consistent_count(self):
        r = job_progress.ProgressReporter()
        r.stage(job_progress.BUILDING)
        seen = []

        def read():
            for _ in range(2000):
                seen.append(r.snapshot()["layouts_tried"])

        reader = threading.Thread(target=read)
        reader.start()
        for i in range(2000):
            r.layout_done(_layout(i, 0.1))
        reader.join()
        assert r.snapshot()["layouts_tried"] == 2000 and r.snapshot()["best_placed_count"] == 1999
        assert seen == sorted(seen)


class TestWriter:
    def _writer(self, write):
        r = job_progress.ProgressReporter(total_packages=5)
        return r, job_progress.ProgressWriter(r, write, interval=0.01, clock=lambda: "2026-11-27T01:15:02Z")

    def test_writes_only_when_snapshot_changes(self):
        written = []
        r, w = self._writer(lambda p: written.append(p))
        assert w.flush() is False and written == []           # chưa báo gì: không ghi
        r.stage(job_progress.BUILDING, "EP_DBLF")
        assert w.flush() is True
        assert w.flush() is False                             # không đổi
        r.layout_done(_layout(4, 0.25))
        assert w.flush() is True
        assert written[-1] == {"stage": "BUILDING", "algorithm": "EP_DBLF", "layouts_tried": 1, "total_packages": 5,
                               "best_placed_count": 4, "best_volume_percent": 25.0,
                               "updated_at": "2026-11-27T01:15:02Z"}
        assert len(written) == 2

    def test_skipped_write_is_retried(self):
        answers = iter([False, True])
        calls = []
        r, w = self._writer(lambda p: (calls.append(p), next(answers))[1])
        r.stage(job_progress.BUILDING)
        assert w.flush() is False
        assert w.flush() is True and len(calls) == 2

    def test_write_errors_are_swallowed_and_nothing_written_after_close(self):
        r, w = self._writer(Mock(side_effect=RuntimeError("db down")))
        r.stage(job_progress.BUILDING)
        assert w.flush() is False
        written = []
        r, w = self._writer(lambda p: written.append(p))
        r.stage(job_progress.BUILDING)
        w.close()
        assert w.flush() is False and written == []

    def test_loop_writes_on_its_own_beat_until_stopped(self):
        written = []
        r, w = self._writer(lambda p: written.append(p))
        r.stage(job_progress.BUILDING)
        stop = threading.Event()
        t = threading.Thread(target=w.loop, args=(stop,))
        t.start()
        for _ in range(200):
            if written:
                break
            threading.Event().wait(0.01)
        stop.set()
        t.join(1)
        assert len(written) == 1 and not t.is_alive()


class TestSolverStages:
    def test_basic_builds_then_selects_and_counts_every_ep_layout(self):
        problem = _problem("EP_DBLF")
        r = Recording(total=len(problem.packages))
        result = _run_with(r, problem)
        stages = [e[1:] for e in r.events if e[0] == "stage"]
        assert stages == [("BUILDING", "EP_DBLF"), ("SELECTING", None)]
        counts = [e[1] for e in r.events if e[0] == "layout"]
        assert counts == list(range(1, len(counts) + 1)) and counts
        snap = r.snapshot()
        assert snap["total_packages"] == 10
        assert snap["best_placed_count"] == max(len(c.result.placements) for c in result.candidates)
        assert snap["best_volume_percent"] is not None and 0 < snap["best_volume_percent"] <= 100

    def test_pro_improves_with_ga_then_sa_after_building(self, fast_meta):
        r = Recording()
        _run_with(r, _problem("EP_DBLF_GA"))
        stages = [e[1:] for e in r.events if e[0] == "stage"]
        assert stages[0] == ("BUILDING", "EP_DBLF") and stages[-1] == ("SELECTING", None)
        improving = [s for s in stages if s[0] == "IMPROVING"]
        assert improving and improving[0] == ("IMPROVING", "GA")
        assert stages.index(("IMPROVING", "GA")) > 0
        if ("IMPROVING", "SA") in stages:
            assert stages.index(("IMPROVING", "SA")) > stages.index(("IMPROVING", "GA"))
        building = next(i for i, e in enumerate(r.events) if e == ("stage", "IMPROVING", "GA"))
        before = sum(1 for e in r.events[:building] if e[0] == "layout")
        assert r.snapshot()["layouts_tried"] > before   # GA / SA giải mã thêm cách xếp

    def test_lmai_builder_layouts_are_counted(self, monkeypatch):
        """M9: cách xếp của bộ dựng lmai cũng được đếm (bước EP cũ không chạy khi bộ dựng lmai đã có cách xếp)."""
        pytest.importorskip("numpy")
        monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_BUILDER_ENABLED", True)
        problem = _problem("EP_DBLF")
        r = Recording(total=len(problem.packages))
        result = _run_with(r, problem)
        counts = [e[1] for e in r.events if e[0] == "layout"]
        assert counts == list(range(1, len(counts) + 1)) and counts
        assert r.snapshot()["best_placed_count"] == max(len(c.result.placements) for c in result.candidates)

    def test_pro_reports_lmai_sa_stage_when_flag_on(self, monkeypatch):
        """M10: SA lmai báo bước IMPROVING / SA, và các cách xếp SA qua kiểm được đếm."""
        pytest.importorskip("numpy")
        monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_BUILDER_ENABLED", True)
        monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_SA_ENABLED", True)
        monkeypatch.setattr(settings, "LMAI_SA_BUDGET_SEC", 2.0)
        r = Recording()
        result = _run_with(r, _problem("EP_DBLF_GA"))
        stages = [e[1:] for e in r.events if e[0] == "stage"]
        assert stages[0] == ("BUILDING", "EP_DBLF") and stages[-1] == ("SELECTING", None)
        assert ("IMPROVING", "SA") in stages and ("IMPROVING", "GA") not in stages
        at = r.events.index(("stage", "IMPROVING", "SA"))
        after = sum(1 for e in r.events[at:] if e[0] == "layout")
        if any(c.result.algorithm_name == "SA" for c in result.candidates):
            assert after >= 1                          # cách xếp SA vào job thì đã được đếm sau bước SA

    def test_reporting_never_changes_the_result(self, fast_meta):
        plain = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF_GA"))
        reported = _run_with(Recording(), _problem("EP_DBLF_GA"))
        key = lambda res: [[(p.package_id, p.x, p.y, p.z, p.rotation_type) for p in c.result.placements]
                           for c in res.candidates]
        assert key(plain) == key(reported)

    def test_solver_without_reporter_still_runs(self):
        assert job_progress.current() is None
        result = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF"))
        assert [c.label for c in result.candidates] == ["A", "B", "C"]


@pytest.fixture
def sessions():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine)
    with factory() as db:
        db.add(OptimizationJob(job_uuid=UUID, trip_id=1, status="RUNNING", execution_generation=1))
        db.add(OptimizationJob(job_uuid=str(uuid.uuid4()), trip_id=1, status="COMPLETED", execution_generation=1))
        db.commit()
    return factory


class TestWriteProgress:
    def _progress(self, sessions, job_uuid=UUID):
        with sessions() as db:
            return db.query(OptimizationJob).filter_by(job_uuid=job_uuid).one().progress

    def test_writes_for_the_running_generation_only(self, sessions):
        protocol = JobExecutionProtocol(sessions)
        payload = {"stage": "BUILDING", "layouts_tried": 1}
        assert protocol.write_progress(UUID, 2, payload) is False
        assert self._progress(sessions) is None
        assert protocol.write_progress(UUID, 1, payload) is True
        assert self._progress(sessions) == payload

    def test_never_writes_a_terminal_job(self, sessions):
        with sessions() as db:
            done = db.query(OptimizationJob).filter_by(status="COMPLETED").one().job_uuid
        assert JobExecutionProtocol(sessions).write_progress(done, 1, {"stage": "SAVING"}) is False
        assert self._progress(sessions, done) is None


class TestRunnerProgress:
    def _runner(self, solve):
        from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner

        class Solver:
            async def solve(self, problem):
                return solve(problem)

        runner = AsyncOptimizationRunner.__new__(AsyncOptimizationRunner)
        runner.optimization_client = Solver()
        runner.persistence_service = Mock()
        runner.engine_exception_handler = Mock()
        runner.engine_exception_handler.classify_result.return_value = "COMPLETED"
        return runner

    def _run(self, runner, protocol):
        with patch("app.dto.optimization.engine.rotation_contract.validate_result_rotations"):
            runner._execute_owned(protocol, UUID, 1, _problem("EP_DBLF", count=3).model_dump_json())

    def test_saving_is_written_before_finalize_and_nothing_after(self):
        protocol = Mock(heartbeat_seconds=5)
        protocol.begin.return_value = True
        protocol.write_progress.return_value = True

        def solve(problem):
            job_progress.stage(job_progress.BUILDING, "EP_DBLF")
            job_progress.layout_done(_layout(3, 0.1))
            return EngineOptimizationResponse(placements=[])

        self._run(self._runner(solve), protocol)
        names = [c[0] for c in protocol.mock_calls]
        writes = [i for i, n in enumerate(names) if n == "write_progress"]
        final = names.index("finalize")
        assert writes and max(writes) < final
        last = protocol.write_progress.call_args_list[-1].args
        assert last[:2] == (UUID, 1)
        assert last[2]["stage"] == "SAVING" and last[2]["algorithm"] is None
        assert last[2]["total_packages"] == 3 and last[2]["layouts_tried"] == 1
        assert last[2]["updated_at"].endswith("Z")
        assert job_progress.current() is None

    def test_progress_write_failure_never_loses_the_lease(self):
        protocol = Mock(heartbeat_seconds=0.01)
        protocol.begin.return_value = True
        protocol.write_progress.side_effect = RuntimeError("db down")
        beat = threading.Event()
        protocol.heartbeat.side_effect = lambda *a: beat.set()

        def solve(problem):
            job_progress.stage(job_progress.BUILDING, "EP_DBLF")
            assert beat.wait(3)
            threading.Event().wait(1.2)   # qua ít nhất một nhịp ghi tiến độ hỏng
            return EngineOptimizationResponse(placements=[])

        self._run(self._runner(solve), protocol)
        protocol.finalize.assert_called_once()
        assert protocol.finalize.call_args.args[2] == "COMPLETED"
        assert protocol.write_progress.called

    def test_solver_that_reports_nothing_leaves_progress_empty(self):
        protocol = Mock(heartbeat_seconds=5)
        protocol.begin.return_value = True
        self._run(self._runner(lambda problem: EngineOptimizationResponse(placements=[])), protocol)
        protocol.write_progress.assert_not_called()
        protocol.finalize.assert_called_once()
