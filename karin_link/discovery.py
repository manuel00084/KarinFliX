"""
KARIN Link Zeroconf/mDNS device discovery.

Automatically discovers other KARINFLiX instances on the local network
using mDNS/Zeroconf. Falls back to UDP broadcast if Zeroconf fails.

This is the primary discovery mechanism, similar to AirDrop.
"""

import asyncio
import logging
import socket
from typing import Callable, Optional, Awaitable

from zeroconf import (
    ServiceInfo,
    Zeroconf,
    ServiceBrowser,
)

from .config import LinkConfig
from .models import DeviceInfo, DeviceAnnounce, DeviceStatus, DeviceType
from .utils import get_local_ip

logger = logging.getLogger("karin_link.discovery")


class KarinServiceInfo:
    """Zeroconf service info for KARIN Link."""

    SERVICE_TYPE = "_karinflix._tcp.local."

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self.local_ip = get_local_ip()

    def build(self) -> ServiceInfo:
        """Build Zeroconf ServiceInfo for this device."""
        properties = {
            b"uuid": self.config.device_uuid.encode("utf-8"),
            b"name": self.config.device_name.encode("utf-8"),
            b"user": self.config.user_name.encode("utf-8"),
            b"type": self.config.device_type.encode("utf-8"),
            b"version": self.config.app_version.encode("utf-8"),
            b"port": str(self.config.api_port).encode("utf-8"),
            b"status": "available".encode("utf-8"),
        }
        return ServiceInfo(
            type_=self.SERVICE_TYPE,
            name=f"{self.config.device_name}.{self.SERVICE_TYPE}",
            addresses=[socket.inet_aton(self.local_ip)],
            port=self.config.api_port,
            properties=properties,
            server=f"{self.config.device_uuid}.local.",
        )


class ZeroconfDiscovery:
    """
    Zeroconf-based device discovery for KARIN Link.

    Publishes this device on the local network and discovers
    other KARINFLiX instances automatically.
    """

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self._zeroconf: Optional[Zeroconf] = None
        self._service_info: Optional[ServiceInfo] = None
        self._browser: Optional[ServiceBrowser] = None
        self._devices: dict[str, DeviceInfo] = {}
        self._on_device_found: Optional[Callable[[DeviceInfo], Awaitable[None]]] = None
        self._on_device_lost: Optional[Callable[[str], Awaitable[None]]] = None
        self._running = False

    def on_device_found(self, callback: Callable[[DeviceInfo], Awaitable[None]]) -> None:
        """Register callback for when a device is discovered."""
        self._on_device_found = callback

    def on_device_lost(self, callback: Callable[[str], Awaitable[None]]) -> None:
        """Register callback for when a device goes offline."""
        self._on_device_lost = callback

    async def start(self) -> bool:
        """Start Zeroconf discovery. Returns True if successful."""
        try:
            self._zeroconf = Zeroconf()
            self._service_info = KarinServiceInfo(self.config).build()

            self._zeroconf.register_service(self._service_info, allow_name_change=True)
            logger.info("Zeroconf: Registered service for %s", self.config.device_name)

            self._browser = ServiceBrowser(
                self._zeroconf,
                KarinServiceInfo.SERVICE_TYPE,
                handlers=[self._on_service_added, self._on_service_removed],
            )
            self._running = True
            logger.info("Zeroconf: Discovery started")
            return True

        except Exception as e:
            logger.warning("Zeroconf failed to start: %s", e)
            return False

    async def stop(self) -> None:
        """Stop Zeroconf discovery and deregister."""
        self._running = False
        if self._service_info and self._zeroconf:
            try:
                self._zeroconf.unregister_service(self._service_info)
            except Exception:
                pass
        if self._browser:
            try:
                self._browser.cancel()
            except Exception:
                pass
        if self._zeroconf:
            try:
                self._zeroconf.close()
            except Exception:
                pass
        logger.info("Zeroconf: Discovery stopped")

    def _on_service_added(self, zeroconf: Zeroconf, type_: str, name: str) -> None:
        """Handle a new service appearing on the network."""
        try:
            info = zeroconf.get_service_info(type_, name)
            if info is None:
                return
            device = self._parse_service_info(info)
            if device and device.uuid != self.config.device_uuid:
                self._devices[device.uuid] = device
                logger.info("Zeroconf: Found device %s (%s)", device.name, device.ip_address)
                if self._on_device_found:
                    loop = asyncio.get_event_loop()
                    loop.call_soon_threadsafe(
                        asyncio.ensure_future,
                        self._on_device_found(device)
                    )
        except Exception as e:
            logger.debug("Zeroconf: Error parsing service %s: %s", name, e)

    def _on_service_removed(self, zeroconf: Zeroconf, type_: str, name: str) -> None:
        """Handle a service disappearing from the network."""
        for uuid, device in list(self._devices.items()):
            if device.name in name:
                del self._devices[uuid]
                logger.info("Zeroconf: Device lost %s", device.name)
                if self._on_device_lost:
                    loop = asyncio.get_event_loop()
                    loop.call_soon_threadsafe(
                        asyncio.ensure_future,
                        self._on_device_lost(uuid)
                    )
                break

    def _parse_service_info(self, info: ServiceInfo) -> Optional[DeviceInfo]:
        """Parse ServiceInfo into a DeviceInfo model."""
        try:
            props = info.properties
            ip_address = "0.0.0.0"
            if info.addresses:
                ip_address = socket.inet_ntoa(info.addresses[0])

            device_type_str = props.get(b"type", b"PC").decode("utf-8")
            try:
                dt = DeviceType(device_type_str)
            except ValueError:
                dt = DeviceType.UNKNOWN

            return DeviceInfo(
                uuid=props.get(b"uuid", b"").decode("utf-8"),
                name=props.get(b"name", b"Unknown").decode("utf-8"),
                user_name=props.get(b"user", b"User").decode("utf-8"),
                device_type=dt,
                app_version=props.get(b"version", b"1.0.0").decode("utf-8"),
                api_port=int(props.get(b"port", b"7800").decode("utf-8")),
                status=DeviceStatus.AVAILABLE,
                ip_address=ip_address,
                is_online=True,
            )
        except Exception as e:
            logger.debug("Error parsing service info: %s", e)
            return None

    @property
    def discovered_devices(self) -> dict[str, DeviceInfo]:
        return dict(self._devices)

    def remove_device(self, device_uuid: str) -> None:
        """Manually remove a device from the discovered list."""
        self._devices.pop(device_uuid, None)
