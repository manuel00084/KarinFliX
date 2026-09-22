# Changelog

Todos los cambios notables de KarinFLiX se documentarán aquí.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y este proyecto sigue [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.0] - 2026-09-22

### Agregado
- Módulo backend Python `karin_link` para sincronización entre dispositivos
- API REST con FastAPI para descubrimiento de dispositivos vía Zeroconf
- Salas de reproducción sincronizadas con WebSocket
- Generación de QR codes para compartir episodios
- Sistema de heartbeat para monitoreo de dispositivos
- Detección automática de tipo de dispositivo y OS
- `.env.example` para configuración de entorno
- CI/CD con GitHub Actions

### Seguridad
- Token-based authentication con HMAC
- Revocación de sesiones
- Variables de entorno para secrets

## [1.3.0] - 2026-08-15

### Agregado
- Interpolación de movimiento a 60fps (`MotionX2`, `Frame x2`)
- Escalado dinámico de resolución (DRS)
- Perfiles de audio con detección automática de equipo
- Soporte de archivos IR para simulación de bocinas
- Multirrastreo con más fuentes (RetroTV, DoramaYt)
- Calendario de estrenos con tabs por día

### Cambiado
- Procesamiento de imagen mejorado con Webers-Fechner
- MoonGetter actualizado a 2.0.0-alpha01
- Compilación con AGP 8.9.1 y Kotlin 2.2.0

### Corregido
- Fugas de memoria en adaptadores de RecyclerView
- Resolución de servidores mejorada
- Crash al navegar a pantalla de archivos vacía

## [1.2.0] - 2026-07-01

### Agregado
- Compatibilidad con Media3 (ExoPlayer) 1.9.3
- efectos GL: Depixel, DetailBoost RCAS, KarinLightBoost
- Kotlin Coroutines para operaciones de red
- Caché de imágenes LRU en memoria y disco
- Interfaz Leanback para Android TV

### Cambiado
- Migración de WebView a Rhino para extracción JS
- Refactorización del scraper con patrón Registry

### Corregido
- Manejo de Cloudflare mejorado
- Eliminación de anuncios en fuentes

## [1.1.0] - 2026-05-15

### Agregado
- Extractor de video con MoonGetter
- Jitter reduction y debanding
- Selector de calidad por video y global
- Soporte de reproducción de archivos locales

### Corregido
- Crash en navegación de categorías
- Problemas de memoria con WebView

## [1.0.0] - 2026-04-01

### Agregado
- Lanzamiento inicial
- Reproductor nativo con Media3
- Multi-sourcing de anime (JKAnime, LatAnime, Frikiserie, LaCartoons)
- Interfaz de TV con soporte para mando
- Compilación de APK con firma

---

## [0.x] - Pre-lanzamiento

### Agregado
- Desarrollo de la arquitectura base
- Scraping inicial para fuentes de anime
- Interfaz básica de navegación
