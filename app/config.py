from redberry_webkit.config import ConfigManager

_DEFAULTS: dict[str, str] = {
    "REFRESH_ENABLED": "true",  # ex UiConfig.auto_refresh_enabled
    "REFRESH_INTERVAL": "30",  # ex UiConfig.auto_refresh_seconds
    "SCHEDULER_ENABLED": "true",  # ex SchedulerConfig.enabled
    "SCHEDULER_INTERVAL_SECONDS": "300",  # ex SchedulerConfig.interval_seconds
    "LOG_LEVEL": "INFO",  # ex LoggingConfig.level
    "SPAM_ENABLED": "false",  # ex SpamAssassinConfig.enabled
    "SPAM_HOST": "127.0.0.1",
    "SPAM_PORT": "783",
    "SPAM_USER": "",
    "SPAM_CONNECT_TIMEOUT_MS": "3000",
    "SPAM_READ_TIMEOUT_MS": "5000",
}
_SECRET_KEYS: set[str] = {"SPAM_USER"}

config = ConfigManager(defaults=_DEFAULTS, secret_keys=_SECRET_KEYS)
