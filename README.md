# MailManager

IMAP rule-based email sorter with SpamAssassin integration. Headless processing or TUI management.

## Features
- **Rule-based Sorting**: Move, copy, delete, or forward emails based on FROM, SUBJECT, or MESSAGE content.
- **Spam Detection**: Integrated SpamAssassin support via SPAMC protocol.
- **State Tracking**: IMAP UID tracking to avoid redundant processing.
- **Dual Mode**: Interactive Textual UI (TUI) for configuration and headless mode for automation.
- **Docker Ready**: Easy deployment with isolated environments per account group.

## How it Works
1. **Fetch**: Connects to IMAP servers using UID state tracking.
2. **Scan**: (Optional) Checks messages against SpamAssassin.
3. **Evaluate**: Processes rules sequentially.
4. **Action**: Executes defined actions (MOVE, FORWARD, etc.) on matching emails.
5. **Persist**: Stores configuration and state in a local SQLite database.

## Quick Start

### Docker (Recommended)
1. Clone the repository.
2. Create a `data` directory: `mkdir data`.
3. Start the services:
   ```bash
   docker-compose up -d
   ```

### Manual Installation
1. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
2. Run the TUI to configure your first account:
   ```bash
   python main.py -tui
   ```
3. Run headless for continuous processing:
   ```bash
   python main.py
   ```

## Configuration

### Environment Variables
- `MAILMANAGER_MODE`: `headless` (default) or `tui`.
- `SPAMASSASSIN_HOST`: Hostname of the SpamAssassin server (default: `spamassassin` in Docker, `127.0.0.1` local).

### Volumes
In Docker, mount your local data folder to `/app/data` to persist settings and email state:
```yaml
volumes:
  - ./my_config:/app/data
```

## Development
- **Lint/Type Check**: Run `analyze.sh` (or `analyze.cmd` on Windows).
- **Tests**: `pytest tests`.
