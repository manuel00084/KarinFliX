"""Tests for configuration management."""
import pytest
import uuid
import os
from unittest.mock import patch, MagicMock
from karin_link.config import LinkConfig


class TestLinkConfig:
    """Test the LinkConfig dataclass."""

    def test_default_values(self):
        """Config should have correct default values."""
        config = LinkConfig()
        assert config.api_host == "0.0.0.0"
        assert config.api_port == 7800
        assert config.broadcast_port == 7801
        assert config.heartbeat_interval == 10.0
        assert config.heartbeat_timeout == 30.0
        assert config.token_expiry == 3600
        assert config.db_path == "karin_link.db"
        assert config.remote_enabled is False

    def test_device_uuid_generated(self):
        """UUID should be generated if not provided."""
        config = LinkConfig()
        assert config.device_uuid != ""
        assert len(config.device_uuid) == 36  # UUID format

    def test_env_override(self, monkeypatch):
        """Environment variables should override defaults."""
        monkeypatch.setenv("KARIN_API_PORT", "9999")
        monkeypatch.setenv("KARIN_DEVICE_NAME", "MyDevice")
        config = LinkConfig()
        assert config.api_port == 9999
        assert config.device_name == "MyDevice"

    def test_config_from_dict(self):
        """Config should be creatable from a dictionary."""
        data = {
            "api_port": 8888,
            "device_name": "CustomDevice",
            "user_name": "CustomUser",
        }
        config = LinkConfig(**{k: v for k, v in data.items() if k in LinkConfig.__dataclass_fields__})
        assert config.api_port == 8888
        assert config.device_name == "CustomDevice"

    def test_to_dict(self):
        """to_dict should return all config fields."""
        config = LinkConfig()
        d = config.to_dict()
        assert "api_port" in d
        assert "device_name" in d
        assert "device_uuid" in d

    def test_token_expiry_custom(self):
        """Token expiry should be configurable."""
        config = LinkConfig()
        config.token_expiry = 7200
        assert config.token_expiry == 7200

    def test_broadcast_port_configurable(self, monkeypatch):
        """Broadcast port should be configurable via env."""
        monkeypatch.setenv("KARIN_BROADCAST_PORT", "9999")
        config = LinkConfig()
        assert config.broadcast_port == 9999
