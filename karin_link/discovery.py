"""
KARIN Link device discovery via Zeroconf/mDNS.
"""
import logging
from typing import Optional, Callable
from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
from .config import LinkConfig
from .models import DeviceInfo

logger = logging.getLogger("karin_link.discovery")


class ZeroconfDiscovery:
    """Discover devices on the local network using Zeroconf/mDNS."""

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self.zeroconf: Optional[Zeroconf] = None
        self._browser: Optional[ServiceBrowser] = None
        self._on_found: Optional[Callable] = None
        self._on_lost: Optional[Callable] = None

    def on_device_found(self, callback: Callable) -> None:
        """Set callback for when a device is found."""
        self._on_found = callback

    def on_device_lost(self, callback: Callable) -> None:
        """Set callback for when a device goes offline."""
        self._on_lost = callback

    async def start(self) -> bool:
        """Start Zeroconf discovery."""
        try:
            self.zeroconf = Zeroconf()
            self._browser = ServiceBrowser(
                self.zeroconf,
                self.config.zeroconf_type,
                listener=_ZeroconfListener(self),
            )
            logger.info("Zeroconf discovery started: %s", self.config.zeroconf_type)
            return True
        except Exception as e:
            logger.error("Zeroconf discovery failed: %s", e)
            return False

    async def stop(self) -> None:
        """Stop Zeroconf discovery."""
        if self._browser:
            self._browser.cancel()
        if self.zeroconf:
            await self.zeroconf.aclose()
        logger.info("Zeroconf discovery stopped")

    def _handle_device_found(self, service):
        if self._on_found:
            device = DeviceInfo(
                uuid=service.name.split(".")[0],
                name=service.name,
                api_port=self.config.api_port,
            )
            self._on_found(device)

    def _handle_device_lost(self, service_type, name):
        if self._on_lost:
            self._on_lost(name)


class _ZeroconfListener(ServiceListener):
    """Zeroconf service listener wrapper."""

    def __init__(self, discovery: ZeroconfDiscovery) -> None:
        self.discovery = discovery

    def add_service(self, zc, service_type, name):
        self.discovery._handle_device_found(type('Service', (), {'name': name})())

    def remove_service(self, zc, service_type, name):
        self.discovery._handle_device_lost(service_type, name)

    def update_service(self, zc, service_type, name):
        pass