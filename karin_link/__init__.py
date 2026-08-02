"""
KARIN Link - Module for automatic device discovery and communication.

Similar to AirDrop/Nearby Share/Steam Link, KARIN Link discovers devices
on the local network running KARINFLiX and enables sharing, rooms, and
synchronized playback.

Architecture:
    discovery.py   - mDNS/Zeroconf device discovery
    broadcast.py   - UDP broadcast fallback
    websocket.py   - WebSocket server for real-time sync
    api.py         - FastAPI REST endpoints
    qr.py          - QR code generation for episode sharing
    models.py      - Pydantic data models
    database.py    - SQLite async database
    heartbeat.py   - Device heartbeat system
    rooms.py       - Room/sync management
    security.py    - UUID/token authentication
    config.py      - Configuration management
    utils.py       - Utility functions
    server.py      - Main entry point
    client.py      - Client for connecting to other instances
"""

__version__ = "1.0.0"
__app_name__ = "KARIN Link"
