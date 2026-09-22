"""
KARIN Link utility functions.
"""
import socket
import platform
import uuid
import logging

logger = logging.getLogger("karin_link.utils")


def get_local_ip() -> str:
    """Get the local IP address."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def get_device_type() -> str:
    """Detect the device type."""
    system = platform.system()
    if system == "Windows":
        return "PC"
    elif system == "Darwin":
        return "Mac"
    elif system == "Linux":
        return "Linux"
    return "PC"


def get_os_info() -> str:
    """Get OS information string."""
    return f"{platform.system()} {platform.release()}"


def generate_device_name() -> str:
    """Generate a unique device name."""
    return f"KARINFLiX-{uuid.uuid4().hex[:6].upper()}"