import os
from pathlib import Path

from redberry_webkit.metrics import MetricsRecord, MetricsStore

# Overridable so tests can point at a temp dir instead of the real data/ directory
# (uvicorn.md §6: never point a test at the project's real data/ directory).
DATA_DIR = Path(os.environ.get("MAILMANAGER_DATA_DIR", "data"))

metrics = MetricsStore(db_path=DATA_DIR / "metrics.db")

__all__ = ["MetricsRecord", "metrics"]
