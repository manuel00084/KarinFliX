# Xuper TV — Dossier de Ingeniería Inversa

> Objetivo: reconstruir el cliente IPTV de Xuper (paquete `com.android.mgstv`) de forma
> independiente. Todo lo que sigue fue obtenido durante una sesión de análisis en
> LD Player 9 (Android 9, root) sobre la versión instalada **4.99.20** (versionCode **49920**).

---

## 1. Identidad de la app

| Campo            | Valor                                   |
|------------------|-----------------------------------------|
| Paquete          | `com.android.mgstv`                     |
| Etiqueta         | `Xuper`                                 |
| Versión instalada| 4.99.20 (versionCode 49920)             |
| APK en disco     | `XuperTV_oficial_TV_1764408199.apk` (4.34.4) |
| minSdk / targetSdk | 19 / 33                               |
| Firmas           | `apk: com.android.msandroid`, `apkVer: 49920` |
| Motor streaming  | AVIO/Luna + P2P (ranger): `[2.9.12-f98248e]-[4.4.3-0f8b900]-[ijk-4.4.4-…]-[4.13.9-…]` |

- `itconfig.sp` y `paconfig.sp` (configs cifradas del engine) están **vacías/encubiertas**.
- La app se actualiza sola (servidor de market) → la versión instalada puede ser más
  nueva que el APK oficial.

## 2. Protecciones (orden de dominancia)

1. **Empaquetado iJiami (爱加密)**:
   `assets/ijiami.ajm`, `assets/ijiami.dat`, `assets/IJMDal.Data`, `assets/signed.bin`.
   - `classes.dex` real es un stub (~13 KB).
   - El dex de negocio se descarga/descifra en runtime (iJiami + verificación de firma).
2. **Cifrado de config y de caché**: dominios en memoria cifrados; telemetría envía
   `{DES de dominio}` y `host` recifrado con AES. La caché del playador
   (`app_luna/block_cache/*.metainfo`, `.block_meta`) está cifrada.
3. **DNS-over-HTTPS**: DNS propio contra `8.8.8.8:443`, `cloudflare-dns`, `quad9`, `alidns`.
   Requiere apuntar manualmente el Nameserver `.sp` vacío → el DNS no se toca por UDP 53.
4. **Firma de peticiones**: query `md5=...`, JSON con `sn` (MD5 de datos de dispositivo),
   `userId` (sim riderId).
5. **Dominios rotatorios**: varios FQDN por rol, rotados periódicamente.
   Varios hosts ya no resuelven tras el análisis (`vahc4ktx.xyz`, `gn5h3hxar2k.com`).
6. **403 de Cloudflare** en `/epg/v2/live/app/utc-5/26` incluso replicando cabeceras
   exactas → la autorización no vive solo en el token firmado (sesión/SNI/IP).
7. Anti-debug: `assets/domain_test.json` con "Illegal users", `sign_verify.png`.

## 3. Descripción de roles por dominio (DomainCache `domain_test.json`)

Estructura JSON: `upgrade` `portal` `epg` `market` `epg4b_sn` `epg4b_data` `diamond`
`notice` `dccore` `datacollect` `ad` — cada rol con `main`/`backup`, valores ocultos
`"xx"`. Los endpoints descubiertos en captura:

| Rol            | Endpoint observado                                | Ejemplo de host         |
|----------------|---------------------------------------------------|-------------------------|
| epg (canales)  | `GET /epg/v2/live/app/utc-5/26`                   | `vgwbm.uwfyobivh.com`, `rokbd.ysrkwctjg.com` |
| market/update  | `GET /MarketServer/update?action=checkUpdate`     | `yefs.bjkmtnzgs.com` |
| descargas      | `http://yefs.bjkmtnzgs.com/marketdatas/apk/Xtv/{v}/Xtv_{v}.apk?cc=<b64>` | —      |
| vod            | `https://sqacjy.s5lm5aydc.xyz/public/images/…`   | `sqacjy.s5lm5aydc.xyz`  |
| ad             | `POST /api/adserver/v3/get_content`               | `yvhcn.hxjebagrv.com`   |
| datacollect    | `dcs_internal_main` (campo de telemetría)         | —                       |
| portal         | stream URL: `/live/{canal}_{res}/{canal}_{res}_shisui_{ts}.ts` | —       |
| diamond (auto-update portal) | `launch_diamond_*` addon | —                    |
| push (websocket)| `GET /v1/imagine`, `GET /v1/ws/{session}`         | `sgyc.bfj1k2g4v.com`, `s23sdf56.45lc9mx79ab.com` |
| epg4b          | `epg4b_sn`/`epg4b_data` (cache EPG)              | —      |

## 4. Hosts conocidos y resolución (durante el análisis)

| FQDN                        | IPs                                          |
|-----------------------------|----------------------------------------------|
| `yefs.bjkmtnzgs.com`        | 104.21.25.89 / 172.67.133.231                |
| `sqacjy.s5lm5aydc.xyz`      | 104.21.59.36 / 172.67.211.243                |
| `iyut.xgw3sdzoac.com`       | 104.21.26.200 / 172.67.168.132               |
| `nxiqj.jgrqyxupl.com`       | 104.21.47.134 / 172.67.148.10                |
| `sydrgt.a878kkoyc.com`      | 104.21.94.218 / 172.67.140.134               |
| `ftmrmy.jdfey0cd.com`       | 172.67.206.119 / 104.21.77.97                |
| `eskna.ucpjdhivl.com`       | 172.67.141.221 / 104.21.89.119               |
| `rokbd.ysrkwctjg.com`       | 172.67.135.132 / 104.21.6.249                |
| `vgwbm.uwfyobivh.com`       | 104.21.2.120 / 172.67.129.39                 |
| `yvhcn.hxjebagrv.com`       | 172.67.129.19 / 104.21.2.102                 |
| `zxiws.tcgwhnvym.com`       | 172.67.148.192 / 104.21.29.101               |
| `sgyc.bfj1k2g4v.com`        | 104.18.53.6 / 104.18.53.7                    |
| `s23sdf56.45lc9mx79ab.com`  | 146.71.124.186 / 23.239.109.66 / 104.250.134.94 |
| `yuwc.swzablvpm.com`        | 66.225.197.106 (API + VOD, `:80`)             |
| `bcdod.faolmbghu.com`       | 66.225.197.106 (stream `.ts` en vivo)          |
| `aluve.rdgqkfxio.com`       | (stream secundario)                            |
| `niguof.vynbszicd.com`      | (stream/API)                                   |
| `rjqcfy.3xfmjizq.xyz`       | (API)                                          |
| `changzhi.top` / `sg-datahub.changzhi.top` | 47.100.224.108, 47.117.165.236, 139.196.161.45, 139.224.193.219, 106.14.25.68 / 8.222.131.165, 47.245.87.130 |
| `nlyawhqs.vahc4ktx.xyz`     | ❌ rotado (no resuelve)                      |
| `neijbs.gn5h3hxar2k.com`    | ❌ rotado (no resuelve)                      |

- La mayoría detrás de **Cloudflare**. `changzhi.top` y `sg-datahub` en **Alibaba Cloud**.
- `s23sdf56.45lc9mx79ab.com` en **GorillaServers/Linode** (websocket push, sin CF).

## 5. Cabeceras y formato de petición

### GET `/epg/v2/live/app/utc-5/26` (lista de canales)
```
GET /epg/v2/live/app/utc-5/26?md5=966c1ba6e09b4d96-8617e8c0a0a21587 HTTP/1.1
NoLog: true
Content-Type: application/json;charset=utf-8
apk: com.android.msandroid
apkVer: 49920
spkgVer: 2026-07-13 17:48:35_28_9_4.4.146
Host: <dominio rotatorio>
Connection: Keep-Alive
Accept-Encoding: gzip
User-Agent: okhttp/3.12.12
```
Respuesta durante el análisis: **HTTP/1.1 403 Forbidden** (Cloudflare) → la sesión actual
no puede consultar EPG fuera de la resolución de dominios del día.

### GET `/MarketServer/update`
```
GET /MarketServer/update?action=checkUpdate&packagenamesAndVersioncodes=com.android.mgstv,49920&language=es&sn=<enc>&userId=961940146
```

### POST `/api/adserver/v3/get_content` (real, capturado)
```json
{"ad_version":"1.0","apk_versioncode":"49920","channel":"oficial1","os_version":"9",
 "osd_language":"es","pic_suport":"jpg/png/bmp","pkg":"com.android.mgstv",
 "platform":"android","sn":"ea163a392910a0c064f156aded6ef4a8","user_id":"961940146",
 "user_name":"","video_suport":"mp4/rmvb/flv"}
```

### Websockets (puerto 80, handshake okhttp)
- `GET /v1/imagine` → `101 Switching Protocols` (CF) — datos binarios.
- `GET /v1/ws/{sesión}` sobre `s23sdf56.45lc9mx79ab.com` → `101` — push de sesión.

## 6. Identidad de usuario (clave para firmar peticiones)

- `userId=961940146`, `sn=ea163a392910a0c064f156aded6ef4a8`, `channel=oficial1`.
- `portal_code=6e54356f76774c54574b303d` → hex→bytes → base64: `nT5ovwLTWK0=`.
- `force_bind_days=7`, `LAUNCH_STATISTICS=2`, `APP_SHOW_TIPS=0`.
- Solo se asignan tras aceptar el modal **"Vincular para usar Xuper gratuitamente"**
  (el botón "Vincular más tarde" también las asigna, con límite de días).

## 7. Streaming en vivo — EXTRACCIÓN CONFIRMADA (sin autenticación)

- **El stream en vivo es HTTP plano (puerto 80), sin token, sin User-Agent, sin cookie.**
  Verificado descargándolo desde un host ajeno al emulador:
  `GET http://bcdod.faolmbghu.com/live/cyx_50fdcc0817d61_720p/cyx_50fdcc0817d61_720p_shisui_305637000.ts`
  → **HTTP 200, `Content-Type: video/mp2t`, 1 580 140 bytes, sync `0x47` en idx 188** (MPEG-TS válido, reproducible).
- Plantilla: `http://{streamHost}/live/{canal}_{res}/{canal}_{res}_{prefijo}_{anchor_ms}.ts`
  - `{prefijo}` cambia según CDN/origen: `shisui`, `cyx_sp`, `cyx_cj`, `xycjco`.
  - `{anchor_ms}` avanza ~3000–6000 ms por segmento; cada segmento pesa ~1,5–3,3 MB
    (seg. últimos: `_305631000.ts` = 3 328 728 bytes, `_305637000.ts` = 1 580 140 bytes).
  - La app los pide secuencialmente cada ~1,5–2 s con `Connection: Keep-Alive` y
    `Range: bytes=...-` para reanudar; el servidor también sirve la pieza completa sin `Range`.
- **Hosts de stream (rotan por día/load-balance)**, todos servidos desde el origen `66.225.197.106`:
  | FQDN | rol |
  |------|-----|
  | `bcdod.faolmbghu.com` (66.225.197.106) | stream `.ts` (200/206) |
  | `aluve.rdgqkfxio.com` | stream (502 en el momento del test secundario) |
  | `niguof.vynbszicd.com` | stream/API (`:80`) |
  | `yuwc.swzablvpm.com` | API + VOD (`:80`) |
  | `rjqcfy.3xfmjizq.xyz` | API |
  | `opmrpybrdkvw`, `/1jji/bdbbnxdlfefc1ebm`, `/jbkddrycedfqwweumz`, etc. | roles obfuscados (EPG/portal), todos devuelven 200 |
- Canales/programas observados en tráfico vivo (IDs estables `cyx_*`):
  `cyx_50fdcc0817d61_720p` (LGVIP La Gala), `cyx_DA442D95A726FA05D4437`,
  `cyx_93531158996778016`, `cyx_0EA669A04973404263A`,
  `cyx-D08E1E3D47688ed3A7F115FEB16A`, `cyx-7BCA096543B49d9fF5E93008926A`.
- Lista de canales en la UI del Home: `LGVIP La Gala`, `LGVIP En Vivo 1-3`, `ECDF`,
  `ECDF FHD`, `Adult Swim`, `A&E FHD`, `AMC HD`, `AMC BO HD`, `AXN HD`, `AXN WHITE HD`,
  `NetFlix Eventos`.
- `POST /slb/v13/vod` (body de 896 B **cifrado**, cookies `d=`/`s=`/`t=`) → 200 con JSON:
  es el "load-balancer" del engine que entrega host/token de VOD. No hay equivalente `live`
  visible: los `.ts` en vivo no pasan por firma.
- El direccionamiento del host de stream del día y el `anchor_ms` los obtiene la app de su
  caché DNS/portal (cifrada) → para un cliente externo hace falta replicar esa resolución
  (o arrancar una captura breve del `dns_cache` del día).
- Cache local: `app_luna/block_cache/{hash}_media.ts/.metainfo` + bloques de 1 MiB cifrados.

## 7b. RESULTADO DE EXTRACCIÓN (catálogo en vivo)

- Catálogo de canales extraído de la lista de TV del Home (desplazando `mLvChannelList`):
  **738 nombres únicos** → `docs/XUPER_TV_canales.txt`. Ejemplos: `LGVIP La Gala`,
  `NBA Eventos`, `A3S`, `A&E HD/FHD/US`, `AMC HD`, `AMC BO HD`, `AXN WHITE/AXN ESPAÑA`,
  `Adult Swim`, `Adn40`, `Animal Planet HD/HD+/FHD`, `Antena 3 HD`, `Al Jazeera`, …
  tramos `24 Horas` → `WIN Sports+ COL HD` (orden alfabético, no estrictamente).
- IDs de programa (`cyx_*`, en tráfico real de reproducción):
  | ID | prefijo de segmento |
  |----|--------------------|
  | `cyx_50fdcc0817d61_720p` (= LGVIP La Gala) | `shisui` |
  | `cyx_8305D098072D9C0370F79944` | `xycjco` |
  | `cyx_DA442D95A726FA05D4437` | `cyx_sp` |
  | `cyx_93531158996778016` | `cyx_cj` |
  | `cyx_0EA669A04973404263A` | `xycjco` |
  | `cyx-D08E1E3D47688ed3A7F115FEB16A` | `cyx_cj` |
  | `cyx-7BCA096543B49d9fF5E93008926A` | `xycjco` |
- **Emparejado nombre→id**: solo `LGVIP La Gala` confirmado. El resto de nombres del
  catálogo aún no está casado con su id (requiere tocar cada canal y capturar su `.ts`).
- El tramo alfabético posterior a `UNICANAL HD PY` (V–Z final) no quedó capturado en la
  última pasada (la app cerró sesiones durante el barrido).

## 7c. DEMO FUNCIONAL (22:xx 2026-09-20) — reproducción externa verificada

1. Captura breve (~40 s) del tráfico de la app con el canal en foco → se obtienen
   `host + anchor` actuales (los únicos datos que caducan).
2. El host de HOY fue `nmbde.ornmwqyup.com:80` (rota a diario; también `bcdod`,
   `aluve`, `niguof`).
3. Desde el **PC Windows**, sin auth: `GET /live/{prog}_{res}/{prog}_{res}_{prefijo}_{anchor}.ts`
   → HTTP 200 `video/mp2t`. 11 anchors de LGVIP La Gala devolvieron 200 con sync 0x47
   (algunos anchos 404: la rejilla real no es múltiplo estricto de 6000; hay que sondear).
4. **Entregable**: `xuper_lgvip_demo.ts` = 6 chunks concatenados de LGVIP La Gala
   (anchor 310680000–310722000) = **16 082 648 bytes, 85 546 paquetes, todos con sync
   válido** → reproducible con VLC/ExoPlayer.
5. El 2º canal `cyx_724540B431CFE08C8007` (`xycjco`, step +5000) **también descargable**
   desde el PC (su payload incrusta metadatos `cyx_m3c1`).
6. Los `anchor` NO se derivan de reloj de pared ni de broadcast-clock (probado: barridos
   ±180 s → 404); solo la captura en vivo de la app entrega el seed correcto.
7. Conclusión práctica: cliente funcional = 10–20 s de captura como semilla + encadenado
   de `.ts` desde el host. El host y el prefijo rotan por día; el listado de canales está
   en `XUPER_TV_canales.txt`; falta casar cada nombre con su `cyx_*` (emitido en cada
   captura).

## 8. Librerías / stack detectadas (huella de tráfico y permisos)

- Red: **okhttp 3.12.12**, okgo. Push: **ACCS (Alibaba/MPS)**.
- Análisis/ads: **Umeng** (analytics+push), **Firebase** (Crashlytics, Messaging,
  AdIdClient), **Yandex AppMetrica**, GMS.
- Reproducción: **ijkffmpeg** + **AVIO/Luna** + P2P (ranger-jni).
- Render: view nativo (`jc.c`, `VideoFLiXter`?), IJKPlayer adaptado.
- Permisos: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`,
  `POST_NOTIFICATIONS`, `READ/WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`,
  `REQUEST_INSTALL_PACKAGES`, `GET_TASKS`, `MOUNT_UNMOUNT_FILESYSTEMS` (deprecado).

## 9. Plan para un cliente independiente

Fase a) **Captura MITM completa (requerida)**
1. Instalar `mitmproxy` en un host (Linux/WSL) y arrancar `mitmproxy --mode regular` (u
   `transparent`). LD Player no soporta VPN por UI → usar proxy HTTP global:
   `adb shell settings put global http_proxy 10.0.2.2:8080`.
2. Instalar el certificado CA de mitmproxy en el sistema (root ya disponible):
   copiar a `/system/etc/security/cacerts/` y `chmod 644`.
3. Reproducir un canal y capturar la conversación completa:
   - registro de canales (EPG) y su **respuesta** (reverse de cómo se eligen los hosts del día),
   - petición del manifiesto AVIO (probar filtros `live`, `manifest`, `m3u8`, `getstream`),
   - algoritmo de generación de `md5`/`sn` a partir de la sesión.
4. Guardar un export `.har` como evidencia de referencia.

Fase b) **Hooking (fallback y verificación)**
- Adquirir `frida-server-<android-x86_64>` y un host con Python/frida-tools.
- Hooks objetivos: `okhttp3.Request`, `com.android.okhttp`, clase de cifrado de dominios
  (`DomainCache`/telemetry), y el motor Luna (`/live/`, `_shisui_`, `block_cache`).
- Permite extraer los dominios del día y las URLs en claro sin descifrar el DEX.

Fase c) **Desempaquetado iJiami (solo lectura, uso interno)**
- Dump de memoria/tamper con `frida` para recuperar el DEX real en runtime
  (o LDPlayer memory dump), y mapear los puntos de firma (`md5`, `sn`, `userId`).

Fase d) **Cliente mínimo**
- Replicar: (1) obtención de hosts del día, (2) petición EPG firmada, (3) lista de
  canales, (4) manifiesto AVIO → URLs `.ts` de sesión, (5) lectura de los `.ts`
  (media player estándar, engine AVIO **no** se redistribuye).

## 10. Limitaciones conocidas

- Los **`.ts` en vivo no firman** (extracción directa confirmada), pero el **host de stream y
  el `{anchor_ms}` rotan/cambian por día y por sesión** → una lista estática no funciona;
  hace falta replicar la resolución de dominios del día y el cálculo del anchor.
- El `{prefijo}` del segmento y la **lista completa de canales** provienen del **EPG**
  (`/epg/v2/live/app/utc-5/26`), que Cloudflare bloquea con **403** desde cualquier IP con
  cabeceras exactas (es regla del servidor, no geo/IP) → la tabla canal→id completa solo se
  consigue estando dentro de la resolución de dominios del día (o capturando el tráfico real).
- DNS-over-HTTPS propio: la resolución de dominios rotatorios pasa por los servidores
  del cliente; útil para detección pero fija rutas de host por día.
- La cache local está cifrada (DES/AES con material embebido en el DEX empaquetado).

---
Archivo generado: 20-sep-2026. Fuentes: capturas `cap.pcap`, SQLite
(`download_info.db`, `user_vod.db`, `BBDatabase.db`), `domain_test.json`,
telemetría/logcat del proceso `com.android.mgstv` (uid u0_a78).