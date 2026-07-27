"""
KARIN Link WebSocket server.

Handles real-time communication for room sync, device status
updates, and live playback control between devices.
"""

import asyncio
import json
import logging
import time
from typing import Optional

from fastapi import WebSocket, WebSocketDisconnect

from .config import LinkConfig
from .security import SecurityManager
from .rooms import RoomManager
from .models import RoomCommand, RoomState

logger = logging.getLogger("karin_link.websocket")


class ConnectionManager:
    """Manages active WebSocket connections."""

    def __init__(self, security: SecurityManager, room_manager: RoomManager) -> None:
        self.security = security
        self.room_manager = room_manager
        self._connections: dict[str, WebSocket] = {}
        self._device_rooms: dict[str, str] = {}

    async def connect(self, websocket: WebSocket, token: str) -> Optional[str]:
        """Authenticate and accept a WebSocket connection."""
        device_uuid = self.security.validate_token(token)
        if not device_uuid:
            await websocket.close(code=4001, reason="Unauthorized")
            return None

        await websocket.accept()
        self._connections[device_uuid] = websocket
        logger.info("WebSocket: Device %s connected", device_uuid[:8])
        return device_uuid

    def disconnect(self, device_uuid: str) -> None:
        """Remove a device's WebSocket connection."""
        self._connections.pop(device_uuid, None)
        room_id = self._device_rooms.pop(device_uuid, None)
        if room_id:
            self.room_manager.leave_room(room_id, device_uuid)
        logger.info("WebSocket: Device %s disconnected", device_uuid[:8])

    async def send_to(self, device_uuid: str, message: dict) -> bool:
        """Send a message to a specific device."""
        ws = self._connections.get(device_uuid)
        if ws:
            try:
                await ws.send_json(message)
                return True
            except Exception:
                self._connections.pop(device_uuid, None)
        return False

    async def broadcast(self, message: dict, exclude: Optional[str] = None) -> int:
        """Broadcast a message to all connected devices."""
        sent = 0
        disconnected = []
        for uuid, ws in self._connections.items():
            if uuid == exclude:
                continue
            try:
                await ws.send_json(message)
                sent += 1
            except Exception:
                disconnected.append(uuid)
        for uuid in disconnected:
            self._connections.pop(uuid, None)
        return sent

    async def broadcast_to_room(self, room_id: str, message: dict,
                                exclude: Optional[str] = None) -> int:
        """Broadcast a message to all devices in a room."""
        room = self.room_manager.get_room(room_id)
        if not room:
            return 0
        targets = [room.host_uuid] + room.guests
        sent = 0
        for uuid in targets:
            if uuid == exclude:
                continue
            if await self.send_to(uuid, message):
                sent += 1
        return sent

    async def handle_message(self, device_uuid: str, data: dict) -> None:
        """Route an incoming WebSocket message."""
        msg_type = data.get("type", "")
        payload = data.get("payload", {})

        if msg_type == "room.command":
            await self._handle_room_command(device_uuid, payload)
        elif msg_type == "room.join":
            await self._handle_room_join(device_uuid, payload)
        elif msg_type == "room.leave":
            await self._handle_room_leave(device_uuid)
        elif msg_type == "device.status":
            await self._handle_status_update(device_uuid, payload)
        elif msg_type == "ping":
            await self.send_to(device_uuid, {"type": "pong", "timestamp": time.time()})
        else:
            logger.debug("WebSocket: Unknown message type: %s", msg_type)

    async def _handle_room_command(self, device_uuid: str, payload: dict) -> None:
        room_id = payload.get("room_id", "")
        room = self.room_manager.get_room(room_id)
        if not room:
            return
        if device_uuid != room.host_uuid:
            return

        command = payload.get("command", "")
        position = payload.get("position", 0.0)

        if command == "play":
            self.room_manager.update_playback(room_id, RoomState.PLAYING, position)
        elif command == "pause":
            self.room_manager.update_playback(room_id, RoomState.PAUSED, position)
        elif command == "seek":
            self.room_manager.update_playback(room_id, RoomState.SEEKING, position)

        await self.broadcast_to_room(room_id, {
            "type": "room.sync",
            "payload": {
                "room_id": room_id,
                "command": command,
                "position": position,
                "state": room.state.value,
                "timestamp": time.time(),
            },
        }, exclude=device_uuid)

    async def _handle_room_join(self, device_uuid: str, payload: dict) -> None:
        room_id = payload.get("room_id", "")
        self.room_manager.join_room(room_id, device_uuid)
        self._device_rooms[device_uuid] = room_id
        room = self.room_manager.get_room(room_id)
        if room:
            await self.send_to(device_uuid, {
                "type": "room.state",
                "payload": room.model_dump(mode="json"),
            })

    async def _handle_room_leave(self, device_uuid: str) -> None:
        room_id = self._device_rooms.pop(device_uuid, None)
        if room_id:
            self.room_manager.leave_room(room_id, device_uuid)
            await self.broadcast_to_room(room_id, {
                "type": "room.device_left",
                "payload": {"device_uuid": device_uuid},
            })

    async def _handle_status_update(self, device_uuid: str, payload: dict) -> None:
        await self.broadcast({
            "type": "device.status_update",
            "payload": {"uuid": device_uuid, **payload},
        }, exclude=device_uuid)

    @property
    def active_connections(self) -> int:
        return len(self._connections)

    @property
    def connected_devices(self) -> list[str]:
        return list(self._connections.keys())


class KarinWebSocketServer:
    """WebSocket server endpoint factory for FastAPI."""

    def __init__(self, config: LinkConfig, security: SecurityManager,
                 room_manager: RoomManager) -> None:
        self.config = config
        self.manager = ConnectionManager(security, room_manager)
        room_manager.set_ws_manager(self.manager)

    async def websocket_endpoint(self, websocket: WebSocket) -> None:
        """FastAPI WebSocket endpoint handler."""
        token = websocket.query_params.get("token", "")
        device_uuid = await self.manager.connect(websocket, token)
        if not device_uuid:
            return

        try:
            while True:
                raw = await websocket.receive_text()
                try:
                    data = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                await self.manager.handle_message(device_uuid, data)
        except WebSocketDisconnect:
            pass
        except Exception as e:
            logger.debug("WebSocket error for %s: %s", device_uuid[:8], e)
        finally:
            self.manager.disconnect(device_uuid)
