import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import pytest


@pytest.fixture(autouse=True)
def reset_color_envvars(monkeypatch):
    """Remove color-related envvars to fix test output"""
    monkeypatch.delenv("FORCE_COLOR", raising=False)
    monkeypatch.delenv("NO_COLOR", raising=False)
