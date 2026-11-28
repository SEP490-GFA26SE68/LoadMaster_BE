"""P21 / P13 (KE-HOACH-TOI-UU, M8a): một hạn chót cho cả job (cờ OPTIMIZATION_SHARED_DEADLINE_ENABLED, mặc định bật từ M8)."""
import time

import pytest

from app.config.settings import settings
from app.service.optimize.optimization_client import OptimizationClient
from tests.test_load_balance_search import package, problem


@pytest.fixture
def shared_deadline(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_SHARED_DEADLINE_ENABLED", True)



def test_shared_deadline_flag_is_on_by_default():
    from app.config.settings import Settings
    fresh = Settings(_env_file=None, KEYCLOAK_CLIENT_SECRET="x")
    assert fresh.OPTIMIZATION_SHARED_DEADLINE_ENABLED is True


def test_job_deadline_is_ignored_when_flag_off(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_SHARED_DEADLINE_ENABLED", False)
    request = problem([package("A"), package("B")], time_limit_sec=5)
    result = OptimizationClient().solve_in_process(request, job_deadline=time.monotonic() - 1)
    assert len(result.placements) == 2


def test_expired_job_deadline_stops_ep_pass_when_flag_on(shared_deadline):
    request = problem([package("A"), package("B")], time_limit_sec=5)
    result = OptimizationClient().solve_in_process(request, job_deadline=time.monotonic() - 1)
    assert result.placements == []
    assert all("OPTIMIZATION_TIME_LIMIT_REACHED" in u.violated_constraints for u in result.unplaced)


def test_solve_candidates_gives_every_ep_pass_the_same_job_deadline(monkeypatch):
    seen = []
    real = OptimizationClient.solve_in_process

    def spy(self, request, *args, job_deadline=None, **kwargs):
        seen.append(job_deadline)
        return real(self, request, *args, job_deadline=job_deadline, **kwargs)

    monkeypatch.setattr(OptimizationClient, "solve_in_process", spy)
    request = problem([package("A"), package("B")], time_limit_sec=7)
    before = time.monotonic()
    OptimizationClient().solve_candidates(request)
    assert seen and None not in seen and len(set(seen)) == 1
    assert before + 7 <= seen[0] <= time.monotonic() + 7


def test_ga_sa_decode_is_bounded_by_the_search_end(monkeypatch):
    from app.service.optimize.metaheuristic import _Search
    request = problem([package("A"), package("B", 2)], time_limit_sec=7)
    client = OptimizationClient()
    search = _Search(client, request, seed=1, decode_estimate=0.0)
    search.deadline = time.monotonic() + 1.5
    seen = []
    real = OptimizationClient.solve_in_process

    def spy(self, req, *args, job_deadline=None, **kwargs):
        seen.append(job_deadline)
        return real(self, req, *args, job_deadline=job_deadline, **kwargs)

    monkeypatch.setattr(OptimizationClient, "solve_in_process", spy)
    search.decode(search.seed_genome(search.strategies[0]))
    assert seen == [search.deadline]
