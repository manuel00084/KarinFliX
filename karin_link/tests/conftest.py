"""Shared fixtures and configuration for KARIN Link tests."""
import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock, patch
from karin_link.config import LinkConfig
from karin_link.models import DeviceInfo, EpisodeInfo, AuthRequest, SessionToken
import uuid
import time


@pytest.fixture
def config():
    """Create a test configuration."""
    cfg = LinkConfig()
    cfg.device_uuid = str(uuid.uuid4())
    cfg.device_name = "TestDevice"
    cfg.user_name = "TestUser"
    cfg.api_port = 7800
    cfg.db_path = ":memory:"
    cfg.token_secret = "test-secret-key-for-testing-only"
    cfg.token_expiry = 3600
    return cfg


@pytest.fixture
def device_info(config):
    """Create a test device info."""
    return DeviceInfo(
        uuid=config.device_uuid,
        name="TestDevice",
        user_name="TestUser",
        device_type="PC",
        os_info="Linux 5.15",
        api_port=7800,
        ip_address="127.0.0.1",
    )


@pytest.fixture
def auth_request(config):
    """Create a test auth request."""
    return AuthRequest(
        device_uuid=config.device_uuid,
        device_name="TestDevice",
        user_name="TestUser",
    )


@pytest.fixture
def episode_info():
    """Create a test episode info."""
    return EpisodeInfo(
        anime_title="Test Anime",
        season=1,
        episode=5,
        server="Karin",
        episode_title="Test Episode",
        episode_url="https://example.com/video.mp4",
    )


@pytest.fixture
def mock_database():
    """Create a mock database."""
    db = MagicMock()
    db.init = AsyncMock()
    db.close = AsyncMock()
    db.upsert_device = AsyncMock()
    db.get_device = AsyncMock()
    db.get_online_devices = AsyncMock(return_value=[])
    db.add_history = AsyncMock()
    db.mark_offline = AsyncMock()
    return db


@pytest.fixture
def sample_tokens():
    """Sample token data for testing."""
    return {
        "valid_token": str(uuid.uuid4()),
        "expired_token": str(uuid.uuid4()),
    }


@pytest.fixture(autouse=True)
def event_loop():
    """Create an event loop for async tests."""
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()
