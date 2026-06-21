# MailManager

IMAP rule-based email sorter with SpamAssassin integration. Runs as a daemon: FastAPI backend + NiceGUI web UI + background scheduler.

## Features
- **Rule-based Sorting**: Move, copy, delete, flag, or forward emails based on FROM, SUBJECT, TO, CC, BCC, or MESSAGE content.
- **Spam Detection**: Integrated SpamAssassin support via SPAMC protocol.
- **State Tracking**: IMAP UID tracking to avoid redundant processing.
- **Web UI**: NiceGUI interface with Status, IMAP, Rules, and Settings tabs.
- **Auth**: Cookie-based session auth when `MAILMANAGER_API_KEY` is set.
- **Docker Ready**: Easy deployment with isolated environments.

## How it Works
1. **Fetch**: Connects to IMAP servers using UID state tracking.
2. **Scan**: (Optional) Checks messages against SpamAssassin.
3. **Evaluate**: Processes rules sequentially; first match wins.
4. **Action**: Executes defined actions (MOVE, COPY, DELETE, FORWARD, etc.) on matching emails.
5. **Persist**: Stores configuration and state in a local SQLite database.

## Quick Start

### Docker (Recommended)
1. Download `docker-compose.yml`.
2. Start the services:
   ```bash
   docker-compose up -d
   ```
3. Open `http://localhost:8080` in your browser.

### Manual Installation
1. Create venv and install dependencies:
   ```bash
   python -m venv venv
   ./venv/Scripts/pip install -r requirements.txt
   ```
2. Run:
   ```bash
   ./venv/Scripts/python main.py
   ```
3. Open `http://127.0.0.1:8080` in your browser.

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `MAILMANAGER_API_KEY` | _(unset)_ | If set: binds `0.0.0.0`, enables cookie auth (set password via `scripts/set_password.py`) |
| `MAILMANAGER_PORT` | `8080` | HTTP port |
| `SPAMASSASSIN_HOST` | `127.0.0.1` | SpamAssassin hostname |
| `AUTH_SECURE_COOKIE` | _(unset)_ | Set to `1` to force Secure cookie flag |

When `MAILMANAGER_API_KEY` is **not** set, the server binds `127.0.0.1` only and no authentication is required.

## First-run with Auth

```bash
export MAILMANAGER_API_KEY=your-secret-key
python scripts/set_password.py   # set login password
python main.py
```

## Development

- **Lint/Type Check**: `scripts/analyze.cmd` (Windows) / `scripts/analyze.sh` (Unix)
- **Tests**: `./venv/Scripts/pytest tests/`

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + lock), `/app/logs` (log files)
