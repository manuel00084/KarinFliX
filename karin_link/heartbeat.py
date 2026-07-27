"""
KARIN Link heartbeat system.

Monitors device liveness by sending periodic heartbeats and
detecting when devices go offline.
"""

import asyncio
import logging
import time
from typing import Callable, Optional, Awaitable

from .config import LinkConfig
from .models import DeviceInfo, DeviceStatus
from .database import Database

logger = logging.getLogger("karin_link.heartbeat")


class HeartbeatManager:
    """
    Manages device heartbeats.

    - Sends heartbeat to known devices every N seconds.
    - Marks devices as offline after timeout.
    - Cleans up stale entries periodically.
    """

    def __init__(self, config: LinkConfig, database: Database) -> None:
        self.config = config
        self.db = database
        self._task: Optional[asyncio.Task] = None
        self._running = False
        self._on_device_offline: Optional[Callable[[str], Awaitable[None]]] = None
        self._on_device_online: Optional[Callable[[str], Awaitable[None]]] = None

    def on_device_offline(self, callback: Callable[[str], Awaitable[None]]) -> None:
        self._on_device_offline = callback

    def on_device_online(self, callback: Callable[[str], Awaitable[None]]) -> None:
        self._on_device_online = callback

    async def start(self) -> None:
        """Start the heartbeat monitoring loop."""
        self._running = True
        self._task = asyncio.create_task(self._monitor_loop())
        logger.info(
            "Heartbeat: Started (interval=%.0fs, timeout=%.0fs)",
            self.config.heartbeat_interval,
            self.config.heartbeat_timeout,
        )

    async def stop(self) -> None:
        """Stop heartbeat monitoring."""
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("Heartbeat: Stopped")

    async def _monitor_loop(self) -> None:
        """Main heartbeat monitoring loop."""
        while self._running:
            try:
                await self._check_devices()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug("Heartbeat check error: %s", e)
            await asyncio.sleep(self.config.heartbeat_interval)

    async def _check_devices(self) -> None:
        """Check all online devices for heartbeat timeout."""
        devices = await self.db.get_online_devices()
        now = time.time()

        for device in devices:
            if device.uuid == self.config.device_uuid:
                continue

            elapsed = now - device.last_seen
            if elapsed > self.config.heartbeat_timeout:
                if device.is_online:
                    await self.db.mark_offline(device.uuid)
                    logger.info(
                        "Heartbeat: Device %s (%s) marked offline (no heartbeat for %.0fs)",
                        device.name, device.uuid[:8], elapsed,
                    )
                    if self._on_device_offline:
                        await self._on_device_offline(device.uuid)

    async def record_heartbeat(self, device_uuid: str, ip_address: str = "") -> None:
        """Record a heartbeat from a device."""
        device = await self.db.get_device(device_uuid)
        if device:
            device.last_seen = time.time()
            device.is_online = True
            device.status = DeviceStatus.AVAILABLE
            if ip_address:
                device.ip_address = ip_address
            await self.db.upsert_device(device)

            if self._on_device_online:
                await self._on_device_online(device_uuid)

    async def get_stale_devices(self) -> list[str]:
        """Return UUIDs of devices that haven't sent heartbeat."""
        devices = await self.db.get_online_devices()
        now = time.time()
        stale = []
        for d in devices:
            if d.uuid != self.config.device_uuid:
                if (now - d.last_seen) > self.config.heartbeat_timeout:
                    stale.append(d.uuid)
        return stale
