"""Tests for FastAPI endpoints."""
import time
import pytest
from httpx import AsyncClient
from unittest.mock import AsyncMock, MagicMock
from karin_link.api import create_app
from karin_link.config import LinkConfig
import uuid


@pytest.fixture
def app(config):
    """Create test FastAPI app."""
    mock_db = MagicMock()
    mock_db.init = AsyncMock()
    mock_db.close = AsyncMock()
    mock_db.upsert_device = AsyncMock()
    mock_db.get_device = AsyncMock()
    mock_db.get_online_devices = AsyncMock(return_value=[])
    mock_db.add_history = AsyncMock()
    mock_db.mark_offline = AsyncMock()

    mock_security = MagicMock()
    mock_security.validate_token = MagicMock(return_value=config.device_uuid)
    mock_security.authenticate = MagicMock(return_value=MagicMock(
        success=True, token="test-token", expires_at=time.time() + 3600, message="Authenticated"
    ))
    mock_security.revoke_all_tokens = MagicMock(return_value=1)

    mock_room_manager = MagicMock()
    mock_room_manager.create_room = MagicMock(return_value=MagicMock(
        model_dump=MagicMock(return_value={"id": "room1", "name": "Test Room"})
    ))
    mock_room_manager.get_active_rooms = MagicMock(return_value=[])
    mock_room_manager.get_room = MagicMock(return_value=None)
    mock_room_manager.join_room = MagicMock(return_value=True)
    mock_room_manager.leave_room = MagicMock(return_value=True)
    mock_room_manager.update_playback = MagicMock()

    mock_heartbeat = MagicMock()
    mock_heartbeat.record_heartbeat = AsyncMock()

    mock_ws = MagicMock()
    mock_ws.manager.active_connections = 0
    mock_ws.manager.send_to = AsyncMock(return_value=True)
    mock_ws.manager.broadcast_to_room = AsyncMock(return_value=True)

    return create_app(
        config=config,
        database=mock_db,
        security=mock_security,
        room_manager=mock_room_manager,
        heartbeat=mock_heartbeat,
        zeroconf=None,
        broadcast=None,
        ws_server=mock_ws,
    )


@pytest.fixture
def async_client(app):
    """Create async test client."""
    return AsyncClient(app=app, base_url="http://test")


# We need time for the fixture above
import time


class TestAuthEndpoints:
    """Test authentication endpoints."""

    @pytest.mark.asyncio
    async def test_authenticate(self, async_client):
        """Test device authentication."""
        response = await async_client.post("/auth", json={
            "device_uuid": str(uuid.uuid4()),
            "device_name": "TestDevice",
            "user_name": "TestUser"
        })
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "token" in data

    @pytest.mark.asyncio
    async def test_revoke_auth(self, async_client):
        """Test token revocation."""
        response = await async_client.post(
            "/auth/revoke",
            params={"device_uuid": str(uuid.uuid4())},
            headers={"Authorization": "Bearer test-token"}
        )
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_auth_missing_token(self, async_client):
        """Request without auth token should fail."""
        response = await async_client.get("/devices")
        assert response.status_code == 401


class TestDeviceEndpoints:
    """Test device management endpoints."""

    @pytest.mark.asyncio
    async def test_list_devices(self, async_client):
        """Test listing discovered devices."""
        response = await async_client.get(
            "/devices",
            headers={"Authorization": "Bearer test-token"}
        )
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_heartbeat(self, async_client):
        """Test heartbeat endpoint."""
        response = await async_client.post(
            "/devices/heartbeat",
            params={"device_uuid": str(uuid.uuid4())},
            headers={"Authorization": "Bearer test-token"}
        )
        assert response.status_code == 200


class TestShareEndpoints:
    """Test sharing endpoints."""

    @pytest.mark.asyncio
    async def test_share_episode(self, async_client):
        """Test sharing an episode."""
        episode = EpisodeInfo(
            anime_title="Test Anime",
            season=1,
            episode=5,
            server="Karin",
            episode_url="https://example.com/video.mp4"
        )
        response = await async_client.post(
            "/share",
            json={
                "target_device_uuid": str(uuid.uuid4()),
                "episode": episode.model_dump(),
                "message": "Check this out!"
            },
            headers={"Authorization": "Bearer test-token"}
        )
        assert response.status_code == 200


class TestRoomEndpoints:
    """Test room management endpoints."""

    @pytest.mark.asyncio
    async def test_create_room(self, async_client):
        """Test creating a room."""
        response = await async_client.post(
            "/rooms",
            params={"name": "Test Room"},
            headers={"Authorization": "Bearer test-token"}
        )
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_list_rooms(self, async_client):
        """Test listing rooms."""
        response = await async_client.get(
            "/rooms",
            headers={"Authorization": "Bearer test-token"}
        )
        assert response.status_code == 200


class TestSystemEndpoints:
    """Test system/health endpoints."""

    @pytest.mark.asyncio
    async def test_health(self, async_client):
        """Test health endpoint."""
        response = await async_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"

    @pytest.mark.asyncio
    async def test_root(self, async_client):
        """Test root endpoint."""
        response = await async_client.get("/")
        assert response.status_code == 200
        data = response.json()
        assert "name" in data
        assert data["name"] == "KARIN Link"
