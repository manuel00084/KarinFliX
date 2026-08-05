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
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karin.streamtv.R
import com.karin.streamtv.util.AniSkipService
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.CloudflareInterceptor
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmbedWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var mainHandler: Handler
    private lateinit var skipButtonContainer: LinearLayout
    private lateinit var btnSkip: TextView
    private var bridge: VideoBridge? = null
    private var animeId: String = ""
    private var episodeNumber: Int = 0
    private var title: String = ""
    private var currentEpisodeUrl: String = ""
    private var openExternalWhenReady: Boolean = false
    private var hasOpenedExternal: Boolean = false
    private var autoPlayTriggered: Boolean = false
    private var allServerUrls: Array<String> = emptyArray()
    private var allServerNames: Array<String> = emptyArray()
    private var currentServerIndex: Int = 0
    private var videoFound: Boolean = false
    private var nextEpisodeUrlFromDom: String? = null
    private var playlist: List<com.karin.streamtv.model.PlaylistItem> = emptyList()
    private var playlistIndex: Int = 0

    private var presented = false
    private var launching = false
    private var hiddenContainer: FrameLayout? = null
    
    private var skipInterval: AniSkipService.SkipInterval? = null
    private var isShowingSkipButton: Boolean = false
    private var skipType: String = ""

    companion object {
        private const val TAG = "EmbedWebView"

        private const val DIAG_JS = """
(function(){
    if(window.__kf_diag__)return;window.__kf_diag__=1;
    function dumpState(tag){
        try{
            var vids=document.querySelectorAll('video');
            var ifs=document.querySelectorAll('iframe');
            var srcs=[];
            for(var i=0;i<vids.length;i++){srcs.push(vids[i].currentSrc||vids[i].src||vids[i].getAttribute('data-src')||'');}
            var ifsrc=[];
            for(var i=0;i<ifs.length;i++){ifsrc.push((ifs[i].src||ifs[i].getAttribute('data-src')||'').substring(0,120));}
            console.log('KF:DUMP:'+tag+' url='+location.href.substring(0,90)+' state='+document.readyState+' title='+document.title.substring(0,40));
            console.log('KF:DUMP:'+tag+' vids='+vids.length+' vidsrc='+JSON.stringify(srcs));
            console.log('KF:DUMP:'+tag+' iframes='+ifs.length+' ifsrc='+JSON.stringify(ifsrc));
            console.log('KF:DUMP:'+tag+' bodyLen='+(document.body?document.body.innerHTML.length:0)+' dsplayer='+(typeof window.dsplayer)+' md='+(typeof window.MDCore));
            for(var i=0;i<vids.length;i++){
                var v=vids[i];
                console.log('KF:DUMP:'+tag+' vid'+i+' readyState='+v.readyState+' paused='+v.paused+' dur='+(isFinite(v.duration)?v.duration.toFixed(1):'nan')+' w='+v.videoWidth+'x'+v.videoHeight+' err='+(v.error?v.error.code:'none'));
            }
        }catch(e){console.log('KF:DUMP:err '+e.message);}
    }
    dumpState('s');
    var n=0;
    var intv=setInterval(function(){
        n++;
        dumpState('t'+n);
        if(n>=30){clearInterval(intv);}
    },2000);
})();
"""

        private const val ADBLOCK_JS = """
(function(){
    if(window.__kf_adblock__)return;window.__kf_adblock__=1;

    var h=window.location.hostname;
    var isMixdropPage=h.indexOf('mixdrop')>=0||h.indexOf('mxdrop')>=0;
    var isBysePage=h.indexOf('byse')>=0;
    var isHexloadPage=h.indexOf('hexload')>=0;
    var isSavefilesPage=h.indexOf('savefiles')>=0;
    var isDsvplayPage=h.indexOf('dsvplay')>=0||h.indexOf('dood')>=0;
    var isVoePage=h.indexOf('voe')>=0||h.indexOf('jessicachoosemake')>=0;
    var isFilemoonPage=h.indexOf('filemoon')>=0;
    var isStreamtapePage=h.indexOf('streamtape')>=0||h.indexOf('stape')>=0;
    var isFembedPage=h.indexOf('fembed')>=0||h.indexOf('fem')>=0||h.indexOf('24hd')>=0||h.indexOf('feurl')>=0||h.indexOf('vcdn')>=0;
    var isStreamsbPage=h.indexOf('streamsb')>=0||h.indexOf('sbplay')>=0||h.indexOf('sblong')>=0||h.indexOf('sbfull')>=0||h.indexOf('sbembed')>=0;
    var isStreamwishPage=h.indexOf('streamwish')>=0||h.indexOf('embedwish')>=0;
    var isNuploadPage=h.indexOf('nupload')>=0||h.indexOf('nuuuppp')>=0;
    var isLuluPage=h.indexOf('lulu')>=0;
    var isVideoHost=isMixdropPage||isBysePage||isHexloadPage||isSavefilesPage||isDsvplayPage||isVoePage||isFilemoonPage||isStreamtapePage||isFembedPage||isStreamsbPage||isStreamwishPage||isNuploadPage||isLuluPage;

    var adSel=[
        '.ad','.ads','.adsbygoogle','.ad-slot','.ad-unit','.ad-wrapper',
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
        '#ad','#ads','#advert',
        '.floating-ad','.sticky-ad','.fixed-ad',
        '.interstitial','.interstitial-ad',
        '[class*="banner"][class*="ad"]','[id*="banner"][id*="ad"]',
        '.video-overlay-ad','.player-ad','.ad-video',
        '.outbrain','.taboola','.revcontent','.mgid','.nativendo',
        '.pfp,.pfpi,.pfe,.pfi,.pfm',
        '[id*="pfb"],[id*="pfe"],[class*="pfp"],[class*="pfe"]',
        '.auto-ad,.auto_ads,.adsbygoogle-noablate',
        '[data-testid*="ad"]','[aria-label*="ad"]','[aria-label*="publi"]',
        '[class*="social-bar"]','[class*="share-bar"]',
        '[class*="truste"],[class*="consent"],[id*="consent"]',
        '[class*="onetrust"],[id*="onetrust"]',
        '[class*="ad-over"],[class*="overlay-ad"],[class*="ad-overlay"]',
        '[class*="video-ads"],[class*="ads-video"]',
        '[class*="pause-ad"],[class*="ad-pause"]',
        '[class*="preroll"],[class*="pre-roll"],[class*="prerollad"]',
        '[class*="midroll"],[class*="mid-roll"]',
        '[class*="postroll"],[class*="post-roll"]',
        '[id*="preroll"],[id*="midroll"],[id*="postroll"]',
        '[class*="vast"],[id*="vast"]',
        '[class*="ima-container"],[id*="ima-container"]',
        '[class*="adb-overlay"],[class*="adb-modal"],[class*="adb-popup"],[class*="adb-interstitial"]',
        '[class*="adb-preroll"],[class*="adb-midroll"],[class*="adb-postroll"]',
        '[class*="float"],[class*="sticky"],[id*="float"],[id*="sticky"]',
        '[class*="minimize"],[class*="pip"],[class*="expand"],[class*="min-btn"]',
        '[class*="arrow"],[class*="nav-btn"],[class*="close-btn"]',
        '[class*="share"],[class*="social"],[class*="follow"]',
        '[class*="newsletter"],[class*="subscribe"],[class*="notification"]'
    ];

    var adIframeRe=/doubleclick|googlesyndication|adsense|adnxs|adroll|taboola|outbrain|advertising|facebook\.com\/plugins|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem|mgid|exosrv|trafficjunky/;
    var adScriptRe=/doubleclick|googlesyndication|adsense|pagead|adnxs|adsrvr|adroll|taboola|outbrain|mgid|popads|propellerads|exoclick|clickadu|criteo|amazon-adsystem|moatads|quantserve|scorecardresearch|exosrv|trafficjunky|juicyads|epidemictuna|marginoboles/;

    function kfCleanAds(root){
        if(isVideoHost){return;}
        adSel.forEach(function(sel){
            try{root.querySelectorAll(sel).forEach(function(el){el.remove();});}catch(e){}
        });
        root.querySelectorAll('iframe').forEach(function(f){
            var src=(f.src||f.getAttribute('data-src')||'').toLowerCase();
            if(adIframeRe.test(src)){f.remove();}
            if(f.offsetWidth<10||f.offsetHeight<10){f.remove();}
        });
        root.querySelectorAll('[onclick*="window.open"]').forEach(function(el){
            el.removeAttribute('onclick');
        });
        root.querySelectorAll('script[src]').forEach(function(s){
            var src=s.src.toLowerCase();
            if(adScriptRe.test(src)){s.type='text/blocked';s.remove();}
        });
        root.querySelectorAll('[class*="cookie"],[class*="gdpr"],[class*="consent"],[id*="cookie"],[id*="gdpr"],[id*="onetrust"]').forEach(function(el){el.remove();});
        root.querySelectorAll('[class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"],[class*="mask"],[class*="curtain"],[class*="interstitial"]:not(.player-interstitial),[class*="preroll"],[class*="midroll"],[class*="blocker"],[class*="cover"]:not(video):not(.player)').forEach(function(el){
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
                    el.style.pointerEvents='none';el.style.display='none';el.remove();
                }
            }catch(e){}
        });
        root.querySelectorAll('[style*="overflow:hidden"]').forEach(function(el){
            var cls=(el.className||'').toLowerCase();
            if(cls.indexOf('video')===-1&&cls.indexOf('player')===-1){
                el.style.overflow='visible';
            }
        });
        root.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.controls=true;
        });
    }
    kfCleanAds(document);
    try{
        var mo=new MutationObserver(function(mutations){kfCleanAds(document);});
        mo.observe(document.documentElement,{childList:true,subtree:true});
    }catch(e){}
    setInterval(function(){kfCleanAds(document);},2000);
    console.log('KF: AdBlock active ('+adSel.length+' selectors)');
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
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="mid-roll"],[class*="postroll"],[class*="post-roll"],[class*="vast"],[class*="ima-"],[id*="preroll"],[id*="midroll"],[id*="postroll"],[id*="vast"],[id*="ima-"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important;position:absolute!important;width:0!important;height:0!important}
        [class*="ad-container"],[id*="ad-container"],[class*="ad-wrapper"],[id*="ad-wrapper"],[class*="ad-overlay"],[id*="ad-overlay"],[class*="player-ad"],[id*="player-ad"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
        [class*="countdown"],[id*="countdown"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        .skip-btn:not([href*="voe"]),.skip-ad:not([href*="voe"]),[class*="skip-ad"]:not(button){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="skip-intro"],.skip-btn,[class*="btn-skip"],[class*="saltar"]{pointer-events:auto!important;cursor:pointer!important}
        [style*="position: fixed"][style*="z-index"]{pointer-events:none!important;display:none!important;visibility:hidden!important;z-index:-1!important}
        [class*="float"],[class*="sticky"],[id*="float"],[id*="sticky"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="minimize"],[class*="pip"],[class*="fullscreen"],[class*="expand"],[class*="min"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="arrow"],[class*="next"],[class*="prev"],[class*="navigate"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="share"],[class*="social"],[class*="follow"],[class*="newsletter"],[class*="subscribe"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        [onclick*="window.open"],[onclick*="popunder"],[onclick*="clickunder"]{pointer-events:none!important;cursor:default!important}
        a[target="_blank"],a[rel*="nofollow"]{pointer-events:none!important}
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

        private const val VIDEO_EXTRACT_JS = """
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
        if(window.KarinBridge){
            var playing=false;
            document.querySelectorAll('video').forEach(function(v){
                if(v.readyState>=2&&!v.paused&&!v.ended&&isFinite(v.currentTime)){playing=true;}
            });
            if(playing||!window.KarinBridge.onDirectVideoFound){
                window.KarinBridge.onVideoFound(best,JSON.stringify(unique));
            }else{
                window.KarinBridge.onDirectVideoFound(best,JSON.stringify(unique));
            }
        }
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
        try{if(window.jwplayer){var pl=typeof jwplayer==='function'?jwplayer():jwplayer;if(pl&&pl.getPlaylist&&pl.getPlaylist()[0]&&pl.getPlaylist()[0].file){kfPush(pl.getPlaylist()[0].file);}} }catch(e){}
        try{if(window.videojs&&document.querySelector('.video-js')){var vs=document.querySelector('.video-js');if(vs&&vs.querySelector('source[src]')){kfPush(vs.querySelector('source').src);}} }catch(e){}
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
    setInterval(kfFindVideos,4000);
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
    if(window.__kf_dsv__)return;window.__kf_dsv__=1;
    var s=document.createElement('style');
    s.id='kf-dsv-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.popunder,.promo,.cookie-banner,.modal,.grecaptcha-badge{display:none!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe,#os_player,#os_player_wrapper{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important;width:100vw!important;height:100vh!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="overlay"]:not(video):not(iframe):not(#os_player){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"],[class*="vast"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);
    function kfFixDsv(){
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        var playBtn=document.querySelector('#os_player,#play_button,.play_button,[class*="play"][class*="btn"],[class*="play-btn"],button[aria-label*="play"]');
        if(playBtn&&playBtn.offsetParent!==null&&playBtn.offsetWidth>20){
            try{playBtn.click();}catch(e){}
        }
        if(window.dsplayer&&window.dsplayer.play&&typeof window.dsplayer.play==='function'){
            try{window.dsplayer.play();}catch(e){}
        }
    }
    kfFixDsv();
    var mo=new MutationObserver(function(){kfFixDsv();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixDsv();},20000);
    console.log('KF: Dsvplay fix active');
})();
"""

        private const val BYSE_EMBED_CSS = """
(function(){
    if(window.__kf_byse__)return;window.__kf_byse__=1;
    var s=document.createElement('style');
    s.id='kf-byse-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.popunder,.promo,.cookie-banner,.modal{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe,.jw-wrapper,.jw-player,.jw-media{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important;width:100vw!important;height:100vh!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);
    function kfFixByse(){
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('[class*="play"],button[aria-label*="play"],button[aria-label*="reproducir"]').forEach(function(el){
            el.style.pointerEvents='auto';
            if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){try{el.click();}catch(e){}}
        });
    }
    kfFixByse();
    var mo=new MutationObserver(function(){kfFixByse();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixByse();},20000);
    console.log('KF: Byse fix active');
})();
"""

        private const val HEXLOAD_EMBED_CSS = """
(function(){
    if(window.__kf_hexload__)return;window.__kf_hexload__=1;
    var s=document.createElement('style');
    s.id='kf-hex-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);
    function kfFixHexload(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('[class*="play"],button[aria-label*="play"]').forEach(function(el){
            el.style.pointerEvents='auto';
            if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){try{el.click();}catch(e){}}
        });
        if(window.player_start&&typeof window.player_start==='function'){
            try{window.player_start();}catch(e){}
        }
        if(window.np_vid&&window.np_vid.Play){try{window.np_vid.Play();}catch(e){}}
    }
    kfFixHexload();
    var mo=new MutationObserver(function(){kfFixHexload();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixHexload();},15000);
    console.log('KF: Hexload fix active');
})();
"""

        private const val MIXDROP_EMBED_CSS = """
(function(){
    if(window.__kf_mixdrop__)return;window.__kf_mixdrop__=1;
    var s=document.createElement('style');
    s.id='kf-mix-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe,.md-featured,.md-featured-section,.md-featured-section__inner,.md-featured-section__content{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important;controls:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        .md-featured{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important}
        [class*="overlay"]:not(video):not(iframe):not(.md-featured){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="popup"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"],[class*="vast"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);

    function kfFixMixdrop(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
            f.style.margin='0';f.style.padding='0';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('[class*="play"]').forEach(function(el){
            el.style.pointerEvents='auto';
        });
    }
    kfFixMixdrop();
    var mo=new MutationObserver(function(){kfFixMixdrop();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixMixdrop();},10000);
    console.log('KF: Mixdrop fix active');
})();
"""

        private const val SAVEFILES_EMBED_CSS = """
(function(){
    if(window.__kf_savefiles__)return;window.__kf_savefiles__=1;
    var s=document.createElement('style');
    s.id='kf-sf-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);
    function kfFixSavefiles(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('[class*="play"],button[aria-label*="play"]').forEach(function(el){
            el.style.pointerEvents='auto';
            if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){try{el.click();}catch(e){}}
        });
    }
    kfFixSavefiles();
    var mo=new MutationObserver(function(){kfFixSavefiles();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixSavefiles();},15000);
    console.log('KF: Savefiles fix active');
})();
"""

        private const val VOE_EMBED_CSS = """
(function(){
    if(window.__kf_voe__)return;window.__kf_voe__=1;
    var s=document.createElement('style');
    s.id='kf-voe-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important;width:100%!important;height:100%!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"],[class*="vast"],[class*="ima-"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important;position:absolute!important;width:0!important;height:0!important}
        [class*="ad-container"],[id*="ad-container"],[class*="ad-overlay"],[id*="ad-overlay"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="skip"],[id*="skip"]{pointer-events:auto!important;cursor:pointer!important;display:block!important;visibility:visible!important;z-index:9999999!important}
    ';
    document.head.appendChild(s);
    function kfFixVoe(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('[class*="skip"],[id*="skip"],button[aria-label*="skip"],button[aria-label*="saltar"]').forEach(function(el){
            el.style.pointerEvents='auto';el.style.display='block';el.style.visibility='visible';
            el.style.zIndex='9999999';
            if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){try{el.click();}catch(e){}}
        });
    }
    kfFixVoe();
    var mo=new MutationObserver(function(){kfFixVoe();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixVoe();},15000);
    console.log('KF: Voe fix active');
})();
"""

        private const val FILEMOON_EMBED_CSS = """
(function(){
    if(window.__kf_filemoon__)return;window.__kf_filemoon__=1;
    var s=document.createElement('style');
    s.id='kf-fm-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="popup"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);
    function kfFixFilemoon(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
    }
    kfFixFilemoon();
    var mo=new MutationObserver(function(){kfFixFilemoon();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixFilemoon();},15000);
    console.log('KF: Filemoon fix active');
})();
"""

        private const val STREAMTAPE_EMBED_CSS = """
(function(){
    if(window.__kf_streamtape__)return;window.__kf_streamtape__=1;
    var s=document.createElement('style');
    s.id='kf-st-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        #main-container,.video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="countdown"],[id*="countdown"]{display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="preroll"],[class*="pre-roll"],[class*="midroll"],[class*="postroll"]{display:none!important;visibility:hidden!important;pointer-events:none!important;z-index:-1!important}
    ';
    document.head.appendChild(s);
    function kfFixStreamtape(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
        document.querySelectorAll('[class*="play"],[id*="play"]').forEach(function(el){
            el.style.pointerEvents='auto';
            if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){try{el.click();}catch(e){}}
        });
    }
    kfFixStreamtape();
    var mo=new MutationObserver(function(){kfFixStreamtape();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixStreamtape();},15000);
    console.log('KF: Streamtape fix active');
})();
"""

        private const val FEMBED_EMBED_CSS = """
(function(){
    if(window.__kf_fembed__)return;window.__kf_fembed__=1;
    var s=document.createElement('style');
    s.id='kf-fem-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
    ';
    document.head.appendChild(s);
    function kfFixFembed(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
    }
    kfFixFembed();
    var mo=new MutationObserver(function(){kfFixFembed();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixFembed();},15000);
    console.log('KF: Fembed fix active');
})();
"""

        private const val STREAMSB_EMBED_CSS = """
(function(){
    if(window.__kf_streamsb__)return;window.__kf_streamsb__=1;
    var s=document.createElement('style');
    s.id='kf-sb-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
    ';
    document.head.appendChild(s);
    function kfFixStreamsb(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
    }
    kfFixStreamsb();
    var mo=new MutationObserver(function(){kfFixStreamsb();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixStreamsb();},15000);
    console.log('KF: StreamSB fix active');
})();
"""

        private const val STREAMWISH_EMBED_CSS = """
(function(){
    if(window.__kf_streamwish__)return;window.__kf_streamwish__=1;
    var s=document.createElement('style');
    s.id='kf-sw-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
    ';
    document.head.appendChild(s);
    function kfFixStreamwish(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
    }
    kfFixStreamwish();
    var mo=new MutationObserver(function(){kfFixStreamwish();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixStreamwish();},15000);
    console.log('KF: StreamWish fix active');
})();
"""

        private const val NUPLOAD_EMBED_CSS = """
(function(){
    if(window.__kf_nupload__)return;window.__kf_nupload__=1;
    var s=document.createElement('style');
    s.id='kf-nu-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
    ';
    document.head.appendChild(s);
    function kfFixNupload(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
    }
    kfFixNupload();
    var mo=new MutationObserver(function(){kfFixNupload();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixNupload();},15000);
    console.log('KF: Nupload fix active');
})();
"""

        private const val LULU_EMBED_CSS = """
(function(){
    if(window.__kf_lulu__)return;window.__kf_lulu__=1;
    var s=document.createElement('style');
    s.id='kf-lulu-css';
    s.textContent='
        html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}
        header,nav,.navbar,.top-bar,.footer,.sidebar,.ad,.ads,.popunder,.promo,.cookie-banner{display:none!important;visibility:hidden!important}
        .video-container,.player-container,.embed-responsive,.video-wrapper,.plyr,#player,.jwplayer,.video-js,video,iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;border:none!important;margin:0!important;padding:0!important;pointer-events:auto!important}
        video{object-fit:contain!important;pointer-events:auto!important}
        iframe{border:0!important;pointer-events:auto!important}
        [class*="overlay"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
        [class*="modal"]:not(video):not(iframe){display:none!important;visibility:hidden!important;pointer-events:none!important}
    ';
    document.head.appendChild(s);
    function kfFixLulu(){
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';
            f.style.border='none';f.style.pointerEvents='auto';
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';
            v.style.width='100vw';v.style.height='100vh';
            v.controls=true;
            try{v.play().catch(function(){});}catch(e){}
        });
    }
    kfFixLulu();
    var mo=new MutationObserver(function(){kfFixLulu();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfFixLulu();},15000);
    console.log('KF: Lulu fix active');
})();
"""

        private const val VIDEO_AD_SKIP_JS = """
(function(){
    if(window.__kf_ad_skip__)return;window.__kf_ad_skip__=1;
    function kfSkipAds(){
        var skipWords=['skip ad','skip ads','saltar anuncio','saltar publicidad','skip intro','cerrar anuncio','close ad','saltar en','skip in','anuncios en','ads in','omits','omitir','omitir anuncio'];
        var voeDomains=['voe.sx','voe'];
        var isVoePage=voeDomains.some(function(d){return window.location.hostname.indexOf(d)>=0||document.referrer.indexOf(d)>=0;});
        if(isVoePage){return;}
        document.querySelectorAll('button,a,span,div').forEach(function(el){
            var txt=(el.textContent||'').trim().toLowerCase();
            var cls=(el.className||'').toLowerCase();
            var id=(el.id||'').toLowerCase();
            if(cls.indexOf('voe')>=0||cls.indexOf('sx')>=0){return;}
            skipWords.forEach(function(w){
                if(txt===w||txt.indexOf(w)>=0||(cls.indexOf('skip')>=0&&cls.indexOf('btn')>=0)||cls.indexOf('saltar')>=0||(id.indexOf('skip')>=0&&id.indexOf('btn')>=0)||id.indexOf('saltar')>=0){
                    try{
                        if(el.offsetParent!==null&&el.offsetWidth>0&&el.offsetHeight>0){
                            el.click();
                            console.log('KF:SKIP-AD',txt||cls||id);
                        }
                    }catch(e){}
                }
            });
        });
        document.querySelectorAll('[class*="ima-ad"],[class*="ad-container"],[class*="preroll"],[class*="pre-roll"],[id*="ima-ad"],[id*="ad-container"],[id*="preroll"]').forEach(function(el){
            el.style.display='none';el.style.visibility='hidden';el.style.opacity='0';
            el.style.pointerEvents='none';el.style.position='absolute';el.style.width='0';el.style.height='0';el.style.zIndex='-1';
            el.remove();
        });
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';v.controls=true;
        });
        document.querySelectorAll('[class*="overlay"],[class*="modal"],[class*="popup"],[class*="backdrop"]').forEach(function(el){
            var txt=(el.textContent||'').toLowerCase();
            if(txt.indexOf('ad')>=0||txt.indexOf('publicidad')>=0||txt.indexOf('anuncio')>=0){
                el.remove();
            }
        });
    }
    kfSkipAds();
    var mo=new MutationObserver(function(){kfSkipAds();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setInterval(kfSkipAds,1000);
    console.log('KF: VideoAdSkip active');
})();
"""

        private const val AUTOPLAY_JS = """
(function(){
    function kfAutoPlay(){
        document.querySelectorAll('video').forEach(function(v){
            v.style.pointerEvents='auto';v.style.zIndex='9999998';v.controls=true;
            v.muted=false;
            v.play().catch(function(){});
        });
        var playSelectors=['[class*="play"]','[id*="play"]','button[aria-label*="Play"]','button[aria-label*="play"]','[class*="btn-play"]','[class*="play-btn"]','[class*="big-play"]','[class*="jw-icon-display"]'];
        playSelectors.forEach(function(sel){
            document.querySelectorAll(sel).forEach(function(el){
                if(el.offsetParent!==null&&el.offsetWidth>20&&el.offsetHeight>20){
                    try{el.click();console.log('KF:auto-play',sel);}catch(e){}
                }
            });
        });
        document.querySelectorAll('iframe').forEach(function(f){
            f.style.position='fixed';f.style.top='0';f.style.left='0';
            f.style.width='100vw';f.style.height='100vh';f.style.zIndex='999999';f.style.border='none';
        });
    }
    kfAutoPlay();
    var mo=new MutationObserver(function(){kfAutoPlay();});
    mo.observe(document.documentElement,{childList:true,subtree:true});
    setTimeout(function(){mo.disconnect();kfAutoPlay();},20000);
})();
"""

        private const val NEXT_EPISODE_JS = """
(function(){
    if(window.__kf_next_ep__)return;window.__kf_next_ep__=1;
    function kfFindNextEp(){
        var nextWords=['siguiente','next','next episode','siguiente episodio','sig. episodio','sig episodio','próximo','proximo'];
        var prevWords=['anterior','previous','prev','previous episode','episodio anterior'];
        var allLinks=document.querySelectorAll('a[href]');
        var bestNext=null;
        var bestScore=0;
        allLinks.forEach(function(a){
            var href=a.href||'';
            var txt=(a.textContent||'').trim().toLowerCase();
            var title=(a.getAttribute('title')||'').toLowerCase();
            var cls=(a.className||'').toLowerCase();
            var ariaLabel=(a.getAttribute('aria-label')||'').toLowerCase();
            if(!href||href.indexOf('http')!==0)return;
            if(href.indexOf(location.hostname)===-1&&href.indexOf('/')!==0)return;
            var isPrev=prevWords.some(function(w){return txt.indexOf(w)>=0||title.indexOf(w)>=0||ariaLabel.indexOf(w)>=0;});
            if(isPrev)return;
            var score=0;
            nextWords.forEach(function(w){
                if(txt.indexOf(w)>=0)score+=10;
                if(title.indexOf(w)>=0)score+=5;
                if(ariaLabel.indexOf(w)>=0)score+=5;
                if(cls.indexOf(w)>=0)score+=3;
            });
            if(a.querySelector('svg,i,.icon'))score+=2;
            if(txt.indexOf('›')>=0||txt.indexOf('»')>=0||txt.indexOf('→')>=0||txt.indexOf('>')>=0||txt.indexOf('arrow')>=0)score+=3;
            var arrow=a.querySelector('[class*="arrow"],[class*="next"],[class*="right"],.fa-chevron-right,.fa-angle-right,.icon-arrow-right,.glyphicon-chevron-right');
            if(arrow)score+=5;
            if(score>bestScore){
                bestScore=score;
                bestNext=href;
            }
        });
        if(bestNext&&bestScore>=5){
            console.log('KF:NEXT_EP_FOUND:'+bestNext+'|score:'+bestScore);
            if(window.KarinBridge){KarinBridge.onNextEpisodeFound(bestNext);}
            return bestNext;
        }
        return null;
    }
    var result=kfFindNextEp();
    if(!result){
        setTimeout(kfFindNextEp,3000);
        setTimeout(kfFindNextEp,8000);
    }
})();
"""

        private const val VIDEO_ENDED_JS = """
(function(){
    if(window.__kf_ended__)return;window.__kf_ended__=1;
    function kfCheckEnded(){
        document.querySelectorAll('video').forEach(function(v){
            if(v.ended||v.currentTime>0&&v.duration>0&&(v.duration-v.currentTime)<3){
                if(!window.__kf_ended_fired__){
                    window.__kf_ended_fired__=1;
                    console.log('KF:VIDEO_ENDED');
                    if(window.KarinBridge){KarinBridge.onVideoEnded();}
                }
            }
        });
    }
    kfCheckEnded();
    setInterval(kfCheckEnded,3000);
})();
"""

        private const val VIDEO_ENHANCE_CSS = """
(function(){
    if(window.__kf_enhance__)return;window.__kf_enhance__=1;
    var s=document.createElement('style');
    s.id='kf-video-enhance';
    s.textContent='video{filter:contrast(1.10)saturate(1.35)brightness(1.04)!important;image-rendering:auto!important}';
    document.head.appendChild(s);
    var l=document.createElement('style');
    l.id='kf-hide-login';
    l.textContent='a[href*="login"],a[href*="signin"],a[href*="sign-in"],a[href*="iniciar"],a[href*="registro"],a[href*="register"],a[href*="signup"],a[href*="sign-up"],button[class*="login"],button[class*="auth"],button[class*="signin"],button[class*="session"],.login-btn,.login-button,.auth-btn,.btn-login,.btn-auth,.nav-login,.nav-auth,.header-login,.header-auth,.user-login,.user-auth,.account-login,a.md-auth-trigger,li:has(>a.md-auth-trigger),.md-login-toggle,.md-auth-overlay,.md-auth-modal,#authOverlay,#authModal,.modal-login,.modal-auth,.overlay-login,.overlay-auth,.popup-login,.popup-auth,.sidebar-login,.sidebar-auth,.menu-login,.menu-auth,.dropdown-login,.dropdown-auth,[class*="login" i],[class*="signin" i],[class*="sign-in" i],[class*="auth" i][class*="modal" i],[class*="auth" i][class*="overlay" i],[class*="iniciar" i][class*="sesion" i],[class*="iniciar" i][class*="sesión" i]{display:none!important;visibility:hidden!important;opacity:0!important;height:0!important;overflow:hidden!important}';
    document.head.appendChild(l);
    console.log('KF: VideoEnhance + LoginHid CSS applied');
})();
"""
    }

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
        "mediaplex.com", "turn.com", "adsymptotic.com",
        "epidemictuna.com", "marginoboles.com",
        "cloudflareinsights.com", "beacon.min.js",
        "connect.facebook.net", "static.ads-twitter.com",
        "analytics.tiktok.com", "ads.linkedin.com",
        "bat.bing.com", "pixel.quantserve.com",
        "sb.scorecardresearch.com", "cm.everesttech.net",
        "cdn.taboola.com", "widgets.outbrain.com",
        "static.criteo.net", "ad.de.doubleclick.net",
        "emea.apialytics.net", "tag.brandcdn.com",
        "cdnads.maps.arcgis.com", "analytics.edgekey.net",
        "tags.tiqcdn.com", "cdn.optimizely.com",
        "a.impactradius-go.com", "static.perfdrive.com",
        "astatic.net", "go.skecherstr.com",
        "syndication.twitter.com", "platform.twitter.com/widgets",
        "connect.facebook.net/signals", "facebook.com/tr",
        "google-analytics.com/ga.js", "google-analytics.com/analytics.js",
        "googletagmanager.com/gtm.js",
        "imasdk.googleapis.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
        "securepubads.g.doubleclick.net", "www.googleadservices.com",
        "static.fleshjack.com", "ads.mondo.com",
        "ad.admob.com", "a.adorolla.com",
        "cdn.trafficstars.com", "go.lastybeauty.com",
        "tag.brightshare.com", "cdn.popinads.com",
        "adx.adform.net", "ad.auditude.com",
        "vast.adsrvr.org", "bidder.criteo.com",
        "ad.yieldlab.net", "serving-sys.com",
        "ad.smartadserver.com", "cdn.stroeerdigitalmedia.de"
    )

    private val adUrlPatterns = listOf(
        "/pop.", "/popup", "/click.", "/track.", "/pixel.", "/beacon.",
        "adsystem", "adserver", "adclick", "adview", "adlink", "adchoice",
        "/advert", "/adserve", "/adshow", "/adsync", "/adtag",
        ".gif?", "pixel.gif", "tracking.js", "analytics.js",
        "imasdk", "vast.xml", "vpaid", "preroll", "midroll",
        "googlesyndication", "pagead", "doubleclick"
    )

    private val videoAllowDomains = listOf(
        "dsvplay.com", "i.doodcdn.io", "static.doodcdn.io", "doodcdn.io",
        "doimg.net", "cloudatacdn.com", "r1148gsx.cloudatacdn.com",
        "mixdrop.top", "mixdrop.co", "mxcontent.net", "mxcontent.io",
        "bysekoze.com", "byse.sx",
        "hexload.com", "hexupload.net", "vjs.zencdn.net",
        "savefiles.com",
        "mega.nz",
        "voe.sx", "jessicachoosemake.com", "chaliceguzzlerlandlord.com",
        "reedunpack.com",
        "filemoon.sx", "filemoon.to",
        "streamtape.com", "stape.cc",
        "fembed.com", "24hd.su", "feurl.com", "vcdn.io",
        "streamsb.net", "sbplay1.com", "sbembed.com",
        "streamwish.com", "embedwish.com",
        "nupload.cc", "nuuuppp.com",
        "lulu.st", "luluvdo.com"
    )

    private fun shouldBlockUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (videoAllowDomains.any { lower.contains(it) }) return false
        if (blockedDomains.any { lower.contains(it) }) return true
        if (adUrlPatterns.any { lower.contains(it) }) return true
        if (lower.endsWith(".js") && (lower.contains("ads") || lower.contains("analytics") || lower.contains("tracking") || lower.contains("beacon"))) return true
        if (lower.endsWith(".gif") && (lower.contains("pixel") || lower.contains("track") || lower.contains("beacon"))) return true
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_embed_webview)

        loadingLayout = findViewById(R.id.webview_loading)
        skipButtonContainer = findViewById(R.id.skip_button_container)
        btnSkip = findViewById(R.id.btn_skip)
        mainHandler = Handler(Looper.getMainLooper())

        hiddenContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            x = -9999f
            y = -9999f
            clipChildren = false
            visibility = View.INVISIBLE
        }
        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            visibility = View.INVISIBLE
            alpha = 0f
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            setOnTouchListener { _, _ -> true }
            layoutParams = FrameLayout.LayoutParams(720, 1280)
        }
        hiddenContainer!!.addView(webView)
        val rootView = findViewById<FrameLayout>(android.R.id.content)
        rootView.addView(hiddenContainer)

        val embedUrl = intent.getStringExtra("embed_url") ?: ""
        title = intent.getStringExtra("video_title") ?: ""
        val serverName = intent.getStringExtra("server_name") ?: ""
        val episodeUrl = intent.getStringExtra("episode_url") ?: ""
        val epNum = intent.getIntExtra("episode_number", 0)
        currentEpisodeUrl = episodeUrl
        openExternalWhenReady = intent.getBooleanExtra("open_external", false)
        allServerUrls = intent.getStringArrayExtra("all_server_urls") ?: emptyArray()
        allServerNames = intent.getStringArrayExtra("all_server_names") ?: emptyArray()
        currentServerIndex = intent.getIntExtra("current_server_index", 0)
        playlist = com.karin.streamtv.util.PlaylistQueue.fromJson(intent.getStringExtra("playlist_json"))
        playlistIndex = intent.getIntExtra("playlist_index", 0)

        if (episodeUrl.isNotBlank()) {
            animeId = EpisodeProgress.generateAnimeId(episodeUrl)
            episodeNumber = epNum
        }

        btnSkip.setOnClickListener {
            skipCurrentInterval()
        }

        if (title.isNotBlank() && episodeNumber > 0) {
            fetchSkipTimes()
        }

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
            allowFileAccess = false
            allowContentAccess = false
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

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()?.lowercase() ?: return false
                if (shouldBlockUrl(url)) {
                    Log.d(TAG, "Blocked ad navigation: ${url.takeLast(60)}")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: return null
                if (shouldBlockUrl(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                }
                return null
            }

            private var isDoingAutoClick = false
            private var hasNavigatedAway = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(ADBLOCK_JS, null)
                view?.evaluateJavascript(DIAG_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url ?: embedUrl
                Log.d(TAG, "Page loaded: ${currentUrl.takeLast(80)}")

                if (currentUrl != embedUrl && embedUrl.isNotBlank()) {
                    hasNavigatedAway = true
                }

                view?.evaluateJavascript(ADBLOCK_JS, null)

                if (serverName.isNotBlank() && !hasNavigatedAway) {
                    isDoingAutoClick = true
                    val sn = serverName.replace("'", "\\'")
                    view?.evaluateJavascript("""
                        (function(){
                            var sn='$sn'.toLowerCase();
                            function kfClick(){
                                var els=document.querySelectorAll('a,button,span,li,div[role="tab"]');
                                for(var i=0;i<els.length;i++){
                                    var el=els[i];
                                    var txt=(el.textContent||'').trim().toLowerCase();
                                    var cd=(el.getAttribute('content-desc')||'').toLowerCase();
                                    var dataServer=(el.getAttribute('data-server')||'').toLowerCase();
                                    if(txt.indexOf(sn)>=0||cd.indexOf(sn)>=0||dataServer.indexOf(sn)>=0){
                                        try{el.click();console.log('KF:clk-server',txt||cd||dataServer);return true;}catch(e){}
                                    }
                                }
                                return false;
                            }
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
                            kfClick();
                            var mo=new MutationObserver(function(){
                                kfClick();
                                var src=kfExtract();
                                if(src&&src.indexOf(window.location.href)===-1){
                                    mo.disconnect();
                                    console.log('KF:redirect',src);
                                    window.location.href=src;
                                }
                            });
                            mo.observe(document.documentElement,{childList:true,subtree:true});
                            function kfTryExtract(attempt){
                                kfClick();
                                var src=kfExtract();
                                if(src&&src.indexOf(window.location.href)===-1){
                                    mo.disconnect();
                                    console.log('KF:redirect-'+attempt,src);
                                    window.location.href=src;
                                    return true;
                                }
                                return false;
                            }
                            setTimeout(function(){kfTryExtract(1);},2000);
                            setTimeout(function(){kfTryExtract(2);},5000);
                            setTimeout(function(){kfTryExtract(3);},8000);
                            setTimeout(function(){kfTryExtract(4);},12000);
                            setTimeout(function(){
                                kfClick();
                                var src=kfExtract();
                                if(!src){
                                    mo.disconnect();
                                    var videoHosts=['streamtape','stape','doodstream','dood','dsvplay','voe','mixdrop','filemoon','streamsb','sbplay','streamwish','embedwish','fembed','feurl','vcdn','nupload','hexload','savefiles','lulu','mega','byse','netu','mp4upload','sendvid','ok.ru','vk.com'];
                                    var all=document.querySelectorAll('a[href],video[src],source[src]');
                                    for(var i=0;i<all.length;i++){
                                        var h=all[i].href||all[i].src||all[i].getAttribute('data-src')||'';
                                        if(h&&h.indexOf('http')===0){
                                            var hl=h.toLowerCase();
                                            if(videoHosts.some(function(vh){return hl.indexOf(vh)>=0;})){
                                                window.location.href=h;return;
                                            }
                                        }
                                    }
                                }
                            },15000);
                        })();
                    """.trimIndent(), null)
                    Log.d(TAG, "Server auto-click+redirect: $serverName")
                }

                if (hasNavigatedAway || serverName.isBlank()) {
                    applyVideoCss(view, currentUrl)
                    view?.evaluateJavascript(VIDEO_AD_SKIP_JS, null)
                }

                view?.evaluateJavascript(VIDEO_EXTRACT_JS, null)

                if (hasNavigatedAway && !videoFound && !presented) {
                    val server = com.karin.streamtv.model.VideoServer.detectServer(currentUrl)
                    if (server.httpResolvable || currentUrl.lowercase().let { u ->
                            videoAllowDomains.any { d -> u.contains(d) }
                        }) {
                        Log.i(TAG, "Post-redirect HTTP resolve for: ${currentUrl.takeLast(80)}")
                        lifecycleScope.launch {
                            val resolved = com.karin.streamtv.scraper.ServerDirectResolver.resolve(currentUrl, currentEpisodeUrl)
                            if (resolved != null && !videoFound) {
                                Log.i(TAG, "Post-redirect HTTP resolved: ${resolved.url.takeLast(80)}")
                                presentExtracted(listOf(resolved))
                            }
                        }
                    }
                }
                view?.evaluateJavascript(VIDEO_ENDED_JS, null)
                view?.evaluateJavascript(NEXT_EPISODE_JS, null)

                view?.evaluateJavascript(AUTOPLAY_JS, null)
                view?.evaluateJavascript(NEXT_EPISODE_JS, null)
                mainHandler.postDelayed({
                    view?.evaluateJavascript(AUTOPLAY_JS, null)
                    view?.evaluateJavascript(NEXT_EPISODE_JS, null)
                }, 3000)
                mainHandler.postDelayed({
                    view?.evaluateJavascript(AUTOPLAY_JS, null)
                    view?.evaluateJavascript(NEXT_EPISODE_JS, null)
                }, 8000)
                mainHandler.postDelayed({
                    view?.evaluateJavascript(AUTOPLAY_JS, null)
                    view?.evaluateJavascript(NEXT_EPISODE_JS, null)
                }, 15000)

                view?.evaluateJavascript("""
                    (function(){
                        window.open=function(){return null;};
                        window.alert=function(){};
                        window.confirm=function(){return false;};
                        window.prompt=function(){return null;};
                        try{
                            Object.defineProperty(window,'onbeforeunload',{get:function(){return null;},set:function(){}});
                        }catch(e){}
                        try{window.direct_link='';}catch(e){}
                        try{window.popunder='';}catch(e){}
                        try{window.__popunder='';}catch(e){}
                        var origAssign=window.location.assign;
                        window.location.assign=function(u){
                            if(u&&u.indexOf(location.hostname)===-1){return;}
                            origAssign.call(window.location,u);
                        };
                        var origReplace=window.location.replace;
                        window.location.replace=function(u){
                            if(u&&u.indexOf(location.hostname)===-1){return;}
                            origReplace.call(window.location,u);
                        };
                        document.addEventListener('click',function(e){
                            var t=e.target;
                            while(t&&t!==document){
                                if(t.tagName==='A'&&t.target==='_blank'){
                                    e.preventDefault();e.stopPropagation();return false;
                                }
                                t=t.parentElement;
                            }
                        },true);
                        console.log('KF: Popup/redirect/ad blockers active');
                    })();
                """.trimIndent(), null)

            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView error: $errorCode - $description at $failingUrl")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                Log.e(TAG, "WebView HTTP error: ${errorResponse?.statusCode} for ${request?.url?.toString()?.takeLast(100)}")
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                Log.e(TAG, "WebView SSL error: ${error?.primaryError} - ${error?.url?.takeLast(100)}")
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
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

        webView.loadUrl(embedUrl)

        mainHandler.postDelayed({
            Log.w(TAG, "Background extraction timeout — still scraping servers")
        }, 15000)

        bridge = VideoBridge()
        webView.addJavascriptInterface(bridge!!, "KarinBridge")

        if (allServerUrls.isNotEmpty()) {
            mainHandler.postDelayed({
                if (!videoFound) {
                    tryNextServer()
                }
            }, 20000)
        }

        resolveViaHttp()
    }

    private fun resolveViaHttp() {
        val targetUrl = intent.getStringExtra("embed_url") ?: return
        val server = com.karin.streamtv.model.VideoServer.detectServer(targetUrl)
        if (!server.httpResolvable) {
            Log.d(TAG, "HTTP resolve skipped for ${server.displayName}")
            return
        }
        lifecycleScope.launch {
            var resolved = com.karin.streamtv.scraper.ServerDirectResolver.resolve(targetUrl, currentEpisodeUrl)
            if (resolved == null) {
                Log.w(TAG, "HTTP resolve failed, trying Cloudflare bypass for ${targetUrl.takeLast(60)}")
                val html = com.karin.streamtv.util.CloudflareInterceptor.solveWithWebView(this@EmbedWebViewActivity, targetUrl)
                if (html != null) {
                    resolved = com.karin.streamtv.scraper.ServerDirectResolver.resolveFromHtml(targetUrl, html, currentEpisodeUrl)
                }
            }
            if (resolved != null && !videoFound) {
                Log.i(TAG, "HTTP resolution produced direct link")
                withContext(Dispatchers.Main) { presentExtracted(listOf(resolved!!)) }
            }
        }
    }

    private fun openExoFromResolved(resolved: com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo) {
        videoFound = true
        if (animeId.isNotBlank() && episodeNumber > 0) {
            EpisodeProgress.markWatched(animeId, episodeNumber)
            EpisodeProgress.setLastWatchedEpisode(animeId, episodeNumber)
        }
        if (hasOpenedExternal) return
        hasOpenedExternal = true
        mainHandler.post {
            if (openExternalWhenReady) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(resolved.url), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(Intent.createChooser(intent, "Abrir con..."))
                } catch (e: Exception) {
                    Toast.makeText(this@EmbedWebViewActivity, "No se encontró reproductor externo", Toast.LENGTH_SHORT).show()
                }
                finish()
            } else {
                val intent = Intent(this@EmbedWebViewActivity, com.karin.streamtv.player.ExoPlayerActivity::class.java).apply {
                    putExtra("video_url", resolved.url)
                    putExtra("video_title", title)
                    putExtra("episode_url", currentEpisodeUrl)
                    putExtra("episode_number", episodeNumber)
                    putExtra("referer", resolved.referer)
                    putExtra("site_name", intent.getStringExtra("site_name") ?: "")
                    if (playlist.isNotEmpty()) {
                        putExtra("playlist_json", com.karin.streamtv.util.PlaylistQueue.toJson(playlist))
                        putExtra("playlist_index", playlistIndex)
                    }
                    if (resolved.needsMegaDecrypt && resolved.megaKey != null) {
                        putExtra("mega_key", android.util.Base64.encodeToString(resolved.megaKey, android.util.Base64.NO_WRAP))
                        putExtra("mega_ctr", resolved.megaCtrStart)
                    }
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun presentExtracted(bestUrl: String, allUrlsJson: String) {
        val urls = parseJsonUrls(allUrlsJson).filter { it.startsWith("http") }
        val list = if (urls.isEmpty()) {
            if (bestUrl.startsWith("http")) listOf(bestUrl) else emptyList()
        } else urls
        val links = list.map {
            com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo(
                url = it,
                referer = try { webView.url ?: currentEpisodeUrl } catch (e: Exception) { currentEpisodeUrl }
            )
        }
        presentExtracted(links)
    }

    private fun presentExtracted(links: List<com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo>) {
        if (presented || launching) return
        val unique = links.distinctBy { it.url }.filter { it.url.startsWith("http") }
        if (unique.isEmpty()) {
            mainHandler.post { showNoLinkDialog() }
            return
        }
        presented = true
        mainHandler.post { showExtractedDialog(unique) }
    }

    private fun parseJsonUrls(allUrlsJson: String): List<String> {
        return try {
            val arr = org.json.JSONArray(allUrlsJson)
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showExtractedDialog(links: List<com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo>) {
        loadingLayout.visibility = View.GONE
        val labels = links.map { link ->
            val server = com.karin.streamtv.model.VideoServer.detectServer(link.url)
            val host = try { android.net.Uri.parse(link.url).host ?: "" } catch (e: Exception) { "" }
            if (host.isBlank()) server.displayName else "${server.displayName} · $host"
        }.toTypedArray()

        val dialog = android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Enlaces extraídos (${links.size})")
            .setItems(labels) { _, which ->
                launching = true
                openExoFromResolved(links[which])
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.setOnDismissListener { if (!hasOpenedExternal && !launching) finish() }
        dialog.show()
    }

    private fun showNoLinkDialog() {
        if (presented || launching) return
        presented = true
        loadingLayout.visibility = View.GONE
        val embedUrl = intent.getStringExtra("embed_url") ?: ""
        val dialog = android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Sin enlace directo")
            .setMessage("No se pudo extraer un enlace de video directo de este servidor en segundo plano.")
            .setPositiveButton("Reintentar") { _, _ ->
                presented = false
                videoFound = false
                if (embedUrl.isNotBlank()) webView.loadUrl(embedUrl)
            }
            .setNegativeButton("Abrir en navegador") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(embedUrl)))
                } catch (e: Exception) {
                    Log.w(TAG, "No browser for embed url")
                }
                finish()
            }
            .setNeutralButton("Cancelar") { _, _ -> finish() }
            .create()
        dialog.show()
    }

    private fun tryNextServer() {
        if (presented || hasOpenedExternal) return
        currentServerIndex++
        if (currentServerIndex >= allServerUrls.size) {
            Log.d(TAG, "All servers exhausted")
            showNoLinkDialog()
            return
        }
        val nextUrl = allServerUrls[currentServerIndex]
        val nextName = allServerNames.getOrElse(currentServerIndex) { "" }
        Log.d(TAG, "Failover to server $currentServerIndex: $nextName - ${nextUrl.takeLast(60)}")
        Toast.makeText(this, "Probando servidor: $nextName", Toast.LENGTH_SHORT).show()
        videoFound = false
        autoPlayTriggered = false
        nextEpisodeUrlFromDom = null
        webView.loadUrl(nextUrl)

        mainHandler.postDelayed({
            if (!videoFound) {
                tryNextServer()
            }
        }, 20000)
    }

    private inner class VideoBridge {
        @JavascriptInterface
        fun onVideoFound(url: String, allUrlsJson: String) {
            Log.i(TAG, "Video source found: ${url.takeLast(60)}")
            presentExtracted(url, allUrlsJson)
        }

        @JavascriptInterface
        fun onDirectVideoFound(url: String, allUrlsJson: String) {
            Log.i(TAG, "Direct video URL (native fallback): ${url.takeLast(60)}")
            presentExtracted(url, allUrlsJson)
        }

        @JavascriptInterface
        fun onNextEpisodeFound(url: String) {
            Log.i(TAG, "Next episode URL from DOM: ${url.takeLast(80)}")
            nextEpisodeUrlFromDom = url
        }

        @JavascriptInterface
        fun onSkipIntervalReached(type: String) {
            Log.i(TAG, "Skip interval reached: $type")
            mainHandler.post {
                showSkipButton(type)
            }
        }

        @JavascriptInterface
        fun onSkipIntervalLeft() {
            Log.i(TAG, "Skip interval left")
            mainHandler.post {
                hideSkipButton()
            }
        }

        @JavascriptInterface
        fun onVideoEnded() {
            Log.i(TAG, "Video ended - auto-play check")
            if (autoPlayTriggered) return
            autoPlayTriggered = true
            mainHandler.post {
                if (!com.karin.streamtv.util.AutoPlayManager.isAutoPlayEnabled()) return@post

                val nextFromQueue = com.karin.streamtv.util.VideoQueue.peek()
                if (nextFromQueue != null) {
                    com.karin.streamtv.util.VideoQueue.poll()
                    Log.d(TAG, "Auto-play from queue: ${nextFromQueue.title}")
                    Toast.makeText(this@EmbedWebViewActivity, "Siguiente: ${nextFromQueue.title}", Toast.LENGTH_SHORT).show()
                    com.karin.streamtv.util.AutoPlayManager.startCountdown(object : com.karin.streamtv.util.AutoPlayManager.AutoPlayCallback {
                        override fun onCountdownTick(secondsRemaining: Int) {
                            Toast.makeText(this@EmbedWebViewActivity, "Siguiente: ${nextFromQueue.title} en ${secondsRemaining}s", Toast.LENGTH_SHORT).show()
                        }
                        override fun onCountdownFinish() {
                            val intent = android.content.Intent(this@EmbedWebViewActivity, SiteBrowserActivity::class.java).apply {
                                putExtra("autoplay_url", nextFromQueue.embedUrl)
                                putExtra("autoplay_title", nextFromQueue.title)
                                putExtra("site_name", nextFromQueue.serverName)
                            }
                            startActivity(intent)
                            finish()
                        }
                        override fun onAutoPlayCancelled() {}
                    })
                    return@post
                }

                if (playlist.isNotEmpty()) {
                    val nextIndex = playlistIndex + 1
                    if (nextIndex >= playlist.size) return@post
                    val next = playlist[nextIndex]
                    val siteName = intent.getStringExtra("site_name") ?: ""
                    Log.d(TAG, "Auto-play next playlist item: ${next.title}")
                    Toast.makeText(this@EmbedWebViewActivity, "Siguiente: ${next.title}", Toast.LENGTH_SHORT).show()
                    com.karin.streamtv.util.AutoPlayManager.startCountdown(object : com.karin.streamtv.util.AutoPlayManager.AutoPlayCallback {
                        override fun onCountdownTick(secondsRemaining: Int) {
                            Toast.makeText(this@EmbedWebViewActivity, "Siguiente: ${next.title} en ${secondsRemaining}s", Toast.LENGTH_SHORT).show()
                        }
                        override fun onCountdownFinish() {
                            startActivity(com.karin.streamtv.util.PlaylistQueue.buildIntent(this@EmbedWebViewActivity, playlist, nextIndex, siteName))
                            finish()
                        }
                        override fun onAutoPlayCancelled() {}
                    })
                    return@post
                }

                if (currentEpisodeUrl.isBlank() || episodeNumber <= 0) return@post

                val domUrl = nextEpisodeUrlFromDom
                val nextUrl = domUrl ?: com.karin.streamtv.util.AutoPlayManager.findNextEpisodeUrl(currentEpisodeUrl, episodeNumber)
                if (nextUrl == null) {
                    Log.d(TAG, "No next episode URL found (DOM: $domUrl, regex fallback)")
                    return@post
                }
                Log.d(TAG, "Auto-play next episode: $nextUrl")

                Toast.makeText(this@EmbedWebViewActivity, "Siguiente episodio en 5s...", Toast.LENGTH_LONG).show()

                com.karin.streamtv.util.AutoPlayManager.startCountdown(object : com.karin.streamtv.util.AutoPlayManager.AutoPlayCallback {
                    override fun onCountdownTick(secondsRemaining: Int) {
                        Toast.makeText(this@EmbedWebViewActivity, "Siguiente episodio en ${secondsRemaining}s", Toast.LENGTH_SHORT).show()
                    }
                    override fun onCountdownFinish() {
                        val intent = Intent(this@EmbedWebViewActivity, SiteBrowserActivity::class.java).apply {
                            putExtra("autoplay_url", nextUrl)
                            putExtra("autoplay_title", "Episodio ${episodeNumber + 1}")
                            putExtra("site_name", intent.getStringExtra("site_name") ?: "")
                        }
                        startActivity(intent)
                        finish()
                    }
                    override fun onAutoPlayCancelled() {}
                })
            }
        }
    }

    private fun fetchSkipTimes() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AniSkipService.getSkipTimes(title, episodeNumber)
                withContext(Dispatchers.Main) {
                    if (AppPreferences.isSkipOpeningEnabled() && result.opening != null) {
                        skipInterval = result.opening
                        skipType = "op"
                        Log.d(TAG, "Skip opening available: ${result.opening.startTime} - ${result.opening.endTime}")
                        startSkipMonitor()
                    } else if (AppPreferences.isSkipEndingEnabled() && result.ending != null) {
                        skipInterval = result.ending
                        skipType = "ed"
                        Log.d(TAG, "Skip ending available: ${result.ending.startTime} - ${result.ending.endTime}")
                        startSkipMonitor()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch skip times: ${e.message}")
            }
        }
    }

    private fun startSkipMonitor() {
        val interval = skipInterval ?: return
        val js = """
            (function(){
                if(window.__kf_skip_monitor__)return;window.__kf_skip_monitor__=1;
                var startTime=${interval.startTime};
                var endTime=${interval.endTime};
                var lastUpdate=0;
                function kfCheckSkip(){
                    var videos=document.querySelectorAll('video');
                    for(var i=0;i<videos.length;i++){
                        var v=videos[i];
                        if(v.currentTime>=startTime&&v.currentTime<endTime){
                            if(!window.__kf_skip_visible__){
                                window.__kf_skip_visible__=1;
                                if(window.KarinBridge){KarinBridge.onSkipIntervalReached('skip');}
                            }
                        }else{
                            if(window.__kf_skip_visible__){
                                window.__kf_skip_visible__=0;
                                if(window.KarinBridge){KarinBridge.onSkipIntervalLeft();}
                            }
                        }
                    }
                }
                setInterval(kfCheckSkip,500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun skipCurrentInterval() {
        val interval = skipInterval ?: return
        val endTime = interval.endTime
        val js = """
            (function(){
                var videos=document.querySelectorAll('video');
                for(var i=0;i<videos.length;i++){
                    var v=videos[i];
                    v.currentTime=$endTime;
                    v.play();
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        hideSkipButton()
        Log.d(TAG, "Skipped $skipType to $endTime")
    }

    private fun showSkipButton(type: String) {
        if (isShowingSkipButton) return
        isShowingSkipButton = true
        mainHandler.post {
            skipButtonContainer.visibility = View.VISIBLE
            btnSkip.text = if (type == "op") "Saltar Opening" else "Saltar Ending"
            btnSkip.requestFocus()
        }
    }

    private fun hideSkipButton() {
        isShowingSkipButton = false
        mainHandler.post {
            skipButtonContainer.visibility = View.GONE
        }
    }

    private fun applyVideoCss(view: WebView?, url: String) {
        val cssToInject = buildCssForUrl(url)
        if (cssToInject.isNotEmpty()) {
            view?.evaluateJavascript(VIDEO_AD_SKIP_JS, null)
            view?.evaluateJavascript(cssToInject, null)
            if (com.karin.streamtv.player.VideoEnhanceConfig.isEnabled()) {
                view?.evaluateJavascript(VIDEO_ENHANCE_CSS, null)
            }
            mainHandler.postDelayed({
                view?.evaluateJavascript(VIDEO_AD_SKIP_JS, null)
                view?.evaluateJavascript(cssToInject, null)
                if (com.karin.streamtv.player.VideoEnhanceConfig.isEnabled()) {
                    view?.evaluateJavascript(VIDEO_ENHANCE_CSS, null)
                }
            }, 2000)
            mainHandler.postDelayed({
                view?.evaluateJavascript(ADBLOCK_JS, null)
                view?.evaluateJavascript(VIDEO_AD_SKIP_JS, null)
                view?.evaluateJavascript(cssToInject, null)
                if (com.karin.streamtv.player.VideoEnhanceConfig.isEnabled()) {
                    view?.evaluateJavascript(VIDEO_ENHANCE_CSS, null)
                }
            }, 5000)
            mainHandler.postDelayed({
                view?.evaluateJavascript(ADBLOCK_JS, null)
                view?.evaluateJavascript(VIDEO_AD_SKIP_JS, null)
                view?.evaluateJavascript(cssToInject, null)
                if (com.karin.streamtv.player.VideoEnhanceConfig.isEnabled()) {
                    view?.evaluateJavascript(VIDEO_ENHANCE_CSS, null)
                }
            }, 10000)
        }
    }

    private fun buildCssForUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            "mega.nz" in lower || "mega.co" in lower -> MEGA_EMBED_CSS
            "filemoon" in lower -> FILEMOON_EMBED_CSS
            "streamtape" in lower || "stape" in lower -> STREAMTAPE_EMBED_CSS
            "fembed" in lower || "fem" in lower || "24hd" in lower || "feurl" in lower || "vcdn" in lower -> FEMBED_EMBED_CSS
            "streamsb" in lower || "sbplay" in lower || "sblong" in lower || "sbfull" in lower || "sbembed" in lower -> STREAMSB_EMBED_CSS
            "streamwish" in lower || "embedwish" in lower -> STREAMWISH_EMBED_CSS
            "nupload" in lower || "nuuuppp" in lower -> NUPLOAD_EMBED_CSS
            "lulu" in lower -> LULU_EMBED_CSS
            "mxdrop" in lower || "mxdplayer" in lower -> MIXDROP_EMBED_CSS
            "dsvplay" in lower || "doodstream" in lower || "dood" in lower -> DSVPLAY_EMBED_CSS
            "bysekoze" in lower || "byse" in lower -> BYSE_EMBED_CSS
            "savefiles" in lower -> SAVEFILES_EMBED_CSS
            "hexload" in lower -> HEXLOAD_EMBED_CSS
            "mixdrop" in lower -> MIXDROP_EMBED_CSS
            "voe" in lower || "jessicachoosemake" in lower -> VOE_EMBED_CSS
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
            "trembed=" in lower -> TOROPLAY_EMBED_CSS
            else -> GENERIC_EMBED_CSS
        }
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
        mainHandler.removeCallbacksAndMessages(null)
        webView.handler?.removeCallbacksAndMessages(null)
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        hiddenContainer?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        hiddenContainer = null
        super.onDestroy()
    }
}
