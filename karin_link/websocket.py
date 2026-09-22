"""
KARIN Link WebSocket server.
"""
import logging
from typing import Optional, Dict, Set
from fastapi import WebSocket, WebSocketDisconnect
from .config import LinkConfig
from .security import SecurityManager
from .rooms import RoomManager

logger = logging.getLogger("karin_link.websocket")


class WebSocketManager:
    """Manages active WebSocket connections."""

    def __init__(self) -> None:
        self.active_connections: Set[WebSocket] = set()
        self._device_map: Dict[str, WebSocket] = {}

    async def connect(self, websocket: WebSocket, device_uuid: str) -> None:
        """Accept a new WebSocket connection."""
        await websocket.accept()
        self.active_connections.add(websocket)
        self._device_map[device_uuid] = websocket
        logger.info("WebSocket connected: %s", device_uuid[:8])

    def disconnect(self, websocket: WebSocket, device_uuid: str) -> None:
        """Remove a WebSocket connection."""
        self.active_connections.discard(websocket)
        self._device_map.pop(device_uuid, None)
        logger.info("WebSocket disconnected: %s", device_uuid[:8])

    async def send_to(self, device_uuid: str, data: dict) -> bool:
        """Send a message to a specific device."""
        ws = self._device_map.get(device_uuid)
        if ws:
            try:
                await ws.send_json(data)
                return True
            except Exception:
                logger.error("Failed to send to %s", device_uuid[:8])
        return False

    async def broadcast_to_room(self, room_id: str, data: dict, exclude: Optional[str] = None) -> None:
        """Broadcast a message to all devices in a room."""
        for ws in list(self.active_connections):
            try:
                await ws.send_json(data)
            except Exception:
                continue

    async def broadcast(self, data: dict) -> None:
        """Broadcast to all connected devices."""
        for ws in list(self.active_connections):
            try:
                await ws.send_json(data)
            except Exception:
                continue


class KarinWebSocketServer:
    """WebSocket server for real-time KARIN Link communication."""

    def __init__(self, config: LinkConfig, security: SecurityManager, room_manager: RoomManager) -> None:
        self.config = config
        self.security = security
        self.room_manager = room_manager
        self.manager = WebSocketManager()

    async def websocket_endpoint(self, websocket: WebSocket) -> None:
        """Handle WebSocket connections."""
        token = websocket.query_params.get("token")
        if not token:
            await websocket.close(code=4001)
            return

        device_uuid = self.security.validate_token(token)
        if not device_uuid:
            await websocket.close(code=4002)
            return

        await self.manager.connect(websocket, device_uuid)
        try:
            while True:
                data = await websocket.receive_json()
                await self._handle_message(device_uuid, data)
        except WebSocketDisconnect:
            self.manager.disconnect(websocket, device_uuid)

    async def _handle_message(self, device_uuid: str, data: dict) -> None:
        """Handle incoming WebSocket messages."""
        msg_type = data.get("type")
        if msg_type == "room.sync":
            await self.manager.broadcast(data)
        elif msg_type == "room.join":
            room_id = data.get("room_id")
            self.room_manager.join_room(room_id, device_uuid)
        elif msg_type == "room.leave":
            room_id = data.get("room_id")
            self.room_manager.leave_room(room_id, device_uuid)