import os
import sys

import pytest

# the package lives in src/ (the phone runs pytest without PYTHONPATH)
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))


@pytest.fixture(autouse=True)
def clean_proxy_environ(monkeypatch):
    """Remove proxy related environment variables for every test."""
    for var in ("http_proxy", "https_proxy", "no_proxy", "ftp_proxy", "all_proxy"):
        monkeypatch.delenv(var, raising=False)
        monkeypatch.delenv(var.upper(), raising=False)
