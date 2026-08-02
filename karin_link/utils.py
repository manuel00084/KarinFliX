"""
KARIN Link utility functions.

Common helpers used across the KARIN Link module.
"""

import asyncio
import platform
import socket
import time
from typing import Optional


def get_local_ip() -> str:
    """Get the local IP address of this machine."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.5)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def get_device_type() -> str:
    """Detect device type from the current platform."""
    system = platform.system().lower()
    if system == "windows":
        try:
            import wmi
            c = wmi.WMI()
            for item in c.Win_ComputerSystem():
                model = item.Model.lower()
                if "laptop" in model or "notebook" in model or "thinkpad" in model:
                    return "Laptop"
        except Exception:
            pass
        return "PC"
    elif system == "linux":
        try:
            with open("/proc/version", "r") as f:
                version = f.read().lower()
            if "android" in version:
                return "Android"
        except Exception:
            pass
        import os
        if os.path.exists("/system/app/") or os.path.exists("/system/priv-app/"):
            return "Android"
        return "Linux"
    elif system == "darwin":
        import platform as pf
        machine = pf.machine().lower()
        if "ipad" in machine or "iphone" in machine:
            return "Tablet" if "ipad" in machine else "iOS"
        return "Mac"
    return "Unknown"


def get_os_info() -> str:
    """Get a readable OS information string."""
    system = platform.system()
    release = platform.release()
    version = platform.version()
    return f"{system} {release} ({version})"


def generate_device_name() -> str:
    """Generate a default device name from hostname."""
    try:
        hostname = socket.gethostname()
        if hostname:
            return hostname
    except Exception:
        pass
    return f"KARINFLiX-{get_device_type()}"


def is_port_available(port: int, host: str = "0.0.0.0") -> bool:
    """Check if a port is available for binding."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1)
        s.bind((host, port))
        s.close()
        return True
    except OSError:
        return False


def find_available_port(start: int = 7800, end: int = 7900) -> int:
    """Find an available port in the given range."""
    for port in range(start, end):
        if is_port_available(port):
            return port
    return start


def time_since(timestamp: float) -> float:
    """Return seconds since a given timestamp."""
    return time.time() - timestamp


async def run_periodic(callback, interval: float, *args, **kwargs) -> None:
    """Run a callback periodically with the given interval."""
    while True:
        try:
            result = callback(*args, **kwargs)
            if asyncio.iscoroutine(result):
                await result
        except asyncio.CancelledError:
            break
        except Exception:
            pass
        await asyncio.sleep(interval)
