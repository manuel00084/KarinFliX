package com.karin.streamtv.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoExtractorHelper(private val container: ViewGroup) {

    companion object {
        private const val TAG = "VideoExtractor"

        private val ADBLOCK_JS = """
(function(){
    if(window.__kf_adblock__)return;window.__kf_adblock__=1;
    var adSel=['.ad','.ads','.adsbygoogle','.ad-slot','.ad-unit','.ad-wrapper','.advertisement','.advert','.adsense','.adfox','.banner-ad','.ad-banner','.ad-popup','.ad-overlay','.ad-modal','.ad-interstitial','.ads-container','.ad-container','.ads-wrapper','[id*="google_ads"]','[id*="ad-"]','[class*="ad-"]','[class*="ads-"]','[data-ad]','[data-ads]','[data-adunit]','ins.adsbygoogle','.promo','.popup-ad','.popunder','.clickunder','.tp-ad','.preroll-ad','.video-ad','.vast-ad','.ima-ad-container','.ad-block','#ad','#ads','#advert','.floating-ad','.sticky-ad','.fixed-ad','.interstitial','.interstitial-ad','.outbrain','.taboola','.revcontent','.mgid','.nativendo','.pfp,.pfpi,.pfe,.pfi,.pfm','[id*="pfb"],[id*="pfe"]','.auto-ad,.auto_ads,.adsbygoogle-noablate','[data-testid*="ad"]','[aria-label*="ad"]','[aria-label*="publi"]','[class*="social-bar"]','[class*="share-bar"]','[class*="truste"],[class*="consent"],[id*="consent"]','[class*="onetrust"],[id*="onetrust"]','[class*="skip"],[class*="countdown"],[id*="skip"],[id*="countdown"]','[class*="adb-overlay"],[class*="adb-modal"],[class*="adb-popup"],[class*="adb-interstitial"]','[class*="float"],[class*="sticky"],[id*="float"],[id*="sticky"]','[class*="minimize"],[class*="pip"],[class*="expand"]','[class*="arrow"],[class*="nav-btn"],[class*="close-btn"]','[class*="share"],[class*="social"],[class*="follow"],[class*="newsletter"],[class*="subscribe"]'];
    var adIframeRe=/doubleclick|googlesyndication|adsense|adnxs|adroll|taboola|outbrain|advertising|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem|mgid|exosrv|trafficjunky/;
    var adScriptRe=/doubleclick|googlesyndication|adsense|pagead|adnxs|adsrvr|adroll|taboola|outbrain|mgid|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem|moatads|quantserve|scorecardresearch|exosrv|trafficjunky|juicyads|epidemictuna|marginoboles/;
    var __kfSweep__=0;
    function kfCleanAds(root){
        adSel.forEach(function(sel){try{root.querySelectorAll(sel).forEach(function(el){el.remove();});}catch(e){}});
        root.querySelectorAll('iframe').forEach(function(f){var src=(f.src||f.getAttribute('data-src')||'').toLowerCase();if(adIframeRe.test(src)){f.remove();}if(f.offsetWidth<10||f.offsetHeight<10){f.remove();}});
        root.querySelectorAll('[onclick*="window.open"]').forEach(function(el){el.removeAttribute('onclick');});
        root.querySelectorAll('script[src]').forEach(function(s){var src=s.src.toLowerCase();if(adScriptRe.test(src)){s.type='text/blocked';s.remove();}});
        root.querySelectorAll('[class*="cookie"],[class*="gdpr"],[class*="consent"],[id*="cookie"],[id*="gdpr"],[id*="onetrust"]').forEach(function(el){el.remove();});
        root.querySelectorAll('[class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="interstitial"],[class*="preroll"],[class*="midroll"],[class*="blocker"],[class*="cover"]:not(video):not(.player)').forEach(function(el){el.style.display='none';el.style.visibility='hidden';el.style.pointerEvents='none';el.style.zIndex='-1';el.remove();});
        root.querySelectorAll('[class*="play"]').forEach(function(el){el.style.pointerEvents='auto';});
        // Barrido caro (recorre TODO el DOM con getComputedStyle): solo las primeras 2 veces.
        if(__kfSweep__<2){
            __kfSweep__++;
            root.querySelectorAll('*').forEach(function(el){try{var s=getComputedStyle(el);if((s.position==='fixed'||s.position==='sticky')&&parseInt(s.zIndex)>900000){var tag=el.tagName.toLowerCase();if(tag!=='video'&&tag!=='div'||!el.querySelector('video')){var w=el.offsetWidth,h=el.offsetHeight;if(w>0&&h>0&&(w<300||h<50||h<100)){el.remove();}}}}catch(e){}});
            root.querySelectorAll('*').forEach(function(el){try{var s=getComputedStyle(el);if(s.position==='fixed'&&parseInt(s.zIndex)>1000&&el.querySelector('video')===null&&el.querySelector('iframe')===null){el.style.pointerEvents='none';el.style.display='none';el.remove();}}catch(e){}});
        }
        root.querySelectorAll('video').forEach(function(v){v.style.pointerEvents='auto';v.style.zIndex='9999998';v.controls=true;});
    }
    kfCleanAds(document);
    // Observer con debounce y auto-desconexión + limpiezas acotadas (sin setInterval infinito).
    try{var moLast=0;var mo=new MutationObserver(function(){var t=Date.now();if(t-moLast>700){moLast=t;kfCleanAds(document);}});mo.observe(document.documentElement,{childList:true,subtree:true});setTimeout(function(){try{mo.disconnect();}catch(e){}},30000);}catch(e){}
    (function(){var k=0;var iv=setInterval(function(){++k;if(k>=6){clearInterval(iv);}kfCleanAds(document);},2500);})();
})();
"""

        private val REDIRECT_JS = """
(function(){
    function kfExtract(){
        var ff=document.querySelectorAll('iframe');
        for(var i=0;i<ff.length;i++){
            var s=ff[i].src||ff[i].getAttribute('data-src')||'';
            if(s&&s.indexOf('about:blank')===-1&&s.indexOf('doubleclick')===-1&&s.indexOf('google')===-1&&s.indexOf('facebook')===-1){
                return s;
            }
        }
        return null;
    }
    var mo=new MutationObserver(function(){
        var src=kfExtract();
        if(src&&src.indexOf(window.location.href)===-1){mo.disconnect();window.location.href=src;}
    });
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){
        var src=kfExtract();
        if(src&&src.indexOf(window.location.href)===-1){mo.disconnect();window.location.href=src;}
    },2000);
    setTimeout(function(){
        var src=kfExtract();
        if(src&&src.indexOf(window.location.href)===-1){mo.disconnect();window.location.href=src;}
    },5000);
    setTimeout(function(){
        var src=kfExtract();
        if(src&&src.indexOf(window.location.href)===-1){mo.disconnect();window.location.href=src;}
    },8000);
})();
"""

        private val VIDEO_EXTRACT_JS = """
(function(){
    var urls=[];
    var AD_HOST_RE=/doubleclick|googlesyndication|adsense|pagead|adnxs|adsrvr|adroll|taboola|outbrain|mgid|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem|moatads|quantserve|scorecardresearch|exosrv|trafficjunky|juicyads|epidemictuna|marginoboles|google-analytics|googletagmanager|cloudflareinsights|\/analytics|beacon|pixel|facebook\.com|fbcdn|adservice|adserver|advert/i;
    function kfNorm(u){
        if(!u)return '';
        u=(u+'').trim();
        u=u.replace(/\\\//g,'/');
        if(u.indexOf('//')===0)u='https:'+u;
        if(u.indexOf('http')!==0)return '';
        u=u.split('"')[0].split("'")[0].split('\\n')[0].split('\\r')[0];
        u=u.replace(/\s+$/,'');
        return u;
    }
    function kfPush(u){
        u=kfNorm(u);
        if(!u)return;
        try{
            var h=(u.split('/')[2]||'').toLowerCase();
            if(!h||AD_HOST_RE.test(h))return;
        }catch(e){return;}
        if(urls.indexOf(u)===-1){urls.push(u);}
    }
    function kfRawScan(){
        var texts=[];
        try{texts.push(document.documentElement.outerHTML);}catch(e){}
        document.querySelectorAll('script').forEach(function(s){try{texts.push(s.textContent||s.innerHTML||'');}catch(e){}});
        var joined=texts.join('\n');
        var seen={};
        var re=/(?:https?:\/\/|\/\/)[^"'\s<>]+?\.(?:m3u8|mp4|webm|mkv|mov)(?:\?[^"'\s<>]*)?/gi;
        var m;
        while((m=re.exec(joined))!==null){
            if(seen[m[0]])continue;
            seen[m[0]]=1;
            kfPush(m[0]);
        }
        try{
            var mdc=window.MDCore||{};
            var host=mdc.host||mdc.host2||'';
            ['vfile','wurl','furl','durl','purl'].forEach(function(k){
                var v=mdc[k];
                if(typeof v==='string'&&v){
                    if(v.indexOf('//')===0&&host&&v.indexOf(host)===-1){v='//'+host+v;}
                    kfPush(v);
                }
            });
        }catch(e){}
    }
    function kfScanHexloadBase64(){
        try{
            var texts=[];
            try{texts.push(document.documentElement.outerHTML);}catch(e){}
            document.querySelectorAll('script').forEach(function(s){try{texts.push(s.textContent||s.innerHTML||'');}catch(e){}});
            var joined=texts.join('\n');
            var hexRe=/cgi-bin\/test\.pl\?op=([A-Za-z0-9+/=\-_]+)/gi;
            var hm;
            while((hm=hexRe.exec(joined))!==null){
                try{
                    var dec=atob(hm[1]);
                    if(dec.indexOf('http')===0){kfPush(dec);}
                }catch(e){}
            }
        }catch(e){}
    }
    function kfScore(u){
        var s=0;
        try{
            var h=(u.split('/')[2]||'').toLowerCase();
            var ph=(window.location.hostname||'').toLowerCase();
            if(h===ph)s+=200;
            if(ph&&h.indexOf(ph.split('.')[0])!==-1)s+=100;
            if(/\.(mp4|m3u8|webm|mkv|mov)(\?|$)/i.test(u))s+=60;
            if(/test\.pl|\.pl\?op=|cgi-bin/i.test(u))s-=300;
            if(/mixdrop|mxcontent|dood|doood|cdnc|hexload|hex|byse|voe|jessicachoosemake|savefiles|mp4upload|fembed|24hd|feurl|vcdn|streamtape|stape|streamsb|sbplay|filemoon|streamwish|nupload|sendvid|mega|fastplay|netu|droply/.test(h))s+=30;
            if(/\/preview\//i.test(u))s-=100;
        }catch(e){}
        return s;
    }
    function kfReport(best,unique){
        if(window.__kf_video_found__===best)return;
        window.__kf_video_found__=best;
        console.log('KF:VIDEO_SRC:'+best);
        if(window.KarinBridge){window.KarinBridge.onVideoFound(best,JSON.stringify(unique));}
    }
    function kfFindVideos(){
        urls=[];
        document.querySelectorAll('video').forEach(function(v){
            var s=v.currentSrc||v.src||v.getAttribute('data-src')||'';
            if(s&&s.indexOf('blob:')!==0&&s.indexOf('data:')!==0){kfPush(s);}
        });
        document.querySelectorAll('video source[src]').forEach(function(s){
            var u=s.src||s.getAttribute('data-src')||'';
            if(u&&u.indexOf('blob:')!==0&&u.indexOf('data:')!==0){kfPush(u);}
        });
        document.querySelectorAll('[src*=".m3u8"],[data-src*=".m3u8"],[href*=".m3u8"]').forEach(function(el){
            var u=el.src||el.href||el.getAttribute('data-src')||'';
            kfPush(u);
        });
        document.querySelectorAll('a[href$=".mp4"],a[href$=".webm"],a[href$=".mkv"],a[href$=".avi"]').forEach(function(a){
            kfPush(a.href);
        });
        try{
            ['playerConfig','player_config','videoConfig','videoData','source','sources','playlist','file','link','url','videoUrl','video_url','streamUrl','stream_url','src'].forEach(function(k){
                var v=window[k];
                if(v&&typeof v==='object'){
                    var val=v.file||v.src||v.url||v.link||v.source||(v.sources&&v.sources[0]&&(v.sources[0].file||v.sources[0].src))||'';
                    if(val&&typeof val==='string'){kfPush(val);}
                }
            });
        }catch(e){}
        try{if(window.jwplayer){var pl=typeof jwplayer==='function'?jwplayer():jwplayer;if(pl&&pl.getPlaylist&&pl.getPlaylist()[0]&&pl.getPlaylist()[0].file){kfPush(pl.getPlaylist()[0].file);}}}catch(e){}
        try{if(window.videojs&&document.querySelector('.video-js')){var vs=document.querySelector('.video-js');if(vs&&vs.querySelector('source[src]')){kfPush(vs.querySelector('source').src);}}}catch(e){}
        kfScanHexloadBase64();
        if(urls.length===0){
            kfRawScan();
        }
        var seen={},unique=[];
        urls.forEach(function(u){if(u&&!seen[u]){seen[u]=1;unique.push(u);}});
        if(unique.length===0)return;
        unique.sort(function(a,b){return kfScore(b)-kfScore(a);});
        kfReport(unique[0],unique);
    }
    kfFindVideos();
    setInterval(kfFindVideos,3000);
})();
"""

        private val AUTOPLAY_JS = """
(function(){
    function kfAutoPlay(){
        document.querySelectorAll('video').forEach(function(v){v.style.pointerEvents='auto';v.style.zIndex='9999998';v.controls=true;v.muted=false;v.play().catch(function(){});});
        document.querySelectorAll('[class*="play"],[id*="play"],button[aria-label*="Play"],button[aria-label*="play"]').forEach(function(el){
            if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){try{el.click();console.log('KF:auto-play');}catch(e){}}
        });
        document.querySelectorAll('iframe').forEach(function(f){f.style.position='fixed';f.style.top='0';f.style.left='0';f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';f.style.border='none';});
    }
    kfAutoPlay();
    var mo=new MutationObserver(function(){kfAutoPlay();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfAutoPlay();},15000);
})();
"""

        private val EMBED_CSS = """
(function(){
    var s=document.createElement('style');
    s.id='kf-ext-css';
    s.textContent='html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.advertisement,.popunder,.promo,.overlay,.modal,.cookie-banner,.gdpr,#ad,#ads{display:none!important}[class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="cover"]:not(video):not(.player){display:none!important;pointer-events:none!important}.video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}video{object-fit:contain!important;pointer-events:auto!important}iframe{border:0!important;pointer-events:auto!important}[class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="mid-roll"],[class*="postroll"],[class*="post-roll"],[class*="vast"],[class*="ima-"],[id*="preroll"],[id*="midroll"],[id*="postroll"],[id*="vast"],[id*="ima-"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}[class*="ad-container"],[id*="ad-container"],[class*="ad-wrapper"],[id*="ad-wrapper"],[class*="ad-overlay"],[id*="ad-overlay"],[class*="player-ad"],[id*="player-ad"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}[class*="skip"],[class*="countdown"],[id*="skip"],[id*="countdown"]{display:none!important;visibility:hidden!important;pointer-events:none!important}[style*="position: fixed"][style*="z-index"]{pointer-events:none!important;display:none!important;visibility:hidden!important;z-index:-1!important}[class*="float"],[class*="sticky"],[id*="float"],[id*="sticky"]{display:none!important;visibility:hidden!important;pointer-events:none!important}[class*="arrow"],[class*="next"],[class*="prev"],[class*="navigate"]{display:none!important;visibility:hidden!important;pointer-events:none!important}';
    document.head.appendChild(s);
})();
"""
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    suspend fun extractSuspend(embedUrl: String, serverName: String = "", referer: String = ""): String? {
        // Try CF bypass before the WebView to avoid challenge pages blocking extraction.
        // IMPORTANT: ScrapingEngine.fetch hace un OkHttp .execute() síncrono; va en
        // Dispatchers.IO para no congelar el hilo principal (ANR) si tarda hasta 60s.
        val cfBypassedHtml: String? = try {
            withContext(Dispatchers.IO) {
                val doc = com.karin.streamtv.scraper.ScrapingEngine.fetch(
                    embedUrl, "VideoExtractorHelper", "vext::${embedUrl.hashCode()}", true,
                    referer = referer.ifBlank { null }?.let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it
                        else "https://$it"
                    }
                )
                doc?.body()?.html()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "CF bypass for $embedUrl: ${e.message}")
            null
        }

        val deferred = CompletableDeferred<String?>()
        val cfHtml = cfBypassedHtml
        mainHandler.post {
            doExtract(embedUrl, serverName, referer, cfHtml) { url ->
                deferred.complete(url)
            }
        }
        return deferred.await()
    }

    private fun doExtract(embedUrl: String, serverName: String, referer: String = "", preFetchedHtml: String? = null, onResult: (String?) -> Unit) {
        val wv = WebView(container.context)
        webView = wv
        wv.layoutParams = FrameLayout.LayoutParams(1, 1)
        wv.setBackgroundColor(0)
        wv.visibility = android.view.View.GONE
        container.addView(wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }

        val bridge = object {
            @JavascriptInterface
            fun onVideoFound(url: String, allUrlsJson: String) {
                Log.i(TAG, "Video found: ${url.takeLast(60)}")
                mainHandler.post {
                    cleanup()
                    onResult(url)
                }
            }
        }

        wv.addJavascriptInterface(bridge, "KarinBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()?.lowercase() ?: return false
                if (url.contains("doubleclick") || url.contains("googlesyndication") ||
                    url.contains("adsense") || url.contains("popads") || url.contains("mgid") ||
                    url.contains("propellerads") || url.contains("exoclick") || url.contains("criteo") ||
                    url.contains("amazon-adsystem") || url.contains("adnxs")) {
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: return null
                if (url.contains("doubleclick") || url.contains("googlesyndication") ||
                    url.contains("adsense") || url.contains("popads") || url.contains("mgid") ||
                    url.contains("propellerads") || url.contains("exoclick") || url.contains("criteo") ||
                    url.contains("amazon-adsystem") || url.contains("adnxs") ||
                    url.contains("imasdk") || url.contains("vast.xml")) {
                    return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(ADBLOCK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: ${(url ?: embedUrl).takeLast(60)}")

                view?.evaluateJavascript(ADBLOCK_JS, null)
                view?.evaluateJavascript(EMBED_CSS, null)
                view?.evaluateJavascript(REDIRECT_JS, null)
                view?.evaluateJavascript(AUTOPLAY_JS, null)
                view?.evaluateJavascript(VIDEO_EXTRACT_JS, null)

                mainHandler.postDelayed({
                    view?.evaluateJavascript(ADBLOCK_JS, null)
                    view?.evaluateJavascript(VIDEO_EXTRACT_JS, null)
                }, 3000)

                mainHandler.postDelayed({
                    view?.evaluateJavascript(VIDEO_EXTRACT_JS, null)
                }, 6000)

                mainHandler.postDelayed({
                    view?.evaluateJavascript(VIDEO_EXTRACT_JS, null)
                }, 10000)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    val m = it.message()
                    if (m.contains("KF:") || m.contains("VIDEO_SRC") || m.contains("VIDEO_ENDED")) {
                        Log.d(TAG, "JS: $m")
                    }
                }
                return true
            }
        }

        Log.d(TAG, "Loading hidden WebView: ${embedUrl.takeLast(80)}")

        // Build extra headers (Referer for hotlink protection)
        val extraHeaders = mutableMapOf<String, String>()
        if (referer.isNotBlank()) {
            extraHeaders["Referer"] = if (referer.startsWith("http")) referer else "https://$referer"
        }

        if (preFetchedHtml != null) {
            val isChallenge = com.karin.streamtv.util.CloudflareInterceptor.isCloudflareChallenge(200, preFetchedHtml, null)
            if (!isChallenge) {
                Log.i(TAG, "Using CF-bypassed HTML for $embedUrl")
                wv.loadDataWithBaseURL(embedUrl, preFetchedHtml, "text/html", "UTF-8", null)
            } else {
                Log.w(TAG, "CF bypass returned challenge page — falling back to direct loadUrl")
                wv.loadUrl(embedUrl, extraHeaders)
            }
        } else {
            wv.loadUrl(embedUrl, extraHeaders)
        }

        mainHandler.postDelayed({
            cleanup()
            onResult(null)
        }, 15000)
    }

    private fun cleanup() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
            container.removeView(wv)
        }
        webView = null
    }

    fun destroy() {
        cleanup()
    }
}
