<div align="center">

# KarinFLiX

![Android](https://img.shields.io/badge/Android-6.0%2B-3ddc84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7f52ff?logo=kotlin&logoColor=white)
![Versión](https://img.shields.io/badge/versi%C3%B3n-1.3.0-blue)
![Min SDK](https://img.shields.io/badge/minSdk-23-green)

**Reproductor de anime con multirrastreo, mejoras de imagen y sonido en tiempo real, y envío de episodios entre dispositivos — diseñado para Android TV y cajas de streaming modestas.**

</div>

---

## Índice

1. [Descripción](#descripción)
2. [Características](#características)
3. [Stack tecnológico](#stack-tecnológico)
4. [Arquitectura](#arquitectura)
5. [Estructura del proyecto](#estructura-del-proyecto)
6. [Cómo compilar](#cómo-compilar)
7. [Cómo firmar el APK](#cómo-firmar-el-apk)
8. [Rendimiento en equipos modestos](#rendimiento-en-equipos-modestos)
9. [Aviso legal](#aviso-legal)

---

## Descripción

**KarinFLiX** es una aplicación Android para ver anime en streaming desde múltiples fuentes a la vez, con énfasis en **funcionar bien en hardware modesto**: Android TV, Fire TV, cajas con 1-2 GB de RAM y decodificación limitada.

No es un reproductor pasivo: agrega varios sitios en una sola interfaz, elimina anuncios y avisos de las páginas, extrae los enlaces reales de video (sin depender de WebView), y luego **procesa la imagen y el sonido en tiempo real** dentro de la app para mejorar la experiencia:

- **Imagen**: upscaling (incluye KarinSuperRes, Anime4K DoG y FSR), interpolación a 60 fps, ajustes de nitidez/color.
- **Sonido**: sintetizador de subgraves, bajo virtual, ecualización por perfiles y detección automática del tipo de bocina.
- **Red**: enlaces LAN entre dispositivos para enviar capítulos a otro equipo (mando desde el teléfono a la TV) y ver archivos de la red local, sin servidor central.

La interfaz está pensada para **mando remoto / gamepad**, con una variante Leanback (Android TV) incluida.

---

## Características

### Reproducción (Media3 / ExoPlayer)
- Reproducción nativa con soporte **HLS** y múltiples formatos.
- **Selector de calidad**: 480p / 720p / 1080p… por video o global (es el tope *máximo*, se puede bajar en cualquier momento).
- **Selector de códec**: decodificador hardware del equipo o el software de Google (fallback).
- **Escala / upscaling**: Apagado, **KarinSuperRes** (default: nítido, sin halos ni ruido, DRS-aware, variante por gama), **FSR** (calidad/bajo consumo) y **Anime4K DoG**.
- **Interpolación de movimiento a 60 fps**: `MotionX2`, `Frame x2`, `Suavizado`, `Doubling + Micro-Blend`.
- **Escalado dinámico de resolución (DRS)**: baja la resolución de *dibujado* automáticamente cuando el equipo no da abasto, sin tocar la decodificación.
- Reproducción de **videos locales** (explorador de archivos e intención `video/*`).

### Sonido mejorado (DSP en tiempo real)
- Sintetizador de **subgraves** (extiende las notas graves que la bocina no puede reproducir).
- **Bajo virtual (VBass)** para parlantes pequeños.
- **Detección automática del equipo**: TV, bocina / barra de sonido 5.1, auriculares.
- **Perfiles de audio**: Auto, Anime, Surround Envolvente, Bass Boost, Diálogos/Noticias, Música y True MaxBass.
- Importación de **archivos IR** para simular la respuesta de otra bocina.

### Búsqueda y navegación
- **Multirrastreo**: agrega múltiples fuentes en una sola interfaz (JKAnime, LatAnime, Frikiserie, LaCartoons, MundoDonghua, DoramaYt, RetroTV…).
- **Calendario de estrenos** con tabs por día.
- **Historial de reproducción** con reanudación por capítulo y minuto.
- **Colas / maratón**: reproducción automática del siguiente capítulo.
- Búsqueda por voz y **búsqueda difusa** (tolerante a errores).
- Eliminación automática de **publicidad y avisos** en las páginas de las fuentes.

### KARIN Link (red local)
- **Enviar un capítulo** a otro dispositivo de la red y que se reproduzca automáticamente.
- **Cola de reproducción**: "reproducir ahora" sustituye lo que suena y "añadir a la cola"
  lo deja terminar para que empiece el siguiente. Saltar y quitar desde la pantalla de la TV.
- **Descubrimiento automático de dispositivos** en la LAN (NSD), sin servidor central.
- **Acceso a archivos remotos** entre dispositivos: carpeta compartida por HTTP
  (`/fs`) con token, solo lectura, en el propio puerto del enlace.
- **Subir un vídeo del móvil** a la TV (`POST /push`) para los casos sin enlace directo:
  se reproduce y se borra al terminar. Es la única vía que escribe en disco.

### Emisión a otro dispositivo (DLNA / Cast)
- Botón **"Emitir"** en el reproductor: manda el vídeo actual a un televisor,
  decodificador o barra de sonido en lugar de reproducirlo aquí.
- **DLNA propio, sin dependencias**: descubrimiento por SSDP
  (`M-SEARCH` a `239.255.255.250:1900`) y control por SOAP
  (`SetAVTransportURI` + `Play`/`Pause`/`Stop`) contra el `AVTransport` del receptor.
- **Google Cast** con `play-services-cast-framework` y el receptor por defecto
  de Google: el selector de dispositivos es el del propio sistema.
- **Archivos locales**: un receptor DLNA no abre rutas de disco, así que el
  archivo se sirve por HTTP con soporte de `Range` (sin `206` muchas TVs no
  hacen seek ni arrancan).

### Robustez
- Manejo de sitios protegidos por **Cloudflare** con resolución vía WebView (acotada para no agotar la memoria).
- **Caché de imágenes** en disco + memoria (LRU) con reducción de tamaño en caliente.
- Registro de errores local (crash logger).
- Interfaz pensada para **mando**, gamepad y TV (Leanback).

---

## Stack tecnológico

| Área | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.2.0 |
| Reproducción | **Media3 (ExoPlayer) 1.11.0** — exoplayer, hls, ui, leanback, effect, cronet, transformer |
| Extracción de video | **MoonGetter 2.0.0-alpha01** (core + server-bundle), Rhino 1.7.14 (JS sin WebView) |
| HTTP | OkHttp 4.12.0 |
| HTML | jsoup 1.17.2 |
| Asincronía | Kotlin Coroutines 1.7.3 (+ kotlinx-serialization 1.7.3) |
| UI | AppCompat 1.6.1, Material 1.11.0, RecyclerView 1.3.2, **Leanback / tvprovider** |
| Red local / archivos | jcifs-ng 2.1.10 (SMB), NSD (mDNS), WebSocket propio |
| Pruebas | JUnit 4.13.2, Robolectric 4.11.1 |
| Build | Gradle con AGP 8.10.1, Java 11, viewBinding + buildConfig |

> La reproducción es **nativa** (Media3): no se usa FFmpeg ni WebView para extraer los enlaces de video.

---

## Arquitectura

La app se organiza en paquetes con responsabilidades claras:

```
com.karin.streamtv
├── scraper/       Motor de rastreo: ScrapingEngine, GenericScraper, ScraperRegistry,
│                  parsers por sitio (JKAnime, LatAnime, Frikiserie, LaCartoons,
│                  MundoDonghua, DoramaYt, RetroTV…), CalendarParser, ServerExtractor
│                  y resolución de servidores (ServerDirectResolver, ServerResolutionDetector).
├── extractor/     Extracción de URLs reales de video (MoonGetter).
├── player/        Reproducción: ExoPlayerActivity, CodecSelectorFactory, KarinAudioProcessor (DSP),
│                  efectos GL (Depixel, Detail Boost/RCAS, Karin Light Boost, Colors Boost,
│                  MotionX2 Boost) y los diálogos de ajustes/stats del reproductor.
├── karinlink/     Red local: LinkServer/LinkClient, DiscoveryManager (NSD),
│                  y KarinLinkActivity (enviar episodios entre dispositivos).
├── ui/            Actividades: Main, SiteBrowser, SeriesDetail, Settings, Calendar,
│                  Tutorial, Onboarding, FileExplorer… + variante TV (ui/tv).
├── util/          Infraestructura: caché de imágenes, interceptor Cloudflare,
│                  historial, colas, búsqueda, HtmlClean, preferencias.
├── model/         Modelos de dominio (Series, Episode, VideoSource, VideoServer…).
└── share/         Compartición entre actividades.
```

Flujo principal de un capítulo:

```
Navegación → ScraperRegistry (jsoup) → lista de episodios
   → ServerExtractor (MoonGetter/Rhino) → URL de video real
   → ExoPlayerActivity (Media3)
       ├─ CodecSelectorFactory   → códec hw/sw
       ├─ DepixelBoostEffect     → reducción de artefactos + debanding (Weber-Fechner)
       ├─ DetailBoostEffect      → nitidez RCAS (AMD FidelityFX)
       ├─ KarinLightBoostEffect  → luz/contraste/HDR + Colors Boost fusionado (half-res)
       ├─ MotionX2BoostEffect    → fluidez (mezcla con el cuadro previo)
       └─ KarinAudioProcessor    → DSP de audio (graves, claridad, potencia, EQ)
```

---

## Estructura del proyecto

```
KarinFLiX/
├── app/
│   ├── build.gradle              Configuración de compilación y dependencias
│   ├── proguard-rules.pro        Reglas R8/ProGuard
│   ├── karintv.keystore          Keystore de firma (NO está en el repositorio)
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/karin/streamtv/   Código fuente (ver Arquitectura)
│           └── res/              Recursos, layouts, tema, strings
├── gradle/                       Wrapper y config del build
├── build.gradle                  Build raíz
├── settings.gradle               Definición de módulos
└── README.md
```

---

## Cómo compilar

**Requisitos**

- JDK 11 o superior.
- Android SDK (compileSdk 36 / targetSdk 34).
- Android Studio (o Gradle CLI).

**Pasos**

```bash
# 1. Clona el repositorio
git clone https://github.com/manuel00084/KarinFliX.git
cd KarinFLiX

# 2. Compila el APK de debug (ver "Cómo firmar" si pide keystore)
./gradlew assembleDebug
```

El APK se genera en `app/build/outputs/apk/`.

> **Importante**: el `build.gradle` referencia el archivo `karintv.keystore` para firmar (incluso el build de debug). Si no existe, crea uno o proporciona el tuyo (ver abajo).

**Tests unitarios**

```bash
./gradlew test
```

**Compilar solo Kotlin (verificación rápida)**

```bash
./gradlew :app:compileDebugKotlin
```

---

## Cómo firmar el APK

La configuración de firma en `app/build.gradle` es:

```groovy
signingConfigs {
    release {
        storeFile file("karintv.keystore")
        storePassword findProperty("KARIN_STORE_PASSWORD") ?: "karintv2026"
        keyAlias findProperty("KARIN_KEY_ALIAS") ?: "karintv"
        keyPassword findProperty("KARIN_KEY_PASSWORD") ?: "karintv2026"
    }
}
```

Opciones para compilar sin exponer el keystore del proyecto:

```bash
# 1) Generar un keystore propio
keytool -genkeypair -v -keystore karintv.keystore \
        -alias karintv -keyalg RSA -keysize 2048 -validity 10000

# 2) (Opcional) usar variables en lugar de los valores por defecto
./gradlew assembleRelease \
    -PKARIN_STORE_PASSWORD=tu_password \
    -PKARIN_KEY_ALIAS=tu_alias \
    -PKARIN_KEY_PASSWORD=tu_password
```

> **Recomendación de seguridad**: no compartas keystores ni contraseñas en el repositorio. Si publicas el proyecto, mueve las credenciales a variables de entorno o a `gradle.properties` local (ignorado por git).

---

## Rendimiento en equipos modestos

KarinFLiX está optimizado para cajas con poca RAM/CPU:

- **Caché de imágenes** en memoria (LRU) + disco, con reducción de tamaño (inSampleSize) en las imágenes calientes.
- **WebViews de Cloudflare acotados**: se resuelve como máximo **2 captchas a la vez** y con tiempo límite por intento (evita agotar la RAM).
- **Descarga en paralelo** con control de concurrencia (páginas de episodios, servidores, candidatos de favicon).
- **Escalado dinámico de resolución (DRS)**: si el dibujado se retrasa, baja la resolución de render.
- **Interpolación de 60 fps y upscaling bajo demanda**: ambas opciones se apagan con un clic; en equipos flojos se recomienda 60 fps = Apagado y Escala = Apagado.
- **Destrucción explícita** de adaptadores y recursos al cerrar pantallas (sin fugas de memoria).

**Receta para gama baja**: Calidad 720p/480p + Escala Apagado + 60 fps Apagado → reproducción fluida en la mayoría de cajas.

---

## Aviso legal

KarinFLiX **no aloja ni distribuye contenido audiovisual**. Es un agregador que, bajo petición explícita del usuario, se conecta a sitios de terceros y extrae los enlaces públicos que estos exponen. La app incluye herramientas para eliminar anuncios de esas páginas y procesar el video localmente.

- **Tú eres responsable** de las fuentes que configures y de la legalidad de su uso en tu país.
- Este proyecto no está afiliado, respaldado ni patrocinado por ninguna de las fuentes compatibles.
- Las marcas comerciales pertenecen a sus respectivos dueños.

Úsalo solo para contenido del que tengas derecho a disfrutar.

Documentos legales del proyecto:

- **Términos y Condiciones v1.1** — en la app (bienvenida y Ajustes) y en `app/src/main/res/layout/activity_terms.xml`.
- **[Aviso de Privacidad](AVISO_DE_PRIVACIDAD.md)** — qué datos se guardan localmente, red LAN y derechos ARCO.
- **[Descargo de Responsabilidad](DESCARGO_DE_RESPONSABILIDAD.md)** — uso bajo tu propio riesgo, código abierto auditable y reporte de bugs en Issues.

---

## Licencia

Este proyecto se distribuye bajo la licencia [MIT](LICENSE).

---

## 🖥️ KARIN Link — P2P nativo

Sincronización y control remoto entre dispositivos KarinFLiX, implemented
enteramente en Kotlin dentro de la app. No hay backend, ni servidor externo, ni
dependencia de Python: los equipos se descubren entre ellos en la red local.

### Arquitectura

- **Descubrimiento** con NSD (mDNS) en `_karinflix._tcp`
- **Transporte** WebSocket sobre un servidor propio, sin librerías de por medio
- **Emparejado** por código de 6 caracteres, con caducidad de 5 minutos
- **Autenticación** por clave derivada con HKDF-SHA256 y firma HMAC de cada mensaje
- **Servicio en primer plano** para que el socket sobreviva en segundo plano
- **Cola de reproducción** en el propio proceso: `PlaybackQueue` decide qué va
  después y `QueueHub` lo ejecuta, así que la lógica se prueba sin reproductor
- **El fin de un vídeo** lo avisa `ExoPlayerActivity` con el id del elemento que
  está sonando; si ese id ya no es el primero, el aviso se ignora, porque
  mientras sonaba alguien pudo quitarlo o cambiar la cola

### Protocolo v2

Cada mensaje es un sobre con versión, tipo, emisor, destinatario, marca de
tiempo, id anti-replay y firma:

```json
{ "v": 2, "t": "sync", "id": "…", "from": "…", "to": "…", "ts": 0, "sig": "…", "d": { } }
```

El handshake es `hello` → `hello.ack`, y a partir de ahí la firma es
obligatoria. Se rechazan relojes desviados más de 5 minutos, ids repetidos y
destinatarios que no somos nosotros.

### Emparejado

1. En Ajustes → KARIN Link, genera un código o escribe el que muestra el otro equipo.
2. El código debe estar puesto en **los dos** equipos: la clave se deriva del
   código y los identificadores de ambos.
3. El primero que se conecta valida al otro y queda emparejado para siempre.
4. Para revocar un emparejamiento, límpialo desde la lista de equipos de confianza.

### Estructura del módulo

```
app/src/main/java/com/karin/streamtv/karinlink/
├── protocol/
│   ├── LinkProtocol.kt    Sobre, rutas y versión
│   ├── Pairing.kt         Códigos, HKDF, HMAC, Base64
│   ├── PeerRegistry.kt    Identidad y tabla de confianza
│   ├── LinkSession.kt     Máquina de estados y reglas de seguridad
│   ├── WsFrameParser.kt   Codec WebSocket RFC 6455
│   └── JsonPayload.kt     Accesores estrictos del payload
├── LinkServer.kt          Servidor WebSocket
├── LinkClient.kt          Cliente WebSocket
├── DiscoveryManager.kt    Registro y browse NSD
├── KarinLinkHost.kt       Servidor a nivel de aplicación
├── KarinLinkService.kt    Servicio en primer plano
├── KarinLinkManager.kt    Estado y orquestación para la UI
├── RemoteInput.kt         Límite de frames y diff de texto del mando
├── KarinLinkQueueActivity.kt  Cola en pantalla, pensada para el mando
├── queue/
│   ├── PlaybackQueue.kt   La cola: decide qué va después
│   ├── QueueHub.kt        Une la cola con el reproductor real
│   ├── QueueProtocol.kt   Mensajes de queue.play / queue.add / ...
│   └── QueueCommands.kt   Reparto de cada mensaje
└── upload/
    └── UploadStore.kt     Dónde caen los vídeos subidos y se borran
```

```
app/src/main/java/com/karin/streamtv/cast/
├── DlnaProtocol.kt         SSDP, descripción UPnP y envelopes SOAP (puro, sin Android)
├── DlnaDiscovery.kt        M-SEARCH multicast + lectura del controlURL
├── DlnaController.kt       SetAVTransportURI / Play / Pause / Stop
├── CastOptionsProvider.kt  Receptor por defecto, leído desde el manifest
├── EphemeralMediaServer.kt Sirve un fichero local por HTTP con soporte de Range
├── CastSession.kt          Qué se está emitiendo (sobrevive a la pantalla)
└── KarinLinkCastActivity.kt  La pantalla "Emitir": DLNA y Cast juntos
```


### Seguridad

- La clave de cada pareja se deriva con HKDF-SHA256 a partir del código y de
  ambos identificadores de dispositivo
- Todos los mensajes van firmados; el handshake es el único punto sin firma
- Ventana temporal de 5 minutos y caché de 512 ids contra repeticiones
- Un frame sin máscara o un protocolo distinto cierran la conexión
- Las claves se sellan con AES-GCM bajo una clave del Android Keystore; el
  valor en disco es `v1:<iv>:<cifrado>` y las claves antiguas en claro se
  re-sellan solas al leerlas

---

## 🧪 Testing

### Android (JUnit)
```bash
./gradlew test
./gradlew connectedDebugAndroidTest
```
- Tests unitarios con Robolectric
- Tests de instrumentación en dispositivos/emuladores

### Protocolo de KARIN Link
```bash
./gradlew :app:testDebugUnitTest --tests "com.karin.streamtv.karinlink.protocol.*"
```
- Cubre el sobre, el emparejado, el registro de confianza, la sesión, el codec
  WebSocket y los payloads
- `LinkServerHandshakeTest` habla por un socket real de loopback: handshake,
  máscara, rechazo y broadcast firmado

### Emisión DLNA / Cast
```bash
./gradlew :app:testDebugUnitTest --tests "com.karin.streamtv.cast.*"
```
- `DlnaProtocolTest`: el M-SEARCH, la lectura de la descripción del dispositivo
  (de ahí sale la `controlURL`), los envelopes SOAP y el escaping
- `EphemeralMediaServerTest` habla HTTP real contra un socket: 200 completo,
  206 con rango, sufijo, `HEAD`, 404/405/416

### Control remoto
```bash
./gradlew :app:testDebugUnitTest --tests "com.karin.streamtv.karinlink.RemoteInputTest"
```
- `RemoteInputTest`: la escritura en vivo (qué se manda al teclear, al borrar
  y al pegar) y el presupuesto compartido de frames del cursor y el scroll

---

## 🔒 Seguridad

### KARIN Link
- Claves derivadas con HKDF-SHA256; nunca se transmite el código de emparejado
- Firma HMAC obligatoria en todo mensaje posterior al handshake
- Antirrepetición por id de mensaje y ventana de reloj de 5 minutos
- Claves de emparejado selladas con AES-GCM en reposo, bajo una clave no
  exportable del Android Keystore
- El acceso remoto a archivos (`/fs`) es de solo lectura y exige a la vez
  interruptor activado, token válido y ruta dentro de una carpeta compartida
- La única escritura en disco es la subida de vídeo (`/push`): exige interruptor
  y token, impone `Content-Length` y un tope de tamaño, escribe a un temporal que
  se renombra al final, y el nombre que manda el otro equipo no decide la ruta
- Ese token se escribe a mano en el móvil en vez de repartirse por el enlace
  firmado: es lo único que protege `/fs`, y compartirlo convertiría a cualquier
  equipo emparejado en alguien con acceso a las carpetas compartidas
- Los vídeos subidos se borran al terminar o al quitarse de la cola, y lo que
  quedara de una sesión anterior se limpia al arrancar

### Buenas prácticas
- Sin secrets hardcodeados en el código
- `.gitignore` protege `*.keystore` y los ficheros de base de datos

---

## 🔄 CI/CD

GitHub Actions configurado con:
- **Android Build & Test** — compilación + tests unitarios
- **Security Scan** — secret scan
- **Deploy** — condicional a main

Ver: `.github/workflows/ci.yml`

---

## 🤝 Contribuir

Ver [CONTRIBUTING.md](CONTRIBUTING.md) para guías detalladas.

---

## 📊 Estado del Proyecto

| Componente | Estado |
|------------|--------|
| Android App | ✅ Activo |
| KARIN Link P2P | ✅ Nativo Kotlin |
| Tests | ✅ Cobertura completa |
| CI/CD | ✅ GitHub Actions |
| Documentación | ✅ README + Docs + Legal |
| Seguridad | ✅ HMAC + HKDF |

---

<div align="center">

Hecho con ❤️ para Android TV y cajas de streaming.  
Problemas o ideas → abre un *issue* en este repositorio.

</div>
