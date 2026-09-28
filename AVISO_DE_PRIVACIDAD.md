# AVISO DE PRIVACIDAD — KarinFLiX

**Versión 1.1 — Septiembre 2026**
**Responsable: Manuel00084 — Correo: REEMPLAZAR_POR_TU_CORREO@tudominio.mx**

> Completa el correo antes de publicar. Sin correo este aviso no es válido bajo LFPDPPP.

## 1. Qué es KarinFLiX en materia de datos
KarinFLiX es **local-first y sin telemetría**. No operamos servidores de cuentas, analítica ni publicidad. No enviamos tus datos al desarrollador.

## 2. Datos que tratamos (solo en tu dispositivo)
| Dato | Dónde vive | Finalidad | Plazo |
|---|---|---|---|
| Historial, minuto de reanudación, colas, favoritos | SharedPreferences / BD local | Reanudar y maratón | Hasta que lo borres en Ajustes |
| Preferencias (calidad, códec, escala, audio, idioma) | SharedPreferences | Recordar configuración | Hasta reinstalar / borrar datos |
| Caché de imágenes, cookies WebView de sitios de terceros | App storage / WebView | Rendimiento y sesión en sitios que visitas | Se purga por LRU / al borrar datos |
| Crash logs locales | App storage | Diagnosticar fallos si tú los compartes | Hasta que los borres |
| Claves KARIN Link (emparejamientos, tokens) selladas con AES-GCM bajo Android Keystore | App storage | Emparejar tus dispositivos | Hasta revocar en lista de confianza |
| Videos subidos vía POST /push | Temporal app storage | Reproducir y borrar al terminar o al arrancar | Borrado automático |

**No recabamos:** nombre, email, ubicación GPS, contactos ni identificadores publicitarios.

## 3. Datos que circulan en tu red local (solo si activas KARIN Link)
Si activas KARIN Link, tus dispositivos intercambian en LAN: nombre de dispositivo, IP, IDs anti-replay, marcas de tiempo, estado de cola y tokens de `/fs` y `/push`. Todo va firmado con HMAC y con ventana de 5 min. No sale a internet. No actives carpetas compartidas con documentos personales.

## 4. Permisos Android y por qué se piden
- `INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE` — conectar a sitios que TÚ pides.
- `RECORD_AUDIO` — solo búsqueda por voz, si tocas el micrófono.
- `READ_EXTERNAL_STORAGE / READ_MEDIA_VIDEO / MANAGE_EXTERNAL_STORAGE` — reproducir TUS archivos locales.
- `CHANGE_WIFI_MULTICAST_STATE` — descubrimiento mDNS de TUS dispositivos.
- `FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK` — mantener KARIN Link vivo.
- `BLUETOOTH_CONNECT` — mostrar códec BT activo.
- `SYSTEM_ALERT_WINDOW, SET_WALLPAPER` — funciones opcionales de overlay y galería.

Puedes revocarlos; la función asociada dejará de operar.

## 5. Datos de terceros (sitios fuente)
Al navegar, cada sitio de terceros aplica su propia política de cookies y rastreo. KarinFLiX no los controla. El filtrado de publicidad reduce rastreo, pero no lo elimina. Revisa las políticas de cada sitio.

## 6. Finalidades
Proveer reproducción, recordar preferencias, operar KARIN Link en LAN y mejorar estabilidad localmente. No hay finalidades secundarias, no vendemos ni cedemos datos.

## 7. Transferencias
No hay transferencias a terceros. La única "transmisión" es entre tus propios dispositivos en tu LAN bajo tu control.

## 8. Derechos ARCO y revocación
Ejerce Acceso, Rectificación, Cancelación, Oposición y revocación escribiendo a **REEMPLAZAR_POR_TU_CORREO@tudominio.mx** con: (a) nombre, (b) medio de respuesta, (c) descripción del derecho. Respondemos en máximo 20 días hábiles (arts. 28-35 LFPDPPP). Como casi todo vive en tu equipo, puedes ejercer cancelación directamente borrando datos desde Ajustes del sistema o desinstalando.

## 9. Menores
No dirigido a menores de 13 años sin tutor. Si detectamos tratamiento indebido, lo suprimimos.

## 10. Cambios a este aviso
Publicaremos nueva versión fechada en `github.com/manuel00084/KarinFliX` y avisaremos en la app. Cambios materiales requieren nueva aceptación de Términos v1.1+.

## 11. Autoridad
INAI — inai.org.mx — si consideras vulnerados tus derechos.

---
*Teams: este archivo es informativo y no sustituye dictamen de abogado. Crea el correo, define al responsable y vincula este archivo desde Términos Cláusula 7 y desde Ajustes → Privacidad.*
