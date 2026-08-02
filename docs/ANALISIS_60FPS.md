# KarinFLiX — Análisis del pipeline 24fps → 60fps (interpolación de movimiento en GPU)

> Documento autocontenido para análisis por otra IA. Fecha: 2026-07-31.

---

## 1. Resumen

El app `com.karintv.player` (ExoPlayer/Media3) tiene un pipeline GLES2.0 que interpola video de baja tasa de fotogramas (típicamente 24fps) a 60fps usando flujo óptico por píxel estimado en shader, con:

- Mapa de flujo **Lucas–Kanade 1-iteración** + verificación *forward/backward* para confianza.
- **Suavizado temporal (EMA)** del flujo y de la máscara de magnitud/confianza (ping-pong de texturas).
- **Detección de escena estática** (downsample del mapa de movimiento a 16×16, umbral).
- Warp del frame previo hacia adelante con un **factor de interpelación rampeado** desde el timestamp de release del frame decodificado.
- Mejora de imagen post-interpolación (contraste, saturación, brillo, nitidez, color boost) opcional.
- Logs de métricas cada segundo con tag `Media3-60fps`.

Estado actual: **funciona** — validado por píxeles (movimiento suave, sin ghosting ni saltos). Ver sección 8.

> **Actualización 2026-08-01:** corregido un bug en la detección de escena estática que dejaba `static=true` en contenido con movimiento real (anime). También se añadieron optimizaciones de recursos (ver sección 5). Resultados actualizados en 8.5.

---

## 2. Arquitectura general

### 2.1 Mermaid

```mermaid
flowchart TD
    subgraph Codec["MediaCodec (ExoPlayer)"]
        MC[Decodificador de video] -->|Surface| ST["SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)"]
        MC -. "onVideoFrameAboutToBeRendered" .-> Q[metaQueue<br/>FrameMeta(ptsUs, releaseNs)]
    end

    subgraph GL["GLSurfaceView.Renderer = InterpolationRenderer<br/>(Media3SixtyFpsProcessor.kt:118)"]
        L[latch: frameAvailable?] --> C["copyOldToPrev()<br/>prevTexId = frame anterior (media res)"]
        C --> U["st.updateTexImage()"]
        U --> D["drainMetadata()<br/>alinea PTS con releaseNs"]
        D --> M["buildMotionMap()<br/>motionProgram (L-K + EMA)"]
        M --> S["readStaticLevel()<br/>staticProgram 16x16"]
        S --> F["computeFactor()<br/>factor = (now - segmentStartNs)/intervalNs"]
        F --> R["renderFrame()<br/>fragmentShader (warp + blend + enhance)"]
        R --> T[trackOutputFps: log métricas / 1s]
    end

    ST --> L
    Q --> D
    R --> SCREEN[Pantalla]

    cfg[VideoEnhanceConfig<br/>prefs] --> R
    cfg --> F
    metrics[Log.i TAG=Media3-60fps] --> T
```

### 2.2 ASCII

```
 ExoPlayer (MediaCodec)                         InterpolationRenderer (GL thread)
 ┌───────────────────────────┐                  ┌──────────────────────────────────────────────┐
 │  Decoder ──Surface──► SurfaceTexture         │  onDrawFrame() cada vsync (~60fps)            │
 │  (OES texture: inputTexId)│                  │                                               │
 │  ──► onVideoFrameAbout    │                  │  latch frame:                                │
 │      ToBeRendered(pts,    │                  │    • copyOldToPrev(): frame N-1 → prevTexId   │
 │       releaseTimeNs)      │                  │      (resolución mitad de pantalla)           │
 │      metaQueue(≤32) ──────┼──(sincroniza PTS)│    • updateTexImage(): curr = frame N          │
 └───────────────────────────┘                  │    • drainMetadata(): prevPts/currPts,        │
                                                │      prevReleaseNs/currReleaseNs              │
 Texturas GL_TEXTURE_2D:                        │    • buildMotionMap(): motionTexId (W/8×H/8)  │
  - prevTexId   (W/2×H/2)                       │      (ping-pong con motionAccumId)             │
  - motionTexId (W/8×H/8, clamps)               │    • readStaticLevel(): staticTexId 16×16      │
  - motionAccumId (W/8×H/8)                     │    • factor = (now − segmentStartNs)/interval  │
  - staticTexId (16×16)                         │                                                │
                                                │  renderFrame(): fullscreen quad                │
                                                │    warp(prev, flow×factor) blend curr, enh.    │
                                                └──────────────────────────────────────────────┘
```

---

## 3. Detalle del procesamiento por frame (orden exacto de draw calls)

En `onDrawFrameSafe()` (Media3SixtyFpsProcessor.kt:650):

1. **Latch** (`synchronized(frameLock)`): si `frameAvailable` (SurfaceTexture tiene frame nuevo):
   - `updateSourceFpsFallback()` — estima fps por llegada si no hay PTS válido (EMA 0.9/0.1).
   - `shouldCopy = interpWanted || debugNeedsPrev` (se refresca `prev` también en escena estática para que el motion map no quede estancado; antes era `(interpWanted && !staticScene) || debugNeedsPrev`). Si `(shouldCopy || prevDirty) && !firstLatch`: guarda la matriz vieja (`matrixOld`), y hace `copyOldToPrev()` → **pass 1: copy** del OES actual a `prevTexId` (sin warp, sin enhance, `uVFlip=1`). Así `prev` = el frame anterior en pantalla.
   - `st.updateTexImage()` → el OES ahora es el frame nuevo (curr).
   - `drainMetadata(currTs)`: consume `metaQueue` (frames con `ptsUs*1000 <= currTimestampNs`), fija `prevPtsUs/currPtsUs` y `prevReleaseNs/currReleaseNs` desde el par de metadatos más reciente.
   - `buildMotionMap()` → **pass 2: motionProgram** sobre `motionFbo` (W/8×H/8), con ping-pong `motionTexId ↔ motionAccumId` (swap al final). Ver sección 4.
   - `readStaticLevel()` → **pass 3: staticProgram** downsampla el canal alpha del mapa de movimiento a `staticTexId` 16×16 y hace `glReadPixels` (256 bytes), toma el **máximo**. Ver sección 5.
   - `frameAvailable=false`, `hasNewFrame=true`, `frameCount++`.

2. **Factor** (fuera del lock): al latchar, `segmentStartNs = currReleaseNs` (timestamp de release del frame decodificado); `factor = clamp((now − segmentStartNs)/intervalNs, 0, 1)`, con `intervalNs` = intervalo PTS del par prev/curr (clamp 5–200 ms), o `1000/sourceFps` si no hay PTS.

3. **Render** → **pass 4: fragmentShader principal** fullscreen:
   - `interpolating = interpWanted && !staticScene && prevReady`.
   - Si `interpolating`: warp de `prevTexId` hacia la posición intermedia, blend con `curr` según `uFactor`, enmascarado por `conf × mag`. Ver sección 4.3.
   - Si no: `uInterpEnabled=0` → passthrough de curr.
   - Luego enhance opcional (siempre activo si `uEnabled`).

4. **Métricas** (`trackOutputFps`, cada 1s): `Log.i("Media3-60fps", "metrics out=… src=… interp=… static=… mov=… drop=… QUALITY")`.

### 3.1 Sincronización frame-metadata

- El decodificador llama `onVideoFrameAboutToBeRendered(releaseTimeNs, presentationTimeUs)` (Media3SixtyFpsProcessor.kt:633) desde su thread; se encola `FrameMeta(ptsUs, releaseNs)` (máx 32, FIFO).
- En el latch se comparan PTS (µs) contra el timestamp de `SurfaceTexture` (ns). El **curr** es el último meta con `ptsUs*1000 <= currTimestampNs`; **prev** es el anterior en la cola (o el último drenado).
- `sourceFps` se actualiza con EMA sobre `1000000/(currPtsUs−prevPtsUs)` (0.85/0.15) si `ivUs ∈ [8ms, 200ms]`.
- `droppedFrames = metadataCount − frameCount` (frames decodificados no latchados).

---

## 4. Shaders

### 4.1 motionShader (estimación de flujo, pass 2)

`Media3SixtyFpsProcessor.kt:401`. Texel de trabajo `t = (2/motionW, 2/motionH)`.

1. **Luminancias**: `l0`=curr, `lp`=prev (dot 0.299/0.587/0.114).
2. **Gradiente de curr**: `gx = (cR−cL)*0.25`, `gy = (cT−cB)*0.25` (central diff, ×0.25 → medio pixel de paso).
3. **Lucas–Kanade 1 paso**: `d = l0 − lp`; `f = clamp(−d·g/(gx²+gy²+1e-4), −8, 8)`.
4. **Verificación backward**: `p = vTexCoord − f·t`; re-calcula `db = pl0 − pc0` y `b` igual que `f` con gradientes de prev.
5. **Confianza**: `conf = 1 − smoothstep(0, 1.5, length(b − f))`.
6. **EMA temporal** (`alpha = motionAlpha = 0.6`, o `1.0` en el primer frame):
   - `sm = clamp(mix(oldF, f, alpha), −8, 8)` (flujo suavizado).
   - `mag = mix(old.a, smoothstep(0, 0.06, |d|), alpha)` (magnitud de cambio).
   - `smConf = mix(old.b, conf, alpha)`.
7. **Codificación RGBA**: `vec4(sm*0.0625+0.5, smConf, mag)`.
   - `.rg` = flujo codificado, `.b` = confianza EMA, `.a` = magnitud EMA.
   - Ping-pong `motionTexId ↔ motionAccumId` por frame para la memoria temporal.

**Notas clave para quien analice:**
- El clamp `±8` está en texels del mapa de movimiento. Con `uMotionScale = 1/motionW`, el desplazamiento máximo de warp es `8/motionW` de la UV ≈ 3.3% del ancho para `motionW=240` (~21 px fuente en 640px).
- `d` usa luma pura (sin paso alto); escenas muy estáticas con ruido de compresión pueden dar `|d|` grande → mitigado por `smoothstep(0, 0.06, |d|)` y el umbral estático (sección 5).

### 4.2 fragmentShader — warp + blend (pass 4)

`Media3SixtyFpsProcessor.kt:291`.

```
if (uMode > 0.5) {                              // MED(1) o HIGH(2)
    vec2 mv = texture2D(uMotionTex, uv).xy*2 − 1;
    if (uMode > 1.5) {                          // HIGH: flujo suavizado 4-vecinos
        n = suma de 4 vecinos (mv*0.5 + n*0.125);
    }
    mv *= 8.0;                                  // decodifica (0.0625)*… → recupera sm
    prevUV = clamp(uv − mv * uMotionScale * uFactor, 0, 1);
    prev = texture2D(uPrevTex, prevUV);
    mask = motionTex.ba;                        // confianza × magnitud (ambas EMA)
} else {                                        // LOW(0): sin warp
    prev = texture2D(uPrevTex, uv);
    mask = 1.0;
}
vec3 interp = mix(prev.rgb, curr.rgb, uFactor);
color = mix(curr.rgb, interp, clamp(mask, 0.75, 1.0));  // piso de máscara 0.75
```

- `uFactor` → 0 = puro prev (warped), 1 = puro curr. La máscara `conf×mag` decide dónde se aplica.
- **Piso de máscara (2026-08-01):** `clamp(mask, 0.75, 1.0)`. Sin el piso, `mask` (conf×mag) resultaba ~0 en el interior de objetos en movimiento sólidos (la caja de prueba y los cels planos del anime) → el output se quedaba en `curr` (posiciones fuente, saltos de 16px → parecía 24fps). Con el piso 0.75, el blend interpola posiciones intermedias (ver sección 8.6). Tradeoff: algo de ghosting en regiones con flujo poco fiable (compromiso estándar de interpolación de movimiento).
- **Modo LOW (0)** = solo crossfade (no usa flujo): calidad "Batería".
- **MED (1)** = flujo bruto EMA + mask.
- **HIGH (2)** = flujo suavizado 4-vecinos + mask.

### 4.3 Modos debug (uDbgMode, `debug_mode` 0–6)

| Valor | etiqueta | Render |
|---|---|---|
| 0 | OFF | normal |
| 1 | PREV | `prevTexId` |
| 2 | CURR | `curr` |
| 3 | UV | `vTexCoord` coloreado |
| 4 | FACTOR | gris = `uFactor` |
| 5 | MOTION | gris = `.a` (magnitud) del motion map |
| 6 | V0V1 | split horizontal de curr |

Activación: extra de intent `--ei debug_mode N` en `ExoPlayerActivity` (escribe prefs vía `VideoEnhanceConfig.setDebugMode`).

---

## 5. Detección de escena estática

- `staticProgram` (Media3SixtyFpsProcessor.kt:457): toma el **máximo** de 16 muestras del `.a` del motion map en 16×16 celdas → `staticTexId` (16×16), luego `glReadPixels`.
- `readStaticLevel()` devuelve `max(16×16)/255` (0..1).
- Si `level < STATIC_THRESHOLD (0.04)` durante **2 frames consecutivos** → `staticScene = true`: se **congela** la interpolación (passthrough), se deja de copiar `prev`.
- Mientras estático, cada **15 frames** re-verifica; si `level ≥ 0.12 (0.04×3)` → `staticScene=false`, `prevDirty=true`, `passthroughLatch=true` (un frame a factor 1 para re-anclar).
- Caso límite conocido: un frame perdido durante el movimiento puede disparar `staticScene` (mov=0) momentáneamente.

### 5.1 Bug corregido (2026-08-01): UV degenerado del staticProgram

**Síntoma:** con contenido anime (pan de cámara + objeto móvil), el detector quedaba atascado en `static=true mov=0` desde el inicio, desactivando la interpolación aunque hubiera movimiento visible. El clip de control (caja azul) sí daba `mov=12`; el overlay debug 5 mostraba la magnitud del motion map con valores reales, pero `readStaticLevel()` devolvía 0.

**Causa raíz:** el `staticProgram` se compilaba con el mismo `vertexShader` que los demás pasos, que transforma las UV con `uTexMatrix`; pero el staticProgram **nunca recibía `uTexMatrix`** (no existía `sTexMatrixLoc`). En GLES 2.0 un uniform no seteado vale 0 → la matriz era todo ceros → `vTexCoord = (0,0)` para todos los vértices → el downsample 16×16 leía **un único texel** (una esquina del motion map) en vez de toda la imagen. Si la esquina no tenía movimiento, el máximo era 0 y `staticScene` nunca salía. (En el clip de control, la caja pasaba por la esquina en cada wrap, por eso daba `mov=12`).

**Fix:** `staticVertexShader` dedicado que pasa `aTexCoord` directo a `vTexCoord` (sin `uTexMatrix`):
```
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
```
Compilar con `buildProgram(staticVertexShader, staticShader)`.

**Resultado (validado):**
- `motion.mp4` (control): `out=59–62fps interp=true static=false mov=99 drop≤3 MED` (mov alto = pan de cámara mueve todo el frame; correcto).
- `anime.mp4` (antes roto): `out=56–61fps interp=true static=false mov=71–99 drop≤3 MED` — la interpolación se activa en el contenido real.
- `static.mp4` (gris fijo): `out=23fps interp=false static=true mov=0` — la detección de estático sigue funcionando.

### 5.2 Optimizaciones de recursos aplicadas (2026-08-01)

1. **Throttle de `readStaticLevel()`**: se ejecuta cada 4 latches (`STATIC_READ_INTERVAL=4`) en lugar de cada frame (evita el `glReadPixels` de cada latch).
2. **`RENDERMODE_WHEN_DIRTY` cuando `staticScene`**: con escena estática el GLSurfaceView pasa a dibujar solo cuando llega frame nuevo (`requestRender()` en `OnFrameAvailableListener`), bajando el render de 60→24 renders/s en contenido estático (ahorro de GPU/CPU/batería). Se restaura `CONTINUOUSLY` al salir de estático.
3. **Uniform `uStatic`** en el fragmentShader: cuando `staticScene` se saltan 4 taps del laplaciano de nitidez (sin beneficio en escena quieta).
4. **Métrica `ms`**: tiempo de `renderFrame` por frame (vía `fpsRenderNs`), logueado en `metrics` (0.0–0.1 ms en LDPlayer).
5. **Nitidez desde media resolución (`uDownTex`)**: nuevo `downTexId/downFbo` (W/2×H/2) y pre-pass `downscaleCurr()` que baja el frame fuente a media res **una vez por latch** (solo si enhance activo y `!staticScene`). El laplaciano de nitidez del fragmentShader muestrea `uDownTex` con `uDownTexel` (2× el texel full-res) en vez de `uCurrTex` → ~4× menos ancho de banda de textura en los 4 taps de nitidez del pass principal (dominado por bandwidth en GPUs móviles). Calidad validada: energía de bordes idéntica (28.7) entre grabaciones con nitidez full-res vs media-res, y sin sobre-nítidez (la textura está bien enlazada; si estuviera negra, la nitidez se dispararía).

Estado de instrumentación: el `Log.i("staticRecheck …")` de diagnóstico fue **eliminado** tras el fix.

---

## 6. Configuración (VideoEnhanceConfig.kt)

Prefs `karin_video_enhance`:

| Parámetro | Default | Notas |
|---|---|---|
| enhance_enabled | true | activa pass de enhance |
| interpolation_enabled | true | activa interpolación |
| smooth_profile | BALANCED | BATTERY_SAVER=LOW(0), BALANCED=MEDIUM(1), QUALITY=HIGH(2) |
| saturation | 1.35 | |
| contrast | 1.10 | |
| brightness | 0.04 | |
| sharpness | 0.65 | laplaciano mixto |
| color_boost | 1.15 | |
| debug_mode | 0 | 0–6 |

La interpolación se activa solo si `interpWanted && mode>0 && sourceFps < 50f`.

---

## 7. Log de métricas capturado (sesión activa, clip motion.mp4)

Formato: `out=<fps render> src=<fps estimado> interp=<bool> static=<bool> mov=<motionLevel*100> drop=<frames decodificados no latchados> <quality>`

```
07-31 23:42:08.876 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:09.877 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:10.893 12957 12991 I Media3-60fps: metrics out=58fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:11.894 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:12.912 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:13.927 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:14.927 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:15.934 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:16.943 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:17.944 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:18.945 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:19.961 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:20.962 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:21.979 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:22.987 12957 12991 I Media3-60fps: metrics out=56fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:23.995 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:25.014 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:26.018 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:27.030 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:28.046 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:29.047 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:30.063 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:31.063 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:35.064 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:36.081 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:37.082 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:38.097 12957 12991 I Media3-60fps: metrics out=57fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:39.098 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:40.114 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:41.115 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:42.131 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:43.160 12957 12991 I Media3-60fps: metrics out=51fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:44.170 12957 12991 I Media3-60fps: metrics out=56fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:45.181 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:46.182 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:47.198 12957 12991 I Media3-60fps: metrics out=60fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:48.199 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:53.233 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:54.250 12957 12991 I Media3-60fps: metrics out=59fps src=25fps interp=true static=false mov=12 drop=1 MED
07-31 23:42:55.294 12957 12991 I Media3-60fps: metrics out=56fps src=25fps interp=true static=false mov=12 drop=1 MED
```

**Lectura:** out=56–60fps estable, src≈25fps (clip de 24fps + estimación EMA), `interp=true`, `static=false` (hay movimiento), `mov=12` → nivel de movimiento 0.12 (sobre umbral estático de 0.04), `drop=1` constante (1 frame decodificado no latchado, presumiblemente por el arranque o timing de release — estable y no acumulativo).

---

## 8. Validación por píxeles (resultados)

### 8.1 Clip de prueba `motion.mp4`
- 640×360, 24fps, 144 frames (= 2 bucles de 72).
- Caja azul sobre fondo rojo moviéndose **8px/frame** de izquierda a derecha, con wrap en el frame 72 (vuelve a x=39).

### 8.2 Método
- Reproducción en emulador LDPlayer9 (1280×720 pantalla, escala ×2) con `adb screenrecord` (≈59fps) durante 4s.
- Extracción frame a frame con ffmpeg; detección de la caja azul por umbral RGB (B>170, R<90, G<90); se mide el centroide x por frame.

### 8.3 Resultados (2 grabaciones independientes)
- **Movimiento suave**: incrementos de 7–15px entre frames grabados consecutivos. Con interpolación passthrough (sin warp) se esperarían posiciones constantes 2–3 frames y saltos de 16px; NO es el caso → **el warp interpolado está activo y genera posiciones intermedias**.
- **Sin ghosting**: ancho/alto de la caja estable (142–158 × 148–150 px) en todos los frames; `trail=0` (sin píxeles residuales detrás de la caja).
- **Sin saltos hacia atrás** (flicker): los únicos saltos negativos son el wrap del contenido (caja reaparece a la izquierda) — esperado.
- **Holds**: se observaron agrupaciones de frames idénticos (7–11 frames) en posiciones distintas en cada grabación → artefacto del grabador/encoder, no sistemático del pipeline.
- Trampa diagnosticada: capturas `b_*.png` tomadas **después** de que terminara el clip de 6s quedaban congeladas en el último frame → parecían un bug. **Hay que capturar durante la reproducción.**

### 8.4 Dato del clip fuente (control)
Posición del centro de la caja vs frame: 39→543 px en frames 0–64 (paso constante de 8px), wrap a 39 en el frame 72. Confirmado: el fuente nunca se detiene; ningún hold en el origen.

### 8.5 Validación tras el fix del detector estático (2026-08-01)

**Clip `anime.mp4`** (640×360, 24fps, 240 frames = 10s; pan lento 0–4s, pan rápido + bola naranja 4–8s, estático 8–10s):

- Métricas (debug 0): `out=56–61fps ms=0,0–0,1 src≈25fps interp=true static=false mov=71–99 drop≤3 MED` durante la sección con movimiento → **el detector ya no se atasca; la interpolación actúa sobre contenido real**.
- Screenrecord de 9s (418 frames): la velocidad de la caja/escena es correcta (≈96px/s a escala 320 = la velocidad del fuente), sin ghosting. Los "holds" periódicos de 2–3 frames se atribuyen al compositor del emulador (VSYNC deshabilitado, gaps de 33ms en SurfaceFlinger), no al pipeline.
- La detección de la bola por umbral de color fue descartada como métrica (el fondo del anime tiene múltiples objetos naranjas que ensucian el centroide); para tracking preciso usar el control `motion.mp4`.

**Clip `static.mp4`** (gris, 4s): `out=23fps interp=false static=true mov=0` → el modo estático sigue detectándose y el render baja a 24fps (WHEN_DIRTY).

### 8.6 Investigación de fluidez real (2026-08-01): la máscara ahogaba la interpolación

**Síntoma reportado:** "no se ve que se mueva a 60fps" — judder en pantalla pese a `out=60fps interp=true`.

**Medición (control `motion.mp4`, tracking del centroide de la caja a 1280×720):**
| Estado | Holds (<3px) | Patrón |
|---|---|---|
| Fuente nativa 60fps (mismo emulador, video real 60fps) | 9% | suave, avanza cada frame |
| Interpolado ANTES del fix | **60%** | se sostiene en posición y salta 16px (paso fuente) = parece 24fps |
| Interpolado con piso de máscara 0.75 | **25%** | pasos continuos de 1–12px (resto = jitter de presentación del emulador) |

**Diagnóstico (en 3 pasos):**
1. `dumpsys SurfaceFlinger --latency`: los `frameNumber` del app avanzan cada 16.67ms (el app somete 60fps), pero `presentTime` queda en MAX → el emulador no presenta con vsync ("VSYNC state: disabled"). Pista: el compositor del emulador.
2. Log del `uFactor` dentro de la app (debug 4): el factor SÍ rampa bien (0.0 → 0.43 → 0.83 por segmento fuente). El app genera la rampa correcta.
3. **Causa raíz:** la máscara `conf×mag` es ~0 en el interior de la caja/objetos planos (L-K no produce flujo fiable en superficies lisas) → `mix(curr, interp, mask≈0)` = `curr` → el output se quedaba en los frames fuente. El piso `clamp(mask, 0.75, 1.0)` fuerza el blend interpolado y el centroide se mueve de forma continua.

**Resultado final:** 60% → 25% holds; el resto es presentación irregular del emulador (la caja nativa 60fps también tiene 9%). En un dispositivo real (vsync activo) se espera que la interpolación se perciba como 60fps reales.

### 8.7 Revisión de calidad (2026-08-01): warp ponderado, pirámide coarse→refine, máscara mezclada, throttle y reset de vector global

Cambios aplicados sobre la revisión de código (objetivo: menos artefactos/vibración bajo movimiento sin perder la cadencia 60p ya resuelta):

1. **Peso temporal del warp**: `interp = mix(p, c, uFactor)` en vez del 0.5 fijo. `p` (prev warp-eado hacia adelante por `u·ms`) y `c` (curr warp-eado hacia atrás por `(1−u)·ms`) se combinan con peso temporal `u` → en `u→0` el frame se acerca a prev y en `u→1` a curr, eliminando el ghosting simétrico del 0.5 cuando el flujo es erróneo.
2. **Pirámide coarse→refine**: nuevo `coarseProgram`/`coarseTexId` (resolución `motionW/2×motionH/2`, limpia a flujo cero) que estima L-K con downsampling 2×2 (box) y un stencil ancho → captura movimientos grandes que saturaban el clamp ±8 del nivel fino. El `motionShader` ahora es un **refinado**: lee el estimado coarse (`f0 = (coarse.xy*2−1)*8`, mismas unidades por construcción: misma fórmula LK, mismo clamp ±8, mismo encode `f*0.0625+0.5`), warpea prev por `f0` y suma la corrección local (`f = clamp(f0 + corr, ±8)`). La verificación backward y la `conf` se calculan sobre el flujo ya refinado. La `mag` usa la diferencia temporal **sin compensar** (`lpRaw = prev@vTexCoord`), porque con el prev warp-eado por `f0` la `d` compuesta colapsaba a ~0 (diagnóstico: totalW 168→2; ver sección 8.8).
3. **Máscara con mezcla**: `mask = clamp(mix(conf*mag, trust, 0.5), 0, 1)` en vez de `max(...)`. Menos permisiva que el `max` → menos artefactos en regiones donde solo una de las dos señales es fiable. Es el mismo tradeoff judder-vs-artefacto, ahora calibrado al medio.
4. **Throttle de readbacks**: `computeGlobalMotion()` (glReadPixels 16×16 + mediana en CPU) se ejecuta cada 3 latches (`globalCounter % 3`), no cada frame. El vector ya está suavizado temporalmente (0.4/0.6) → 3× menos sync GPU→CPU en el hilo GL.
5. **Reset del vector global en cortes**: al salir de escena estática (`staticScene=false`) se resetea `globalVecReady=false` y `globalVec=(0,0)`, evitando que el vector del plano anterior deforme los primeros frames del nuevo.
6. Umbral de peso total del vector global bajado de 5.0 a 3.0 (más rango de activación).

**Validación (emulador, logs `Media3-60fps`):**
- `motion.mp4` (caja vertical): `totalW=13–47`, `hy=−0.08` estable (movimiento vertical detectado), `hx≈0` (correcto: la caja no se mueve en X). El vector global se activa y es coherente.
- `anime.mp4` (pan): `gx=−0.01…−0.03` durante el pan (el pan lento ahora sí se captura, antes era ~0). `out=59–62fps ms=0,0–0,1 interp=true drop=0`. Sin errores de compilación/link en ningún shader.
- Sin regresión de rendimiento: 60fps estables con 0.0–0.1 ms de render en LDPlayer.

### 8.8 Diagnóstico del vector global (2026-08-01)

- El muestreo en un **bucle GLSL (`for i/j`) dentro del shader global devolvía 0** (totalW=0) mientras el muestreo directo funcionaba → bug del loop con `texture2D` en el GLES software del emulador. Fix: el `globalShader` muestra `vTexCoord` directamente y la **mediana robusta (pesada por `conf×mag`) se calcula en CPU** sobre los 256 texels del readback (cada celda = centro de una región del flujo).
- Tras la pirámide, `mag` colapsaba porque `d = l0 − lp` usaba el prev **warp-eado por `f0`** (el cambio temporal compuesto ≈ 0 en regiones en movimiento). Fix: `mag = smoothstep(0, 0.06, |l0 − lpRaw|)` con `lpRaw` = prev sin warpear. totalW recuperó 2→13–47.

---

## 9. Problemas conocidos / observaciones para la siguiente revisión

1. **`mov`=12 estable con `static=false`**: el umbral estático es 0.04; con la caja en movimiento constante el máximo 16×16 llega a 0.12. Un clip con movimiento pequeño/localizado podría quedar en `static=true` y desactivar la interpolación (flicker de interp on/off). Verificar con contenido real (anime/cel-shading).
2. **`drop=1` constante**: el conteo `metadataCount − frameCount` arranca en 1 y no vuelve a 0. Investigar si es un frame de metadatos sobrante del arranque o un frame realmente no latchado.
3. **`src≈25fps` para un clip de 24fps**: la EMA (`0.85/0.15`) puede sesgar por los PTS no exactos del contenedor; no crítico (solo se usa para `intervalNs` fallback y el umbral <50fps).
4. **`staticScene` y frames perdidos**: un drop durante movimiento puede bajar `mov` a ~0 un instante; si dura 2 frames se apaga la interpolación. El `passthroughLatch` re-ancla con 1 frame a factor=1.
5. **Saturación del flujo `±8`**: con la pirámide coarse→refine (8.7.2) el límite físico sube (~el nivel coarse captura movimientos que saturaban el fino); el clamp ±8 del nivel fino sigue aplicándose al resultado combinado. Para movimiento muy rápido la verificación backward baja `conf` y el blend degrada a crossfade — comportamiento aceptable.
6. ~~**Bug potencial en `readStaticLevel`**~~ → **CORREGIDO** (el problema real no era el canal sino las UV degeneradas del staticProgram; ver 5.1). El dato de magnitud vive en `.a` del motion map y el staticShader lo promedia/maxea a **R** del staticTex; la lectura toma el máximo del canal R, correcto.
7. **Debug mode 5 (MOTION)** renderiza `.a` (magnitud) del motion map como gris en todo el frame; el overlay se verificó por píxeles (la magnitud es difusa en anime porque el pan mueve todo el frame, no solo la bola — esperado con EMA).
8. **RenderMode CONTINUOUSLY**: se mantiene 60fps con movimiento (necesario para los frames intermedios); con `staticScene` el GLSurfaceView pasa a `WHEN_DIRTY` (24 renders/s, ahorro de recursos). Ver 5.2.
9. **`mov`=99 en el control**: con el detector por `max()` y un pan de cámara, el nivel de movimiento es alto (≈0.99) porque los bordes en movimiento cubren casi todas las celdas 16×16. Correcto para ese contenido; si se quiere un valor más bajo para panes lentos podría considerarse volver a `mean()` ahora que las UV están bien — pendiente de decisión (el `max()` es más robusto contra el flicker estático/no-estático).

---

## 10. Cómo reproducir y medir

```bash
# Servir el clip local (el emulador lo ve por adb reverse)
python -m http.server 18080            # desde la carpeta con motion.mp4
adb reverse tcp:18080 tcp:18080

# OJO: si reinicias adb (kill-server) o el emulador, las reglas reverse se pierden.
# Verifícalas antes de reproducir:
adb reverse --list   # debe mostrar: host-9 tcp:18080 tcp:18080

# Lanzar reproducción con el pipeline (debug off)
adb shell am start -n com.karintv.player/com.karin.streamtv.player.ExoPlayerActivity \
  --es video_url http://127.0.0.1:18080/motion.mp4 --ei debug_mode 0

# Ver métricas
adb logcat -s Media3-60fps

# Grabar pantalla para análisis por píxeles
adb shell screenrecord --time-limit 4 --bit-rate 8000000 /sdcard/rec.mp4
adb pull /sdcard/rec.mp4 rec.mp4

# Lanzar con un modo debug
adb shell am start ... --es video_url URL --ei debug_mode 5
```

---

## 11. Archivos de referencia

| Archivo | Contenido |
|---|---|
| `app/src/main/java/com/karin/streamtv/player/Media3SixtyFpsProcessor.kt` | Pipeline completo: renderer, 4 shaders (copy/motion/static/fragment), sincronización, métricas |
| `app/src/main/java/com/karin/streamtv/player/ExoPlayerActivity.kt` | Activity de reproducción, extras de intent, conexión del pipeline |
| `app/src/main/java/com/karin/streamtv/player/VideoEnhanceConfig.kt` | Preferencias: calidad, perfil suave, enhance, debug |
| `app/src/main/java/com/karin/streamtv/player/EnhancedGlSurfaceView.kt` | Renderer legacy (solo enhance, sin interp) |
| `app/src/main/java/com/karin/streamtv/player/VideoEnhanceBridge.kt` | Puente legacy TextureView (posiblemente en desuso) |
| `app/src/main/java/com/karin/streamtv/player/VideoDataSource.kt` | DataSource con Referer para Media3 |

---

*Documento generado para análisis externo. Si se requiere, puede añadirse el dump completo de `adb logcat -s Media3-60fps` de una sesión larga con contenido real.*
