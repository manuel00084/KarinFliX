"""Tests for room management."""
import pytest
from karin_link.rooms import RoomManager
from karin_link.models import EpisodeInfo, RoomState, DeviceStatus


class TestRoomManager:
    """Test the RoomManager."""

    def test_create_room(self):
        """Should create a room with correct properties."""
        manager = RoomManager()
        room = manager.create_room("host-uuid", "Host", name="Test Room")
        assert room.name == "Test Room"
        assert room.host_uuid == "host-uuid"
        assert room.id is not None
        assert len(room.guests) == 1
        assert room.guests[0] == "host-uuid"

    def test_join_leave_room(self):
        """Should join and leave rooms."""
        manager = RoomManager()
        room = manager.create_room("host-uuid", "Host")
        assert manager.join_room(room.id, "guest-1") is True
        assert "guest-1" in room.guests
        manager.leave_room(room.id, "guest-1")
        assert "guest-1" not in room.guests

    def test_join_nonexistent_room(self):
        """Should return False for nonexistent room."""
        manager = RoomManager()
        assert manager.join_room("nonexistent", "guest-1") is False

    def test_get_room(self):
        """Should get room by ID."""
        manager = RoomManager()
        room = manager.create_room("host-uuid", "Host")
        found = manager.get_room(room.id)
        assert found is not None
        assert found.id == room.id

    def test_get_nonexistent_room(self):
        """Should return None for nonexistent room."""
        manager = RoomManager()
        assert manager.get_room("nonexistent") is None

    def test_get_active_rooms(self):
        """Should list all active rooms."""
        manager = RoomManager()
        manager.create_room("host-1", "Host1")
        manager.create_room("host-2", "Host2")
        rooms = manager.get_active_rooms()
        assert len(rooms) == 2

    def test_update_playback(self):
        """Should update playback state."""
        manager = RoomManager()
        room = manager.create_room("host-uuid", "Host")
        manager.update_playback(room.id, DeviceStatus.PLAYING, 120.5)
        assert room.state == RoomState.PLAYING
        assert room.current_time == 120.5

    def test_room_count(self):
        """Should track room count."""
        manager = RoomManager()
        assert manager.get_room_count() == 0
        manager.create_room("host-1", "Host1")
        manager.create_room("host-2", "Host2")
        assert manager.get_room_count() == 2