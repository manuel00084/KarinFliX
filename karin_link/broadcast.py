"""
KARIN Link UDP broadcast fallback for device discovery.
"""
import logging
import socket
import asyncio
from typing import Optional, Callable
from .config import LinkConfig
from .models import DeviceInfo

logger = logging.getLogger("karin_link.broadcast")


class BroadcastDiscovery:
    """UDP broadcast fallback for device discovery."""

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self._sock: Optional[socket.socket] = None
        self._task: Optional[asyncio.Task] = None
        self._on_found: Optional[Callable] = None
        self._on_lost: Optional[Callable] = None
        self._last_seen: dict[str, float] = {}

    def on_device_found(self, callback: Callable) -> None:
        self._on_found = callback

    def on_device_lost(self, callback: Callable) -> None:
        self._on_lost = callback

    async def start(self) -> None:
        """Start UDP broadcast listener."""
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self._sock.bind(("", self.config.broadcast_port))
        logger.info("UDP broadcast listening on port %d", self.config.broadcast_port)

        self._task = asyncio.create_task(self._listen())

    async def _listen(self) -> None:
        """Listen for broadcast messages."""
        loop = asyncio.get_event_loop()
        while True:
            try:
                data, addr = await loop.sock_recvfrom(self._sock, 4096)  # type: ignore
                self._handle_message(data, addr)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug("Broadcast receive error: %s", e)

    def _handle_message(self, data: bytes, addr: tuple) -> None:
        """Handle incoming broadcast message."""
        try:
            message = data.decode("utf-8")
            if "karinflix" in message.lower():
                device_uuid = message.split(":")[1].strip() if ":" in message else device_uuid
                self._last_seen[device_uuid] = asyncio.get_event_loop().time()
                if self._on_found:
                    self._on_found(DeviceInfo(uuid=device_uuid, ip_address=addr[0]))
        except Exception:
            pass

    async def stop(self) -> None:
        """Stop UDP broadcast."""
        if self._task:
            self._task.cancel()
        if self._sock:
            self._sock.close()
        logger.info("UDP broadcast stopped")