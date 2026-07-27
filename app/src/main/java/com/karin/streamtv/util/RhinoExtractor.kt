package com.karin.streamtv.util

import android.util.Base64
import android.util.Log
import okhttp3.Request
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

object RhinoExtractor {

    private const val TAG = "RhinoExtractor"
    private const val MAX_SCRIPT_TOTAL_BYTES = 2 * 1024 * 1024
    private val client = Http.client

    private val VIDEO_RE = Regex(
        """(https?://[^\s"'<>\\]+\.(?:mp4|m3u8|mpd|webm)(?:\?[^\s"'<>\\]*)?)""",
        RegexOption.IGNORE_CASE
    )

    fun extractVideoUrl(html: String, baseUrl: String = ""): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val inlineScripts = doc.select("script:not([src])").map { it.html() }

        val externalScripts = mutableListOf<String>()
        var externalTotalBytes = 0
        doc.select("script[src]").forEach { el ->
            if (externalTotalBytes >= MAX_SCRIPT_TOTAL_BYTES) return@forEach
            val src = el.attr("abs:src").ifBlank { el.attr("src") }
            if (src.isNotBlank()) {
                val fetched = fetchExternalScript(src, baseUrl)
                if (fetched != null) {
                    externalTotalBytes += fetched.toByteArray().size
                    if (externalTotalBytes <= MAX_SCRIPT_TOTAL_BYTES) {
                        externalScripts.add(fetched)
                    }
                }
            }
        }

        val allJs = (externalScripts + inlineScripts).joinToString("\n;\n")
        if (allJs.isBlank()) return null

        Log.d(TAG, "Running Rhino on ${inlineScripts.size} inline + ${externalScripts.size} external scripts (${allJs.length} chars)")
        ExtractionLogger.logRhinoEval(inlineScripts.size, externalScripts.size, allJs.length, 0)

        val preprocessedJs = preprocessEs6Plus(allJs)

        var cx: Context? = null
        try {
            cx = Context.enter()
            cx.optimizationLevel = -1
            try {
                cx.languageVersion = Context.VERSION_ES6
            } catch (_: Throwable) {
                cx.languageVersion = Context.VERSION_DEFAULT
            }
            val scope = cx.initStandardObjects()

            val envJs = buildEnvironmentJs(baseUrl)
            try {
                cx.evaluateString(scope, envJs, "env", 1, null)
            } catch (e: Throwable) {
                Log.w(TAG, "Rhino env eval failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            val interceptJs = buildInterceptJs()
            cx.evaluateString(scope, interceptJs, "intercept", 1, null)

            cx.evaluateString(scope, preprocessedJs, "embed-js", 1, null)

            val captured = readCapturedArray(scope)

            val fromScripts = VIDEO_RE.findAll(allJs)
                .map { it.groupValues[1] }
                .filter { it.length > 15 }
                .toList()

            val all = (captured + fromScripts)
                .filter { url ->
                    !url.contains("google", ignoreCase = true) &&
                            !url.contains("facebook", ignoreCase = true) &&
                            !url.contains("analytics", ignoreCase = true) &&
                            !url.contains("doubleclick", ignoreCase = true) &&
                            !url.contains("adsense", ignoreCase = true) &&
                            !url.contains("tracking", ignoreCase = true) &&
                            !url.contains("pixel", ignoreCase = true) &&
                            !url.endsWith(".js", ignoreCase = true) &&
                            !url.endsWith(".css", ignoreCase = true) &&
                            !url.contains("/ads/", ignoreCase = true) &&
                            !url.contains("/ad/", ignoreCase = true) &&
                            !url.contains("amazon-adsystem", ignoreCase = true) &&
                            !url.contains("googlesyndication", ignoreCase = true)
                }
                .distinct()

            Log.d(TAG, "Rhino: ${all.size} valid URLs from ${captured.size} captured + ${fromScripts.size} from scripts")
            ExtractionLogger.logRhinoEval(inlineScripts.size, externalScripts.size, allJs.length, all.size)
            all.forEachIndexed { i, u -> Log.d(TAG, "  [$i] ${u.take(150)}") }

            return all.firstOrNull()
        } catch (e: Throwable) {
            Log.e(TAG, "Rhino failed: ${e.message}")
            return null
        } finally {
            try { Context.exit() } catch (_: Throwable) {}
        }
    }

    private fun fetchExternalScript(src: String, baseUrl: String): String? {
        return try {
            val url = if (src.startsWith("http")) src else {
                try {
                    val base = java.net.URL(baseUrl)
                    java.net.URL(base, src).toString()
                } catch (_: Exception) { return null }
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", baseUrl)
                .build()
            val response = client.newBuilder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
            val body = response.body?.string()
            response.close()
            body
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch external script: $src — ${e.message}")
            null
        }
    }

    private fun preprocessEs6Plus(js: String): String {
        var result = js
        result = result.replace(Regex("""(?<!\w)const\s+"""), "var ")
        result = result.replace(Regex("""(?<!\w)let\s+"""), "var ")
        result = result.replace(Regex("""\bof\s*\("""), ".of(")
        result = result.replace(Regex("""(?<=[,.\[(\s:!<>=&|?+\-])\s*=>\s*"""), "function ")
        result = result.replace(Regex("""(\w+)\s*=>\s*(\{)"""), "function($1) $2")
        result = result.replace(Regex("""\.\.\.(?=\w)"""), "")
        return result
    }

    private fun buildEnvironmentJs(baseUrl: String): String {
        val safeBaseUrl = baseUrl.replace("\\", "\\\\").replace("'", "\\'")
        return """
        var __captured = [];
        var __baseUrl = '$safeBaseUrl';

        function __hookUrl(u) {
            try {
                var s = String(u);
                if (s.length > 10 && /^https?:\/\//.test(s)) {
                    __captured.push(s);
                }
            } catch(e) {}
        }

        function __hookUrlFromObj(obj) {
            try {
                if (typeof obj === 'string') { __hookUrl(obj); return; }
                if (obj && typeof obj === 'object') {
                    if (obj.src) __hookUrl(String(obj.src));
                    if (obj.file) __hookUrl(String(obj.file));
                    if (obj.url) __hookUrl(String(obj.url));
                    if (obj.source) __hookUrl(String(obj.source));
                    if (Array.isArray(obj)) {
                        obj.forEach(function(x) {
                            if (x) {
                                if (x.src) __hookUrl(String(x.src));
                                if (x.file) __hookUrl(String(x.file));
                                if (x.url) __hookUrl(String(x.url));
                                if (typeof x === 'string') __hookUrl(x);
                            }
                        });
                    }
                }
            } catch(e) {}
        }

        var document = {
            querySelector: function(sel) {
                var m = {
                    src: '', innerHTML: '', textContent: '', value: '',
                    style: { cssText: '', display: '', visibility: '' },
                    addEventListener: function(){},
                    removeEventListener: function(){},
                    setAttribute: function(n,v){ if(n==='src') this.src=v; },
                    getAttribute: function(n){ if(n==='src') return this.src; return ''; },
                    querySelector: function() { return m; },
                    querySelectorAll: function(){ return []; },
                    parentNode: { removeChild: function(){}, appendChild: function(){} },
                    classList: { add: function(){}, remove: function(){}, contains: function(){ return false; } },
                    children: [], childNodes: [], firstChild: null,
                    innerText: '', outerHTML: '', tagName: 'DIV',
                    getBoundingClientRect: function(){ return {top:0,left:0,width:1920,height:1080,right:1920,bottom:1080}; },
                    click: function(){},
                    getElementsByTagName: function(){ return []; },
                    getElementsByClassName: function(){ return []; },
                    offsetWidth: 1920, offsetHeight: 1080
                };
                return m;
            },
            querySelectorAll: function(){ return []; },
            getElementById: function(id) { return this.querySelector('#'+id); },
            getElementsByClassName: function(cls) { return []; },
            getElementsByTagName: function(tag) { return []; },
            createElement: function(tag) {
                var el = {
                    style: {}, src: '', innerHTML: '', textContent: '',
                    appendChild: function(){}, addEventListener: function(){},
                    removeEventListener: function(){},
                    setAttribute: function(n,v){ if(n==='src') this.src=v; },
                    getAttribute: function(n){ if(n==='src') return this.src; return ''; }
                };
                return el;
            },
            createElementNS: function(ns, tag) {
                return this.createElement(tag);
            },
            createTextNode: function(t) { return { textContent: t }; },
            addEventListener: function(){},
            removeEventListener: function(){},
            body: { appendChild: function(){}, removeChild: function(){}, style: {}, innerHTML: '' },
            head: { appendChild: function(){} },
            documentElement: { style: {} },
            cookie: '', readyState: 'complete', title: '',
            documentMode: 0, compatMode: 'CSS1Compat'
        };

        var __locHref = __baseUrl || '';
        var __urlM = __locHref ? __locHref.match(/^(https?:)\/\/([^\/]+)(\/[^?#]*)?(\?[^#]*)?(#.*)?$/) : null;
        var __location = {
            href: __locHref,
            hostname: __urlM ? __urlM[2] : '',
            protocol: __urlM ? __urlM[1] : 'https:',
            pathname: (__urlM && __urlM[3]) ? __urlM[3] : '/',
            search: (__urlM && __urlM[4]) ? __urlM[4] : '',
            hash: '',
            assign: function(){},
            replace: function(u){ if(u) __hookUrl(String(u)); },
            reload: function(){}
        };

        var window = {
            location: __location,
            addEventListener: function(){},
            removeEventListener: function(){},
            postMessage: function(){},
            localStorage: { getItem: function(){ return null; }, setItem: function(){}, removeItem: function(){} },
            sessionStorage: { getItem: function(){ return null; }, setItem: function(){}, removeItem: function(){} },
            innerWidth: 1920, innerHeight: 1080, outerWidth: 1920, outerHeight: 1080,
            screen: { width: 1920, height: 1080, orientation: { angle: 0 } },
            navigator: { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36', platform: 'Win32', languages: ['en-US','en'], cookieEnabled: true, plugins: { length: 0 } },
            performance: { now: function(){ return Date.now ? Date.now() : 0; }, timing: { navigationStart: 0 } },
            setTimeout: function(fn, ms) { if (typeof fn === 'function') { try { fn(); } catch(e) {} } return 0; },
            setInterval: function(fn, ms) { return 0; },
            clearTimeout: function(){},
            clearInterval: function(){},
            requestAnimationFrame: function(fn) { try { if (fn) fn(); } catch(e){} return 0; },
            cancelAnimationFrame: function(){},
            JSON: JSON,
            Math: Math, Date: Date, RegExp: RegExp,
            Array: Array, Object: Object, String: String, Number: Number, Boolean: Boolean,
            Error: Error, TypeError: TypeError, RangeError: RangeError,
            parseInt: parseInt, parseFloat: parseFloat, isNaN: isNaN,
            Infinity: Infinity, NaN: NaN, undefined: undefined,
            encodeURIComponent: encodeURIComponent, decodeURIComponent: decodeURIComponent,
            encodeURI: encodeURI, decodeURI: decodeURI,
            atob: typeof atob !== 'undefined' ? atob : function(s) { return ''; },
            btoa: typeof btoa !== 'undefined' ? btoa : function(s) { return ''; },
            fetch: function(url, opts) {
                __hookUrl(String(url));
                return Promise.resolve({ json: function(){ return {}; }, text: function(){ return ''; }, ok: true, status: 200,
                    headers: { get: function(){ return ''; } } });
            },
            XMLHttpRequest: function() {
                this.open = function(m,u){ this._url = u; __hookUrl(u); };
                this.send = function(){};
                this.setRequestHeader = function(){};
                this.addEventListener = function(){};
                this.getResponseHeader = function(){ return ''; };
                this.getAllResponseHeaders = function(){ return ''; };
                this.readyState = 0; this.status = 0; this.responseText = ''; this.response = '';
            },
            open: function(){ return { document: document, close: function(){} }; },
            print: function(){}, alert: function(){}, confirm: function(){ return true; },
            prompt: function(){ return ''; },
            frames: {}, top: {}, parent: {}, self: {},
            CSS: { supports: function() { return true; } },
            CustomEvent: function(type, opts) { this.type = type; this.detail = opts && opts.detail; },
            Event: function(type) { this.type = type; },
            MutationObserver: function() { return { observe: function(){}, disconnect: function(){} }; },
            IntersectionObserver: function() { return { observe: function(){}, unobserve: function(){}, disconnect: function(){} }; },
            requestSubmit: function(){}
        };

        var navigator = window.navigator;
        var location = window.location;
        var top = window;
        var parent = window;
        var self = window;
        var global = window;
        var frames = window;
        var self_ = window;
    """.trimIndent()
    }

    private fun buildInterceptJs(): String = """
        function __Player(src) {
            if (src) __hookUrl(String(src));
            this.src = src || '';
            this.play = function(){};
            this.pause = function(){};
            this.load = function(){};
            this.addEventListener = function(){};
            this.removeEventListener = function(){};
            this.setCurrentTime = function(){};
            this.setVolume = function(){};
            this.getDuration = function(){ return 0; };
            this.getPosition = function(){ return 0; };
            this.getState = function(){ return 0; };
            this.on = function(){ return this; };
            this.off = function(){ return this; };
            this.one = function(){ return this; };
            this.trigger = function(){ return this; };
            this.resize = function(){};
            this.getPlaybackQuality = function(){ return ''; };
            this.setPlaybackQuality = function(){};
            this.destroy = function(){};
            this.getBuffer = function(){ return 0; };
            this.setMute = function(){};
            this.isMuted = function(){ return false; };
            this.setVolume = function(){};
            this.getVolume = function(){ return 1; };
            this.getContainer = function(){ return {}; };
            this.setContainer = function(){};
            this.attachMedia = function(){};
            this.detachMedia = function(){};
            this.enableSubtitle = function(){};
            this.disableSubtitle = function(){};
        }

        var player = new __Player('');
        var videojs = function(id, opts, cb) {
            var p = new __Player('');
            if (opts && opts.sources) {
                if (Array.isArray(opts.sources)) {
                    opts.sources.forEach(function(s) { if (s) __hookUrlFromObj(s); });
                }
            }
            if (opts && opts.file) __hookUrl(String(opts.file));
            p.src = function(s) {
                if (typeof s === 'string') __hookUrl(s);
                else if (s && typeof s === 'object') __hookUrlFromObj(s);
            };
            p.ready = function(fn) { if (typeof fn === 'function') fn(); return p; };
            p.play = function(){};
            p.pause = function(){};
            p.playlist = function(){ return p; };
            p.width = function(){ return p; };
            p.height = function(){ return p; };
            p.poster = function(){ return p; };
            p.autoplay = function(){ return p; };
            p.preload = function(){ return p; };
            p.dispose = function(){};
            p.reset = function(){};
            if (typeof cb === 'function') cb(p);
            return p;
        };

        var jwplayer = function(id) {
            var p = new __Player('');
            p.setup = function(opts) {
                if (opts) {
                    if (opts.file) __hookUrl(String(opts.file));
                    if (opts.sources && Array.isArray(opts.sources))
                        opts.sources.forEach(function(s) { if(s) __hookUrlFromObj(s); });
                    if (opts.playlist && Array.isArray(opts.playlist))
                        opts.playlist.forEach(function(item) {
                            if (item && item.file) __hookUrl(String(item.file));
                            if (item && item.sources) item.sources.forEach(function(s) { if(s) __hookUrlFromObj(s); });
                        });
                }
                return p;
            };
            p.on = function(){ return p; };
            p.play = function(){};
            p.pause = function(){};
            p.getState = function(){ return 'idle'; };
            return p;
        };

        var Flowplayer = function(el, opts) {
            var p = new __Player('');
            if (opts && opts.clip && opts.clip.url) __hookUrl(opts.clip.url);
            p.load = function(clip) { if (clip && clip.url) __hookUrl(clip.url); };
            p.play = function(){}; p.pause = function(){};
            return p;
        };

        var Clappr = function(opts) {
            var p = new __Player('');
            if (opts && opts.source) __hookUrl(opts.source);
            p.play = function(){}; p.pause = function(){};
            return p;
        };

        var Plyr = function(sel, opts) {
            var p = new __Player('');
            if (opts && opts.sources && Array.isArray(opts.sources))
                opts.sources.forEach(function(s) { if(s && s.src) __hookUrl(s.src); });
            p.play = function(){}; p.pause = function(){};
            return p;
        };

        function HLS(src, video) {
            if (src) __hookUrl(String(src));
            this.loadSource = function(s) { if (s) __hookUrl(s); };
            this.attachMedia = function(){};
            this.on = function(){};
            this.destroy = function(){};
        }

        var dashjs = {
            MediaPlayer: function() {
                return { initialize: function(sel, url, auto) { if (url) __hookUrl(url); }, update: function(){}, destroy: function(){} };
            }
        };

        var ShakaPlayer = function() {
            return { load: function(url) { if (url) __hookUrl(url); }, play: function(){ return Promise.resolve(); }, pause: function(){}, on: function(){}, destroy: function(){} };
        };

        var DPlayer = function(opts) {
            var p = new __Player('');
            if (opts && opts.video && opts.video.url) __hookUrl(opts.video.url);
            if (opts && opts.video && opts.video.type) {}
            p.play = function(){}; p.pause = function(){};
            p.notice = function(){};
            return p;
        };

        var ArtPlayer = function(opts) {
            var p = new __Player('');
            if (opts && opts.url) __hookUrl(opts.url);
            p.play = function(){}; p.pause = function(){};
            return p;
        };

        var MediaElement = function(sel, opts) {
            var p = new __Player('');
            if (opts && opts.similarVideo) __hookUrl(opts.similarVideo);
            p.play = function(){}; p.pause = function(){};
            return p;
        };

        var metas = document.querySelectorAll('meta');
        var scripts = document.querySelectorAll('script');
    """.trimIndent()

    private fun readCapturedArray(scope: org.mozilla.javascript.Scriptable): List<String> {
        val result = mutableListOf<String>()
        try {
            val arr = ScriptableObject.getProperty(scope, "__captured")
            if (arr != null && arr is ScriptableObject) {
                val len = Context.toNumber(ScriptableObject.getProperty(arr, "length")).toInt()
                for (i in 0 until len) {
                    val item = ScriptableObject.getProperty(arr, i)
                    if (item != null && item != org.mozilla.javascript.Undefined.instance) {
                        val s = item.toString().trim()
                        if (s.isNotEmpty()) result.add(s)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading captured: ${e.message}")
        }
        return result
    }

    fun extractFromStreamTape(html: String): String? {
        val patterns = listOf(
            Regex("""var\s+vt\s*=\s*["']([^"']+)["']"""),
            Regex("""(?:robotlink|tokten|token)\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""document\.getElementById\(["']robotlink["']\)\.innerHTML\s*=\s*["']([^"']+)["']"""),
            Regex("""innerHTML\s*=\s*["']([^"']*get_video[^"']*)["']"""),
            Regex("""\b(?:url|link|href)\s*[=:]\s*["']([^"']*get_video[^"']*)["']"""),
            Regex("""["']([^"']*\/get_video\.php[^"']*)["']"""),
            // StreamTape additional patterns
            Regex("""(?:video_url|download_url|stream_url)\s*[=:]\s*["'](https?://[^"']+)["']"""),
            Regex("""["'](https?://[^"'\s]*streamtape[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']"""),
            // StreamTape token in script
            Regex("""['"]([^'"]+)['"]\s*\+\s*['"]([^'"]+)['"]"""),
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            var token = m.groupValues[1]
            if (token.isNotBlank()) {
                val right = m.groupValues.getOrElse(2) { "" }
                if (right.isNotBlank()) token += right
                val base = "https://streamtape.com"
                val fullUrl = if (token.startsWith("http")) token else base + token
                Log.d(TAG, "StreamTape specific: $fullUrl")
                return fullUrl
            }
        }
        return null
    }

    fun extractFromDoodStream(html: String): String? {
        val passPattern = Regex("""(https?://[^"'\s]+/pass_md5/[^"'\s]+)""")
        val m = passPattern.find(html)
        if (m != null) {
            val passUrl = m.groupValues[1]
            Log.d(TAG, "DoodStream pass_md5 found: $passUrl")
            return passUrl
        }
        val altPatterns = listOf(
            Regex("""(?:file|video|source|stream)\s*[=:]\s*["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']"""),
            Regex("""(?:file|video|source|stream)\s*[=:]\s*["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']"""),
            // DoodStream token extraction
            Regex("""(?:token|_token|dood)\s*[=:]\s*["']([^"']+)["']"""),
            // DoodStream embed URL patterns
            Regex("""["'](https?://[^"'\s]*dood[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']"""),
            // DoodStream iframe src
            Regex("""iframe[^>]*src=["'](https?://[^"'\s]*dood[^"'\s]*)["']"""),
        )
        for (p in altPatterns) {
            val match = p.find(html) ?: continue
            val url = match.groupValues[1]
            if (url.isNotBlank()) {
                Log.d(TAG, "DoodStream alt: $url")
                return url
            }
        }
        return null
    }

    fun extractFromVoe(html: String): String? {
        val patterns = listOf(
            // Direct video URL patterns
            Regex("""sources?\s*[=:]\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""\bvideo_url\s*[=:]\s*["'](https?://[^"']+)["']"""),
            Regex("""document\.getElementById\(["'][^"']*["']\)\.innerHTML\s*=\s*["'](https?://[^"']+)["']"""),
            Regex("""(?:file|src|source|video)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""(?:sources|videos|files)\s*[=:]\s*\[\s*\{[^}]*["']?(?:file|src|source|url)["']?\s*[:=]\s*["'](https?://[^"']+)["']"""),
            // VOE redirect domain pattern
            Regex("""window\.location\.href\s*=\s*['"](https?://[^"']+/e/[^"']+)['"]"""),
            // var source='URL' (VOE test pattern - not the real video but useful)
            Regex("""var\s+source\s*=\s*['"](https?://[^"']+)['"]"""),
            // VOE obfuscated JSON - extract URL from nested JSON structure
            Regex("""["']https?://[^"']*voe[^"']*["']"""),
            // VOE nonce/token extraction
            Regex("""(?:nonce|token|_token)\s*[=:]\s*["']([a-zA-Z0-9]+)["']"""),
            // VOE hls/mp4 direct patterns
            Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)(?:\?[^"']*)?)["']"""),
        )
        for (p in patterns) {
            val match = p.find(html) ?: continue
            val url = match.groupValues[1]
            if (url.isNotBlank() && url.startsWith("http")) {
                // Skip test/placeholder videos
                if (url.contains("test-videos") || url.contains("bigbuckbunny")) continue
                Log.d(TAG, "VOE specific: $url")
                return url
            }
        }
        // Try Base64-encoded VOE URLs (MoonGetter pattern)
        val b64Pattern = Regex("""(?:atob|window\.atob|base64decode)\s*\(\s*['"]([A-Za-z0-9+/=]{20,})['"]""")
        for (b64Match in b64Pattern.findAll(html)) {
            try {
                val decoded = String(Base64.decode(b64Match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                val videoMatch = VIDEO_RE.find(decoded)
                if (videoMatch != null) {
                    Log.d(TAG, "VOE base64 decoded to video: ${videoMatch.value}")
                    return videoMatch.value
                }
            } catch (_: Exception) {}
        }
        // Fallback: find any redirect domain in the HTML
        val redirectPatterns = listOf(
            Regex("""window\.location\.href\s*=\s*['"]?(https?://[^'"?\s]+)"""),
            Regex("""document\.location\s*=\s*['"]?(https?://[^'"?\s]+)"""),
            Regex("""location\.href\s*=\s*['"]?(https?://[^'"?\s]+)"""),
        )
        for (redirectPattern in redirectPatterns) {
            val redirectMatch = redirectPattern.find(html)
            if (redirectMatch != null) {
                val redirectUrl = redirectMatch.groupValues[1]
                if (redirectUrl.isNotBlank() && !redirectUrl.contains("voe.sx")) {
                    Log.d(TAG, "VOE redirect found: $redirectUrl")
                    return null // Return null so VideoExtractor follows the redirect
                }
            }
        }
        return null
    }

    fun extractFromStreamSB(html: String): String? {
        val patterns = listOf(
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)"""),
            Regex("""sources\s*[=:]\s*["'](https?://[^"']+)["']"""),
            Regex("""(?:file|src|source|stream)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
        )
        for (p in patterns) {
            val match = p.find(html) ?: continue
            val url = match.groupValues[1]
            if (url.isNotBlank() && url.startsWith("http")) {
                Log.d(TAG, "StreamSB specific: $url")
                return url
            }
        }
        return null
    }

    fun extractFromAtob(html: String): String? {
        val atobPattern = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
        for (m in atobPattern.findAll(html)) {
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                val videoMatch = VIDEO_RE.find(decoded)
                if (videoMatch != null) {
                    Log.d(TAG, "atob decoded to video: ${videoMatch.value}")
                    return videoMatch.value
                }
                if (decoded.startsWith("http")) {
                    Log.d(TAG, "atob decoded URL: $decoded")
                    return decoded
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun extractAllCandidateUrls(html: String): List<String> {
        return VIDEO_RE.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.length > 15 }
            .distinct()
            .toList()
    }
}
