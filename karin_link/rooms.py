"""
KARIN Link room management.
"""
import logging
import uuid
import time
from typing import Optional, List
from .models import Room, RoomState, DeviceStatus, EpisodeInfo

logger = logging.getLogger("karin_link.rooms")


class RoomManager:
    """Manages synchronized playback rooms."""

    def __init__(self) -> None:
        self._rooms: dict[str, Room] = {}

    def create_room(
        self,
        host_uuid: str,
        host_name: str,
        episode: Optional[EpisodeInfo] = None,
        name: str = ""
    ) -> Room:
        """Create a new playback room."""
        room = Room(
            name=name or f"Room-{uuid.uuid4()[:4]}",
            host_uuid=host_uuid,
            host_name=host_name,
            current_episode=episode,
            state=RoomState.PLAYING if episode else RoomState.IDLE,
        )
        room.guests.append(host_uuid)
        self._rooms[room.id] = room
        logger.info("Room created: %s by %s", room.id, host_name)
        return room

    def get_room(self, room_id: str) -> Optional[Room]:
        """Get a room by ID."""
        return self._rooms.get(room_id)

    def get_active_rooms(self) -> List[Room]:
        """Get all active rooms."""
        return list(self._rooms.values())

    def join_room(self, room_id: str, device_uuid: str) -> bool:
        """Add a device to a room."""
        room = self._rooms.get(room_id)
        if not room:
            return False
        if device_uuid not in room.guests:
            room.guests.append(device_uuid)
        return True

    def leave_room(self, room_id: str, device_uuid: str) -> None:
        """Remove a device from a room."""
        room = self._rooms.get(room_id)
        if room and device_uuid in room.guests:
            room.guests.remove(device_uuid)

    def update_playback(
        self,
        room_id: str,
        status: DeviceStatus,
        position: float = 0.0
    ) -> None:
        """Update playback state in a room."""
        room = self._rooms.get(room_id)
        if room:
            room.state = RoomState(status.value)
            room.current_time = position

    def remove_room(self, room_id: str) -> None:
        """Remove a room."""
        if room_id in self._rooms:
            del self._rooms[room_id]

    def get_room_count(self) -> int:
        """Get number of active rooms."""
        return len(self._rooms)