import socket
from typing import Tuple
from pydantic import BaseModel
from .models import SpamAssassinConfig


class CheckResult(BaseModel):
    ok: bool
    isSpam: bool
    score: float
    threshold: float
    rawResponse: str


class SpamAssassinClient:
    def __init__(self, config: SpamAssassinConfig):
        self.config = config

    def check(self, message_bytes: bytes) -> CheckResult:
        try:
            resp = self._send_with_body("CHECK", message_bytes)
        except Exception as e:
            # Fallback if SpamAssassin is down
            return CheckResult(
                ok=False, isSpam=False, score=0.0, threshold=0.0, rawResponse=str(e)
            )

        headers = self._parse_headers(resp)
        spam_header = headers.get("spam", "")

        if not spam_header:
            return CheckResult(
                ok=False,
                isSpam=False,
                score=0.0,
                threshold=0.0,
                rawResponse="Missing 'Spam' header",
            )

        try:
            is_spam, score, threshold = self._parse_spam_header(spam_header)
            return CheckResult(
                ok=True,
                isSpam=is_spam,
                score=score,
                threshold=threshold,
                rawResponse=resp,
            )
        except Exception as e:
            return CheckResult(
                ok=False,
                isSpam=False,
                score=0.0,
                threshold=0.0,
                rawResponse=f"Parse error: {e}",
            )

    def _send_with_body(self, cmd: str, body: bytes) -> str:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(self.config.connectTimeoutMillis / 1000.0)
            s.connect((self.config.host, self.config.port))
            s.settimeout(self.config.readTimeoutMillis / 1000.0)

            req = f"{cmd} SPAMC/1.5\r\n"
            req += f"Content-length: {len(body)}\r\n"
            if self.config.user:
                req += f"User: {self.config.user}\r\n"
            req += "\r\n"

            s.sendall(req.encode("ascii") + body)

            response_bytes = b""
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                response_bytes += chunk

            return response_bytes.decode("utf-8", errors="replace")

    def _parse_headers(self, response: str) -> dict:
        headers = {}
        lines = response.split("\r\n")
        for line in lines[1:]:
            if not line.strip():
                break
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        return headers

    def _parse_spam_header(self, header_value: str) -> Tuple[bool, float, float]:
        v = header_value.strip()
        is_spam = False
        if v.lower().startswith("true"):
            is_spam = True
            v = v[4:].strip()
        elif v.lower().startswith("false"):
            is_spam = False
            v = v[5:].strip()

        if v.startswith(";"):
            v = v[1:].strip()

        parts = v.split("/")
        if len(parts) != 2:
            raise ValueError(
                f"Cannot parse score/threshold in 'Spam' header: {header_value}"
            )

        left = parts[0].replace("score=", "").strip()
        right = parts[1].replace("required=", "").strip()

        return is_spam, float(left), float(right)
