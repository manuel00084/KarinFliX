"""Tests for device discovery."""
import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from karin_link.discovery import ZeroconfDiscovery
from karin_link.config import LinkConfig
from karin_link.models import DeviceInfo


class TestZeroconfDiscovery:
    """Test ZeroconfDiscovery."""

    def test_init(self):
        """Should initialize with config."""
        config = LinkConfig()
        discovery = ZeroconfDiscovery(config)
        assert discovery.config == config
        assert discovery.zeroconf is None
        assert discovery._on_found is None
        assert discovery._on_lost is None

    def test_callbacks(self):
        """Should set callbacks."""
        config = LinkConfig()
        discovery = ZeroconfDiscovery(config)
        callback_found = MagicMock()
        callback_lost = MagicMock()
        discovery.on_device_found(callback_found)
        discovery.on_device_lost(callback_lost)
        assert discovery._on_found is not None
        assert discovery._on_lost is not None

    def test_start_returns_bool(self):
        """Start should return bool."""
        config = LinkConfig()
        discovery = ZeroconfDiscovery(config)
        with patch('karin_link.discovery.Zeroconf') as mock_zc:
            mock_zc.return_value = MagicMock()
            result = discovery.start()
            # Result depends on mock, but it should be bool-like
            assert isinstance(result, bool)


class TestBroadcastDiscovery:
    """Test BroadcastDiscovery."""

    def test_init(self):
        """Should initialize with config."""
        config = LinkConfig()
        from karin_link.broadcast import BroadcastDiscovery
        discovery = BroadcastDiscovery(config)
        assert discovery.config == config
        assert discovery._sock is None
        assert discovery._on_found is None

    def test_callbacks(self):
        """Should set callbacks."""
        config = LinkConfig()
        from karin_link.broadcast import BroadcastDiscovery
        discovery = BroadcastDiscovery(config)
        callback_found = MagicMock()
        callback_lost = MagicMock()
        discovery.on_device_found(callback_found)
        discovery.on_device_lost(callback_lost)
        assert discovery._on_found is not None
        assert discovery._on_lost is not None