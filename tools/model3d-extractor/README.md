# model3d-extractor

Descarga los **modelos 3D que una pagina web ya carga** (`.glb`, `.gltf`, `.fbx`, `.obj`...)
usando Playwright. No interactua con la web de forma intrusiva: abre la pagina, deja que
haga sus peticiones y copia los ficheros que reciba.

Pensado para paginas con **three.js / WebGL**, visores de modelos y paginas que embeben
el modelo dentro del propio HTML.

> Herramienta independiente del proyecto Android KarinFLiX.

## Instalacion

```bash
cd tools/model3d-extractor
npm install
npx playwright install chromium
```

## Uso

```bash
node extract.mjs https://ejemplo.com/escena-3d
```

Salida:

```
downloads/ejemplo.com-2026-09-28T04-12-14/
  index.html          visor 3D para abrir los modelos descargados
  manifest.json       inventario: URL de origen, tamano, formato
  assets/
    escena-a1b2c3.glb
```

Abre `index.html` en el navegador para ver los modelos (soporta Draco, Meshopt y KTX2).

## Opciones

| Opcion | Descripcion |
| --- | --- |
| `-o, --out <dir>` | Carpeta destino. Por defecto `downloads/<host>-<fecha>` |
| `--wait <ms>` | Espera tras cargar la pagina (def. 4000). Sube si el modelo tarda |
| `--scroll <n>` | Scroll automatico en `n` pasos para disparar la carga perezosa |
| `--click <sel>` | Clic antes de esperar (repetible) |
| `--select <sel=valor>` | Elige opcion en un `<select>` (variantes de formato) |
| `--wait-for <sel>` | Espera a que aparezca un selector antes de continuar |
| `--ext <lista>` | Solo estas extensiones: `.glb,.gltf,.fbx` (descarta texturas) |
| `--include <regex>` | Solo URLs que coincidan |
| `--exclude <regex>` | Ignorar URLs que coincidan |
| `--ua <texto>` | Cambiar el User-Agent |
| `--proxy <url>` | Proxy del navegador |
| `--viewport <WxH>` | Tamano de ventana (def. `1440x900`) |
| `--nav-timeout <ms>` | Timeout de navegacion (def. 60000) |
| `--no-deps` | No bajar texturas sueltas, solo las que referencia el modelo |
| `--user-data-dir <dir>` | Reutilizar un perfil de Chromium persistente (webs con sesion) |
| `--dry-run` | Solo lista lo detectado, no descarga |
| `--no-viewer` | No generar `index.html` |
| `-h, --headful` | Navegador visible (util para depurar) |

Varias URLs en la misma linea: cada una va a su propia carpeta.

```bash
# Primero mira que detectas, sin descargar nada
node extract.mjs https://ejemplo.com/modelo --dry-run

# Pagina con galeria: recorre y descarga solo glb
node extract.mjs https://ejemplo.com/galeria --scroll 20 --wait 8000 --ext glb

# El modelo solo carga al pulsar un boton
node extract.mjs https://ejemplo.com/demo --click "#ver-3d" --wait 10000

# Web con WAF: mismo UA y proxy que un navegador real
node extract.mjs https://ejemplo.com --ua "Mozilla/5.0 ..." --proxy http://127.0.0.1:8080
```

## webs con sesion (Meshy, Sketchfab, tu cuenta...)

La mayoria de las webs donde hay "tus" modelos exigen iniciar sesion. El extractor
reutiliza un perfil de Chromium persistente:

```bash
# 1) Una sola vez, en ventana visible: inicia sesion tu mismo
node extract.mjs https://www.meshy.ai/es/workspace --user-data-dir ./perfil --headful

# 2) A partir de ahi ya va solo, con las cookies del perfil
node extract.mjs https://www.meshy.ai/es/workspace --user-data-dir ./perfil --no-deps
```

El perfil guarda cookies y `localStorage`. **No lo subas a git** (ya esta en
`.gitignore` si lo creas dentro de la carpeta de la herramienta).

## Notas sobre Meshy.ai

- La vista anonima solo sirve los modelos de ejemplo publicos. Sin sesion no aparecen
  los tuyos.
- **El `.meshy` no se puede abrir con three.js.** No es un formato que falte soportarle
  a un loader: es un contenedor glTF con `EXT_meshopt_compression` cuyo encabezado (8 KB)
  va cifrado con AES-256-CTR usando una clave fija incrustada en el cliente de Meshy.
  Cabecera real del fichero:

  ```
  0..7      magic "MESHY.AI"
  8..9      versión (uint16 LE) = 1
  10..21    nonce AES de 12 bytes
  22..31    reservado
  32..8224  8192 bytes de glTF cifrados
  8224..8240  tag GCM de 16 bytes
  8240..EOF   WebP y flujos meshopt en claro
  ```

  Por eso el visor generado lo marca como propietario en lugar de fingir que cargo.
  La via soportada es **exportar desde la propia web**: el `.glb`/`.fbx`/`.obj` que
  genera la interfaz es glTF normal y se renderiza aqui sin problemas.
- La pagina tambien inyecta un modulo WebAssembly de 33 KB por `data:` URI. El
  extractor lo detecta y lo descarta: no es un modelo.

### Exportar y capturar a la vez

El extractor escucha el evento `download` del navegador, asi que las exportaciones
generadas en la propia pagina se guardan solas y entran en el visor:

```bash
node extract.mjs "https://www.meshy.ai/es/workspace" \
  --user-data-dir ./perfil --click "#exportar-glb" --wait 8000
```

Salida esperada:

```
  v descarga del navegador: model.glb (4200 KB)
  + assets\... .gltf (1 KB, glTF 2.0 JSON)
```

Un `.gltf` exportado junto a su `.bin` se reenlaza al fichero hermano que ya esta en
la carpeta, y si pulsas el boton dos veces no se pisan: el segundo va a
`model (2).glb`.

## Formatos

| Formato | Extension | Estado |
| --- | --- | --- |
| glTF 2.0 | `.glb` | autocontenido |
| glTF 2.0 | `.gltf` | **se desentrela** (buffers y texturas a ficheros sueltos) |
| VRM (avatar) | `.vrm` | autocontenido, se abre en el visor como glTF |
| glTF auto-optimizado | `.sog` | autocontenido |
| FBX | `.fbx` | tal cual |
| Wavefront OBJ | `.obj` | tal cual (el `.mtl` se descarga aparte) |
| STL | `.stl` | tal cual |
| PLY | `.ply` | tal cual |
| MagicaVoxel | `.vox` | tal cual |
| COLLADA | `.dae` | tal cual |
| X3D | `.x3d` | tal cual |
| 3D Studio Max | `.3ds` | tal cual |
| USD (AR) | `.usdz` | tal cual |
| USD | `.usd` `.usda` `.usdc` | tal cual |
| Alembic | `.abc` | tal cual |
| 3D Manufacturing | `.3mf` | tal cual |
| AMF | `.amf` | tal cual |
| BabylonJS | `.babylon` | tal cual |
| VRML | `.vrml` `.wrl` | tal cual |
| OFF (Geomview) | `.off` | tal cual |
| Meshy (propietario) | `.meshy` | tal cual (ver nota abajo) |

Dependencias que tambien se descargan: `.bin` `.png` `.jpg` `.jpeg` `.webp` `.gif`
`.ktx` `.ktx2` `.basis` `.pvr` `.astc` `.hdr` `.exr` `.dds` `.tga` `.bmp` `.rgb`
`.rgbe` `.mtl`

"Tal cual" significa que se guarda el fichero original, sin transformar. Para abrirlo
en un visor 3D necesitaras otra herramienta (Blender, assimp, MeshLab). El `index.html`
que genera el extractor solo abre `.gltf`, `.glb` y `.vrm`; el resto los lista con un
enlace y su formato en la nota final.

Los `.vrm` se renderizan como glTF (malla y texturas). Las poses, expresiones y
blendshapes de un avatar VRM necesitan la libreria `@pixiv/three-vrm`, que el visor
no carga a proposito porque fija su propia version de three.

## Que detecta


1. **Peticiones de red**: respuestas cuyo tipo MIME o extension corresponde a alguno de
   los formatos de la tabla de arriba.
2. **`PerformanceResourceTiming`**: recursos que ya estaban en cache antes de empezar a
   escuchar, que la primeravia se le escaparian.
3. **Modelos embebidos en la pagina**: `data:` URIs que pasan por `fetch`/`XHR` y
   `data:` URIs escritas en el HTML o en los `<script>`. Cada buffer se valida por
   contenido (magic `glTF` o un JSON con `asset.version`), asi que los `data:` URI que
   no son modelos se descartan. Se tolera un byte NUL delante de la cabecera `glTF`,
   porque hay webs que generan el base64 con `btoa` sobre un string y lo dejan delante.
4. **Sin extension y MIME generico** (`application/octet-stream`): se hace un `HEAD`
   y se decide por el `Content-Type`.
5. **Descargas del navegador**: los ficheros que la propia pagina genera al pulsar un
   boton (tipicamente "exportar") se capturan por el evento `download`, se guardan en
   la carpeta de salida y se anaden al visor y al manifiesto.

Por defecto se ignoran las peticiones a trackers y beacons
(analytics, googletagmanager, sentry, hotjar, facebook, apm, ads…). Si pasas tu
propio `--exclude`, sustituye esa lista.

## `.gltf` con dependencias

Un `.gltf` suele repartir el peso entre varios ficheros. La herramienta deja cada modelo
autocontenido:

```
assets/pato-a1b2c3.gltf
assets/pato-a1b2c3.gltf-parts/
  pato-a1b2c3.0.bin     (descargado, o extraido si venia en base64)
  pato-a1b2c3.1.png
```

- Buffers y texturas **embebidos en base64** se decodifican a ficheros sueltos.
- Buffers y texturas **externos** se descargan resolviendo la URL relativa contra la del
  modelo, y se reescriben los `uri` del `.gltf` para que apunten a `./<carpeta>/<fichero>`.
- Lo que ya se descargo como parte de un `.gltf` no se vuelve a bajar como textura suelta.

## Visor

`index.html` se genera con three.js y permite:

- Elegir entre todos los modelos extraidos.
- `wireframe` y rotacion automatica.
- Camara y entorno HDRI integrated, encuadre automatico al cargar.
- Lista de todos los ficheros descargados con enlace directo.

Los `.gltf` se cargan por URL absoluta para que sus rutas relativas a `.bin` y texturas
resuelvan bien en local.

## Casos que no cubre

- Modelos generados en memoria y nunca enviados a la red (WebGL directo, shaders,
  geometria procedural). Aqui no hay fichero que descargar.
- Modelos servidos por streaming/chunking proprietary que no exponen URLs `.gltf`.
- Contenido protegido: si la web exige sesion o token por JS, la peticion se hace desde
  el mismo contexto del navegador, asi que normalmente funciona; si hay DRM o cifrado
  en cliente, no.
- Usa esto con material propio o con licencia que lo permita. Revisa los terminos de la
  web origen: extraerse no equivale a ser libre de uso.

## Estructura

```
extract.mjs     todo el extractor y el generador del visor
package.json    dependencia unica: playwright
```
