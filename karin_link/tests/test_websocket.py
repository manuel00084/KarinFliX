"""Tests for WebSocket server."""
import pytest
from unittest.mock import MagicMock, AsyncMock
from karin_link.websocket import WebSocketManager, KarinWebSocketServer
from karin_link.config import LinkConfig
from karin_link.security import SecurityManager
from karin_link.rooms import RoomManager


class TestWebSocketManager:
    """Test WebSocketManager."""

    def test_manager_init(self):
        """Should initialize with empty connections."""
        manager = WebSocketManager()
        assert len(manager.active_connections) == 0
        assert len(manager._device_map) == 0

    def test_connect_disconnect(self):
        """Should track connections."""
        manager = WebSocketManager()
        ws = MagicMock()
        ws.send_json = AsyncMock()
        manager.connect(ws, "device-uuid")
        assert len(manager.active_connections) == 1
        assert "device-uuid" in manager._device_map
        manager.disconnect(ws, "device-uuid")
        assert len(manager.active_connections) == 0
        assert "device-uuid" not in manager._device_map

    def test_send_to(self):
        """Should send message to specific device."""
        manager = WebSocketManager()
        ws = MagicMock()
        ws.send_json = AsyncMock()
        manager.connect(ws, "device-uuid")
        result = asyncio.run(manager.send_to("device-uuid", {"type": "test"}))
        assert result is True

    def test_send_to_missing(self):
        """Should return False for missing device."""
        manager = WebSocketManager()
        result = asyncio.run(manager.send_to("missing", {"type": "test"}))
        assert result is False


class TestKarinWebSocketServer:
    """Test KarinWebSocketServer."""

    def test_init(self):
        """Should initialize server."""
        config = LinkConfig()
        security = SecurityManager(config)
        rooms = RoomManager()
        server = KarinWebSocketServer(config, security, rooms)
        assert server.manager is not None
        assert server.config == config