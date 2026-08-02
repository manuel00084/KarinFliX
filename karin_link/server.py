"""
KARIN Link main server entry point.

Initializes all components and starts the FastAPI server
with Zeroconf discovery, WebSocket support, and heartbeat monitoring.
"""

import asyncio
import logging
import signal
import sys
from typing import Optional

import uvicorn

from .config import LinkConfig
from .database import Database
from .security import SecurityManager
from .rooms import RoomManager
from .heartbeat import HeartbeatManager
from .discovery import ZeroconfDiscovery
from .broadcast import BroadcastDiscovery
from .websocket import KarinWebSocketServer
from .api import create_app
from .utils import get_local_ip, get_device_type, get_os_info, generate_device_name

logger = logging.getLogger("karin_link")


class KarinLinkServer:
    """
    Main KARIN Link server.

    Orchestrates discovery, API, WebSocket, heartbeat, and database
    into a single cohesive service.
    """

    def __init__(self, config: Optional[LinkConfig] = None) -> None:
        self.config = config or LinkConfig()
        if not self.config.device_name or self.config.device_name == "KARINFLiX Device":
            self.config.device_name = generate_device_name()
        if self.config.device_type == "PC":
            self.config.device_type = get_device_type()

        self.db = Database(self.config.db_path)
        self.security = SecurityManager(self.config)
        self.room_manager = RoomManager()
        self.heartbeat = HeartbeatManager(self.config, self.db)
        self.zeroconf: Optional[ZeroconfDiscovery] = None
        self.broadcast: Optional[BroadcastDiscovery] = None
        self.ws_server: Optional[KarinWebSocketServer] = None
        self._app: Optional[uvicorn.Server] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    async def start(self) -> None:
        """Start all KARIN Link services."""
        logger.info("=" * 50)
        logger.info("  KARIN Link v%s starting", self.config.app_version)
        logger.info("  Device: %s (%s)", self.config.device_name, self.config.device_uuid[:8])
        logger.info("  User: %s", self.config.user_name)
        logger.info("  Type: %s | OS: %s", self.config.device_type, get_os_info())
        logger.info("  IP: %s:%d", get_local_ip(), self.config.api_port)
        logger.info("=" * 50)

        await self.db.init()
        logger.info("Database: Initialized (%s)", self.config.db_path)

        self.ws_server = KarinWebSocketServer(
            self.config, self.security, self.room_manager
        )

        self.zeroconf = ZeroconfDiscovery(self.config)
        self.zeroconf.on_device_found(self._on_device_found)
        self.zeroconf.on_device_lost(self._on_device_lost)

        self.broadcast = BroadcastDiscovery(self.config)
        self.broadcast.on_device_found(self._on_device_found)
        self.broadcast.on_device_lost(self._on_device_lost)

        app = create_app(
            config=self.config,
            database=self.db,
            security=self.security,
            room_manager=self.room_manager,
            heartbeat=self.heartbeat,
            zeroconf=self.zeroconf,
            broadcast=self.broadcast,
            ws_server=self.ws_server,
        )

        zeroconf_ok = await self.zeroconf.start()
        if not zeroconf_ok:
            logger.info("Zeroconf unavailable, using UDP broadcast fallback")
            await self.broadcast.start()

        await self.heartbeat.start()
        logger.info("Heartbeat: Monitoring started")

        uvicorn_config = uvicorn.Config(
            app,
            host=self.config.api_host,
            port=self.config.api_port,
            log_level="warning",
            access_log=False,
        )
        self._app = uvicorn.Server(uvicorn_config)
        logger.info("API: Server starting on http://%s:%d", self.config.api_host, self.config.api_port)

        await self._app.serve()

    async def _on_device_found(self, device) -> None:
        """Handle discovery of a new device on the network."""
        logger.info("Discovery: Found device %s (%s)", device.name, device.uuid[:8])
        await self.db.upsert_device(device)

    async def _on_device_lost(self, device_uuid: str) -> None:
        """Handle a device going offline."""
        logger.info("Discovery: Device %s lost", device_uuid[:8])
        await self.db.mark_offline(device_uuid)

    async def stop(self) -> None:
        """Gracefully stop all services."""
        logger.info("KARIN Link: Shutting down...")
        if self._app:
            self._app.should_exit = True
        await self.heartbeat.stop()
        if self.zeroconf:
            await self.zeroconf.stop()
        if self.broadcast:
            await self.broadcast.stop()
        await self.db.close()
        logger.info("KARIN Link: Stopped")


async def main() -> None:
    """Main entry point for running KARIN Link standalone."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    server = KarinLinkServer()

    loop = asyncio.get_event_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, lambda: asyncio.create_task(server.stop()))
        except NotImplementedError:
            pass

    try:
        await server.start()
    except KeyboardInterrupt:
        await server.stop()


if __name__ == "__main__":
    asyncio.run(main())
