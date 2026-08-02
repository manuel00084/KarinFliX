"""
KARIN Link room/sync management.

Manages synchronized playback rooms where multiple devices can
watch the same episode together with synchronized play/pause/seek.
"""

import asyncio
import logging
import time
from typing import Optional

from .models import (
    Room, RoomState, RoomCommand, RoomInvite,
    EpisodeInfo, DeviceInfo, DeviceStatus,
)

logger = logging.getLogger("karin_link.rooms")


class RoomManager:
    """
    Manages playback rooms for synchronized watching.

    A room has:
    - A host who creates it and controls playback
    - Guests who join and follow the host's playback
    - A shared episode and synchronized state
    """

    def __init__(self) -> None:
        self._rooms: dict[str, Room] = {}
        self._ws_manager: Optional[object] = None

    def set_ws_manager(self, ws_manager: object) -> None:
        """Set the WebSocket manager for broadcasting commands."""
        self._ws_manager = ws_manager

    def create_room(
        self,
        host_uuid: str,
        host_name: str,
        episode: Optional[EpisodeInfo] = None,
        name: str = "",
    ) -> Room:
        """Create a new room."""
        room = Room(
            host_uuid=host_uuid,
            host_name=host_name,
            current_episode=episode,
            name=name or f"Sala de {host_name}",
            state=RoomState.IDLE,
        )
        self._rooms[room.id] = room
        logger.info("Room: Created %s by %s", room.id, host_name)
        return room

    def get_room(self, room_id: str) -> Optional[Room]:
        return self._rooms.get(room_id)

    def get_room_by_host(self, host_uuid: str) -> Optional[Room]:
        for room in self._rooms.values():
            if room.host_uuid == host_uuid:
                return room
        return None

    def get_room_for_device(self, device_uuid: str) -> Optional[Room]:
        for room in self._rooms.values():
            if room.host_uuid == device_uuid or device_uuid in room.guests:
                return room
        return None

    def join_room(self, room_id: str, device_uuid: str) -> bool:
        """Add a guest to a room."""
        room = self._rooms.get(room_id)
        if not room:
            return False
        if device_uuid not in room.guests and device_uuid != room.host_uuid:
            room.guests.append(device_uuid)
        logger.info("Room: %s joined %s", device_uuid[:8], room_id)
        return True

    def leave_room(self, room_id: str, device_uuid: str) -> None:
        """Remove a device from a room."""
        room = self._rooms.get(room_id)
        if not room:
            return
        if device_uuid in room.guests:
            room.guests.remove(device_uuid)
        if device_uuid == room.host_uuid:
            if room.guests:
                new_host = room.guests.pop(0)
                room.host_uuid = new_host
                logger.info("Room: %s is now host of %s", new_host[:8], room_id)
            else:
                del self._rooms[room_id]
                logger.info("Room: %s dissolved (empty)", room_id)

    def destroy_room(self, room_id: str) -> bool:
        if room_id in self._rooms:
            del self._rooms[room_id]
            logger.info("Room: %s destroyed", room_id)
            return True
        return False

    def update_playback(self, room_id: str, state: RoomState,
                        position: float = 0.0) -> Optional[Room]:
        """Update the playback state of a room."""
        room = self._rooms.get(room_id)
        if not room:
            return None
        room.state = state
        room.current_time = position
        return room

    def set_episode(self, room_id: str, episode: EpisodeInfo) -> Optional[Room]:
        """Set the current episode for a room."""
        room = self._rooms.get(room_id)
        if not room:
            return None
        room.current_episode = episode
        room.state = RoomState.IDLE
        room.current_time = 0.0
        return room

    def get_active_rooms(self) -> list[Room]:
        return list(self._rooms.values())

    def cleanup_empty(self) -> int:
        """Remove rooms with no participants. Returns count removed."""
        to_remove = []
        for room_id, room in self._rooms.items():
            if not room.guests and not room.host_uuid:
                to_remove.append(room_id)
        for rid in to_remove:
            del self._rooms[rid]
        return len(to_remove)
