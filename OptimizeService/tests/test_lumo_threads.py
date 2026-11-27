"""LUMO_TORCH_THREADS: số luồng torch của tiến trình service khi nạp mô hình Lumo (chủ dự án đồng ý 27/11/2026)."""
import pytest

from app.config.settings import settings
from app.ml import lumo_models

torch = pytest.importorskip("torch")


@pytest.fixture
def keep_threads():
    old = torch.get_num_threads()
    yield
    torch.set_num_threads(old)


def test_default_is_one_thread():
    assert settings.LUMO_TORCH_THREADS == 1


@pytest.mark.parametrize("n", [1, 2])
def test_configure_sets_thread_count(monkeypatch, keep_threads, n):
    torch.set_num_threads(3)
    monkeypatch.setattr(settings, "LUMO_TORCH_THREADS", n)
    lumo_models.configure_torch_threads()
    assert torch.get_num_threads() == n


def test_zero_keeps_torch_default(monkeypatch, keep_threads):
    torch.set_num_threads(3)
    monkeypatch.setattr(settings, "LUMO_TORCH_THREADS", 0)
    lumo_models.configure_torch_threads()
    assert torch.get_num_threads() == 3


def test_loading_a_model_applies_setting(monkeypatch, keep_threads):
    if not lumo_models.model_path("LUMO_R1_1").is_file():
        pytest.skip("thiếu trọng số LUMO_R1_1")
    torch.set_num_threads(3)
    monkeypatch.setattr(settings, "LUMO_TORCH_THREADS", 1)
    monkeypatch.setattr(lumo_models, "_loaded", {})
    monkeypatch.setattr(lumo_models, "_failed", {})
    lumo_models._load("LUMO_R1_1")
    assert torch.get_num_threads() == 1
