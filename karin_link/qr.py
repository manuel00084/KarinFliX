"""
KARIN Link QR code generation.
"""
import logging
import io
from typing import Optional
import qrcode
from PIL import Image
from .config import LinkConfig
from .models import EpisodeInfo

logger = logging.getLogger("karin_link.qr")


class QRGenerator:
    """Generates QR codes for episode sharing."""

    def __init__(self, config: LinkConfig) -> None:
        self.config = config

    def generate_episode_qr(self, episode: EpisodeInfo, size: int = 300) -> bytes:
        """Generate QR code bytes for an episode."""
        data = f"{self.config.qr_uri_scheme}?title={episode.anime_title}&s={episode.season}&e={episode.episode}"
        qr = qrcode.QRCode(version=1, box_size=10, border=2)
        qr.add_data(data)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        img = img.resize((size, size))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()

    def generate_episode_qr_bytes(self, episode: EpisodeInfo, size: int = 300) -> bytes:
        """Generate QR code bytes (alternative name)."""
        return self.generate_episode_qr(episode, size)