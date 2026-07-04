#!/bin/bash
set -e
cd "$(dirname "$0")/.."

if [ ! -d ".venv" ]; then
    echo "Creating venv..."
    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt -r requirements-dev.txt
fi

source .venv/bin/activate

echo "Running Ruff check..."
ruff check .
echo "Running Ruff format..."
ruff format .
echo "Running Mypy..."
mypy .
echo "Running Pytest..."
pytest tests/
