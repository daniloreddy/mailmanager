import asyncio
import hashlib
import json
import logging
import os
import secrets
import time
from pathlib import Path
from typing import Optional

import jwt
from fastapi import Response

logger = logging.getLogger(__name__)

_TRUSTED_PROXIES: frozenset[str] = frozenset(
    s.strip()
    for s in os.environ.get("TRUSTED_PROXIES", "127.0.0.1").split(",")
    if s.strip()
)


class AuthManager:
    def __init__(
        self,
        auth_file: Path,
        cookie_name: str = "app_session",
        token_ttl: int = 7 * 24 * 3600,
    ) -> None:
        self.auth_file = auth_file
        self.cookie_name = cookie_name
        self.token_ttl = token_ttl
        self._data: dict = self._load()
        self._failed: dict[str, list[float]] = {}
        self._blocked: dict[str, float] = {}
        self._global_times: list[float] = []

    def _load(self) -> dict:
        if self.auth_file.exists():
            try:
                return json.loads(self.auth_file.read_text())
            except Exception:
                pass
        data: dict = {"secret": secrets.token_hex(32)}
        self.auth_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.auth_file.write_text(json.dumps(data))
        logger.warning("Created new auth.json — run: python scripts/set_password.py")
        return data

    @property
    def _secret(self) -> str:
        return str(self._data["secret"])

    def has_password(self) -> bool:
        return "password_hash" in self._data

    def set_password(self, password: str) -> None:
        salt = secrets.token_bytes(16)
        h = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1, dklen=32)
        self._data["password_hash"] = salt.hex() + ":" + h.hex()
        self.auth_file.write_text(json.dumps(self._data))

    def _verify_password(self, password: str) -> bool:
        ph = self._data.get("password_hash", "")
        if not ph:
            return False
        try:
            salt_hex, h_hex = ph.split(":", 1)
            salt = bytes.fromhex(salt_hex)
            expected = bytes.fromhex(h_hex)
        except ValueError:
            return False
        h = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1, dklen=32)
        return secrets.compare_digest(h, expected)

    def create_token(self) -> str:
        payload = {"exp": int(time.time()) + self.token_ttl}
        return jwt.encode(payload, self._secret, algorithm="HS256")

    def verify_token(self, token: str) -> bool:
        if not token:
            return False
        try:
            jwt.decode(token, self._secret, algorithms=["HS256"])
            return True
        except Exception:
            return False

    def set_cookie(self, response: Response, token: str, secure: bool) -> None:
        response.set_cookie(
            self.cookie_name,
            token,
            httponly=True,
            samesite="strict",
            secure=secure,
            max_age=self.token_ttl,
        )

    def clear_cookie(self, response: Response) -> None:
        response.delete_cookie(self.cookie_name)

    def client_ip(self, headers: dict, client_host: Optional[str]) -> str:
        if client_host in _TRUSTED_PROXIES:
            cf = headers.get("cf-connecting-ip", "")
            if cf:
                return cf
            fwd = headers.get("x-forwarded-for", "")
            if fwd:
                return fwd.split(",")[0].strip()
        return client_host or "unknown"

    def _check_rate_limit(self, ip: str) -> Optional[str]:
        now = time.time()
        self._global_times = [t for t in self._global_times if now - t < 60]
        self._global_times.append(now)
        if len(self._global_times) > 20:
            return "Too many login attempts globally. Try again later."
        if ip in self._blocked:
            if now < self._blocked[ip]:
                remaining = int(self._blocked[ip] - now)
                return f"Too many failed attempts. Blocked for {remaining}s."
            del self._blocked[ip]
        return None

    def _record_failure(self, ip: str) -> None:
        now = time.time()
        self._failed.setdefault(ip, [])
        self._failed[ip] = [t for t in self._failed[ip] if now - t < 300]
        self._failed[ip].append(now)
        if len(self._failed[ip]) >= 5:
            self._blocked[ip] = now + 300
            logger.warning("Blocked IP %s for excessive login failures", ip)

    def _record_success(self, ip: str) -> None:
        self._failed.pop(ip, None)
        self._blocked.pop(ip, None)

    def attempt_login(self, password: str, ip: str) -> tuple[bool, Optional[str]]:
        err = self._check_rate_limit(ip)
        if err:
            return False, err
        if not self._verify_password(password):
            self._record_failure(ip)
            return False, "Invalid password"
        self._record_success(ip)
        return True, None

    async def purge_loop(self) -> None:
        while True:
            await asyncio.sleep(600)
            now = time.time()
            self._blocked = {k: v for k, v in self._blocked.items() if v > now}
            self._failed = {
                k: [t for t in v if now - t < 300] for k, v in self._failed.items() if v
            }
