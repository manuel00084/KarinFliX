"""
KARIN Link UDP broadcast discovery.

Fallback discovery mechanism when Zeroconf is unavailable.
Uses UDP broadcast on the local network to announce and discover
KARINFLiX instances.
"""

import asyncio
import json
import logging
import socket
import struct
from typing import Callable, Optional, Awaitable

from .config import LinkConfig
from .models import DeviceAnnounce, DeviceInfo, DeviceStatus, DeviceType
from .utils import get_local_ip

logger = logging.getLogger("karin_link.broadcast")

MAGIC = b"KFLX"
VERSION = 1
MSG_ANNOUNCE = 0x01
MSG_DISCOVER = 0x02
MSG_RESPONSE = 0x03
MSG_HEARTBEAT = 0x04
MSG_GOODBYE = 0x05


class BroadcastDiscovery:
    """
    UDP broadcast-based device discovery.

    Broadcasts announcements on the local network and listens
    for other KARINFLiX instances. Used as fallback when Zeroconf
    is not available.
    """

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self._devices: dict[str, DeviceInfo] = {}
        self._on_device_found: Optional[Callable[[DeviceInfo], Awaitable[None]]] = None
        self._on_device_lost: Optional[Callable[[str], Awaitable[None]]] = None
        self._running = False
        self._listen_task: Optional[asyncio.Task] = None
        self._announce_task: Optional[asyncio.Task] = None

    def on_device_found(self, callback: Callable[[DeviceInfo], Awaitable[None]]) -> None:
        self._on_device_found = callback

    def on_device_lost(self, callback: Callable[[str], Awaitable[None]]) -> None:
        self._on_device_lost = callback

    def _build_announce_packet(self, msg_type: int = MSG_ANNOUNCE) -> bytes:
        """Build a binary announcement packet."""
        announce = DeviceAnnounce(
            uuid=self.config.device_uuid,
            name=self.config.device_name,
            user_name=self.config.user_name,
            device_type=self.config.device_type,
            os_info="",
            app_version=self.config.app_version,
            api_port=self.config.api_port,
            status="available",
        )
        payload = announce.model_dump_json().encode("utf-8")
        header = struct.pack(">4sBBH", MAGIC, VERSION, msg_type, len(payload))
        return header + payload

    async def start(self) -> bool:
        """Start broadcast discovery."""
        try:
            self._running = True
            self._listen_task = asyncio.create_task(self._listen_loop())
            self._announce_task = asyncio.create_task(self._announce_loop())
            logger.info("Broadcast: Discovery started on port %d", self.config.broadcast_port)
            return True
        except Exception as e:
            logger.warning("Broadcast failed to start: %s", e)
            return False

    async def stop(self) -> None:
        """Stop broadcast discovery and send goodbye."""
        self._running = False
        if self._announce_task:
            self._announce_task.cancel()
        if self._listen_task:
            self._listen_task.cancel()

        try:
            packet = self._build_announce_packet(MSG_GOODBYE)
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.sendto(packet, ("255.255.255.255", self.config.broadcast_port))
            sock.close()
        except Exception:
            pass
        logger.info("Broadcast: Discovery stopped")

    async def _announce_loop(self) -> None:
        """Periodically broadcast our presence."""
        while self._running:
            try:
                await asyncio.get_event_loop().run_in_executor(
                    None, self._send_broadcast, MSG_ANNOUNCE
                )
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug("Broadcast send error: %s", e)
            await asyncio.sleep(self.config.heartbeat_interval)

    async def _listen_loop(self) -> None:
        """Listen for broadcast packets from other devices."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.setblocking(False)
            sock.bind(("", self.config.broadcast_port))
        except Exception as e:
            logger.warning("Broadcast: Cannot bind port %d: %s", self.config.broadcast_port, e)
            return

        loop = asyncio.get_event_loop()
        while self._running:
            try:
                data, addr = await loop.sock_recvfrom(sock, 65535)
                self._handle_packet(data, addr[0])
            except asyncio.CancelledError:
                break
            except BlockingIOError:
                await asyncio.sleep(0.1)
            except Exception as e:
                logger.debug("Broadcast listen error: %s", e)
                await asyncio.sleep(0.1)

        sock.close()

    def _send_broadcast(self, msg_type: int) -> None:
        """Send a UDP broadcast packet."""
        packet = self._build_announce_packet(msg_type)
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.sendto(packet, ("255.255.255.255", self.config.broadcast_port))
            sock.close()
        except Exception as e:
            logger.debug("Broadcast send error: %s", e)

    def _handle_packet(self, data: bytes, sender_ip: str) -> None:
        """Parse and handle a received broadcast packet."""
        if len(data) < 8:
            return

        magic, version, msg_type, payload_len = struct.unpack(">4sBBH", data[:8])
        if magic != MAGIC:
            return
        if version != VERSION:
            return

        payload = data[8:8 + payload_len]
        try:
            announce = DeviceAnnounce.model_validate_json(payload.decode("utf-8"))
        except Exception:
            return

        if announce.uuid == self.config.device_uuid:
            return

        if msg_type == MSG_GOODBYE:
            self._devices.pop(announce.uuid, None)
            if self._on_device_lost:
                loop = asyncio.get_event_loop()
                loop.call_soon_threadsafe(
                    asyncio.ensure_future, self._on_device_lost(announce.uuid)
                )
            return

        try:
            dt = DeviceType(announce.device_type)
        except ValueError:
            dt = DeviceType.UNKNOWN

        device = DeviceInfo(
            uuid=announce.uuid,
            name=announce.name,
            user_name=announce.user_name,
            device_type=dt,
            app_version=announce.app_version,
            api_port=announce.api_port,
            status=DeviceStatus.AVAILABLE,
            ip_address=sender_ip,
            is_online=True,
        )
        self._devices[device.uuid] = device

        if self._on_device_found:
            loop = asyncio.get_event_loop()
            loop.call_soon_threadsafe(
                asyncio.ensure_future, self._on_device_found(device)
            )

    @property
    def discovered_devices(self) -> dict[str, DeviceInfo]:
        return dict(self._devices)
