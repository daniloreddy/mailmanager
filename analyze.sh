#!/bin/bash
if [ -d "venv" ]; then
    source venv/bin/activate
elif [ -d ".venv" ]; then
    source .venv/bin/activate
fi

echo "Running Ruff check..."
ruff check .
echo "Running Ruff format..."
ruff format .
echo "Running Mypy..."
mypy .
