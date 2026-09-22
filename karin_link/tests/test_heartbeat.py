"""Tests for heartbeat manager."""
import pytest
import asyncio
from unittest.mock import MagicMock
from karin_link.heartbeat import HeartbeatManager
from karin_link.config import LinkConfig
from karin_link.database import Database


class TestHeartbeatManager:
    """Test the HeartbeatManager."""

    @pytest.fixture(autouse=True)
    def setup(self):
        """Set up HeartbeatManager."""
        self.config = LinkConfig()
        self.db = MagicMock(spec=Database)
        self.heartbeat = HeartbeatManager(self.config, self.db)

    def test_initial_state(self):
        """Should initialize with empty heartbeat data."""
        assert self.heartbeat._last_heartbeat == {}

    def test_record_heartbeat(self):
        """Should record heartbeat for a device."""
        asyncio.run(self.heartbeat.record_heartbeat("device-1"))
        assert "device-1" in self.heartbeat._last_heartbeat

    def test_device_status_online(self):
        """Device should be online after heartbeat."""
        self.heartbeat._last_heartbeat["device-1"] = asyncio.get_event_loop().time()
        assert self.heartbeat.get_device_status("device-1") == "online"

    def test_device_status_offline(self):
        """Device should be offline after timeout."""
        old_time = asyncio.get_event_loop().time() - 60
        self.heartbeat._last_heartbeat["device-1"] = old_time
        assert self.heartbeat.get_device_status("device-1") == "offline"

    def test_device_status_unknown(self):
        """Unknown device should be offline."""
        assert self.heartbeat.get_device_status("unknown-device") == "offline"