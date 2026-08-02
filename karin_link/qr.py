"""
KARIN Link QR code generation.

Generates QR codes for episode sharing using the karinflix:// URI scheme.
The QR contains episode information (not the video URL) and can be
scanned by another KARINFLiX instance to open the episode.
"""

import io
import logging
from typing import Optional

import qrcode
from qrcode.image.styledpil import StyledPilImage
from qrcode.image.styles.colormasks import SolidFillColorMask
from PIL import Image

from .models import EpisodeInfo, QRData
from .config import LinkConfig

logger = logging.getLogger("karin_link.qr")


class QRGenerator:
    """
    Generates QR codes for KARIN Link episode sharing.

    The QR encodes a karinflix://watch URI with episode metadata.
    Scanning it opens KARINFLiX directly to that episode.
    """

    def __init__(self, config: LinkConfig) -> None:
        self.config = config

    def generate_episode_qr(
        self,
        episode: EpisodeInfo,
        size: int = 300,
        include_logo: bool = False,
    ) -> Image.Image:
        """Generate a QR code for an episode."""
        uri = self._build_uri(episode)

        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=10,
            border=4,
        )
        qr.add_data(uri)
        qr.make(fit=True)

        qr_data = QRData(
            scheme=self.config.qr_uri_scheme,
            episode_url=episode.episode_url,
            anime_title=episode.anime_title,
            episode_title=episode.episode_title,
            server=episode.server,
            episode=episode.episode,
            poster_url=episode.poster_url,
            source_site=episode.source_site,
        )

        try:
            img = qr.make_image(
                image_factory=StyledPilImage,
                color_mask=SolidFillColorMask(
                    back_color=(255, 255, 255),
                    front_color=(0, 32, 96),
                ),
            )
        except Exception:
            img = qr.make_image(fill_color=(0, 32, 96), back_color=(255, 255, 255))

        if img.size[0] != size:
            img = img.resize((size, size), Image.LANCZOS)

        return img

    def generate_episode_qr_bytes(
        self,
        episode: EpisodeInfo,
        size: int = 300,
        format: str = "PNG",
    ) -> bytes:
        """Generate a QR code and return as bytes."""
        img = self.generate_episode_qr(episode, size)
        buf = io.BytesIO()
        img.save(buf, format=format)
        return buf.getvalue()

    def generate_device_qr(
        self,
        device_uuid: str,
        device_name: str,
        api_port: int,
        ip_address: str,
        size: int = 300,
    ) -> Image.Image:
        """Generate a QR code to connect to a device."""
        uri = f"karinflix://connect?uuid={device_uuid}&port={api_port}&host={ip_address}"

        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=10,
            border=4,
        )
        qr.add_data(uri)
        qr.make(fit=True)

        try:
            img = qr.make_image(
                image_factory=StyledPilImage,
                color_mask=SolidFillColorMask(
                    back_color=(255, 255, 255),
                    front_color=(108, 99, 255),
                ),
            )
        except Exception:
            img = qr.make_image(fill_color=(108, 99, 255), back_color=(255, 255, 255))

        if img.size[0] != size:
            img = img.resize((size, size), Image.LANCZOS)

        return img

    def decode_qr_uri(self, uri: str) -> Optional[QRData]:
        """Decode a karinflix:// URI into QRData."""
        if not uri.startswith("karinflix://"):
            return None

        try:
            from urllib.parse import urlparse, parse_qs
            parsed = urlparse(uri)
            params = parse_qs(parsed.query)

            return QRData(
                scheme=f"{parsed.scheme}://{parsed.netloc}",
                episode_url=params.get("url", [""])[0],
                anime_title=params.get("anime", [""])[0],
                episode_title=params.get("title", [""])[0],
                server=params.get("server", [""])[0],
                episode=int(params.get("ep", ["0"])[0]),
                poster_url=params.get("poster", [""])[0],
                source_site=params.get("site", [""])[0],
            )
        except Exception as e:
            logger.error("Failed to decode QR URI: %s", e)
            return None

    def _build_uri(self, episode: EpisodeInfo) -> str:
        """Build a karinflix://watch URI from episode info."""
        from urllib.parse import urlencode
        params = {
            "url": episode.episode_url,
            "anime": episode.anime_title,
            "title": episode.episode_title,
            "ep": str(episode.episode),
            "server": episode.server,
            "site": episode.source_site,
            "poster": episode.poster_url,
        }
        return f"{self.config.qr_uri_scheme}?{urlencode(params)}"
