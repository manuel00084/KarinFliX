"""
KARIN Link client for connecting to other instances.
"""
import logging
from typing import Optional
from httpx import AsyncClient, Timeout
from .config import LinkConfig
from .models import DeviceInfo

logger = logging.getLogger("karin_link.client")


class KarinLinkClient:
    """Client to connect to a remote KARIN Link server."""

    def __init__(self, config: LinkConfig) -> None:
        self.config = config
        self._http: Optional[AsyncClient] = None

    async def connect(self, host: str, port: int = 7800) -> bool:
        """Connect to a remote KARIN Link server."""
        base_url = f"http://{host}:{port}"
        self._http = AsyncClient(base_url=base_url, timeout=Timeout(5.0))
        try:
            response = await self._http.get("/health")
            if response.status_code == 200:
                logger.info("Connected to KARIN Link at %s", base_url)
                return True
        except Exception as e:
            logger.error("Connection failed: %s", e)
        return False

    async def authenticate(self, device_uuid: str) -> Optional[str]:
        """Authenticate and get a token."""
        if not self._http:
            return None
        try:
            response = await self._http.post("/auth", json={
                "device_uuid": device_uuid
            })
            if response.status_code == 200:
                return response.json()["token"]
        except Exception as e:
            logger.error("Auth failed: %s", e)
        return None

    async def list_devices(self, token: str) -> list:
        """List discovered devices."""
        if not self._http:
            return []
        try:
            response = await self._http.get("/devices", headers={"Authorization": f"Bearer {token}"})
            if response.status_code == 200:
                return response.json()
        except Exception as e:
            logger.error("List devices failed: %s", e)
        return []

    async def close(self) -> None:
        """Close the client."""
        if self._http:
            await self._http.aclose()