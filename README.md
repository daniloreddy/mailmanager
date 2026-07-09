# MailManager

IMAP rule-based email sorter with SpamAssassin integration. Runs as a daemon: FastAPI backend + NiceGUI web UI + background scheduler.

## Features
- **Rule-based Sorting**: Move, copy, delete, flag, or forward emails based on FROM, SUBJECT, TO, CC, BCC, or MESSAGE content.
- **Spam Detection**: Integrated SpamAssassin support via SPAMC protocol.
- **State Tracking**: IMAP UID tracking to avoid redundant processing.
- **Web UI**: NiceGUI interface with Status, IMAP, Rules, and Settings tabs (auto-refresh interval configurable).
- **Auth**: Cookie-based session auth always required on `/ui`, independent of network binding (`HOST`/`BIND_HOST`).
- **Docker Ready**: Single container, no external dependencies.

## Quick Start (Docker)

### 1. Create folders and compose file

```bash
mkdir mailmanager && cd mailmanager
mkdir data
```

Create `.env` (template: [`.env.example`](.env.example) in the repo):

```bash
# PORT=8080             # HTTP port (default 8080)
# BIND_HOST=127.0.0.1   # host-side publish address — 0.0.0.0 for LAN/reverse-proxy/direct exposure
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
      - "${BIND_HOST:-127.0.0.1}:${PORT:-8080}:${PORT:-8080}"
    environment:
      - PYTHONUNBUFFERED=1
      - TZ=${TZ:-UTC}
      - PORT=${PORT:-8080}
      - HOST=0.0.0.0   # container-internal bind, always 0.0.0.0 — see BIND_HOST for external exposure
      - NICEGUI_STORAGE_PATH=/app/data/.nicegui
      - ENV_FILE=/app/hostcfg/.env
    volumes:
      - .:/app/hostcfg
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

## Auth is always on; network binding is a separate, independent choice

Login (cookie session) is always required to reach `/ui` — there's no way to disable it. `HOST`/`BIND_HOST` control something else entirely: which interface the server accepts connections on. Default `127.0.0.1` (localhost only); set to `0.0.0.0` to accept connections from other machines.

Examples:
- Trusted LAN, `HOST=0.0.0.0` — still logs in with a password, just reachable from other machines on the LAN.
- `HOST=127.0.0.1` (default) behind a Cloudflare Tunnel or SSH reverse tunnel exposed to the internet — bind stays local, auth protects the internet-reachable tunnel endpoint.

For manual (non-Docker) runs, `HOST` is read directly from `.env`/the shell environment (default `127.0.0.1`). In Docker, the container's own `127.0.0.1` is unreachable from the host, so `HOST=0.0.0.0` is fixed inside the container (see `docker-compose.yml`) — use `BIND_HOST` to control what's actually reachable from outside the container.

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `HOST` | `127.0.0.1` | Interface to bind (manual/non-Docker runs only; fixed to `0.0.0.0` inside Docker) |
| `BIND_HOST` | `127.0.0.1` | Docker only — host-side publish address for the `ports:` mapping |
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
./venv/Scripts/python -m app.main

# Linux/Mac
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt -r requirements.dev.txt
./.venv/bin/python -m app.main
```

CLI flags (override the env vars above for a single run): `--host`, `--port`, `--dev` (uvicorn reload), `--env-file`.

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
