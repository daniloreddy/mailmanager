# MailManager

IMAP rule-based email sorter with SpamAssassin integration. Runs as a daemon: FastAPI backend + NiceGUI web UI + background scheduler.

## Features
- **Rule-based Sorting**: Move, copy, delete, flag, or forward emails based on FROM, SUBJECT, TO, CC, BCC, or MESSAGE content.
- **Spam Detection**: Integrated SpamAssassin support via SPAMC protocol.
- **State Tracking**: IMAP UID tracking to avoid redundant processing.
- **Web UI**: NiceGUI interface with Status, IMAP, Rules, and Settings tabs.
- **Auth**: Cookie-based session auth when `MAILMANAGER_API_KEY` is set.
- **Docker Ready**: Single container, no external dependencies.

## Quick Start (Docker)

### 1. Create folders and compose file

```bash
mkdir mailmanager && cd mailmanager
mkdir data logs
```

Copy `.env.example` to `.env` and fill in values:

```bash
cp .env.example .env   # then edit .env
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
    volumes:
      - ./data:/app/data
      - ./logs:/app/logs
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

Open `http://localhost:8080` — log in with the password you set.

---

## Without auth (local use only)

Remove `MAILMANAGER_API_KEY` from the compose file. The server binds `127.0.0.1` only and requires no login.

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `MAILMANAGER_API_KEY` | _(unset)_ | If set: binds `0.0.0.0`, enables login auth |
| `MAILMANAGER_PORT` | `8080` | HTTP port |
| `AUTH_SECURE_COOKIE` | _(unset)_ | Set to `1` to force `Secure` flag on the session cookie |
| `TRUSTED_PROXIES` | `127.0.0.1` | Comma-separated IPs trusted to forward `X-Forwarded-For` |

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
python -m venv venv
./venv/Scripts/pip install -r requirements.txt   # Windows
# ./venv/bin/pip install -r requirements.txt     # Linux/Mac
./venv/Scripts/python main.py
```

---

## Development

- **Lint/Type Check**: `scripts/analyze.cmd` (Windows) / `scripts/analyze.sh` (Unix)
- **Tests**: `./venv/Scripts/pytest tests/`
- **Dev Docker**: `docker compose -f docker-compose-dev.yml up --build` (builds locally)

---

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + auth config)
- Logs: stdout → `docker compose logs -f mailmanager`
