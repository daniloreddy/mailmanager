# MailManager

IMAP rule-based email sorter with SpamAssassin integration. Runs as a daemon: FastAPI backend + NiceGUI web UI + background scheduler.

## Features
- **Rule-based Sorting**: Move, copy, delete, flag, or forward emails based on FROM, SUBJECT, TO, CC, BCC, or MESSAGE content.
- **Spam Detection**: Integrated SpamAssassin support via SPAMC protocol.
- **State Tracking**: IMAP UID tracking to avoid redundant processing.
- **Web UI**: NiceGUI interface with Status, IMAP, Rules, and Settings tabs (auto-refresh interval configurable).
- **Auth**: Cookie-based session auth when `REQUIRE_AUTH` is set.
- **Docker Ready**: Single container, no external dependencies.

## Quick Start (Docker)

### 1. Create folders and compose file

```bash
mkdir mailmanager && cd mailmanager
mkdir data
```

Create `.env` (template: [`.env.example`](.env.example) in the repo):

```bash
REQUIRE_AUTH=true        # required for network access: enables auth, binds 0.0.0.0
# PORT=8080             # HTTP port (default 8080)
# AUTH_SECURE_COOKIE=1              # force Secure flag on session cookie
# TRUSTED_PROXIES=127.0.0.1         # IPs trusted for X-Forwarded-For
# TZ=Europe/Rome                    # timezone for UI timestamps (default UTC)
```

Create `docker-compose.yml`:

```yaml
services:
  mailmanager:
    image: ghcr.io/daniloreddy/mailmanager:latest
    container_name: mailmanager
    restart: unless-stopped
    ports:
      - "8080:8080"
    env_file:
      - .env
    environment:
      - PYTHONUNBUFFERED=1
      - TZ=${TZ:-UTC}
      - NICEGUI_STORAGE_PATH=/app/data/.nicegui
    volumes:
      - ./data:/app/data
```

### 2. Set the login password

**First time** (container not running):

```bash
docker run --rm -it \
  -v ./data:/app/data \
  ghcr.io/daniloreddy/mailmanager:latest \
  python scripts/set_password.py
```

**To change it** (container already running):

```bash
docker exec -it mailmanager python scripts/set_password.py
```

### 3. Start

```bash
docker compose up -d
```

Open `http://localhost:8080` — you'll be redirected to the login page; after logging in with the password you set, you land on the dashboard at `/ui`.

---

## Without auth (local use only)

If `REQUIRE_AUTH` is unset the server binds `127.0.0.1` only and requires no login. This works for manual (non-Docker) runs on your machine. **Not usable with Docker**: inside a container `127.0.0.1` is unreachable from the host, so the Docker deployment always requires `REQUIRE_AUTH=true`.

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `REQUIRE_AUTH` | _(unset)_ | If `true`: binds `0.0.0.0`, enables login auth |
| `PORT` | `8080` | HTTP port |
| `AUTH_SECURE_COOKIE` | _(unset)_ | Set to `1` to force `Secure` flag on the session cookie |
| `TRUSTED_PROXIES` | `127.0.0.1` | Comma-separated IPs trusted to forward `X-Forwarded-For` |
| `TZ` | `UTC` | IANA timezone (e.g. `Europe/Rome`) used to display timestamps in the UI |

---

## How it Works
1. **Fetch**: Connects to IMAP servers using UID state tracking.
2. **Scan**: (Optional) Checks messages against SpamAssassin.
3. **Evaluate**: Processes rules sequentially; first match wins.
4. **Action**: Executes defined actions (MOVE, COPY, DELETE, FORWARD, etc.) on matching emails.
5. **Persist**: Stores configuration and state in a local SQLite database.

---

## Manual Installation (dev)

```bash
# Windows
python -m venv venv
./venv/Scripts/pip install -r requirements.txt -r requirements.dev.txt
./venv/Scripts/python main.py

# Linux/Mac
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt -r requirements.dev.txt
./.venv/bin/python main.py
```

---

## Development

- **Lint/Type Check + Tests**: `scripts/checks.bat` (Windows) / `scripts/checks.sh` (Unix) — ruff, mypy, pytest
- **Tests only**: `./venv/Scripts/pytest tests/`
- **Run local**: `scripts/run.bat` (Windows) / `scripts/run.sh` (Unix) — auto-create venv, start server
- **Dev Docker**: `docker compose -f docker-compose-dev.yml up --build` (builds locally)

---

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + auth config)
- Logs: stdout → `docker compose logs -f mailmanager`
