"""
KARIN Link configuration management.

Handles all configurable parameters with sensible defaults.
Supports loading from environment variables and JSON config files.
"""
import json
import os
import secrets
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional


@dataclass
class LinkConfig:
    """Central configuration for KARIN Link."""

    # Device identity
    device_uuid: str = ""
    device_name: str = "KARINFLiX Device"
    user_name: str = "User"
    device_type: str = "PC"
    app_version: str = __version__

    # Network
    api_host: str = "0.0.0.0"
    api_port: int = 7800
    broadcast_port: int = 7801
    zeroconf_type: str = "_karinflix._tcp.local."
    multicast_group: str = "224.0.0.251"
    multicast_port: int = 5353

    # Heartbeat
    heartbeat_interval: float = 10.0
    heartbeat_timeout: float = 30.0

    # Security
    token_expiry: int = 3600
    token_secret: str = ""

    # Database
    db_path: str = "karin_link.db"

    # QR
    qr_uri_scheme: str = "karinflix://watch"

    # Remote (future)
    remote_enabled: bool = False
    relay_server: str = ""

    def __post_init__(self) -> None:
        if not self.device_uuid:
            self.device_uuid = self._load_or_create_uuid()
        if not self.token_secret:
            self.token_secret = os.environ.get("KARIN_TOKEN_SECRET", secrets.token_hex(32))
        self._load_from_env()

    def _load_or_create_uuid(self) -> str:
        """Load existing UUID from config file or generate a new one."""
        config_path = Path(self.db_path).parent / "karin_link_config.json"
        if config_path.exists():
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if "device_uuid" in data and data["device_uuid"]:
                    return data["device_uuid"]
            except (json.JSONDecodeError, OSError):
                pass
        new_uuid = str(secrets.token_urlsafe(16))
        self._save_uuid_to_config(new_uuid)
        return new_uuid

    def _save_uuid_to_config(self, uuid_str: str) -> None:
        """Persist UUID to config file."""
        config_path = Path(self.db_path).parent / "karin_link_config.json"
        config_path.parent.mkdir(parents=True, exist_ok=True)
        data = {"device_uuid": uuid_str}
        if config_path.exists():
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                data["device_uuid"] = uuid_str
            except (json.JSONDecodeError, OSError):
                pass
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)

    def _load_from_env(self) -> None:
        """Override configuration from environment variables."""
        env_map = {
            "KARIN_DEVICE_NAME": ("device_name", str),
            "KARIN_USER_NAME": ("user_name", str),
            "KARIN_DEVICE_TYPE": ("device_type", str),
            "KARIN_API_PORT": ("api_port", int),
            "KARIN_BROADCAST_PORT": ("broadcast_port", int),
            "KARIN_API_HOST": ("api_host", str),
            "KARIN_DB_PATH": ("db_path", str),
            "KARIN_REMOTE_ENABLED": ("remote_enabled", lambda v: v.lower() in ("true", "1", "yes")),
            "KARIN_TOKEN_SECRET": ("token_secret", str),
            "KARIN_TOKEN_EXPIRY": ("token_expiry", int),
            "KARIN_HEARTBEAT_INTERVAL": ("heartbeat_interval", float),
            "KARIN_HEARTBEAT_TIMEOUT": ("heartbeat_timeout", float),
        }
        for env_key, (attr, converter) in env_map.items():
            val = os.environ.get(env_key)
            if val is not None:
                try:
                    setattr(self, attr, converter(val))
                except (ValueError, TypeError):
                    pass

    def to_dict(self) -> dict:
        """Serialize config to dictionary."""
        return asdict(self)

    @classmethod
    def from_file(cls, path: str) -> "LinkConfig":
        """Load configuration from a JSON file."""
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})

    def save(self, path: Optional[str] = None) -> None:
        """Save configuration to a JSON file."""
        target = path or str(Path(self.db_path).parent / "karin_link_config.json")
        Path(target).parent.mkdir(parents=True, exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, indent=2)