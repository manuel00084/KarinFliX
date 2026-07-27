"""
KARIN Link client.

Connects to other KARIN Link instances to share episodes,
join rooms, and exchange playback commands.
"""

import asyncio
import json
import logging
import time
from typing import Optional, Callable, Awaitable

import aiohttp

from .config import LinkConfig
from .models import (
    AuthRequest, AuthResponse, EpisodeInfo,
    ShareRequest, ShareResponse, DeviceInfo, QRData,
)

logger = logging.getLogger("karin_link.client")


class KarinLinkClient:
    """
    Client for connecting to other KARIN Link instances.

    Provides methods for device discovery, episode sharing,
    room management, and real-time playback sync.
    """

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self._session: Optional[aiohttp.ClientSession] = None
        self._token: str = ""
        self._ws: Optional[aiohttp.ClientWebSocketResponse] = None
        self._on_message: Optional[Callable[[dict], Awaitable[None]]] = None

    async def _ensure_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=10)
            )
        return self._session

    async def close(self) -> None:
        if self._ws and not self._ws.closed:
            await self._ws.close()
        if self._session and not self._session.closed:
            await self._session.close()

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self._token:
            h["Authorization"] = f"Bearer {self._token}"
        return h

    # ── Authentication ─────────────────────────────────────

    async def authenticate(self, host: str, port: int) -> bool:
        """Authenticate with a remote KARIN Link instance."""
        url = f"http://{host}:{port}/auth"
        payload = AuthRequest(
            device_uuid=self.config.device_uuid,
            device_name=self.config.device_name,
            user_name=self.config.user_name,
        )
        session = await self._ensure_session()
        try:
            async with session.post(url, json=payload.model_dump(), headers=self._headers()) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    auth = AuthResponse(**data)
                    if auth.success:
                        self._token = auth.token
                        logger.info("Client: Authenticated with %s:%d", host, port)
                        return True
        except Exception as e:
            logger.warning("Client: Auth failed with %s:%d - %s", host, port, e)
        return False

    # ── Device operations ──────────────────────────────────

    async def get_devices(self, host: str, port: int) -> list[DeviceInfo]:
        """Get list of devices from a remote instance."""
        url = f"http://{host}:{port}/devices"
        session = await self._ensure_session()
        try:
            async with session.get(url, headers=self._headers()) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return [DeviceInfo(**d) for d in data]
        except Exception as e:
            logger.debug("Client: get_devices error: %s", e)
        return []

    # ── Share operations ───────────────────────────────────

    async def share_episode(
        self,
        host: str,
        port: int,
        target_uuid: str,
        episode: EpisodeInfo,
        message: str = "",
    ) -> ShareResponse:
        """Share an episode with a device on a remote instance."""
        url = f"http://{host}:{port}/share"
        payload = ShareRequest(
            target_device_uuid=target_uuid,
            episode=episode,
            message=message,
        )
        session = await self._ensure_session()
        try:
            async with session.post(url, json=payload.model_dump(), headers=self._headers()) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return ShareResponse(**data)
        except Exception as e:
            logger.warning("Client: share_episode error: %s", e)
        return ShareResponse(success=False, message=str(e))

    # ── Room operations ────────────────────────────────────

    async def create_room(
        self,
        host: str,
        port: int,
        episode: Optional[EpisodeInfo] = None,
        name: str = "",
    ) -> Optional[dict]:
        """Create a room on a remote instance."""
        url = f"http://{host}:{port}/rooms"
        params = {"name": name}
        session = await self._ensure_session()
        try:
            body = episode.model_dump() if episode else None
            async with session.post(url, json=body, params=params, headers=self._headers()) as resp:
                if resp.status == 200:
                    return await resp.json()
        except Exception as e:
            logger.warning("Client: create_room error: %s", e)
        return None

    async def join_room(self, host: str, port: int, room_id: str) -> Optional[dict]:
        """Join a room on a remote instance."""
        url = f"http://{host}:{port}/rooms/{room_id}/join"
        session = await self._ensure_session()
        try:
            async with session.post(url, headers=self._headers()) as resp:
                if resp.status == 200:
                    return await resp.json()
        except Exception as e:
            logger.warning("Client: join_room error: %s", e)
        return None

    async def room_command(
        self,
        host: str,
        port: int,
        room_id: str,
        command: str,
        position: float = 0.0,
    ) -> bool:
        """Send a playback command to a room."""
        url = f"http://{host}:{port}/rooms/{room_id}/{command}"
        params = {"position": position}
        session = await self._ensure_session()
        try:
            async with session.post(url, params=params, headers=self._headers()) as resp:
                return resp.status == 200
        except Exception as e:
            logger.warning("Client: room_command error: %s", e)
        return False

    # ── WebSocket ──────────────────────────────────────────

    def on_message(self, callback: Callable[[dict], Awaitable[None]]) -> None:
        """Register callback for WebSocket messages."""
        self._on_message = callback

    async def connect_ws(self, host: str, port: int) -> bool:
        """Connect to a remote WebSocket for real-time sync."""
        url = f"ws://{host}:{port}/ws?token={self._token}"
        session = await self._ensure_session()
        try:
            self._ws = await session.ws_connect(url)
            asyncio.create_task(self._ws_listen())
            logger.info("Client: WebSocket connected to %s:%d", host, port)
            return True
        except Exception as e:
            logger.warning("Client: WebSocket connection failed: %s", e)
            return False

    async def _ws_listen(self) -> None:
        """Listen for incoming WebSocket messages."""
        if not self._ws:
            return
        try:
            async for msg in self._ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        if self._on_message:
                            await self._on_message(data)
                    except json.JSONDecodeError:
                        pass
                elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    break
        except Exception:
            pass
        logger.info("Client: WebSocket disconnected")

    async def send_ws(self, message: dict) -> bool:
        """Send a message over WebSocket."""
        if self._ws and not self._ws.closed:
            try:
                await self._ws.send_json(message)
                return True
            except Exception:
                pass
        return False

    # ── QR ─────────────────────────────────────────────────

    async def get_qr(self, host: str, port: int,
                      episode: EpisodeInfo, size: int = 300) -> Optional[bytes]:
        """Get a QR code for an episode from a remote instance."""
        url = f"http://{host}:{port}/qr/episode"
        session = await self._ensure_session()
        try:
            async with session.post(url, json=episode.model_dump(),
                                    params={"size": size},
                                    headers=self._headers()) as resp:
                if resp.status == 200:
                    return await resp.read()
        except Exception as e:
            logger.warning("Client: get_qr error: %s", e)
        return None
