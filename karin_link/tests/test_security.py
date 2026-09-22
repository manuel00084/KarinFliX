"""Tests for security module."""
import pytest
import time
import uuid
from unittest.mock import MagicMock
from karin_link.security import SecurityManager
from karin_link.config import LinkConfig
from karin_link.models import AuthRequest, AuthResponse


class TestSecurityManager:
    """Test the SecurityManager."""

    @pytest.fixture(autouse=True)
    def setup(self, config):
        """Set up SecurityManager for tests."""
        self.config = config
        self.security = SecurityManager(config)

    def test_create_token(self):
        """Should create a token with correct properties."""
        token = self.security.create_token("device-uuid-123")
        assert token.token != ""
        assert token.device_uuid == "device-uuid-123"
        assert token.expires_at > time.time()
        assert token.token in self.security._active_tokens

    def test_validate_token(self):
        """Should validate a valid token."""
        session = self.security.create_token("device-uuid-123")
        result = self.security.validate_token(session.token)
        assert result == "device-uuid-123"

    def test_validate_expired_token(self):
        """Should reject expired tokens."""
        config = LinkConfig()
        config.token_expiry = -1  # Already expired
        security = SecurityManager(config)
        session = security.create_token("device-uuid-123")
        time.sleep(0.01)
        result = security.validate_token(session.token)
        assert result is None

    def test_validate_invalid_token(self):
        """Should reject invalid tokens."""
        result = self.security.validate_token("nonexistent-token")
        assert result is None

    def test_revoke_token(self):
        """Should revoke a token."""
        session = self.security.create_token("device-uuid-123")
        result = self.security.revoke_token(session.token)
        assert result is True
        assert self.security.validate_token(session.token) is None

    def test_revoke_all_tokens(self):
        """Should revoke all tokens for a device."""
        self.security.create_token("device-a")
        self.security.create_token("device-b")
        self.security.create_token("device-a")
        count = self.security.revoke_all_tokens("device-a")
        assert count == 2

    def test_authenticate(self, config):
        """Should authenticate and return token."""
        security = SecurityManager(config)
        request = AuthRequest(
            device_uuid="device-uuid-123",
            device_name="TestDevice",
            user_name="TestUser"
        )
        response: AuthResponse = security.authenticate(request)
        assert response.success is True
        assert response.token != ""
        assert response.expires_at > time.time()
        assert response.message == "Authenticated"

    def test_authenticate_empty_uuid(self):
        """Should fail authentication with empty UUID."""
        security = SecurityManager(config)
        request = AuthRequest(device_uuid="")
        response: AuthResponse = security.authenticate(request)
        assert response.success is False
        assert response.message == "Invalid device UUID"

    def test_sign_and_verify_data(self):
        """HMAC signature should be verifiable."""
        signature = self.security.sign_data("test-data")
        assert signature != ""
        assert self.security.verify_signature("test-data", signature) is True

    def test_verify_wrong_signature(self):
        """Wrong signature should fail."""
        signature = self.security.sign_data("test-data")
        assert self.security.verify_signature("test-data", "wrong-signature") is False

    def test_cleanup_expired(self):
        """Should remove expired tokens."""
        config = LinkConfig()
        config.token_expiry = -1
        security = SecurityManager(config)
        security.create_token("device-a")
        security.create_token("device-b")
        time.sleep(0.01)
        count = security.cleanup_expired()
        assert count == 2
        assert len(security._active_tokens) == 0
