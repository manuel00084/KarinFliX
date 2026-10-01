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
├── cast/            Emisión externa: DLNA (SSDP + SOAP) y Google Cast
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

## Emisión externa (DLNA / Cast)

Cuando el vídeo debe sonar en otro aparato, el botón **"Emitir"** de la barra del
reproductor abre `KarinLinkCastActivity`. Dos tecnologías conviven ahí porque son
incompatibles entre sí y cada una tiene su forma de descubrir y de arrancar.

```
btn_cast (player_control_view.xml)
    └── KarinLinkCastActivity
        ├── MediaRouteButton + CastButtonFactory   Google Cast
        │     └── selector y diálogo del sistema; la sesión la lleva el framework
        └── DlnaDiscovery → DlnaController         DLNA, nuestro
              ├── M-SEARCH a 239.255.255.250:1900   → cabecera LOCATION
              ├── GET de la descripción UPnP         → controlURL de AVTransport
              └── SetAVTransportURI + Play           → el receptor baja la URL
```

- **`DlnaProtocol` no toca Android**: M-SEARCH, parseo de la descripción y
  envelopes SOAP. Ahí vive todo lo determinista, que es justo lo que se puede
  probar en la JVM sin un receptor en la red.
- **Cast no se puede elegir a mano**: `startSession(Intent)` es la única API
  pública y no selecciona rutas. La selección la hace el `MediaRouteButton`
  montado con `CastButtonFactory.setUpMediaRouteButton`; sin Play Services o sin
  el `OptionsProvider` se oculta y queda solo DLNA.
- **Los ficheros locales**: el receptor solo sabe bajar URLs, así que
  `EphemeralMediaServer` sirve el archivo con soporte de `Range` (sin `206`
  muchas TVs ni hacen seek). El servidor vive en `CastSession`, a nivel de
  proceso, para que el vídeo no se corte al cerrar la pantalla.

## Componentes KARIN Link (P2P nativo)

No hay backend. Cada dispositivo es a la vez servidor y cliente dentro de la LAN.

### Estructura de módulos

```
app/src/main/java/com/karin/streamtv/karinlink/
├── protocol/
│   ├── LinkProtocol.kt     Sobre, rutas, versión
│   ├── Pairing.kt          Códigos, HKDF, HMAC, Base64
│   ├── PeerRegistry.kt     Identidad y tabla de confianza
│   ├── LinkSession.kt      Máquina de estados y reglas de seguridad
│   ├── WsFrameParser.kt    Codec WebSocket RFC 6455
│   └── JsonPayload.kt      Accesores estrictos del payload
├── LinkServer.kt           Servidor WebSocket
├── LinkClient.kt           Cliente WebSocket
├── DiscoveryManager.kt     Registro y browse NSD
├── ServiceRecord.kt        Serialización de TXT
├── KarinLinkHost.kt        Servidor a nivel de aplicación
├── KarinLinkService.kt     Servicio en primer plano
├── KarinLinkManager.kt     Estado y orquestación para la UI
├── RemoteControlHub.kt     Despacho de comandos remotos
├── RemoteProtocol.kt       Tabla de comandos remotos
├── RemoteInput.kt          Límite de frames y diff de texto del mando
├── KarinLinkQueueActivity.kt  Cola en pantalla, para el mando
├── queue/
│   ├── PlaybackQueue.kt    La cola: decide qué va después
│   ├── QueueHub.kt         Une la cola con el reproductor real
│   ├── QueueProtocol.kt    queue.play / queue.add / queue.skip / ...
│   └── QueueCommands.kt    Reparto de cada mensaje
└── upload/
    └── UploadStore.kt      Vídeos subidos: dónde caen y cuándo se borran
```

```
app/src/main/java/com/karin/streamtv/cast/
├── DlnaProtocol.kt          SSDP, descripción UPnP y SOAP (puro)
├── DlnaDiscovery.kt         M-SEARCH multicast + lectura del controlURL
├── DlnaController.kt        SetAVTransportURI / Play / Pause / Stop
├── CastOptionsProvider.kt   Receptor por defecto, leído desde el manifest
├── EphemeralMediaServer.kt  Sirve un fichero local con soporte de Range
├── CastSession.kt           Qué se está emitiendo (a nivel de proceso)
└── KarinLinkCastActivity.kt La pantalla "Emitir"
```

### Cola de reproducción

La cola está partida en dos a propósito. `PlaybackQueue` es una lista pura que
devuelve qué hay que hacer (`Start`, `StopAndStart`, `Stop`) y se prueba en una
JVM normal; `QueueHub` es lo único que toca Activities, y solo existe porque una
cola se rompe en un dispositivo, no en una función.

```
queue.play / queue.add  ──▶ QueueCommands ──▶ QueueHub ──▶ PlaybackQueue
                                                             │
                              ExoPlayerActivity STATE_ENDED ──┘
                                    (con el id del elemento)
```

El aviso de fin llega tarde a propósito y por eso lleva el id del elemento que
estaba sonando: si mientras sonaba alguien lo quitó o cambió la cola, el id ya no
es el primero y el aviso se descarta en lugar de arrancar el vídeo equivocado.

`playNow` descarta lo pendiente, que es lo que espera quien manda otra cosa
mientras se ve algo; `add` no molesta al que suena. Un enlace directo va a
`ExoPlayerActivity` y un embed al WebView, que es el camino que ya existía.

### Subida de un vídeo

`POST /push` es el único endpoint que escribe. Comparte el interruptor y el
token de `/fs` (que es de solo lectura) en vez de inventar un segundo secreto, y
a cambio se endurece: `Content-Length` obligatorio, tope de tamaño, lectura exacta
de esa longitud, escritura a un temporal que se renombra al final, y nombre en
disco generado por la app. El nombre que manda el otro equipo solo se usa para
enseñarlo.

Los ficheros van a `filesDir/karin_uploads` y se borran al terminar el elemento
o al quitarlo de la cola; lo que quedara de una sesión anterior se limpia al
arrancar.

### Flujo de comunicación

Punto a punto, sin servidor central. El servidor acepta la conexión y valida al
 otro extremo; a partir de ahí ambos lados firman cada mensaje.

```
Dispositivo A (servidor)              Dispositivo B (cliente)
      │                                      │
      │◀──────── WebSocket ─────────────────▶│
      │                                      │
      │◀──────────── hello (sin firma) ──────│
      │                                      │
      │──── hello.ack (firmado) ────────────▶│  valida la firma
      │                                      │  guarda la clave
      │                                      │
      │◀──────── sync (firmado) ────────────▶│
      │                                      │──▶ reproducir
```

### Protocolo de discovery

```
1. El servicio registra _karinflix._tcp.local. con su puerto
2. Los TXT llevan nombre, versión, capacidades y huella de identidad
3. Otro dispositivo hace browse de NSD y resuelve un único servicio
4. Se conecta por WebSocket al puerto anunciado
5. Empareja (código compartido) o reconecta (ya está en la tabla de confianza)
```

## Seguridad

### Modelo de autenticación

- Cada dispositivo tiene un identificador estable en `PeerRegistry`
- El emparejado deriva la clave con HKDF-SHA256 a partir del código de 6
  caracteres y de ambos identificadores
- A partir del handshake, **todo** mensaje lleva firma HMAC-SHA256
- Ventana temporal de 5 minutos y caché de 512 ids contra repeticiones
- Un frame de cliente sin máscara, un protocolo distinto o una firma inválida
  cierran la conexión

### Parámetros

| Parámetro | Default | Descripción |
|-----------|---------|-------------|
| `PROTOCOL_VERSION` | 2 | Versión del sobre |
| `CLOCK_SKEW_MS` | 5 min | Desviación de reloj tolerada |
| `REPLAY_CACHE` | 512 ids | Mensajes recordados |
| `pendingPin` TTL | 5 min | Vigencia de un código de emparejado |
| `prune` | 180 días | Antigüedad máxima de un equipo de confianza |

### Pendiente

- Reproducir en el otro equipo: hoy el enlace comparte la URL de embed y
  transmite comandos, no los bytes del vídeo. Falta decidir entre proxy,
  remux o transcode
- El acceso a archivos resuelve una carrera de símbolos entre comprobar y
  abrir: quien controle la TV puede cambiar un enlace simbólico en ese hueco
- `ServerExtractionProbeTest > probe voe decrypt` requiere red y un fichero
  temporal previo, así que falla en un entorno sin salida

## Escalabilidad

### Futuro remoto
- Relay server para dispositivos fuera de LAN
- Acceso por QR hacia el relay, sin exponer la LAN
- Plugin system para nuevas fuentes

## Buenas prácticas seguidas

- Separación de concerns (cada módulo = responsabilidad única)
- `PeerRegistry` como única fuente de identidad
- Protocolo verificado con tests JVM y con un socket real de loopback
- Logging con `Log` y etiquetas por componente
- Sin secrets hardcodeados

- Convención de nombres clara
