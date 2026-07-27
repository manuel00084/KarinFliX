package com.karin.streamtv.util

import android.webkit.WebResourceResponse

object AdBlocker {

    // Cache: calcular 1 vez en init, reusar siempre
    private val cachedAdBlockCss by lazy { inlineCssHide + "\n" + lightweightCss }
    private val cachedBlockerJs by lazy { blockerJs + "\n" + lightweightJs }
    private val cachedCssEncoded by lazy { java.net.URLEncoder.encode(cachedAdBlockCss, "UTF-8") }

    private val adDomains = listOf(
        // LatAnime ads
        "epidemictuna.com",
        "eroneko2.net",
        "eroneko",
        "cdn.eroneko",
        "eroneko.net",
        // Anadidos / recien agregados banners
        "anadidos",
        "baneranadidos",
        // Cuevana3 streaming ads
        "cuevana3ads.",
        // MundoDonghua ads
        "mundodonghuaads.",
        "donghuaads.",
        "ads.mundodonghua",
        // Common streaming ads
        "onclickads.net",
        "popads.net",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "trafficstars.com",
        "hilltopads.com",
        "popcash.net",
        "adsterra.com",
        "monetag.com",
        "a-ads.com",
        "coinzilla.com",
        "bitmedia.io",
        "richpush.co",
        "mgid.com",
        "pushame.com",
        "notifpush.com",
        // Common ad domains
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "facebook.com/tr",
        "fbcdn.net",
        "connect.facebook.net",
        "twitter.com/i/adsct",
        "analytics.twitter.com",
        "ads.twitter.com",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "casalemedia.com",
        "contextweb.com",
        "criteo.com",
        "criteo.net",
        "demdex.net",
        "doubleverify.com",
        "everesttech.net",
        "exelator.com",
        "hotjar.com",
        "hotjar.io",
        "imrworldwide.com",
        "indeed.com",
        "insurads.com",
        "krxd.net",
        "lijit.com",
        "lotame.com",
        "media.net",
        "moatads.com",
        "neso.com",
        "nr-data.net",
        "omtrdc.net",
        "openx.net",
        "optimizely.com",
        "outbrain.com",
        "permutive.com",
        "pubmatic.com",
        "quantserve.com",
        "revjet.com",
        "rfihub.com",
        "rlcdn.com",
        "rubiconproject.com",
        "sail-horizon.com",
        "scorecardresearch.com",
        "segment.com",
        "sharethrough.com",
        "simpli.fi",
        "sitescout.com",
        "smartadserver.com",
        "snap.licdn.com",
        "spotxchange.com",
        "stickyadstv.com",
        "taboola.com",
        "tapad.com",
        "tidaltv.com",
        "trafficjunky.com",
        "tribalfusion.com",
        "triplelift.com",
        "turn.com",
        "videoplaza.tv",
        "w55c.net",
        "yieldmo.com",
        "zip.ru",
        "adbrite.com",
        "adform.net",
        "adfox.ru",
        "adroll.com",
        "advertising.com",
        "bidswitch.net",
        "bluekai.com",
        "chartbeat.com",
        "chartbeat.net",
        "comscore.com",
        "cookielaw.org",
        "crwdcntrl.net",
        "estat.com",
        "flashtalking.com",
        "forensiq.com",
        "ifyoublockthisyouwillbebanned.com",
        "impdesk.com",
        "indieclick.com",
        "kochava.com",
        "leadbolt.com",
        "mathtag.com",
        "mdotm.com",
        "mediaplex.com",
        "mookie1.com",
        "nanigans.com",
        "narrativ.com",
        "nativo.com",
        "netmng.com",
        "nile.works",
        "nxtck.com",
        "pandora.com",
        "pippio.com",
        "plista.com",
        "serving-sys.com",
        "sharethis.com",
        "smaato.net",
        "supersonicads.com",
        "sync-up-prod.com",
        "trafficfuel.com",
        "vidible.tv",
        "viewablemedia.net",
        "wavestable.com",
        "webpower.com",
        "wistia.com",
        "yieldlab.net",
        "youappi.com",
        "zedo.com",
        "adcolony.com",
        "adskeeper.com",
        "bidvertiser.com",
        "clickadu.com",
        "revcontent.com",
        "richpush.com",
        "vcash.com",
        "vendortag.com",
        "vooservers.com",
        "warnet.ru",
        "yandex.ru/ads",
        "mc.yandex.ru",
        "an.yandex.ru",
        // Gambling / redirect spam
        "caliente.com",
        "calienteclick.com",
        "trafficcaliente.com",
        "trafficfactory.biz",
        "trafficforce.com",
        "clk.ink",
        "clicks.ink",
        "clickserve.cc",
        "adskeeper.co.uk",
        "mgid.com",
        "shmonitor.com",
        "go.trk.com",
        "ouo.io",
        "ouo.press",
        "shrinke.me",
        "sh.st",
        "shorte.st",
        "adf.ly",
        "bc.vc",
        "track.xyz",
        "click.xyz",
        "redirecting.to",
        "redirect101.com",
        "go.php",
    )

    private val adElementSelectors = listOf(
        "[id*='ad-']",
        "[id*='ads-']",
        "[id*='advert']",
        "[class*='ad-']",
        "[class*='ads-']",
        "[class*='advert']",
        "[class*='adsbygoogle']",
        "[class*='sponsor']",
        "[class*='promo']",
        "[id*='google_ads']",
        "[id*='gpt-ad']",
        "[id*='taboola']",
        "[id*='outbrain']",
        "[id*='anadidos']",
        "[class*='anadidos']",
        "iframe[src*='ads']",
        "iframe[src*='doubleclick']",
        "iframe[src*='googlesyndication']",
        "iframe[src*='facebook.com/tr']",
        "iframe[width='300'][height='250']",
        "iframe[width='728'][height='90']",
        "iframe[width='160'][height='600']",
        "iframe[width='320'][height='50']",
        "iframe[width='320'][height='100']",
        "iframe[width='468'][height='60']",
        "iframe[width='970'][height='90']",
        "iframe[width='970'][height='250']",
        "ins.adsbygoogle",
        "div[data-ad]",
        "div[data-ads]",
        "div[data-dfp]",
        "div[id*='ad-container']",
        "div[class*='ad-container']",
        "div[class*='ads-container']",
        "div[class*='popup']",
        "div[class*='overlay-ad']",
        "div[class*='interstitial']",
        "div[class*='modal-ad']",
        "div[class*='floating-ad']",
        "div[class*='sticky-ad']",
        "div[class*='bottom-ad']",
        "div[class*='top-ad']",
        "div[class*='side-ad']",
        "video[poster*='ad']",
        "a[href*='click.linksynergy']",
        "a[href*='affiliate']",
        "a[href*='clickhere']",
        "a[target='_blank'][onclick]",
        "a[rel*='sponsored']",
    )

    private val inlineCssHide = adElementSelectors.joinToString("\n") {
        "$it { display: none !important; visibility: hidden !important; height: 0 !important; width: 0 !important; opacity: 0 !important; pointer-events: none !important; position: absolute !important; overflow: hidden !important; }"
    }

    private val blockerJs = """
        (function() {
            // Remove ad elements
            var selectors = ${adElementSelectors.map { "\"$it\"" }.joinToString(",")};
            selectors.forEach(function(sel) {
                try {
                    document.querySelectorAll(sel).forEach(function(el) {
                        el.remove();
                    });
                } catch(e) {}
            });

            // Block popups
            window.open = function() { return null; };
            window.alert = function() {};
            window.confirm = function() { return true; };
            window.prompt = function() { return ''; };

            // Block notification requests
            if ('Notification' in window) {
                Notification.requestPermission = function() { return Promise.resolve('denied'); };
            }

            // Remove "Añadidos recientemente" y "Series recientes" sections
            try {
                var allH2s = document.querySelectorAll('h2');
                allH2s.forEach(function(h2) {
                    var txt = h2.textContent || '';
                    if (txt.indexOf('A\u00f1adidos') !== -1 || txt.indexOf('Series recientes') !== -1) {
                        // Remove all siblings until next h2 or section
                        var next = h2.nextElementSibling;
                        while (next && next.tagName !== 'H2' && next.tagName !== 'SECTION' && next.tagName !== 'FOOTER') {
                            var el = next;
                            next = next.nextElementSibling;
                            el.remove();
                        }
                        h2.remove();
                    }
                });
            } catch(e) {}

            // MutationObserver to remove dynamically added ads
            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType === 1) {
                            selectors.forEach(function(sel) {
                                try {
                                    if (node.matches && node.matches(sel)) {
                                        node.remove();
                                    }
                                    if (node.querySelectorAll) {
                                        node.querySelectorAll(sel).forEach(function(el) {
                                            el.remove();
                                        });
                                    }
                                } catch(e) {}
                            });
                        }
                    });
                });
            });
            observer.observe(document.body || document.documentElement, {
                childList: true,
                subtree: true
            });
        })();
    """.trimIndent()

    private val cdnWhitelist = listOf(
        // AnimeFLV
        "animeflv.or.at/wp-content/",
        "animeflv.or.at/wp-includes/",
        // Cuevana3
        "cuevana3.is/static/",
        // JKAnime
        "cdn.jkdesa.com",
        // LatAnime
        "latanime.org/assets/img/",
        "latanime.org/thumbs/",
        "latanime.org/img/",
        "latanime.org/css/",
        "latanime.org/js/",
        // DoramasYT
        "doramasyt.com/thumbs/",
        "doramasyt.com/img/",
        "doramasyt.com/css/",
        "doramasyt.com/js/",
        // Cuevana3
        "cuevana3.is/static/",
        // MundoDonghua
        "mundodonghua.com/assets/",
        "mundodonghua.com/img/",
        "mundodonghua.com/images/",
        // CDNs genéricos de contenido (no ads)
        "cdn.animefenix.com",
        "cdn1.animefenix.com",
        "img.animefenix.com",
        "i.imgur.com",
        "img.shounenjump.com",
        "img.youtube.com",
        "img1.ak.crunchyroll.com",
        "statics.animenewsnetwork.com",
        "cdn.myanimelist.net",
        "media.kitsu.me",
        "image.tmdb.org",
        // Servidores de video (NUNCA bloquear)
        "fembed",
        "feurl",
        "24hd",
        "vcdn",
        "streamtape.com",
        "doodstream.com",
        "dood.ws",
        "voe.sx",
        "k2s.cc",
        "streamsb.com",
        "sbplay",
        "vk.com",
        "vkvd",
        "ok.ru",
        "sendvid.com",
        "mp4upload.com",
        "rutube.ru",
        "streamwish.com",
    )

    private val lightweightCss = """
        /* Fondo transparente para que se vea el fondo de la app */
        html, body {
            background: transparent !important;
        }

        /* Ocultar carrusel/slider de LatAnime */
        #carouselExampleCaptions, .carousel, .carousel-inner {
            display: none !important;
        }

        /* Ocultar solo menús/botones del navbar, mantener logo */
        .pepu .navbar-collapse, .pepu .navbar-toggler,
        .navmovil .offcanvas, .navmovil .navbar-toggler, .navmovil .collapse {
            display: none !important;
        }
        footer {
            display: none !important;
        }
        
        /* ========== OCULTAR RUIDO ESENCIAL ========== */
        .comments, .comments-section, #comments, #disqus_thread, .disqus {
            display: none !important;
        }
        .social-share, .share-buttons, .sharing {
            display: none !important;
        }
        .sidebar, #sidebar, .left-sidebar {
            display: none !important;
        }
        
        /* Scrollbar personalizado */
        ::-webkit-scrollbar {
            width: 8px !important;
        }
        ::-webkit-scrollbar-track {
            background: transparent !important;
        }
        ::-webkit-scrollbar-thumb {
            background: rgba(255,255,255,0.3) !important;
            border-radius: 4px !important;
        }
    """.trimIndent()

    private val lightweightJs = """
        (function() {
            var KARIN_MAX_RETRIES = 3;

            function retryImage(img) {
                var retryCount = parseInt(img.getAttribute('data-karin-retry') || '0');
                if (retryCount >= KARIN_MAX_RETRIES) return;
                img.setAttribute('data-karin-retry', String(retryCount + 1));
                var origSrc = img.getAttribute('data-src') || img.getAttribute('data-lazy');
                if (origSrc) img.src = origSrc;
            }

            function loadAllLazyImages() {
                document.querySelectorAll('img[data-src], img[data-lazy]').forEach(function(img) {
                    var dataSrc = img.getAttribute('data-src') || img.getAttribute('data-lazy');
                    if (dataSrc) img.src = dataSrc;
                });
            }

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', loadAllLazyImages);
            } else {
                loadAllLazyImages();
            }
            setTimeout(loadAllLazyImages, 1500);
        })();
    """.trimIndent()

    fun shouldBlockRequest(url: String): Boolean {
        val lower = url.lowercase()

        // Whitelist: if it's from the site's own CDN, don't block
        for (cdn in cdnWhitelist) {
            if (lower.contains(cdn.trim())) return false
        }

        // Only block by known ad domains (never block by URL patterns or extensions)
        for (domain in adDomains) {
            if (lower.contains(domain)) return true
        }

        // Block common ad file types
        if (lower.endsWith(".ads") || lower.contains("ad.js") || lower.contains("ads.js")) {
            return true
        }

        return false
    }

    fun getAdBlockCss(): String = cachedAdBlockCss

    fun getBlockerJs(): String = cachedBlockerJs

    fun getLightweightCss(): String = lightweightCss

    fun getLightweightJs(): String = lightweightJs

    fun getCssEncoded(): String = cachedCssEncoded

    fun getBlockScript(): WebResourceResponse? {
        val mimeType = "text/javascript"
        val encoding = "UTF-8"
        return WebResourceResponse(mimeType, encoding, blockerJs.byteInputStream())
    }
}
