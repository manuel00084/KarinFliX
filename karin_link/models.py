"""
KARIN Link data models.

All Pydantic models used across the KARIN Link module.
Separates data contracts from business logic.
"""

import uuid
import time
from enum import Enum
from typing import Optional, List
from pydantic import BaseModel, Field


# ── Enums ──────────────────────────────────────────────────────

class DeviceType(str, Enum):
    """Supported device types."""
    PC = "PC"
    LAPTOP = "Laptop"
    ANDROID = "Android"
    TABLET = "Tablet"
    ANDROID_TV = "Android TV"
    IOS = "iOS"
    MAC = "Mac"
    LINUX = "Linux"
    UNKNOWN = "Unknown"


class DeviceStatus(str, Enum):
    """Device availability status."""
    AVAILABLE = "available"
    BUSY = "busy"
    PLAYING = "playing"
    OFFLINE = "offline"


class RoomState(str, Enum):
    """Room playback state."""
    IDLE = "idle"
    PLAYING = "playing"
    PAUSED = "paused"
    SEEKING = "seeking"


# ── Device Models ──────────────────────────────────────────────

class DeviceInfo(BaseModel):
    """Information about a discovered device."""
    uuid: str = Field(default_factory=lambda: str(uuid.uuid4()))
    name: str = "KARINFLiX Device"
    user_name: str = "User"
    device_type: DeviceType = DeviceType.PC
    os_info: str = ""
    app_version: str = "1.0.0"
    api_port: int = 7800
    status: DeviceStatus = DeviceStatus.AVAILABLE
    ip_address: str = ""
    last_seen: float = Field(default_factory=time.time)
    is_online: bool = True


class DeviceAnnounce(BaseModel):
    """Broadcast/Zeroconf announcement payload."""
    uuid: str
    name: str
    user_name: str
    device_type: str
    os_info: str
    app_version: str
    api_port: int
    status: str
    timestamp: float = Field(default_factory=time.time)


# ── Episode Models ─────────────────────────────────────────────

class EpisodeInfo(BaseModel):
    """Information about an episode to share (no video URL)."""
    anime_title: str = ""
    season: int = 0
    episode: int = 0
    server: str = ""
    language: str = "LAT"
    subtitles: str = "None"
    quality: str = "HD"
    timestamp: float = 0.0
    poster_url: str = ""
    episode_title: str = ""
    episode_url: str = ""
    source_site: str = ""


class ShareRequest(BaseModel):
    """Request to share an episode with a device."""
    target_device_uuid: str
    episode: EpisodeInfo
    message: str = ""


class ShareResponse(BaseModel):
    """Response after sharing an episode."""
    success: bool
    message: str = ""
    session_id: str = ""


# ── Room Models ────────────────────────────────────────────────

class Room(BaseModel):
    """A synchronized playback room."""
    id: str = Field(default_factory=lambda: str(uuid.uuid4())[:8])
    name: str = ""
    host_uuid: str = ""
    host_name: str = ""
    state: RoomState = RoomState.IDLE
    current_episode: Optional[EpisodeInfo] = None
    current_time: float = 0.0
    created_at: float = Field(default_factory=time.time)
    guests: List[str] = Field(default_factory=list)


class RoomInvite(BaseModel):
    """Request to invite a device to a room."""
    room_id: str
    target_device_uuid: str
    host_name: str = ""


class RoomCommand(BaseModel):
    """Synchronized playback command."""
    room_id: str
    command: str  # play, pause, seek, sync
    timestamp: float = 0.0
    position: float = 0.0
    sender_uuid: str = ""


# ── Auth Models ────────────────────────────────────────────────

class SessionToken(BaseModel):
    """Authentication token for a session."""
    token: str = Field(default_factory=lambda: str(uuid.uuid4()))
    device_uuid: str
    created_at: float = Field(default_factory=time.time)
    expires_at: float = 0.0

    def is_valid(self) -> bool:
        return time.time() < self.expires_at


class AuthRequest(BaseModel):
    """Authentication request."""
    device_uuid: str
    device_name: str = ""
    user_name: str = ""


class AuthResponse(BaseModel):
    """Authentication response."""
    success: bool
    token: str = ""
    expires_at: float = 0.0
    message: str = ""


# ── QR Models ──────────────────────────────────────────────────

class QRData(BaseModel):
    """Data encoded in a QR code."""
    scheme: str = "karinflix://watch"
    episode_url: str = ""
    anime_title: str = ""
    episode_title: str = ""
    server: str = ""
    episode: int = 0
    poster_url: str = ""
    source_site: str = ""
