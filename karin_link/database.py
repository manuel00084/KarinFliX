"""
KARIN Link SQLite database layer.

Async-compatible database for storing known devices, history,
and configuration. Uses aiosqlite under the hood.
"""

import time
import json
import aiosqlite
from pathlib import Path
from typing import List, Optional

from .models import DeviceInfo, DeviceType, DeviceStatus, EpisodeInfo


class Database:
    """Async SQLite database for KARIN Link persistence."""

    def __init__(self, db_path: str = "karin_link.db") -> None:
        self.db_path = db_path
        self._db: Optional[aiosqlite.Connection] = None

    async def init(self) -> None:
        """Initialize database connection and create tables."""
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = await aiosqlite.connect(self.db_path)
        self._db.row_factory = aiosqlite.Row
        await self._create_tables()

    async def _create_tables(self) -> None:
        if not self._db:
            return
        await self._db.executescript("""
            CREATE TABLE IF NOT EXISTS devices (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                user_name TEXT NOT NULL DEFAULT '',
                device_type TEXT NOT NULL DEFAULT 'PC',
                os_info TEXT NOT NULL DEFAULT '',
                app_version TEXT NOT NULL DEFAULT '1.0.0',
                api_port INTEGER NOT NULL DEFAULT 7800,
                status TEXT NOT NULL DEFAULT 'available',
                ip_address TEXT NOT NULL DEFAULT '',
                last_seen REAL NOT NULL DEFAULT 0,
                is_online INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_uuid TEXT NOT NULL,
                anime_title TEXT NOT NULL DEFAULT '',
                episode_title TEXT NOT NULL DEFAULT '',
                episode INTEGER NOT NULL DEFAULT 0,
                server TEXT NOT NULL DEFAULT '',
                language TEXT NOT NULL DEFAULT 'LAT',
                quality TEXT NOT NULL DEFAULT 'HD',
                timestamp REAL NOT NULL DEFAULT 0,
                shared_with TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (device_uuid) REFERENCES devices(uuid)
            );

            CREATE TABLE IF NOT EXISTS known_devices (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                user_name TEXT NOT NULL DEFAULT '',
                device_type TEXT NOT NULL DEFAULT 'PC',
                last_ip TEXT NOT NULL DEFAULT '',
                last_seen REAL NOT NULL DEFAULT 0,
                trust_level INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
        """)
        await self._db.commit()

    async def close(self) -> None:
        if self._db:
            await self._db.close()
            self._db = None

    # ── Devices ────────────────────────────────────────────

    async def upsert_device(self, device: DeviceInfo) -> None:
        if not self._db:
            return
        await self._db.execute("""
            INSERT INTO devices (uuid, name, user_name, device_type, os_info,
                app_version, api_port, status, ip_address, last_seen, is_online)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, user_name=excluded.user_name,
                device_type=excluded.device_type, os_info=excluded.os_info,
                app_version=excluded.app_version, api_port=excluded.api_port,
                status=excluded.status, ip_address=excluded.ip_address,
                last_seen=excluded.last_seen, is_online=excluded.is_online
        """, (
            device.uuid, device.name, device.user_name,
            device.device_type.value, device.os_info, device.app_version,
            device.api_port, device.status.value, device.ip_address,
            device.last_seen, int(device.is_online)
        ))
        await self._db.commit()

    async def mark_offline(self, device_uuid: str) -> None:
        if not self._db:
            return
        await self._db.execute(
            "UPDATE devices SET is_online=0, status='offline' WHERE uuid=?",
            (device_uuid,)
        )
        await self._db.commit()

    async def get_device(self, device_uuid: str) -> Optional[DeviceInfo]:
        if not self._db:
            return None
        cursor = await self._db.execute(
            "SELECT * FROM devices WHERE uuid=?", (device_uuid,)
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return self._row_to_device(row)

    async def get_online_devices(self) -> List[DeviceInfo]:
        if not self._db:
            return []
        cursor = await self._db.execute(
            "SELECT * FROM devices WHERE is_online=1 ORDER BY last_seen DESC"
        )
        rows = await cursor.fetchall()
        return [self._row_to_device(r) for r in rows]

    async def get_all_devices(self) -> List[DeviceInfo]:
        if not self._db:
            return []
        cursor = await self._db.execute(
            "SELECT * FROM devices ORDER BY last_seen DESC"
        )
        rows = await cursor.fetchall()
        return [self._row_to_device(r) for r in rows]

    def _row_to_device(self, row: aiosqlite.Row) -> DeviceInfo:
        dt_str = row["device_type"]
        try:
            dt = DeviceType(dt_str)
        except ValueError:
            dt = DeviceType.UNKNOWN
        return DeviceInfo(
            uuid=row["uuid"],
            name=row["name"],
            user_name=row["user_name"],
            device_type=dt,
            os_info=row["os_info"],
            app_version=row["app_version"],
            api_port=row["api_port"],
            status=DeviceStatus(row["status"]) if row["status"] in [s.value for s in DeviceStatus] else DeviceStatus.OFFLINE,
            ip_address=row["ip_address"],
            last_seen=row["last_seen"],
            is_online=bool(row["is_online"]),
        )

    # ── History ────────────────────────────────────────────

    async def add_history(self, device_uuid: str, episode: EpisodeInfo,
                          shared_with: str = "") -> None:
        if not self._db:
            return
        await self._db.execute("""
            INSERT INTO history (device_uuid, anime_title, episode_title,
                episode, server, language, quality, timestamp, shared_with)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            device_uuid, episode.anime_title, episode.episode_title,
            episode.episode, episode.server, episode.language,
            episode.quality, time.time(), shared_with
        ))
        await self._db.commit()

    async def get_history(self, limit: int = 50) -> list:
        if not self._db:
            return []
        cursor = await self._db.execute(
            "SELECT * FROM history ORDER BY timestamp DESC LIMIT ?",
            (limit,)
        )
        return [dict(r) for r in await cursor.fetchall()]

    # ── Known Devices ──────────────────────────────────────

    async def save_known_device(self, device: DeviceInfo) -> None:
        if not self._db:
            return
        await self._db.execute("""
            INSERT INTO known_devices (uuid, name, user_name, device_type,
                last_ip, last_seen, trust_level)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, last_ip=excluded.last_ip,
                last_seen=excluded.last_seen
        """, (
            device.uuid, device.name, device.user_name,
            device.device_type.value, device.ip_address, time.time()
        ))
        await self._db.commit()

    # ── Settings ───────────────────────────────────────────

    async def get_setting(self, key: str, default: str = "") -> str:
        if not self._db:
            return default
        cursor = await self._db.execute(
            "SELECT value FROM settings WHERE key=?", (key,)
        )
        row = await cursor.fetchone()
        return row["value"] if row else default

    async def set_setting(self, key: str, value: str) -> None:
        if not self._db:
            return
        await self._db.execute("""
            INSERT INTO settings (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
        """, (key, value))
        await self._db.commit()
