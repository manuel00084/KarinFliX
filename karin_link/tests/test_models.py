"""Tests for Pydantic models."""
import pytest
import uuid
import time
from karin_link.models import (
    DeviceInfo, EpisodeInfo, ShareRequest, ShareResponse,
    Room, AuthRequest, AuthResponse, SessionToken, QRData,
    DeviceType, DeviceStatus, RoomState
)


class TestDeviceInfo:
    """Test DeviceInfo model."""

    def test_default_values(self):
        device = DeviceInfo()
        assert device.name == "KARINFLiX Device"
        assert device.user_name == "User"
        assert device.device_type == DeviceType.PC
        assert device.is_online is True
        assert device.status == DeviceStatus.AVAILABLE
        assert device.api_port == 7800
        assert device.app_version == "1.0.0"

    def test_uuid_auto_generated(self):
        device = DeviceInfo()
        assert device.uuid != ""
        assert len(device.uuid) == 36

    def test_custom_device(self):
        device = DeviceInfo(
            uuid="test-uuid-123",
            name="MyTV",
            user_name="John",
            device_type=DeviceType.ANDROID_TV,
            os_info="Android 14"
        )
        assert device.name == "MyTV"
        assert device.device_type == DeviceType.ANDROID_TV
        assert device.os_info == "Android 14"

    def test_model_dump(self):
        device = DeviceInfo(uuid="test-uuid", name="Test")
        d = device.model_dump(mode="json")
        assert d["uuid"] == "test-uuid"
        assert d["name"] == "Test"


class TestEpisodeInfo:
    """Test EpisodeInfo model."""

    def test_default_values(self):
        episode = EpisodeInfo()
        assert episode.anime_title == ""
        assert episode.season == 0
        assert episode.episode == 0
        assert episode.language == "LAT"
        assert episode.quality == "HD"

    def test_custom_episode(self):
        episode = EpisodeInfo(
            anime_title="One Piece",
            season=1,
            episode=1,
            server="Karin",
            episode_title="I'm Luffy! The Man Who Will Become King of the Pirates",
            episode_url="https://example.com/episode1.mp4",
        )
        assert episode.anime_title == "One Piece"
        assert episode.season == 1
        assert episode.episode == 1


class TestAuthModels:
    """Test authentication models."""

    def test_auth_request(self):
        req = AuthRequest(device_uuid="test-uuid")
        assert req.device_uuid == "test-uuid"
        assert req.device_name == ""
        assert req.user_name == ""

    def test_auth_response(self):
        resp = AuthResponse(success=True, token="abc123", expires_at=time.time() + 3600)
        assert resp.success is True
        assert resp.token == "abc123"

    def test_session_token_valid(self):
        token = SessionToken(
            token="test",
            device_uuid="uuid",
            created_at=time.time(),
            expires_at=time.time() + 3600
        )
        assert token.is_valid() is True

    def test_session_token_expired(self):
        token = SessionToken(
            token="test",
            device_uuid="uuid",
            created_at=time.time(),
            expires_at=time.time() - 100
        )
        assert token.is_valid() is False


class TestRoom:
    """Test Room model."""

    def test_room_defaults(self):
        room = Room()
        assert len(room.id) == 8
        assert room.state == RoomState.IDLE
        assert room.guests == []
        assert room.current_time == 0.0

    def test_room_with_episode(self):
        episode = EpisodeInfo(anime_title="Test", season=1, episode=1)
        room = Room(
            id="room123",
            name="Test Room",
            host_uuid="host-uuid",
            current_episode=episode,
            guests=["guest1", "guest2"]
        )
        assert room.name == "Test Room"
        assert len(room.guests) == 2
        assert room.current_episode.anime_title == "Test"


class TestShareRequest:
    """Test ShareRequest model."""

    def test_share_request(self):
        episode = EpisodeInfo(anime_title="Test", season=1, episode=1)
        req = ShareRequest(
            target_device_uuid="target-uuid",
            episode=episode,
            message="Check this out!"
        )
        assert req.target_device_uuid == "target-uuid"
        assert req.message == "Check this out!"


class TestQRData:
    """Test QRData model."""

    def test_qr_default_scheme(self):
        qr = QRData()
        assert qr.scheme == "karinflix://watch"

    def test_qr_full_data(self):
        qr = QRData(
            episode_url="https://example.com/video.mp4",
            anime_title="Naruto",
            episode_title="Entry",
            server="Karin",
            episode=5,
        )
        assert qr.anime_title == "Naruto"
        assert qr.episode == 5
