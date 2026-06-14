#!/bin/bash
cd "$(dirname "$0")/.."

if [ -d "venv" ]; then
    source venv/bin/activate
elif [ -d ".venv" ]; then
    source .venv/bin/activate
fi

# Optional: export MAILMANAGER_API_KEY=your_token_here
# Optional: export MAILMANAGER_PORT=8080
# Optional: export SPAMASSASSIN_HOST=spamassassin

exec python main.py
