package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.AudioEffectsManager
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.onActionKey

class EmbedWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private var audioEffectsManager: AudioEffectsManager? = null

    companion object {
        private const val TAG = "EmbedWebView"

        private const val ADBLOCK_JS = """
(function(){
    if(window.__kf_adblock__)return;window.__kf_adblock__=1;
    var adSelectors=[
        '.ad','.ads','.adsbygoogle','.adsbygoogleNoFrame','.ad-slot','.ad-unit','.ad-wrapper',
        '.advertisement','.advert','.adsense','.adfox','.adfox-banners','.adriver',
        '.banner-ad','.banner-ad-container','.ad-banner','.ad-popup','.ad-overlay',
        '.ad-modal','.ad-interstitial','.ad-skin','.ad-wallpaper',
        '.ads-container','.ad-container','.ads-wrapper','.adblock',
        '[id*="google_ads"]','[id*="ad-"]','[class*="ad-"]','[class*="ads-"]',
        '[data-ad]','[data-ads]','[data-adunit]','[data-ad-slot]',
        'ins.adsbygoogle',
        '.promo','.promo-container','.popup-ad','.popunder','.clickunder',
        '.tp-ad','.preroll-ad','.midroll-ad','.postroll-ad',
        '.video-ad','.vast-ad','.ima-ad-container','.ad-block',
        '#ad','#ads','#advert','#adBlock','#adBlocker',
        '.floating-ad','.sticky-ad','.fixed-ad',
        '.interstitial','.interstitial-ad',
        '[class*="banner"][class*="ad"]','[id*="banner"][id*="ad"]',
        '.video-overlay-ad','.player-ad','.ad-video',
        '.outbrain','.taboola','.revcontent','.mgid','.nativendo',
        '.pfp,.pfpi,.pfe,.pfi,.pfm',
        '[id*="pfb"],[id*="pfe"],[class*="pfp"],[class*="pfe"]',
        '[style*="z-index:9999"],[style*="z-index: 9999"]',
        '[style*="z-index:999999"],[style*="z-index: 999999"]',
        '[style*="position:fixed"][style*="width:100"]',
        '[style*="position:fixed"][style*="height:50"]',
        '[style*="position:fixed"][style*="height:100"]',
        '.auto-ad,.auto_ads,.google-auto ads,.adsbygoogle-noablate',
        '[data-testid*="ad"]','[aria-label*="ad"]','[aria-label*="publi"]',
        '[class*="social-bar"]','[class*="share-bar"]',
        '[class*="truste"],[class*="consent"],[id*="consent"]',
        '[class*="onetrust"],[id*="onetrust"]'
    ];
    function kfCleanAds(root){
        adSelectors.forEach(function(sel){
            try{root.querySelectorAll(sel).forEach(function(el){el.remove();});}catch(e){}
        });
        root.querySelectorAll('iframe').forEach(function(f){
            var src=(f.src||f.getAttribute('data-src')||'').toLowerCase();
            if(/doubleclick|googlesyndication|adsense|adnxs|adroll|taboola|outbrain|advertising|facebook\.com\/plugins|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem/.test(src)){
                f.remove();
            }
        });
        root.querySelectorAll('[onclick*="window.open"]').forEach(function(el){
            el.removeAttribute('onclick');
        });
        root.querySelectorAll('script[src]').forEach(function(s){
            var src=s.src.toLowerCase();
            if(/doubleclick|googlesyndication|adsense|pagead|adnxs|adsrvr|adroll|taboola|outbrain|mgid|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem|moatads|quantserve|scorecardresearch/.test(src)){
                s.type='text/blocked';s.remove();
            }
        });
        root.querySelectorAll('[class*="cookie"],[class*="gdpr"],[class*="consent"],[id*="cookie"],[id*="gdpr"],[id*="onetrust"]').forEach(function(el){el.remove();});
        root.querySelectorAll('[class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="interstitial"],[class*="preroll"],[class*="midroll"],[class*="blocker"],[class*="cover"]:not(video):not(.player)').forEach(function(el){
            el.style.display='none';el.style.visibility='hidden';el.style.pointerEvents='none';el.style.zIndex='-1';el.remove();
        });
        root.querySelectorAll('[class*="play"]').forEach(function(el){el.style.pointerEvents='auto';});
        root.querySelectorAll('*').forEach(function(el){
            try{
                var s=getComputedStyle(el);
                if((s.position==='fixed'||s.position==='sticky')&&parseInt(s.zIndex)>900000){
                    var tag=el.tagName.toLowerCase();
                    if(tag!=='video'&&tag!=='div'||!el.querySelector('video')){
                        var w=el.offsetWidth,h=el.offsetHeight;
                        if(w>0&&h>0&&(w<300||h<50||h<100)){el.remove();}
                    }
                }
            }catch(e){}
        });
        root.querySelectorAll('*').forEach(function(el){
            try{
                var s=getComputedStyle(el);
                if(s.position==='fixed'&&parseInt(s.zIndex)>1000&&el.querySelector('video')===null&&el.querySelector('iframe')===null){
                    el.style.pointerEvents='none';el.style.display='none';
                }
            }catch(e){}
        });
        root.querySelectorAll('[style*="overflow:hidden"]').forEach(function(el){
            var cls=(el.className||'').toLowerCase();
            if(cls.indexOf('video')===-1&&cls.indexOf('player')===-1){
                el.style.overflow='visible';
            }
        });
    }
    kfCleanAds(document);
    try{
        var mo=new MutationObserver(function(mutations){
            var shouldClean=false;
            for(var i=0;i<mutations.length;i++){
                var m=mutations[i];
                if(m.addedNodes.length>0){
                    for(var j=0;j<m.addedNodes.length;j++){
                        var n=m.addedNodes[j];
                        if(n.nodeType===1){shouldClean=true;break;}
                    }
                }
                if(shouldClean)break;
            }
            if(shouldClean){
                kfCleanAds(document);
                document.querySelectorAll('[style*="z-index"]').forEach(function(el){
                    try{
                        var z=parseInt(getComputedStyle(el).zIndex);
                        if(z>1000&&el.querySelector('video')===null&&el.querySelector('iframe')===null){
                            el.style.pointerEvents='none';el.style.display='none';
                        }
                    }catch(e){}
                });
                document.querySelectorAll('[style*="position: fixed"],[style*="position:fixed"]').forEach(function(el){
                    if(el.querySelector('video')===null&&el.querySelector('iframe')===null){
                        el.style.pointerEvents='none';el.style.display='none';
                    }
                });
            }
        });
        mo.observe(document.documentElement,{childList:true,subtree:true});
    }catch(e){}
    console.log('KF: AdBlock+MutationObserver active ('+adSelectors.length+' selectors)');
})();
"""

        private const val GENERIC_EMBED_CSS = """
(function(){
    var s=document.createElement('style');
    s.id='kf-embed-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.top-menu,.nav-bar,.menu-bar,.header,.site-header,.footer,.site-footer,.bottom-bar,.sidebar,.ad,.ads,.advertisement,.popunder,.promo,.overlay,.modal,.modal-fullscreen,.cookie-banner,.gdpr,#ad,#ads,.video-ad,.preroll{display:none!important;position:absolute!important;width:0!important;height:0!important;opacity:0!important;pointer-events:none!important}
        [class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="cover"]:not(video):not(.player):not([class*="art"]){display:none!important;pointer-events:none!important}
        [style*="z-index:9"],[style*="z-index: 9"],[style*="z-index:8"],[style*="z-index: 8"]{pointer-events:none!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
    ';
    document.head.appendChild(s);
})();
"""

        private const val MEGA_EMBED_CSS = """
(function(){
    var s=document.createElement('style');
    s.id='kf-mega-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,.top-bar,.nav-main,.fm-dialog-header,.file-status,.transfer-bar,#download-dialog,.js-fm-header,.top-links,footer,.bottom-bar,.logo,.js-thumbnails-select,.js-fm-tab-bar-section,.mega-dialog,.mega-header,.nav-footer,.file-link-bar,.info-header,.settings,.files-view,.gallery-view,.file-type-icon-container,.remove-link-section,.sharing-dialog{display:none!important}
        #js-video-player-container,.video-player-container,#video-container,.mega-player,.video-wrapper,.js-video-player,#js-video-holder-container,.video-theatre,.player-container,.video-block{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important}
        video{width:100%!important;height:100%!important;object-fit:contain!important}
    ';
    document.head.appendChild(s);
})();
"""

        private const val TOROPLAY_EMBED_CSS = """
(function(){
    var s=document.createElement('style');
    s.id='kf-toroplay-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.breadcrumb{display:none!important}
        [class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="blocker"],[class*="interstitial"],[class*="preroll"],[class*="midroll"]{display:none!important;pointer-events:none!important;opacity:0!important;visibility:hidden!important}
        [class*="play"],[class*="btn"],[class*="button"]{pointer-events:auto!important;cursor:pointer!important}
        iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
    ';
    document.head.appendChild(s);
})();
"""

        private const val TOROPLAY_AUTOPLAY_JS = """
(function(){
    if(window.__kf_toroplay_autoplay)return;window.__kf_toroplay_autoplay=1;
    function kfAutoPlay(){
        var btns=document.querySelectorAll('button, [class*="play"], [class*="btn"], [id*="play"], a[href*="play"]');
        btns.forEach(function(b){
            if(b.offsetParent!==null&&b.offsetWidth>0&&b.offsetHeight>0&&b.textContent.trim().toLowerCase().indexOf('play')!==-1||b.className.toLowerCase().indexOf('play')!==-1){
                try{b.click();console.log('KF: auto-clicked',b.className||b.id);}catch(e){}
            }
        });
        document.querySelectorAll('[class*="tp-"]').forEach(function(el){
            el.style.display='none';el.style.visibility='hidden';
        });
        document.querySelectorAll('a[target="_blank"], a[rel*="nofollow"]').forEach(function(a){
            a.removeAttribute('target');a.href='#';a.style.pointerEvents='none';
        });
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';f.style.border='none';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.position='fixed';v.style.top='0';v.style.left='0';v.style.width='100vw';v.style.height='100vh';v.style.objectFit='contain';v.style.zIndex='999999';
            v.muted=false;v.play().catch(function(){});
        });
        console.log('KF: Toroplay autoplay executed');
    }
    kfAutoPlay();
    var mo=new MutationObserver(function(){kfAutoPlay();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfAutoPlay();},15000);
})();
"""

        private const val DSVPLAY_EMBED_CSS = """
(function(){
    var s=document.createElement('style');
    s.id='kf-dsv-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.overlay,.modal:not(.modal-fullscreen),.cookie-banner{display:none!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important}
        video{object-fit:contain!important}
    ';
    document.head.appendChild(s);
})();
"""

        private fun buildVolumeBoostJs(level: Float): String {
            val safeLevel = level.coerceIn(0.1f, 5.0f)
            return """
(function(){
    if(window.__kf_gain_applied) {
        if(window.__kf_gain_node){window.__kf_gain_node.gain.value=$safeLevel;}
        return 'gain-updated:$safeLevel';
    }
    var video=document.querySelector('video');
    if(!video){return 'no-video-found';}
    try{
        var ctx=new(window.AudioContext||window.webkitAudioContext)();
        var source=ctx.createMediaElementSource(video);
        var gain=ctx.createGain();
        gain.gain.value=$safeLevel;
        source.connect(gain);
        gain.connect(ctx.destination);
        window.__kf_gain_node=gain;
        window.__kf_gain_applied=1;
        return 'gain-applied:$safeLevel';
    }catch(e){
        return 'gain-error:'+e.message;
    }
})();
"""
        }

        private fun buildVolumeBoostIframeJs(level: Float): String {
            val safeLevel = level.coerceIn(0.1f, 5.0f)
            return """
(function(){
    var frames=document.querySelectorAll('iframe');
    frames.forEach(function(f){
        try{
            var doc=f.contentDocument||f.contentWindow.document;
            if(!doc)return;
            if(f.contentWindow.__kf_gain_applied){
                f.contentWindow.__kf_gain_node.gain.value=$safeLevel;
                return;
            }
            var video=doc.querySelector('video');
            if(!video)return;
            var ctx=new(f.contentWindow.AudioContext||f.contentWindow.webkitAudioContext)();
            var source=ctx.createMediaElementSource(video);
            var gain=ctx.createGain();
            gain.gain.value=$safeLevel;
            source.connect(gain);
            gain.connect(ctx.destination);
            f.contentWindow.__kf_gain_node=gain;
            f.contentWindow.__kf_gain_applied=1;
        }catch(e){}
    });
    return 'iframe-boost:$safeLevel';
})();
"""
        }

        private fun buildAudioFxJs(eqValues: String, bassBoost: Float, presetName: String): String {
            return """
(function(){
    if(window.__kf_audio_fx_applied){
        if(window.__kf_audio_fx_filters){
            var bands=[$eqValues];
            var f=window.__kf_audio_fx_filters;
            for(var i=0;i<f.length&&i<bands.length;i++){
                f[i].gain.value=bands[i]/1000*15;
            }
        }
        if(window.__kf_audio_fx_bass){
            window.__kf_audio_fx_bass.gain.value=$bassBoost*12;
        }
        return 'fx-updated:$presetName';
    }
    var video=document.querySelector('video');
    if(!video)return 'no-video';
    try{
        var ctx=new(window.AudioContext||window.webkitAudioContext)();
        var source=ctx.createMediaElementSource(video);
        var prevNode=source;
        var freqs=[60,230,910,3000,14000];
        var bands=[$eqValues];
        var filters=[];
        for(var i=0;i<freqs.length;i++){
            var biq=ctx.createBiquadFilter();
            biq.type='peaking';
            biq.frequency.value=freqs[i];
            biq.Q.value=1.0;
            biq.gain.value=bands[i]/1000*15;
            prevNode.connect(biq);
            prevNode=biq;
            filters.push(biq);
        }
        var bass=ctx.createBiquadFilter();
        bass.type='lowshelf';
        bass.frequency.value=150;
        bass.gain.value=$bassBoost*12;
        prevNode.connect(bass);
        prevNode.connect(ctx.destination);
        window.__kf_audio_fx_filters=filters;
        window.__kf_audio_fx_bass=bass;
        window.__kf_audio_fx_applied=1;
        return 'fx-applied:$presetName';
    }catch(e){
        return 'fx-error:'+e.message;
    }
})();
"""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_embed_webview)

        AppPreferences.init(this)
        audioEffectsManager = AudioEffectsManager(this)
        webView = findViewById(R.id.webview)
        loadingLayout = findViewById(R.id.webview_loading)

        val embedUrl = intent.getStringExtra("embed_url") ?: ""
        val title = intent.getStringExtra("video_title") ?: ""

        Log.d(TAG, "Opening WebView for: $title | URL: ${embedUrl.takeLast(80)}")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            val ua = if (DeviceUtils.isTvDevice(this@EmbedWebViewActivity)) {
                "Mozilla/5.0 (Linux; Android 12; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            } else {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            }
            userAgentString = ua
        }

        webView.webViewClient = object : WebViewClient() {
            private val blockedDomains = listOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "adsense.google.com", "adnxs.com", "adsrvr.org", "adroll.com",
                "taboola.com", "outbrain.com", "mgid.com", "nativendo.com",
                "popads.net", "propellerads.com", "exoclick.com", "clickadu.com",
                "criteo.com", "amazon-adsystem.com", "moatads.com",
                "quantserve.com", "scorecardresearch.com", "advertising.com",
                "casalemedia.com", "pubmatic.com", "rubiconproject.com",
                "openx.net", "bidswitch.net", "adskeeper.com", "adsterra.com",
                "hilltopads.com", "juicyads.com", "trafficjunky.com",
                "onclickads.net", "popcash.net", "richpush.com",
                "clarity.ms", "hotjar.com", "mouseflow.com",
                "adtrafficquality.google", "adservice.google.com",
                "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
                "ads.yahoo.com", "ad.yieldmanager.com",
                "spotxchange.com", "spotx.tv", "serving-sys.com",
                "smaato.net", "inmobi.com", "unity3d.com/ads",
                "applovin.com", "ironsrc.com", "fyber.com",
                "adcolony.com", "vungle.com", "chartboost.com",
                "polluxpollex.com", "zergnet.com", "nativo.com",
                "ligatus.com", "smartadserver.com", "adform.net",
                "adition.com", "eyewondertv.com", "mookie1.com",
                "mediaplex.com", "turn.com", "adsymptotic.com"
            )

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString()?.lowercase() ?: return false
                if (blockedDomains.any { url.contains(it) }) {
                    Log.d(TAG, "Blocked ad request: ${url.takeLast(60)}")
                    return true
                }
                if (url.contains("/pop.") || url.contains("/popup") || url.contains("/click.") ||
                    url.contains("/track.") || url.contains("/pixel.") || url.contains("/beacon.") ||
                    url.contains("adsystem") || url.contains("adserver") || url.contains("adclick") ||
                    url.contains("adview") || url.contains("adlink") || url.contains("adchoice")) {
                    Log.d(TAG, "Blocked ad pattern URL: ${url.takeLast(60)}")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: return null
                if (blockedDomains.any { url.contains(it) }) {
                    return android.webkit.WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                Log.d(TAG, "Page loaded: ${url?.takeLast(80)}")

                val lowerUrl = (url ?: embedUrl).lowercase()
                view?.evaluateJavascript(ADBLOCK_JS, null)

                val cssToInject = buildCssForUrl(embedUrl)
                if (cssToInject.isNotEmpty()) {
                    view?.evaluateJavascript(cssToInject, null)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(cssToInject, null)
                    }, 2000)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(ADBLOCK_JS, null)
                        view?.evaluateJavascript(cssToInject, null)
                    }, 5000)
                }

                if ("trembed=" in lowerUrl && AppPreferences.isPlayNowEnabled()) {
                    view?.evaluateJavascript(TOROPLAY_AUTOPLAY_JS, null)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(TOROPLAY_AUTOPLAY_JS, null)
                    }, 3000)
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript(TOROPLAY_AUTOPLAY_JS, null)
                    }, 8000)
                }

                view?.evaluateJavascript("""
                    (function(){
                        window.open=function(){return null;};
                        window.alert=function(){};
                        window.confirm=function(){return false;};
                        window.prompt=function(){return null;};
                        try{
                            Object.defineProperty(window,'onbeforeunload',{get:function(){return null;},set:function(){}});
                        }catch(e){}
                        var origAssign=window.location.assign;
                        window.location.assign=function(u){
                            if(u&&u.indexOf(location.hostname)===-1){return;}
                            origAssign.call(window.location,u);
                        };
                        console.log('KF: Popup/redirect blockers active');
                    })();
                """.trimIndent(), null)

                if (audioEffectsManager?.isFxEnabled == true && view != null) {
                    injectAudioFx(view)
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView error: $errorCode - $description at $failingUrl")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 50 && loadingLayout.visibility == View.VISIBLE) {
                    loadingLayout.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val msg = it.message()
                    if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e(TAG, "JS ERROR [${it.sourceId()}:${it.lineNumber()}]: $msg")
                    } else if (msg.contains("KF:") || msg.contains("fingerprint") || msg.contains("attestation") || msg.contains("captcha") || msg.contains("pow")) {
                        Log.d(TAG, "JS: $msg")
                    }
                }
                return true
            }
        }

        webView.visibility = View.INVISIBLE
        webView.loadUrl(embedUrl)
    }

    private fun buildCssForUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            "mega.nz" in lower || "mega.co" in lower -> MEGA_EMBED_CSS
            "dsvplay" in lower || "doodstream" in lower || "dood" in lower -> DSVPLAY_EMBED_CSS
            "bysekoze" in lower || "byse" in lower -> GENERIC_EMBED_CSS
            "savefiles" in lower -> GENERIC_EMBED_CSS
            "hexload" in lower -> GENERIC_EMBED_CSS
            "mundodonghua" in lower -> """
(function(){
    var s=document.createElement('style');
    s.id='kf-md-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        .md-navbar,.md-mobile-sidebar,.md-mobile-overlay,.md-footer,.md-sidebar,.md-page-spacer,.md-player-controls-bar,.md-interaction-bar,.md-comments-section,.md-section:has(.md-comments-section),.md-auth-overlay,.md-auth-modal{display:none!important;visibility:hidden!important}
        .md-section{padding:0!important}
        .md-player-panes{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important}
        .md-player-pane{width:100%!important;height:100%!important}
        .md-player-container{width:100%!important;height:100%!important}
        .md-player-container iframe,.md-player-container video,.md-player-container div>video{width:100%!important;height:100%!important;position:fixed!important;top:0!important;left:0!important;z-index:999999!important}
        .md-server-tabs-wrap{position:fixed!important;top:0!important;left:0!important;z-index:1000000!important;width:100%!important;background:rgba(0,0,0,0.85)!important;padding:8px 0!important}
        .md-player-title-bar{display:none!important}
        a.md-auth-trigger,li:has(>a.md-auth-trigger),.md-login-toggle,.md-auth-overlay,.md-auth-modal,#authOverlay,#authModal{display:none!important;visibility:hidden!important}
    ';
    document.head.appendChild(s);
})();
"""
            "doramasyt.com/reproductor" in lower -> """
(function(){
    var s=document.createElement('style');
    s.id='kf-dy-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        nav,header,.navbar,.breadcrumb,footer,.sidebar,.col-lg-3,.d-flex.justify-content-center,.modal,.reportar,.d-flex.justify-content-left,.text-left,.fs-5.text-light.my-4{display:none!important}
        iframe,video,.ifplay,.player{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important}
        video{object-fit:contain!important}
    ';
    document.head.appendChild(s);
})();
"""
            "cuevana" in lower -> """
(function(){
    var s=document.createElement('style');
    s.id='kf-cv3-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.breadcrumb,.pagination,.rating,.comments,.genres,.tags,.ads,.ad,.overlay,.modal,.popup,.backdrop,.cookie,.gdpr,.share,.social{display:none!important;position:absolute!important;width:0!important;height:0!important;pointer-events:none!important}
        [class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="blocker"],[class*="interstitial"],[class*="preroll"],[class*="midroll"]{display:none!important;pointer-events:none!important}
        [style*="z-index"],[style*="z-index:9"]{pointer-events:none!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe,.fp-player,.fp-ui,.flowplayer{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
    ';
    document.head.appendChild(s);
})();
"""
            "trembed=" in lower -> TOROPLAY_EMBED_CSS
            else -> GENERIC_EMBED_CSS
        }
    }

    private fun injectAudioFx(webView: WebView) {
        val boostIndex = AppPreferences.getVolumeBoostIndex()
        val boostLevel = AudioEffectsManager.VOLUME_BOOST_LEVELS[boostIndex]
        val gainValue = 1.0f + (boostLevel / 600.0f) * 0.5f
        val presetIndex = AppPreferences.getAudioPresetIndex().coerceIn(0, AudioEffectsManager.PRESETS.size - 1)
        val preset = AudioEffectsManager.PRESETS[presetIndex]
        val eqValues = preset.equalizerBands.joinToString(",")
        val bassBoost = preset.bassBoost / 1000.0f

        val combinedJs = """
(function(){
    if(window.__kf_audio_fx_chain_applied){
        if(window.__kf_fx_gain){window.__kf_fx_gain.gain.value=$gainValue;}
        if(window.__kf_fx_bass){window.__kf_fx_bass.gain.value=$bassBoost*12;}
        if(window.__kf_fx_filters){
            var bands=[$eqValues];
            for(var i=0;i<window.__kf_fx_filters.length&&i<bands.length;i++){
                window.__kf_fx_filters[i].gain.value=bands[i]/1000*15;
            }
        }
        return 'fx-chain-updated';
    }
    function kfSetupAudioChain(root,doc){
        var video=doc.querySelector('video');
        if(!video)return false;
        try{
            var ctx=new(root.AudioContext||root.webkitAudioContext)();
            var source=ctx.createMediaElementSource(video);
            var gain=ctx.createGain();
            gain.gain.value=$gainValue;
            source.connect(gain);
            var prevNode=gain;
            var freqs=[60,230,910,3000,14000];
            var bands=[$eqValues];
            var filters=[];
            for(var i=0;i<freqs.length;i++){
                var biq=ctx.createBiquadFilter();
                biq.type='peaking';
                biq.frequency.value=freqs[i];
                biq.Q.value=1.0;
                biq.gain.value=bands[i]/1000*15;
                prevNode.connect(biq);
                prevNode=biq;
                filters.push(biq);
            }
            var bass=ctx.createBiquadFilter();
            bass.type='lowshelf';
            bass.frequency.value=150;
            bass.gain.value=$bassBoost*12;
            prevNode.connect(bass);
            bass.connect(ctx.destination);
            window.__kf_fx_gain=gain;
            window.__kf_fx_filters=filters;
            window.__kf_fx_bass=bass;
            window.__kf_audio_fx_chain_applied=1;
            return true;
        }catch(e){console.log('KF: AudioFX error '+e.message);return false;}
    }
    var done=kfSetupAudioChain(window,document);
    document.querySelectorAll('iframe').forEach(function(f){
        try{
            var doc=f.contentDocument||f.contentWindow.document;
            if(!doc)return;
            if(!f.contentWindow.__kf_audio_fx_chain_applied){
                kfSetupAudioChain(f.contentWindow,doc);
            }
        }catch(e){}
    });
    var mo=new MutationObserver(function(){
        if(!window.__kf_audio_fx_chain_applied){kfSetupAudioChain(window,document);}
        document.querySelectorAll('iframe').forEach(function(f){
            try{
                var doc=f.contentDocument||f.contentWindow.document;
                if(!doc)return;
                if(!f.contentWindow.__kf_audio_fx_chain_applied){
                    kfSetupAudioChain(f.contentWindow,doc);
                }
            }catch(e){}
        });
    });
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();},10000);
    return done?'fx-chain-applied':'waiting';
})();
"""
        webView.evaluateJavascript(combinedJs) { result ->
            Log.d(TAG, "AudioFX injection: $result")
        }

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({
            webView.evaluateJavascript(combinedJs) { result ->
                Log.d(TAG, "AudioFX re-injection (2s): $result")
            }
        }, 2000)
        mainHandler.postDelayed({
            webView.evaluateJavascript(combinedJs) { result ->
                Log.d(TAG, "AudioFX re-injection (5s): $result")
            }
        }, 5000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            finish()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            webView.evaluateJavascript("""
                (function(){
                    var focused=document.activeElement;
                    if(focused&&focused.tagName!=='BODY'&&focused.tagName!=='HTML'){
                        focused.click();
                        return 'clicked:'+focused.tagName;
                    }
                    var play=document.querySelector('[class*="play"],[id*="play"],button,[role="button"]');
                    if(play){play.click();return 'clicked-play';}
                    return 'no-focus';
                })();
            """.trimIndent()) { result ->
                Log.d(TAG, "DPAD_CENTER: $result")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        audioEffectsManager?.release()
        audioEffectsManager = null
        webView.handler?.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
