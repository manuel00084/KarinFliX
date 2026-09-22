"""
KARIN Link heartbeat manager.
"""
import logging
import time
from typing import Optional
from .config import LinkConfig
from .database import Database

logger = logging.getLogger("karin_link.heartbeat")


class HeartbeatManager:
    """Manages device heartbeat monitoring."""

    def __init__(self, config: LinkConfig, database: Database) -> None:
        self.config = config
        self.db = database
        self._task: Optional[any] = None
        self._last_heartbeat: dict[str, float] = {}

    async def start(self) -> None:
        """Start heartbeat monitoring."""
        logger.info("Heartbeat: Monitoring started (interval=%ss, timeout=%ss)",
                     self.config.heartbeat_interval, self.config.heartbeat_timeout)

    async def record_heartbeat(self, device_id: str) -> None:
        """Record a heartbeat for a device."""
        self._last_heartbeat[device_id] = time.time()
        await self.db.upsert_device(DeviceInfo(uuid=device_id, last_seen=time.time()))  # type: ignore
        logger.debug("Heartbeat recorded for %s", device_id[:8])

    async def stop(self) -> None:
        """Stop heartbeat monitoring."""
        logger.info("Heartbeat: Stopped")

    def get_device_status(self, device_id: str) -> str:
        """Check if a device is alive based on heartbeat."""
        last = self._last_heartbeat.get(device_id, 0)
        if time.time() - last > self.config.heartbeat_timeout:
            return "offline"
        return "online"