"""
KARIN Link async SQLite database.
"""
import asyncio
import logging
from typing import Optional, List, TYPE_CHECKING
import aiosqlite
from .config import LinkConfig
from .models import DeviceInfo, EpisodeInfo, DeviceType, DeviceStatus

if TYPE_CHECKING:
    pass

logger = logging.getLogger("karin_link.database")


class Database:
    """Async SQLite database for device and history management."""

    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self._conn: Optional[aiosqlite.Connection] = None

    async def init(self) -> None:
        """Initialize database tables."""
        self._conn = await aiosqlite.connect(self.db_path)
        await self._conn.execute("""
            CREATE TABLE IF NOT EXISTS devices (
                uuid TEXT PRIMARY KEY,
                name TEXT,
                user_name TEXT,
                device_type TEXT,
                os_info TEXT,
                app_version TEXT,
                api_port INTEGER,
                status TEXT,
                ip_address TEXT,
                last_seen REAL,
                is_online INTEGER DEFAULT 1
            )
        """)
        await self._conn.execute("""
            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_uuid TEXT,
                target_uuid TEXT,
                anime_title TEXT,
                season INTEGER,
                episode INTEGER,
                timestamp REAL
            )
        """)
        await self._conn.commit()
        logger.info("Database initialized: %s", self.db_path)

    async def close(self) -> None:
        """Close database connection."""
        if self._conn:
            await self._conn.close()
            self._conn = None

    async def upsert_device(self, device: DeviceInfo) -> None:
        """Insert or update a device."""
        if not self._conn:
            return
        await self._conn.execute("""
            INSERT OR REPLACE INTO devices
            (uuid, name, user_name, device_type, os_info, app_version, api_port, status, ip_address, last_seen, is_online)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            device.uuid, device.name, device.user_name, device.device_type.value,
            device.os_info, device.app_version, device.api_port, device.status.value,
            device.ip_address, device.last_seen, int(device.is_online)
        ))
        await self._conn.commit()

    async def get_device(self, uuid: str) -> Optional[DeviceInfo]:
        """Get a device by UUID."""
        if not self._conn:
            return None
        cursor = await self._conn.execute("SELECT * FROM devices WHERE uuid = ?", (uuid,))
        row = await cursor.fetchone()
        if row:
            return DeviceInfo(
                uuid=row[0], name=row[1], user_name=row[2],
                device_type=DeviceType(row[3]), os_info=row[4],
                app_version=row[5], api_port=row[6],
                status=DeviceStatus(row[7]), ip_address=row[8],
                last_seen=row[9], is_online=bool(row[10])
            )
        return None

    async def get_online_devices(self) -> List[DeviceInfo]:
        """Get all online devices."""
        if not self._conn:
            return []
        cursor = await self._conn.execute(
            "SELECT * FROM devices WHERE is_online = 1 AND uuid != ?", ("",)
        )
        rows = await cursor.fetchall()
        devices = []
        for row in rows:
            try:
                devices.append(DeviceInfo(
                    uuid=row[0], name=row[1], user_name=row[2],
                    device_type=DeviceType(row[3]), os_info=row[4],
                    app_version=row[5], api_port=row[6],
                    status=DeviceStatus(row[7]), ip_address=row[8],
                    last_seen=row[9], is_online=bool(row[10])
                ))
            except Exception:
                continue
        return devices

    async def mark_offline(self, uuid: str) -> None:
        """Mark device as offline."""
        if not self._conn:
            return
        await self._conn.execute(
            "UPDATE devices SET is_online = 0 WHERE uuid = ?", (uuid,)
        )
        await self._conn.commit()

    async def add_history(self, device_uuid: str, episode: EpisodeInfo, target_uuid: str) -> None:
        """Add a sharing history record."""
        if not self._conn:
            return
        await self._conn.execute("""
            INSERT INTO history (device_uuid, target_uuid, anime_title, season, episode, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (device_uuid, target_uuid, episode.anime_title, episode.season, episode.episode, episode.timestamp))
        await self._conn.commit()

    async def close_async(self) -> None:
        """Async close."""
        await self.close()