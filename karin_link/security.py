"""
KARIN Link security module.

Handles UUID generation, token creation, validation, and
authentication for all KARIN Link sessions.
"""

import hashlib
import hmac
import secrets
import time
from typing import Optional

from .models import SessionToken, AuthRequest, AuthResponse
from .config import LinkConfig


class SecurityManager:
    """Manages authentication tokens and session security."""

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self._active_tokens: dict[str, SessionToken] = {}

    def create_token(self, device_uuid: str) -> SessionToken:
        """Create a new session token for a device."""
        token_str = secrets.token_urlsafe(32)
        now = time.time()
        session = SessionToken(
            token=token_str,
            device_uuid=device_uuid,
            created_at=now,
            expires_at=now + self.config.token_expiry,
        )
        self._active_tokens[token_str] = session
        return session

    def validate_token(self, token: str) -> Optional[str]:
        """Validate a token. Returns device_uuid if valid, None otherwise."""
        session = self._active_tokens.get(token)
        if session is None:
            return None
        if not session.is_valid():
            del self._active_tokens[token]
            return None
        return session.device_uuid

    def revoke_token(self, token: str) -> bool:
        """Revoke a token. Returns True if it existed."""
        if token in self._active_tokens:
            del self._active_tokens[token]
            return True
        return False

    def revoke_all_tokens(self, device_uuid: Optional[str] = None) -> int:
        """Revoke tokens. If device_uuid given, only revoke that device's tokens."""
        to_remove = []
        for token_str, session in self._active_tokens.items():
            if device_uuid is None or session.device_uuid == device_uuid:
                to_remove.append(token_str)
        for t in to_remove:
            del self._active_tokens[t]
        return len(to_remove)

    def authenticate(self, request: AuthRequest) -> AuthResponse:
        """Authenticate a device and return a token."""
        if not request.device_uuid:
            return AuthResponse(
                success=False,
                message="Invalid device UUID"
            )

        session = self.create_token(request.device_uuid)
        return AuthResponse(
            success=True,
            token=session.token,
            expires_at=session.expires_at,
            message="Authenticated"
        )

    def sign_data(self, data: str) -> str:
        """Create HMAC signature for data integrity."""
        return hmac.new(
            self.config.token_secret.encode(),
            data.encode(),
            hashlib.sha256
        ).hexdigest()

    def verify_signature(self, data: str, signature: str) -> bool:
        """Verify HMAC signature."""
        expected = self.sign_data(data)
        return hmac.compare_digest(expected, signature)

    def cleanup_expired(self) -> int:
        """Remove expired tokens. Returns count removed."""
        now = time.time()
        expired = [
            t for t, s in self._active_tokens.items()
            if s.expires_at <= now
        ]
        for t in expired:
            del self._active_tokens[t]
        return len(expired)
