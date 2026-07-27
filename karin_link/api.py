"""
KARIN Link FastAPI REST endpoints.

All HTTP API endpoints for device management, episode sharing,
room control, and QR generation. Fully async using FastAPI.
"""

import logging
import time
from typing import Optional

from fastapi import FastAPI, WebSocket, Query, HTTPException, Header, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel

from .config import LinkConfig
from .database import Database
from .security import SecurityManager
from .rooms import RoomManager
from .heartbeat import HeartbeatManager
from .discovery import ZeroconfDiscovery
from .broadcast import BroadcastDiscovery
from .websocket import KarinWebSocketServer
from .qr import QRGenerator
from .models import (
    AuthRequest, AuthResponse, DeviceInfo, EpisodeInfo,
    ShareRequest, ShareResponse, Room, RoomInvite, RoomCommand,
    QRData, DeviceStatus,
)
from .utils import get_local_ip, get_device_type, get_os_info, generate_device_name

logger = logging.getLogger("karin_link.api")


def create_app(
    config: LinkConfig,
    database: Database,
    security: SecurityManager,
    room_manager: RoomManager,
    heartbeat: HeartbeatManager,
    zeroconf: Optional[ZeroconfDiscovery],
    broadcast: Optional[BroadcastDiscovery],
    ws_server: KarinWebSocketServer,
) -> FastAPI:
    """Create and configure the FastAPI application."""

    app = FastAPI(
        title="KARIN Link",
        description="KARINFLiX device discovery and sync API",
        version="1.0.0",
        docs_url="/docs",
        redoc_url="/redoc",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    qr_gen = QRGenerator(config)

    # ── Auth dependency ────────────────────────────────────

    async def verify_token(authorization: Optional[str] = Header(None)) -> str:
        if not authorization:
            raise HTTPException(status_code=401, detail="Missing authorization token")
        token = authorization.replace("Bearer ", "")
        device_uuid = security.validate_token(token)
        if not device_uuid:
            raise HTTPException(status_code=401, detail="Invalid or expired token")
        return device_uuid

    # ── Auth endpoints ─────────────────────────────────────

    @app.post("/auth", response_model=AuthResponse, tags=["Auth"])
    async def authenticate(request: AuthRequest) -> AuthResponse:
        """Authenticate a device and receive a session token."""
        device = DeviceInfo(
            uuid=request.device_uuid,
            name=request.device_name or generate_device_name(),
            user_name=request.user_name or "User",
            device_type=get_device_type(),
            os_info=get_os_info(),
            api_port=config.api_port,
            ip_address=get_local_ip(),
            last_seen=time.time(),
            is_online=True,
        )
        await database.upsert_device(device)
        auth_response = security.authenticate(request)
        logger.info("Auth: Device %s authenticated", request.device_uuid[:8])
        return auth_response

    @app.post("/auth/revoke", tags=["Auth"])
    async def revoke_auth(device_uuid: str = Query(...), device_id: str = Depends(verify_token)) -> dict:
        """Revoke all tokens for a device."""
        count = security.revoke_all_tokens(device_uuid)
        return {"revoked": count}

    # ── Device endpoints ───────────────────────────────────

    @app.get("/devices", tags=["Devices"])
    async def list_devices(device_id: str = Depends(verify_token)) -> list[dict]:
        """List all discovered online devices."""
        devices = await database.get_online_devices()
        local_ip = get_local_ip()
        this_device = DeviceInfo(
            uuid=config.device_uuid,
            name=config.device_name,
            user_name=config.user_name,
            device_type=config.device_type,
            os_info=get_os_info(),
            app_version=config.app_version,
            api_port=config.api_port,
            status=DeviceStatus.AVAILABLE,
            ip_address=local_ip,
            is_online=True,
        )
        result = [this_device.model_dump(mode="json")]
        for d in devices:
            if d.uuid != config.device_uuid:
                result.append(d.model_dump(mode="json"))
        return result

    @app.get("/devices/{device_uuid}", tags=["Devices"])
    async def get_device(device_uuid: str, device_id: str = Depends(verify_token)) -> dict:
        """Get info about a specific device."""
        if device_uuid == config.device_uuid:
            return DeviceInfo(
                uuid=config.device_uuid,
                name=config.device_name,
                user_name=config.user_name,
                device_type=config.device_type,
                os_info=get_os_info(),
                app_version=config.app_version,
                api_port=config.api_port,
                ip_address=get_local_ip(),
            ).model_dump(mode="json")
        device = await database.get_device(device_uuid)
        if not device:
            raise HTTPException(status_code=404, detail="Device not found")
        return device.model_dump(mode="json")

    @app.post("/devices/heartbeat", tags=["Devices"])
    async def heartbeat_endpoint(
        device_uuid: str = Query(...),
        device_id: str = Depends(verify_token),
    ) -> dict:
        """Receive a heartbeat from a device."""
        await heartbeat.record_heartbeat(device_id)
        return {"ok": True}

    # ── Share endpoints ────────────────────────────────────

    @app.post("/share", response_model=ShareResponse, tags=["Share"])
    async def share_episode(request: ShareRequest, device_id: str = Depends(verify_token)) -> ShareResponse:
        """Share an episode info with another device."""
        target_device = await database.get_device(request.target_device_uuid)
        if not target_device:
            return ShareResponse(success=False, message="Target device not found")

        if not target_device.is_online:
            return ShareResponse(success=False, message="Target device is offline")

        success = await ws_server.manager.send_to(request.target_device_uuid, {
            "type": "share.episode",
            "payload": {
                "from_device": device_id,
                "episode": request.episode.model_dump(mode="json"),
                "message": request.message,
            },
        })

        if success:
            await database.add_history(device_id, request.episode, request.target_device_uuid)
            return ShareResponse(
                success=True,
                message="Episode shared successfully",
                session_id=f"{device_id[:8]}-{request.target_device_uuid[:8]}",
            )
        return ShareResponse(success=False, message="Failed to send to device")

    # ── Room endpoints ─────────────────────────────────────

    @app.post("/rooms", tags=["Rooms"])
    async def create_room(
        episode: Optional[EpisodeInfo] = None,
        name: str = Query(default=""),
        device_id: str = Depends(verify_token),
    ) -> dict:
        """Create a new playback room."""
        device = await database.get_device(device_id)
        host_name = device.user_name if device else "Host"
        room = room_manager.create_room(device_id, host_name, episode, name)
        return room.model_dump(mode="json")

    @app.get("/rooms", tags=["Rooms"])
    async def list_rooms(device_id: str = Depends(verify_token)) -> list[dict]:
        """List all active rooms."""
        rooms = room_manager.get_active_rooms()
        return [r.model_dump(mode="json") for r in rooms]

    @app.get("/rooms/{room_id}", tags=["Rooms"])
    async def get_room(room_id: str, device_id: str = Depends(verify_token)) -> dict:
        """Get room details."""
        room = room_manager.get_room(room_id)
        if not room:
            raise HTTPException(status_code=404, detail="Room not found")
        return room.model_dump(mode="json")

    @app.post("/rooms/{room_id}/invite", tags=["Rooms"])
    async def invite_to_room(
        room_id: str,
        target_uuid: str = Query(...),
        device_id: str = Depends(verify_token),
    ) -> dict:
        """Invite a device to a room."""
        room = room_manager.get_room(room_id)
        if not room:
            raise HTTPException(status_code=404, detail="Room not found")
        if room.host_uuid != device_id:
            raise HTTPException(status_code=403, detail="Only host can invite")

        sent = await ws_server.manager.send_to(target_uuid, {
            "type": "room.invite",
            "payload": {
                "room_id": room_id,
                "host_name": room.host_name,
                "room_name": room.name,
            },
        })
        return {"sent": sent}

    @app.post("/rooms/{room_id}/join", tags=["Rooms"])
    async def join_room(room_id: str, device_id: str = Depends(verify_token)) -> dict:
        """Join an existing room."""
        success = room_manager.join_room(room_id, device_id)
        if not success:
            raise HTTPException(status_code=404, detail="Room not found")
        room = room_manager.get_room(room_id)
        await ws_server.manager.broadcast_to_room(room_id, {
            "type": "room.device_joined",
            "payload": {"device_uuid": device_id},
        })
        return room.model_dump(mode="json") if room else {"ok": True}

    @app.post("/rooms/{room_id}/leave", tags=["Rooms"])
    async def leave_room(room_id: str, device_id: str = Depends(verify_token)) -> dict:
        """Leave a room."""
        room_manager.leave_room(room_id, device_id)
        await ws_server.manager.broadcast_to_room(room_id, {
            "type": "room.device_left",
            "payload": {"device_uuid": device_id},
        })
        return {"ok": True}

    @app.post("/rooms/{room_id}/play", tags=["Rooms"])
    async def room_play(room_id: str, position: float = 0.0, device_id: str = Depends(verify_token)) -> dict:
        """Play in room."""
        room = room_manager.get_room(room_id)
        if not room or room.host_uuid != device_id:
            raise HTTPException(status_code=403, detail="Only host can control")
        room_manager.update_playback(room_id, DeviceStatus.PLAYING, position)
        await ws_server.manager.broadcast_to_room(room_id, {
            "type": "room.sync",
            "payload": {"command": "play", "position": position, "room_id": room_id},
        }, exclude=device_id)
        return {"ok": True}

    @app.post("/rooms/{room_id}/pause", tags=["Rooms"])
    async def room_pause(room_id: str, position: float = 0.0, device_id: str = Depends(verify_token)) -> dict:
        """Pause in room."""
        room = room_manager.get_room(room_id)
        if not room or room.host_uuid != device_id:
            raise HTTPException(status_code=403, detail="Only host can control")
        room_manager.update_playback(room_id, DeviceStatus.BUSY, position)
        await ws_server.manager.broadcast_to_room(room_id, {
            "type": "room.sync",
            "payload": {"command": "pause", "position": position, "room_id": room_id},
        }, exclude=device_id)
        return {"ok": True}

    @app.post("/rooms/{room_id}/seek", tags=["Rooms"])
    async def room_seek(room_id: str, position: float = 0.0, device_id: str = Depends(verify_token)) -> dict:
        """Seek in room."""
        room = room_manager.get_room(room_id)
        if not room or room.host_uuid != device_id:
            raise HTTPException(status_code=403, detail="Only host can control")
        room_manager.update_playback(room_id, DeviceStatus.PLAYING, position)
        await ws_server.manager.broadcast_to_room(room_id, {
            "type": "room.sync",
            "payload": {"command": "seek", "position": position, "room_id": room_id},
        }, exclude=device_id)
        return {"ok": True}

    # ── QR endpoints ───────────────────────────────────────

    @app.post("/qr/episode", tags=["QR"])
    async def generate_episode_qr(
        episode: EpisodeInfo,
        size: int = Query(default=300, le=1000),
        device_id: str = Depends(verify_token),
    ) -> StreamingResponse:
        """Generate a QR code for an episode."""
        img_bytes = qr_gen.generate_episode_qr_bytes(episode, size)
        return StreamingResponse(
            iter([img_bytes]),
            media_type="image/png",
            headers={"Content-Disposition": "inline; filename=karinflix_qr.png"},
        )

    @app.post("/qr/decode", tags=["QR"])
    async def decode_qr(data: QRData, device_id: str = Depends(verify_token)) -> dict:
        """Decode QR data from a scanned code."""
        return {"episode_url": data.episode_url, "anime": data.anime_title}

    # ── WebSocket endpoint ─────────────────────────────────

    @app.websocket("/ws")
    async def websocket_endpoint(
        websocket: WebSocket,
        token: str = Query(...),
    ) -> None:
        """WebSocket endpoint for real-time communication."""
        await ws_server.websocket_endpoint(websocket)

    # ── Health ─────────────────────────────────────────────

    @app.get("/health", tags=["System"])
    async def health() -> dict:
        return {
            "status": "ok",
            "device_uuid": config.device_uuid,
            "connections": ws_server.manager.active_connections,
            "version": config.app_version,
        }

    @app.get("/", tags=["System"])
    async def root() -> dict:
        return {
            "name": "KARIN Link",
            "version": config.app_version,
            "docs": "/docs",
        }

    return app
