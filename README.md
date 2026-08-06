<div align="center">

# KarinFLiX

![Android](https://img.shields.io/badge/Android-6.0%2B-3ddc84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7f52ff?logo=kotlin&logoColor=white)
![Versión](https://img.shields.io/badge/versi%C3%B3n-1.3.0-blue)
![Min SDK](https://img.shields.io/badge/minSdk-23-green)

**Reproductor de anime con multirrastreo, mejoras de imagen y sonido en tiempo real, y sincronización entre dispositivos — diseñado para Android TV y cajas de streaming modestas.**

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

- **Imagen**: upscaling (incluye Anime4K DoG y FSR), interpolación a 60 fps, ajustes de nitidez/color.
- **Sonido**: sintetizador de subgraves, bajo virtual, ecualización por perfiles y detección automática del tipo de bocina.
- **Red**: enlaces LAN entre dispositivos para compartir capítulos, salas de visualización y sincronizar el historial sin servidor central.

La interfaz está pensada para **mando remoto / gamepad**, con una variante Leanback (Android TV) incluida.

---

## Características

### Reproducción (Media3 / ExoPlayer)
- Reproducción nativa con soporte **HLS** y múltiples formatos.
- **Selector de calidad**: 480p / 720p / 1080p… por video o global (es el tope *máximo*, se puede bajar en cualquier momento).
- **Selector de códec**: decodificador hardware del equipo o el software de Google (fallback).
- **Escala / upscaling**: Apagado, Bilineal, Bicúbico, **Anime4K DoG** y **FSR**.
- **Interpolación de movimiento a 60 fps**: `MotionX2`, `Frame x2`, `Suavizado`, `Doubling + Micro-Blend`.
- **Escalado dinámico de resolución (DRS)**: baja la resolución de *dibujado* automáticamente cuando el equipo no da abasto, sin tocar la decodificación.
- Reproducción de **videos locales** (explorador de archivos e intención `video/*`).

### Sonido mejorado (DSP en tiempo real)
- Sintetizador de **subgraves** (extiende las notas graves que la bocina no puede reproducir).
- **Bajo virtual (VBass)** para parlantes pequeños.
- **Detección automática del equipo**: TV, bocina / barra de sonido 5.1, auriculares.
- **Perfiles de audio**: Auto, Anime, Cine, Bass Boost, 3D Surround, Diálogos/Noticias, Música y True MaxBass.
- Importación de **archivos IR** para simular la respuesta de otra bocina.

### Búsqueda y navegación
- **Multirrastreo**: agrega múltiples fuentes en una sola interfaz (JKAnime, LatAnime, Frikiserie, LaCartoons, MundoDonghua, DoramaYt, RetroTV…).
- **Calendario de estrenos** con tabs por día.
- **Historial de reproducción** con reanudación por capítulo y minuto.
- **Colas / maratón**: reproducción automática del siguiente capítulo.
- Búsqueda por voz y **búsqueda difusa** (tolerante a errores).
- Eliminación automática de **publicidad y avisos** en las páginas de las fuentes.

### KARIN Link (red local)
- **Salas de visualización**: crea o únete a una sala por **QR** o *deep link* (`karinflinx://room/…`).
- **Enviar un capítulo** a otro dispositivo de la red y que se reproduzca automáticamente.
- **Sincronización del historial** entre dispositivos.
- Descubrimiento automático de dispositivos en la LAN (NSD), sin servidor central.

### Comunidad (descentralizada)
- Comentarios y votos por serie/episodio, guardados localmente y **propagados entre dispositivos** vía KARIN Link.
- Sin servidor: cada nodo guarda su copia y la reparte.

### Robustez
- Manejo de sitios protegidos por **Cloudflare** con resolución vía WebView (acotada para no agotar la memoria).
- **Caché de imágenes** en disco + memoria (LRU) con reducción de tamaño en caliente.
- Registro de errores local (crash logger).
- Interfaz pensada para **mando**, gamepad y TV (Leanback).

---

## Stack tecnológico

| Área | Tecnología |
|---|---|
| Lenguaje | Kotlin 1.9 |
| Reproducción | **Media3 (ExoPlayer) 1.9.3** — exoplayer, hls, ui, leanback, effect, cronet |
| Extracción de video | **MoonGetter** (core + server-bundle), Rhino 1.7.14 (JS sin WebView) |
| HTTP | OkHttp 4.12.0 |
| HTML | jsoup 1.17.2 |
| Asincronía | Kotlin Coroutines 1.7.3 (+ kotlinx-serialization) |
| UI | AppCompat, Material, RecyclerView, **Leanback / tvprovider** |
| QR | ZXing core 3.5.3 |
| Pruebas | JUnit 4.13.2, Robolectric 4.11.1 |
| Build | Gradle (AGP), Java 11, viewBinding + buildConfig |

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
├── player/        Reproducción: ExoPlayerActivity, CodecSelectorFactory, TrackSelectorFactory,
│                  Media3SixtyFpsProcessor (escala + 60 fps + DRS), VideoEnhanceConfig (DSP),
│                  VideoDataSource y MegaDecryptingDataSource.
├── karinlink/     Red local: LinkServer/LinkClient, DiscoveryManager (NSD),
│                  RoomManager (salas), KarinLinkActivity (QR / deep link).
├── community/     Almacén descentralizado de comentarios y votos (CommunityStore).
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
       ├─ TrackSelectorFactory  → calidad (tope máximo)
       ├─ CodecSelectorFactory  → códec hw/sw
       └─ Media3SixtyFpsProcessor
             ├─ Escala (Bilineal/Bicúbico/Anime4K DoG/FSR)
             ├─ Interpolación a 60 fps + DRS
             └─ DSP de audio (subgraves, VBass, perfiles, IR)
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

---

## Licencia

Este proyecto se distribuye tal cual, sin licencia pública declarada. Consulta al autor para cualquier uso o redistribución.

---

<div align="center">

Hecho con ❤️ para Android TV y cajas de streaming.  
Problemas o ideas → abre un *issue* en este repositorio.

</div>
