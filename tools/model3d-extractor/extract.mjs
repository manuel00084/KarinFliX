#!/usr/bin/env node
/**
 * model3d-extractor
 * Extrae modelos 3D y sus texturas desde paginas web que usan three.js / WebGL.
 *
 *   node extract.mjs https://ejemplo.com/escena
 *
 * No modifica la pagina: solo lee las peticiones de red que la propia web hace.
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { chromium } from 'playwright';

const MODEL_EXT = [
  '.glb', '.gltf', '.vrm', '.sog',
  '.fbx', '.obj', '.stl', '.ply', '.vox', '.dae', '.x3d', '.3ds',
  '.usdz', '.usd', '.usda', '.usdc', '.abc', '.3mf', '.amf',
  '.babylon', '.vrml', '.wrl', '.off', '.meshy',
];

const DEP_EXT = [
  '.bin', '.png', '.jpg', '.jpeg', '.webp', '.gif', '.ktx', '.ktx2',
  '.basis', '.pvr', '.astc', '.hdr', '.exr', '.dds', '.tga', '.bmp',
  '.rgb', '.rgbe', '.mtl', '.bin.js',
];

/** Descripcion legible de cada formato, para el manifest. */
const FORMAT_INFO = {
  '.glb': 'glTF 2.0 binario (autocontenido)',
  '.gltf': 'glTF 2.0 JSON',
  '.vrm': 'VRM (avatar sobre glTF 2.0)',
  '.sog': 'glTF auto-optimizado (Meshopt, SOG)',
  '.fbx': 'FBX (Autodesk)',
  '.obj': 'Wavefront OBJ',
  '.stl': 'STL (STL)',
  '.ply': 'PLY (Stanford)',
  '.vox': 'MagicaVoxel VOX',
  '.dae': 'COLLADA',
  '.x3d': 'X3D',
  '.3ds': '3D Studio Max',
  '.usdz': 'USD zip (AR)',
  '.usd': 'USD',
  '.usda': 'USD (ASCII)',
  '.usdc': 'USD (crate binario)',
  '.abc': 'Alembic',
  '.3mf': '3D Manufacturing Format',
  '.amf': 'Additive Manufacturing Format',
  '.babylon': 'BabylonJS',
  '.vrml': 'VRML',
  '.wrl': 'VRML',
  '.off': 'OFF (Geomview)',
  '.meshy': 'Meshy (formato propietario)',
  '.mtl': 'materiales OBJ',
  '.bin': 'buffer binario',
};

const CT_MAP = [
  [/model\/gltf-binary/i, 'glb'],
  [/model\/gltf\+json/i, 'gltf'],
  [/^image\//i, 'img'],
  [/application\/octet-stream/i, 'bin'],
];

const CT_EXT = {
  glb: '.glb',
  gltf: '.gltf',
  img: '.img',
  bin: '.bin',
};

const GLB_MAGIC = Buffer.from('glTF', 'latin1');

/* ------------------------------------------------------------------ args */

function parseArgs(argv) {
  const opts = {
    urls: [],
    out: null,
    wait: 4000,
    scroll: 0,
    click: [],
    select: [],
    waitFor: null,
    headful: false,
    ua: null,
    include: null,
    exclude: null,
    ext: null,
    navTimeout: 60000,
    viewer: true,
    dryRun: false,
    proxy: null,
    viewport: '1440x900',
    userDataDir: null,
    deps: true,
  };

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--out': case '-o': opts.out = next(); break;
      case '--wait': opts.wait = Number(next()); break;
      case '--scroll': opts.scroll = Number(next()); break;
      case '--click': opts.click.push(next()); break;
      case '--select': opts.select.push(next()); break;
      case '--wait-for': opts.waitFor = next(); break;
      case '--headful': case '-h': opts.headful = true; break;
      case '--ua': opts.ua = next(); break;
      case '--include': opts.include = new RegExp(next(), 'i'); break;
      case '--exclude': opts.exclude = new RegExp(next(), 'i'); break;
      case '--ext': opts.ext = next().split(',').map((e) => (e.startsWith('.') ? e : `.${e}`).toLowerCase()); break;
      case '--nav-timeout': opts.navTimeout = Number(next()); break;
      case '--no-viewer': opts.viewer = false; break;
      case '--dry-run': opts.dryRun = true; break;
      case '--proxy': opts.proxy = next(); break;
      case '--viewport': opts.viewport = next(); break;
      case '--user-data-dir': case '--perfil': opts.userDataDir = next(); break;
      case '--no-deps': opts.deps = false; break;
      case '--help': case '-?': opts.help = true; break;
      default:
        if (a.startsWith('-')) throw new Error(`Opcion desconocida: ${a}`);
        opts.urls.push(a);
    }
  }
  return opts;
}

/**
 * Ruido tipico de cualquier pagina (pixels, beacons, tags). Se ignora salvo que
 * el usuario pase su propio --exclude.
 */
const DEFAULT_EXCLUDE =
  /analytics|googletagmanager|google-analytics|doubleclick|facebook\.net|connect\.facebook|segment\.(io|com)|sentry|hotjar|clarity\.ms|apm\.yahoo|adsct|\/i\/adsct|gtag\/|utm\.gif|scorecardresearch|quantserve|bing\.com\/bat|twitter\.com\/i\/adsct/i;

const HELP = `
model3d-extractor - descarga los modelos 3D que carga una pagina web

Uso:
  node extract.mjs <url> [url...] [opciones]

Opciones:
  -o, --out <dir>        Carpeta destino (por defecto: downloads/<host>-<fecha>)
      --wait <ms>        Espera tras cargar la pagina (def. 4000)
      --scroll <n>       Scroll automatico n pasos para disparar carga perezosa
      --click <sel>      Clic en un selector antes de esperar (repetible)
      --select <sel=val> Elegir opcion en un <select> (repetible)
      --wait-for <sel>   Espera a que aparezca un selector
      --ext <lista>      Solo estas extensiones: .glb,.gltf,.fbx
      --include <regex>  Solo URLs que coincidan
      --exclude <regex>  Ignorar URLs que coincidan
      --ua <string>      User-AgentOverride
      --proxy <url>      Proxy para el navegador
      --viewport <WxH>   Viewport (def. 1440x900)
      --user-data-dir <dir>
                        Reutilizar un perfil de Chromium persistente. Necesario
                        para webs con sesion: arranca una vez con --headful,
                        inicia sesion a mano y las siguientes veces se reutiliza.
      --no-deps          No bajar texturas sueltas: solo los ficheros que el
                        modelo referencia (los .gltf se desentrelan igual)
      --nav-timeout <ms> Timeout de navegacion (def. 60000)
      --dry-run          Solo listar lo que se detecto, no descargar
      --no-viewer        No generar index.html
  -h, --headful          Navegador visible
  -?, --help             Esta ayuda

Formatos de modelo detectados: ${MODEL_EXT.join(' ')}
Dependencias (buffers/texturas): ${DEP_EXT.filter((e) => e !== '.bin.js').join(' ')}
Solo .gltf se desentrela (buffers y texturas a ficheros sueltos). El resto se
guarda tal cual; para convertirlos a glTF hace falta una herramienta aparte.
`;

/* -------------------------------------------------------------- helpers */

const sha1 = (s) => crypto.createHash('sha1').update(s).digest('hex');

function extOf(url) {
  try {
    return path.extname(new URL(url).pathname).toLowerCase();
  } catch {
    return '';
  }
}

function kindFromContentType(ct = '') {
  for (const [re, kind] of CT_MAP) if (re.test(ct)) return kind;
  return null;
}

function isModelExt(e) { return MODEL_EXT.includes(e); }
function isDepExt(e) { return DEP_EXT.includes(e); }

function slugify(text, max = 48) {
  const base = decodeURIComponent(text)
    .replace(/\.[a-z0-9]{1,6}$/i, '')
    .replace(/[^a-z0-9]+/gi, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
  return (base || 'asset').slice(0, max);
}

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

/** Evita pisar ficheros ya descargados anadiendo (2), (3)... */
async function uniquePath(file) {
  if (!await existsSize(file, null)) return file;
  const ext = path.extname(file);
  const stem = file.slice(0, -ext.length);
  for (let i = 2; i < 200; i++) {
    const cand = `${stem} (${i})${ext}`;
    if (!await existsSize(cand, null)) return cand;
  }
  return `${stem}-${Date.now()}${ext}`;
}

async function existsSize(file, size) {
  try {
    const st = await fs.stat(file);
    return size == null || st.size === size;
  } catch {
    return false;
  }
}

/* ------------------------------------------------------- page discovery */

async function openPage(context, opts, outDir) {
  const page = await context.newPage();
  page.setDefaultTimeout(opts.navTimeout);

  const hits = new Map(); // url -> record
  const grabs = [];       // descargas hechas por la pagina (boton "exportar")
  const log = opts.log ?? ((m) => process.stdout.write(m + '\n'));
  let pending = 0;

  page.on('download', async (dl) => {
    pending++;
    try {
      const suggested = dl.suggestedFilename() || 'modelo-descargado';
      const dest = await uniquePath(path.join(outDir, suggested.replace(/[\\/:*?"<>|]/g, '_')));
      await dl.saveAs(dest);
      const st = await fs.stat(dest);
      grabs.push(dest);
      const kb = st.size > 1024 * 1024 ? `${(st.size / 1048576).toFixed(1)} MB` : `${Math.round(st.size / 1024)} KB`;
      log(`  v descarga del navegador: ${path.basename(dest)} (${kb})`);
    } catch (e) {
      log(`  ! descarga del navegador fallo: ${e.message.split('\n')[0]}`);
    } finally {
      pending--;
    }
  });

  /** Espera a que terminen las descargas en curso (exportaciones lentas). */
  const settle = async (ms = 20000) => {
    const t0 = Date.now();
    while (pending > 0 && Date.now() - t0 < ms) await page.waitForTimeout(250);
  };

  const record = (url, extra = {}) => {
    if (!url || url.startsWith('blob:') || url.startsWith('about:')) return;
    let abs;
    try { abs = new URL(url, page.url()).href; } catch { return; }
    const prev = hits.get(abs);
    if (prev) { Object.assign(prev, extra, { seen: prev.seen + 1 }); return prev; }
    const rec = { url: abs, source: extra.source ?? 'network', seen: 1, ...extra };
    hits.set(abs, rec);
    return rec;
  };

  page.on('response', (res) => {
    try {
      record(res.url(), {
        source: 'network',
        status: res.status(),
        contentType: res.headers()['content-type'] ?? '',
      });
    } catch { /* respuesta ya liberada */ }
  });

  return { page, hits, record, grabs, settle };
}

async function collect(page, hits, record, opts) {
  // 1) performance entries: tambien cubre recursos servidos desde cache
  try {
    const entries = await page.evaluate(() =>
      performance.getEntriesByType('resource').map((e) => ({
        name: e.name,
        transferSize: e.transferSize,
        initiatorType: e.initiatorType,
      })),
    );
    for (const e of entries) {
      record(e.name, { source: 'performance', initiatorType: e.initiatorType, hintedSize: e.transferSize });
    }
  } catch { /* pagina sin performance */ }

  // 2) data: URIs en linea (modelos embebidos en el HTML/JS)
  try {
    const uris = await page.evaluate(() => window.__m3d_dataUris ?? []);
    for (const u of uris) {
      record(u, { source: 'inline-data-uri', inline: true });
    }
  } catch { /* sin hook */ }

  // 3) barrido del HTML/scripts en busca de data: URIs de glTF
  try {
    const inSource = await page.evaluate(() => {
      const re = /data:(?:model\/gltf[-\w.+]*|application\/octet-stream);base64,[A-Za-z0-9+/=]{24,}/g;
      const out = new Set();
      let bytes = 0;
      const scan = (t) => {
        if (typeof t !== 'string') return;
        for (const m of t.matchAll(re)) {
          if (out.size >= 12 || bytes > 64e6) return;
          bytes += m[0].length;
          out.add(m[0]);
        }
      };
      for (const s of document.scripts) scan(s.textContent);
      scan(document.documentElement.outerHTML);
      return [...out];
    });
    for (const u of inSource) record(u, { source: 'inline-in-source', inline: true });
  } catch { /* sin DOM */ }
}

async function autoScroll(page, steps) {
  for (let i = 0; i < steps; i++) {
    await page.evaluate(() => window.scrollBy(0, Math.round(window.innerHeight * 0.8))).catch(() => {});
    await page.waitForTimeout(400);
  }
  await page.evaluate(() => window.scrollTo(0, 0)).catch(() => {});
  await page.waitForTimeout(500);
}

/* ------------------------------------------------------------- download */

async function fetchBuffer(request, url) {
  try {
    const res = await request.get(url, { timeout: 120000, failOnStatusCode: false });
    if (!res.ok()) return { ok: false, status: res.status(), body: null, headers: res.headers() };
    return { ok: true, status: res.status(), body: await res.body(), headers: res.headers() };
  } catch (e) {
    return { ok: false, status: 0, body: null, error: e.message.split('\n')[0] };
  }
}

function decodeDataUri(uri) {
  const m = /^data:([^;,]*)?(;base64)?,/.exec(uri);
  if (!m) return null;
  const isB64 = Boolean(m[2]);
  const payload = uri.slice(m[0].length);
  return isB64
    ? Buffer.from(payload, 'base64')
    : Buffer.from(decodeURIComponent(payload), 'binary');
}

/** Decodifica solo los primeros bytes de un data: URI en base64 (barato). */
function peekDataUri(uri, bytes = 16) {
  const i = uri.indexOf(',');
  if (i < 0 || !uri.slice(0, i).endsWith('base64')) return null;
  const chunk = uri.slice(i + 1, i + 1 + Math.ceil(bytes / 3) * 4);
  const buf = Buffer.from(chunk, 'base64');
  return buf.length ? buf.subarray(0, bytes) : null;
}

/**
 * Identifica el formato real por contenido.
 * Devuelve { kind: 'glb' | 'gltf', offset } o null.
 * Se tolera un offset pequeno antes del magic 'glTF': cuando el base64 lo genera
 * el JS de la propia pagina (btoa sobre un string) suele colarse un byte NUL
 * delante, y un GLB con cabecera desplazada no lo carga ni three.js ni Blender.
 */
function sniffModel(buf) {
  if (!buf || buf.length < 8) return null;
  const off = buf.indexOf(GLB_MAGIC, 0);
  if (off >= 0 && off <= 8 && buf.readUInt32LE(off + 4) === 2) return { kind: 'glb', offset: off };
  if (!buf.subarray(0, 4096).toString('utf8').replace(/^[\0\s]+/, '').startsWith('{')) return null;
  try {
    const j = JSON.parse(buf.toString('utf8'));
    return j?.asset?.version ? { kind: 'gltf', offset: 0 } : null;
  } catch {
    return null;
  }
}

/**
 * Procesa un .gltf: descarga buffers/texturas externos, extrae los embebidos
 * en base64 y reescribe el JSON para que apunte a ficheros locales.
 */
async function unpackGltf(gltfJson, baseUrl, modelDir, baseName, request, log, referenced) {
  const local = [];
  const relDir = `${baseName}.gltf-parts`;
  const rewrite = async (list, kind) => {
    if (!Array.isArray(list)) return;
    for (const entry of list) {
      const uri = entry?.uri;
      if (!uri) continue;
      if (uri.startsWith('data:')) {
        const buf = decodeDataUri(uri);
        if (!buf) continue;
        const name = `${baseName}.${local.length}${kind === 'image' ? extOf(uri) || '.png' : '.bin'}`;
        local.push({ name, buf, kind, embedded: true });
        entry.uri = `./${relDir}/${name}`;
      } else {
        let abs;
        try { abs = new URL(uri, baseUrl ?? 'https://localhost/').href; } catch { continue; }
        // Si el .gltf venia de una descarga del navegador, sus hermanos (.bin,
        // texturas) ya estan en la misma carpeta: se enlazan, no se vuelven a bajar.
        const sib = path.basename(new URL(abs).pathname);
        if (await existsSize(path.join(modelDir, sib), null)) {
          entry.uri = `./${sib}`;
          if (referenced) referenced.add(sib);
          continue;
        }
        const name = `${baseName}.${local.length}${extOf(abs) || (kind === 'image' ? '.png' : '.bin')}`;
        local.push({ name, url: abs, kind, embedded: false });
        if (referenced) referenced.add(abs);
        entry.uri = `./${relDir}/${name}`;
      }
    }
  };
  await rewrite(gltfJson.buffers, 'buffer');
  await rewrite(gltfJson.images, 'image');

  const folder = path.join(modelDir, relDir);
  if (local.length) await ensureDir(folder);
  for (const item of local) {
    let buf = item.buf;
    if (!buf) {
      const r = await fetchBuffer(request, item.url);
      if (!r.ok) { log(`  ! ${item.name}: ${r.error ?? 'HTTP ' + r.status}`); continue; }
      buf = r.body;
    }
    await fs.writeFile(path.join(folder, item.name), buf);
    log(`  + ${relDir}\\${item.name} (${(buf.length / 1024).toFixed(0)} KB${item.embedded ? ', embebido' : ''})`);
  }

  return { json: JSON.stringify(gltfJson), folder: local.length ? folder : null, parts: local.map((p) => p.name) };
}

/* --------------------------------------------------------------- viewer */

function buildViewerHtml(items) {
  // El visor solo sabe abrir glTF; el resto se listan como enlaces.
  const models = items.filter((i) => /\.(gltf|glb|vrm)$/i.test(i.file ?? ''));
  const others = items.filter((i) => !models.includes(i));
  const json = JSON.stringify(models.map((m) => m.file).map((f) => f.replace(/\\/g, '/')), null, 2);

  // Aviso especifico para formatos propietarios/cifrados: no es que el visor falle.
  const locked = others.filter((o) => /\.meshy$/i.test(o.file ?? ''));
  const notes = [];
  if (locked.length) {
    notes.push(
      '<b style="color:#ffb86b">Formato propietario de Meshy (.meshy): no se puede abrir aqui.</b> ' +
      'Son contenedores glTF cifrados con una clave embebida en el cliente de Meshy, ' +
      'asi que ningun loader de three.js los lee. Para usarlo, exportalo desde la propia web ' +
      'a GLB/FBX/OBJ y capturalo con <code>--click</code>: ' +
      '<code>node extract.mjs &lt;url&gt; --click "#exportar" --user-data-dir ./perfil</code>',
    );
  }
  if (others.length) {
    notes.push(
      `${others.length} fichero(s) de otros formatos (FBX, OBJ, USDZ\u2026) no se pueden abrir aqui: ` +
      `<code>${others.map((o) => o.file).join('</code>, <code>')}</code>`,
    );
  }
  if (!notes.length) notes.push('Todos los ficheros son glTF/VRM y se pueden abrir con el selector.');

  return `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Modelos extraidos</title>
<style>
  body{margin:0;font:14px/1.5 system-ui,Segoe UI,sans-serif;background:#0e0f13;color:#e8eaf0}
  header{padding:12px 16px;border-bottom:1px solid #262a35;display:flex;gap:16px;align-items:center;flex-wrap:wrap}
  h1{font-size:15px;margin:0;font-weight:600}
  select,button,label{background:#1a1d26;color:#e8eaf0;border:1px solid #313747;border-radius:6px;padding:6px 10px;font:inherit}
  #stage{height:calc(100vh - 58px)}
  #list{padding:8px 16px;border-top:1px solid #262a35;max-height:32vh;overflow:auto}
  #note{padding:6px 16px;font-size:12px;color:#9aa4b8;border-top:1px solid #262a35}
  .solo{margin:auto;max-width:720px;padding:24px;font-size:13px;color:#c8d0e0;line-height:1.7}
  a{color:#7fb2ff} code{color:#9aa4b8}
</style>
<script type="importmap">
{"imports":{"three":"https://unpkg.com/three@0.169.0/build/three.module.js",
"three/addons/":"https://unpkg.com/three@0.169.0/examples/jsm/"}}
</script>
</head>
<body>
<header>
  <h1>${models.length} modelo(s)</h1>
  <select id="model">${models.map((m) => `<option value="${m.file}">${m.file}</option>`).join('')}</select>
  <button id="reload">Recargar</button>
  <label><input type="checkbox" id="wire"> wireframe</label>
  <label><input type="checkbox" id="spin" checked> rotar</label>
  <span id="hud"></span>
</header>
<div id="stage">${models.length ? '' : `<div class="solo">${notes.join('<br>')}</div>`}</div>
<div id="list">
  <strong>Todos los ficheros descargados</strong>
  <ul>${items.map((i) => `<li><a href="${i.file}">${i.file}</a> <code>${i.size} B</code>${i.format ? ' \u00b7 ' + i.format : ''}${i.referenced === false ? ' (no referenciado)' : ''}</li>`).join('')}</ul>
</div>
<div id="note">${notes.join('<br>')}</div>
<script type="module">
const MODELS = ${json};
const list = document.getElementById('model');
const wire = document.getElementById('wire');
if (list && MODELS.length) list.addEventListener('change', () => location.search = '?m=' + encodeURIComponent(list.value));

const THREE = await import('three');
const { GLTFLoader } = await import('three/addons/loaders/GLTFLoader.js');
const { DRACOLoader } = await import('three/addons/loaders/DRACOLoader.js');
const { KTX2Loader } = await import('three/addons/loaders/KTX2Loader.js');
const { RoomEnvironment } = await import('three/addons/environments/RoomEnvironment.js');
const { MeshoptDecoder } = await import('three/addons/libs/meshopt_decoder.module.js');

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
document.getElementById('stage').appendChild(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0e0f13);
const camera = new THREE.PerspectiveCamera(50, 1, 0.01, 5000);
scene.add(new THREE.HemisphereLight(0xffffff, 0x334, 2));
const key = new THREE.DirectionalLight(0xffffff, 2); key.position.set(3, 5, 4); scene.add(key);
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

const draco = new DRACOLoader().setDecoderPath('https://www.gstatic.com/draco/versioned/decoders/1.5.7/');
const ktx2 = new KTX2Loader().setTranscoderPath('https://cdn.jsdelivr.net/npm/three@0.169.0/examples/jsm/libs/basis/').detectSupport(renderer);
const loader = new GLTFLoader().setDRACOLoader(draco).setKTX2Loader(ktx2).setMeshoptDecoder(MeshoptDecoder);

// Un .vrm es un glTF con la extension VRMC_vrm: GLTFLoader lo abre igual.
// (La libreria three-vrm haria falta solo para poses, expresiones y blendshapes.)
let current = null;
async function load(file) {
  if (current) { scene.remove(current); dispose(current); }
  // URL absoluta: GLTFLoader resuelve los .bin/texturas relativas a esta base
  const gltf = await loader.loadAsync(new URL(file, location.href).href);
  current = gltf.scene;
  current.traverse((o) => { if (o.isMesh) o.material.wireframe = wire.checked; });
  const box = new THREE.Box3().setFromObject(current);
  const size = box.getSize(new THREE.Vector3()).length() || 1;
  const center = box.getCenter(new THREE.Vector3());
  current.position.sub(center);
  camera.position.set(size * 0.9, size * 0.6, size * 1.1);
  camera.near = size / 100; camera.far = size * 20; camera.updateProjectionMatrix();
  controls.target.set(0, 0, 0);
  const isVrm = /\.vrm$/i.test(file);
  document.getElementById('hud').textContent =
    (isVrm ? 'VRM (malla en T-pose, sin poses VRM)' : '') + (isVrm ? ' \u00b7 ' : '') + size.toFixed(2) + ' u';
}
function dispose(o){ o.traverse((n)=>{ n.geometry?.dispose?.(); Array.isArray(n.material)?n.material.forEach(m=>m.dispose()):n.material?.dispose?.(); }); }

const { OrbitControls } = await import('three/addons/controls/OrbitControls.js');
const controls = new OrbitControls(camera, renderer.domElement);
const stage = document.getElementById('stage');
function resize() {
  renderer.setSize(stage.clientWidth, stage.clientHeight);
  camera.aspect = stage.clientWidth / stage.clientHeight; camera.updateProjectionMatrix();
}
addEventListener('resize', resize);
const spin = document.getElementById('spin');
(function loop(){ requestAnimationFrame(loop);
  if (spin.checked && current) current.rotation.y += 0.004;
  controls.update(); renderer.render(scene, camera);
})();
resize();
const wanted = new URLSearchParams(location.search).get('m') || MODELS[0];
if (list) list.value = MODELS.includes(wanted) ? wanted : MODELS[0];
if (list && list.value) load(list.value).catch(e => document.getElementById('hud').textContent = 'Error: ' + e.message);
document.getElementById('reload').onclick = () => list && load(list.value);
document.getElementById('wire').onchange = (e) => current?.traverse(o => { if (o.isMesh) o.material.wireframe = e.target.checked; });
</script>
</body>
</html>`;
}

/* ----------------------------------------------------------------- main */

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help || !opts.urls.length) { process.stdout.write(HELP); return opts.help ? 0 : 1; }

  const log = (m) => process.stdout.write(m + '\n');
  opts.log = log;
  const wantedExt = opts.ext ? [...opts.ext] : null;
  const exclude = opts.exclude ?? DEFAULT_EXCLUDE;

  const viewport = (() => {
    const [w, h] = opts.viewport.split('x').map(Number);
    return { width: w || 1440, height: h || 900 };
  })();
  const launchArgs = ['--enable-unsafe-swiftshader', '--disable-blink-features=AutomationControlled'];

  // Con --user-data-dir se reutiliza un perfil de Chromium persistente: es la
  // via para las paginas que exigen sesion (el usuario inicia sesion una vez
  // en modo visible y despues el extractor reutiliza sus cookies).
  let browser = null;
  let baseContext = null;
  if (opts.userDataDir) {
    const dir = path.resolve(opts.userDataDir);
    await ensureDir(dir);
    baseContext = await chromium.launchPersistentContext(dir, {
      headless: !opts.headful,
      proxy: opts.proxy ? { server: opts.proxy } : undefined,
      viewport,
      ignoreHTTPSErrors: true,
      acceptDownloads: true,
      args: launchArgs,
    });
    log(`perfil: ${dir}`);
  } else {
    browser = await chromium.launch({
      headless: !opts.headful,
      proxy: opts.proxy ? { server: opts.proxy } : undefined,
      args: launchArgs,
    });
  }

  const results = [];
  let exitCode = 0;

  try {
    for (const target of opts.urls) {
      const url = /^(https?|file):\/\//i.test(target) ? target : `https://${target}`;
      const parsed = new URL(url);
      const host = (parsed.host || 'local').replace(/[^\w.-]/g, '_');
      const outDir = opts.out && opts.urls.length === 1
        ? path.resolve(opts.out)
        : path.resolve(opts.out ?? 'downloads', `${host}-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}`);

      log(`\n=== ${url}`);
      await ensureDir(outDir);

      const context = baseContext ?? await browser.newContext({
        userAgent: opts.ua ?? undefined,
        viewport,
        ignoreHTTPSErrors: true,
        acceptDownloads: true,
      });

      // Hook de fetch/XHR para detectar modelos embebidos como data: URI
      await context.addInitScript(() => {
        window.__m3d_dataUris = [];
        const keep = (u) => typeof u === 'string' && u.startsWith('data:') && u.length < 5e8
          && (/^data:model\//.test(u) || /^data:application\/octet-stream/.test(u));
        const of = window.fetch;
        window.fetch = function (input, ...rest) {
          try { const u = typeof input === 'string' ? input : input?.url; if (keep(u)) window.__m3d_dataUris.push(u); } catch {}
          return of.call(this, input, ...rest);
        };
        const oo = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function (m, u, ...rest) {
          try { if (keep(u)) window.__m3d_dataUris.push(u); } catch {}
          return oo.call(this, m, u, ...rest);
        };
      });

      const { page, hits, record, grabs, settle } = await openPage(context, opts, outDir);

      try {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: opts.navTimeout });
        if (opts.waitFor) await page.waitForSelector(opts.waitFor, { timeout: opts.navTimeout });
        for (const sel of opts.click) {
          await page.click(sel, { timeout: 5000 }).catch((e) => log(`  ! clic "${sel}" fallo: ${e.message.split('\n')[0]}`));
          await page.waitForTimeout(500);
        }
        for (const pair of opts.select) {
          const eq = pair.lastIndexOf('=');
          const sel = eq === -1 ? pair : pair.slice(0, eq);
          const val = eq === -1 ? '' : pair.slice(eq + 1);
          await page.selectOption(sel, val, { timeout: 5000 })
            .catch((e) => log(`  ! select "${sel}" fallo: ${e.message.split('\n')[0]}`));
          await page.waitForTimeout(500);
        }
        if (opts.scroll > 0) await autoScroll(page, opts.scroll);
        await page.waitForTimeout(opts.wait);
        await collect(page, hits, record, opts);
        // las exportaciones (.glb, .fbx...) llegan como descarga del navegador
        if (grabs.length || opts.click.length) await settle();
      } catch (e) {
        log(`  ! navegacion: ${e.message.split('\n')[0]}`);
        await collect(page, hits, record, opts).catch(() => {});
      }

      /* ---- filtrado de candidatos ---- */
      const candidates = [];
      for (const hit of hits.values()) {
        const { url: u } = hit;
        if (opts.include && !opts.include.test(u)) continue;
        if (exclude.test(u)) continue;

        let ext = extOf(u);
        let kind;
        if (hit.inline) {
          // data: URI: se decide por los primeros bytes para no listar como
          // modelo lo que no lo es (un .wasm, un .bin de buffer, etc.).
          const head = peekDataUri(u);
          if (!head) continue;
          if (head.subarray(0, 4).equals(GLB_MAGIC)) { kind = 'model'; ext = '.glb'; }
          else if (head.toString('utf8').replace(/^[\0\s]+/, '').startsWith('{')) { kind = 'model'; ext = '.gltf'; }
          else continue;
        } else if (isModelExt(ext)) kind = 'model';
        else if (isDepExt(ext)) kind = 'dep';
        else kind = kindFromContentType(hit.contentType) ? 'sniff' : null;

        if (!kind) continue;
        if (wantedExt && !wantedExt.includes(ext || CT_EXT[kind])) continue;
        if (kind === 'dep' && wantedExt) continue;
        // 'sniff' = sin extension util: se decide por Content-Type en la descarga.
        // Colapsarlo a 'model' hacia pasar pixeles de analytics por modelos.
        const finalKind = kind === 'model' ? 'model' : kind === 'dep' ? 'dep' : 'sniff';
        candidates.push({ ...hit, ext: ext || CT_EXT[kind], kind: finalKind });
      }

      if (!candidates.length) {
        log('  (ningun modelo 3D detectado)');
      }

      if (opts.dryRun) {
        for (const c of candidates.sort((a, b) => a.url.localeCompare(b.url))) {
          const shown = c.url.length > 110 ? c.url.slice(0, 70) + '…' + c.url.slice(-35) : c.url;
          log(`  ${c.kind.padEnd(5)} ${String(c.hintedSize ?? '').padStart(8)}  ${shown}`);
        }
        if (!baseContext) await context.close();
        continue;
      }

      /* ---- descarga ---- */
      const assetsDir = path.join(outDir, 'assets');
      const saved = [];
      const seenHash = new Map();
      const referenced = new Set();

      for (const c of candidates.filter((x) => x.kind === 'model')) {
        const base = c.inline
          ? `inline-${sha1(c.url).slice(0, 10)}`
          : `${slugify(new URL(c.url).pathname)}-${sha1(c.url).slice(0, 6)}`;

        if (c.inline) {
          const raw = decodeDataUri(c.url);
          if (!raw) continue;
          const found = sniffModel(raw);
          if (!found) {
            const wasm = raw.subarray(0, 4).toString('latin1') === '\0asm';
            log(`  ? data: URI ignorada (${raw.length} B, ${wasm ? 'WebAssembly, no es un modelo' : 'no es glTF'})`);
            continue;
          }
          const buf = found.offset ? raw.subarray(found.offset) : raw;
          const file = path.join(assetsDir, base + '.' + found.kind);
          await ensureDir(assetsDir);
          await fs.writeFile(file, buf);
          const skipped = raw.length - buf.length;
          log(`  + ${path.relative(outDir, file)} (${(buf.length / 1024).toFixed(0)} KB, inline${skipped ? `, ${skipped} B de cabecera sobrante` : ''})`);
          saved.push({
            file: path.relative(outDir, file).replace(/\\/g, '/'),
            size: buf.length,
            source: 'inline data: URI',
            format: FORMAT_INFO['.' + found.kind],
          });
          if (found.kind === 'gltf') await handleGltf(file, buf, page.url(), outDir, base, context.request, log, referenced, saved);
          continue;
        }

        const r = await fetchBuffer(context.request, c.url);
        if (!r.ok) { log(`  ! ${c.url}: ${r.error ?? 'HTTP ' + r.status}`); continue; }
        const buf = r.body;
        const hash = crypto.createHash('sha256').update(buf).digest('hex');
        if (seenHash.has(hash)) { log(`  = duplicado de ${seenHash.get(hash)}`); continue; }
        seenHash.set(hash, path.basename(new URL(c.url).pathname));

        const sniffed = isModelExt(c.ext) ? null : sniffModel(buf);
        const ext = isModelExt(c.ext)
          ? c.ext
          : (sniffed ? '.' + sniffed.kind : (CT_EXT[kindFromContentType(c.contentType)] ?? '.bin'));
        const payload = sniffed?.offset ? buf.subarray(sniffed.offset) : buf;
        const file = path.join(assetsDir, base + ext);
        await ensureDir(assetsDir);
        await fs.writeFile(file, payload);
        log(`  + ${path.relative(outDir, file)} (${(payload.length / 1024).toFixed(0)} KB, ${FORMAT_INFO[ext] ?? 'modelo'})`);
        saved.push({
          file: path.relative(outDir, file).replace(/\\/g, '/'),
          size: payload.length,
          source: c.url,
          contentType: c.contentType,
          format: FORMAT_INFO[ext] ?? null,
        });

        if (ext === '.gltf') await handleGltf(file, buf, c.url, outDir, base, context.request, log, referenced, saved);
      }

      /* ---- dependencias sueltas (texturas, .bin) ---- */
      for (const c of (opts.deps ? candidates : candidates.filter((x) => x.kind === 'model'))
        .filter((x) => x.kind === 'dep' || x.kind === 'sniff')) {
        if (referenced.has(c.url)) continue;
        if (c.kind === 'sniff') {
          const head = await context.request.fetch(c.url, { method: 'HEAD', timeout: 20000 }).catch(() => null);
          const ct = head?.headers()?.['content-type'] ?? c.contentType ?? '';
          if (!/gltf|^image\/|octet-stream/i.test(ct)) continue;
        }
        const r = await fetchBuffer(context.request, c.url);
        if (!r.ok) continue;
        const hash = crypto.createHash('sha256').update(r.body).digest('hex');
        if (seenHash.has(hash)) continue;
        seenHash.set(hash, path.basename(new URL(c.url).pathname));
        const ext = c.ext && isDepExt(c.ext) ? c.ext : path.extname(new URL(c.url).pathname) || '.bin';
        const base = `${slugify(new URL(c.url).pathname)}-${sha1(c.url).slice(0, 6)}`;
        const file = path.join(assetsDir, base + ext);
        await ensureDir(assetsDir);
        await fs.writeFile(file, r.body);
        log(`  + ${path.relative(outDir, file)} (${(r.body.length / 1024).toFixed(0)} KB, ${FORMAT_INFO[ext] ?? 'dependencia'})`);
        saved.push({
          file: path.relative(outDir, file).replace(/\\/g, '/'),
          size: r.body.length,
          source: c.url,
          format: FORMAT_INFO[ext] ?? null,
          referenced: false,
        });
      }

      /* ---- descargas hechas por la pagina (boton de exportar) ---- */
      for (const g of grabs) {
        const ext = path.extname(g).toLowerCase();
        const rel = path.relative(outDir, g).replace(/\\/g, '/');
        if (saved.some((s) => s.file === rel)) continue;
        const st = await fs.stat(g);
        saved.push({
          file: rel,
          size: st.size,
          source: 'descarga del navegador',
          format: FORMAT_INFO[ext] ?? null,
        });
        if (ext === '.gltf') {
          await handleGltf(g, await fs.readFile(g), url, outDir, path.basename(g, ext), context.request, log, referenced, saved);
        }
      }

      const manifest = {
        source: url,
        capturedAt: new Date().toISOString(),
        userAgent: await page.evaluate(() => navigator.userAgent).catch(() => null),
        files: saved,
      };      await fs.writeFile(path.join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2));

      if (opts.viewer) {
        await fs.writeFile(path.join(outDir, 'index.html'), buildViewerHtml(saved));
        log(`  i visor: ${path.join(outDir, 'index.html')}`);
      }
      log(`  -> ${saved.length} fichero(s) en ${outDir}`);
      results.push({ url, outDir, count: saved.length });
      if (!baseContext) await context.close();
    }
  } finally {
    await (baseContext ?? browser).close();
  }

  log(`\nResumen: ${results.map((r) => `${r.count} (${r.url})`).join(', ') || 'nada'}`);
  return exitCode;
}

async function handleGltf(file, buf, sourceUrl, outDir, base, request, log, referenced, saved) {
  let json;
  try { json = JSON.parse(buf.toString('utf8')); } catch { return; }
  if (!json?.asset?.version) return;
  const modelDir = path.dirname(file);
  const { json: patched, parts } = await unpackGltf(json, sourceUrl, modelDir, base, request, log, referenced);
  if (!parts.length) return;
  await fs.writeFile(file, patched);
  for (const p of parts) {
    const partFile = path.relative(outDir, path.join(modelDir, `${base}.gltf-parts`, p)).replace(/\\/g, '/');
    if (!saved.some((s) => s.file === partFile)) {
      const st = await fs.stat(path.join(outDir, partFile));
      saved.push({ file: partFile, size: st.size, partOf: path.basename(file) });
    }
  }
}

main().then((code) => process.exit(code ?? 0)).catch((e) => {
  process.stderr.write(`\nError: ${e.stack ?? e}\n`);
  process.exit(1);
});
