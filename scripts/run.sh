#!/bin/bash
cd "$(dirname "$0")/.."

if [ ! -d ".venv" ]; then
    echo "Creating venv..."
    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt
fi

source .venv/bin/activate

# Optional: export REQUIRE_AUTH=true
# Optional: export PORT=8080

exec python main.py
