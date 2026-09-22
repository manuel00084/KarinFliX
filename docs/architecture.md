# Arquitectura de KarinFLiX

## Visión General

KarinFLiX es una aplicación de streaming de anime con dos componentes principales:

1. **App Android** — Reproductor con interfaz TV, extracción de video, DSP de audio
2. **Backend KARIN Link** — Servidor FastAPI para sync local entre dispositivos

## Diagrama de Arquitectura

```
┌─────────────────────────────────────────────────────────┐
│                    ANDROID TV / DEVICE                   │
│                                                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │  UI      │ │  Player  │ │  Scraper │ │KARIN Link │  │
│  │  (Leanback│ │(Media3)  │ │(Multi-   │ │(NSD/WS/   │  │
│  │  +TV)    │ │          │ │ source)  │ │ HTTP/QR)  │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬──────┘  │
│       │            │            │            │          │
│  ┌────▼────────────▼────────────▼────────────▼──────┐   │
│  │              ExoPlayer + DSP Pipeline             │   │
│  │  Video → Depixel → DetailBoost → MotionX2        │   │
│  │  Audio → SubGraves → VBass → EQ                   │   │
│  └───────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────┘
                         │ LAN / Internet
                         │
┌────────────────────────▼────────────────────────────────┐
│              KARIN LINK BACKEND (Python/FastAPI)         │
│                                                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Discovery│ │ WebSocket│ │  Rooms   │ │   API     │  │
│  │(Zeroconf │ │ Server   │ │ Manager  │ │ FastAPI   │  │
│  │+UDP)     │ │          │ │          │ │           │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬──────┘  │
│       │            │            │            │          │
│  ┌────▼────────────▼────────────▼────────────▼──────┐   │
│  │              Database (SQLite async)              │   │
│  │         Heartbeat │ Security │ QR Generator       │   │
│  └───────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

## Componentes Android

### Estructura de paquetes

```
com.karin.streamtv/
├── scraper/         Motor de rastreo web
│   ├── ScrapingEngine.java
│   ├── GenericScraper.java
│   ├── ScraperRegistry.java
│   ├── parsers/     Parsers por sitio (JKAnime, LatAnime, etc.)
│   ├── ServerExtractor.java
│   └── resolution/  Resolución de servidores
├── extractor/       Extracción de URLs con MoonGetter/Rhino
├── player/          Reproducción con Media3 y efectos GL
│   ├── ExoPlayerActivity.java
│   ├── CodecSelectorFactory.java
│   ├── KarinAudioProcessor.java (DSP)
│   └── effects/     Efectos GL
├── karinlink/       Red local: discovery, link server/client
├── ui/              Actividades principales
│   └── tv/          Variante Leanback para TV
├── model/           Modelos de dominio
├── share/           Compartición entre actividades
└── util/            Infraestructura (caché, preferencias)
```

### Flujo de reproducción

```
Usuario navega → ScraperRegistry (jsoup) → Lista de episodios
    → ServerExtractor (MoonGetter/Rhino) → URL de video real
    → ExoPlayerActivity (Media3)
        ├── CodecSelectorFactory   → hw/sw codec
        ├── DepixelBoostEffect     → reducción de artefactos
        ├── DetailBoostEffect      → nitidez RCAS
        ├── KarinLightBoostEffect  → luz/contraste/HDR
        ├── MotionX2BoostEffect    → fluidez 60fps
        └── KarinAudioProcessor    → DSP audio
```

## Componentes Backend (KARIN Link)

### Estructura de módulos

```
karin_link/
├── __init__.py      Inicialización del módulo
├── server.py        Punto de entrada principal
├── config.py        Configuración (dataclass con env vars)
├── api.py           Endpoints FastAPI REST
├── websocket.py     WebSocket server para sync tiempo real
├── models.py        Modelos Pydantic (data contracts)
├── database.py      SQLite async con aiosqlite
├── discovery.py     Zeroconf/mDNS discovery
├── broadcast.py     UDP broadcast fallback
├── security.py      Token auth con HMAC
├── rooms.py         Salas de reproducción sincronizada
├── heartbeat.py     Monitoreo de health de dispositivos
├── qr.py            Generación/decodificación QR
├── client.py        Cliente para conectar a otros nodos
├── utils.py         Utilidades (IP, OS info, device name)
└── tests/           Tests pytest
```

### Flujo de comunicación

```
Dispositivo A                    Servidor                     Dispositivo B
     │                           │                              │
     │─── Autenticación ────────▶│                              │
     │     (token)               │                              │
     │◀─── token ──────────────│                              │
     │                           │                              │
     │─── Enviar episodio ─────▶│                              │
     │                           │─── WebSocket broadcast ──────▶│
     │                           │                              │─── Reproducir
     │                           │                              │
     │─── Heartbeat ───────────▶│                              │
     │                           │─── Registrar estado ────────▶│
```

### Protocolo de discovery

```
1. Dispositivo inicia → genera UUID
2. Anuncia vía Zeroconf (_karinflix._tcp.local.)
3. Si Zeroconf falla → UDP broadcast en puerto 7801
4. Otros dispositivos escuchan → responden
5. Se conectan vía WebSocket a puerto 7800
6. Autentican con UUID + token
```

## Seguridad

### Modelo de autenticación

- Cada dispositivo tiene un UUID único
- Al autenticar, genera un token JWT-like con expiry
- Tokens validados con HMAC-SHA256
- CORS restrictivo en producción
- Secrets desde variables de entorno

### Configuración de seguridad

| Parámetro | Default | Descripción |
|-----------|---------|-------------|
| token_expiry | 3600s | Tiempo de vida del token |
| token_secret | env var | Clave HMAC |
| CORS origins | configurables | Orígenes permitidos |
| heartbeat_interval | 10s | Frecuencia de heartbeat |
| heartbeat_timeout | 30s | Tiempo antes de marcar offline |

## Escalabilidad

### Futuro remoto
- Relay server para dispositivos fuera de LAN
- Modo peer-to-peer con STUN/TURN
- Base de datos distribuida con sincronización
- Plugin system para nuevas fuentes

## Buenas prácticas seguidas

- Separación de concerns (cada módulo = responsabilidad única)
- Type hints en todo el código Python
- Pydantic para validación de datos
- Async/await para I/O no bloqueante
- Logging estructurado
- Environment variables para configuración
- `.env.example` sin secrets reales
- Convención de nombres clara
