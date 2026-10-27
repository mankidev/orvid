package com.orvid.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.orvid.net.Fetch;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.concurrent.Executor;

public final class TorBrowser {

    private static final String NO_WEBRTC =
            "(function(){var k=['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel',"
                    + "'RTCSessionDescription','RTCIceCandidate'];for(var i=0;i<k.length;i++){try{"
                    + "Object.defineProperty(window,k[i],{value:undefined,writable:false,configurable:false});"
                    + "}catch(e){}}try{if(navigator.mediaDevices){Object.defineProperty(navigator,'mediaDevices',"
                    + "{value:undefined});}}catch(e){}})();";

    public interface PageListener {
        void finished(WebView view, String url);
    }

    public interface ErrorListener {
        void failed(WebView view, String url, String reason);
    }

    public static void reroute(Context context, String circuit, Executor background, Runnable done) {
        background.execute(() -> {
            int port;
            try {
                Fetch.rotate(circuit);
                port = Fetch.torPortFor(circuit);
            } catch (Exception e) {
                return;
            }
            ProxyConfig config = new ProxyConfig.Builder().addProxyRule("127.0.0.1:" + port).build();
            runOnMain(context, () -> ProxyController.getInstance().setProxyOverride(config,
                    command -> runOnMain(context, command), done));
        });
    }

    public interface Ready {
        void ready(WebView view);

        void unavailable(String reason);
    }

    private TorBrowser() {
    }

    public static boolean supported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    public static String chromeAgent(Context context) {
        String def = WebSettings.getDefaultUserAgent(context);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(def);
        String major = m.find() ? m.group(1) : "130";
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major
                + ".0.0.0 Mobile Safari/537.36";
    }

    public static void openSession(Context context, String circuit, Executor background, Ready ready) {
        if (!supported()) {
            ready.unavailable("webview");
            return;
        }
        if (!Fetch.torMode()) {
            ProxyController.getInstance().clearProxyOverride(command -> runOnMain(context, command),
                    () -> ready.ready(create(context)));
            return;
        }
        background.execute(() -> {
            int port;
            try {
                port = Fetch.torPortFor(circuit);
            } catch (Exception e) {
                runOnMain(context, () -> ready.unavailable("tor"));
                return;
            }
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:" + port)
                    .build();
            runOnMain(context, () -> ProxyController.getInstance().setProxyOverride(config,
                    command -> runOnMain(context, command),
                    () -> ready.ready(create(context))));
        });
    }

    private static void runOnMain(Context context, Runnable r) {
        new android.os.Handler(context.getMainLooper()).post(r);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView create(Context context) {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        WebView view = new WebView(context);
        cookies.setAcceptThirdPartyCookies(view, false);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(chromeAgent(context));
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSaveFormData(false);
        WebViewCompat.addDocumentStartJavaScript(view, NO_WEBRTC, Collections.singleton("*"));
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                boolean ok = allowed(request.getUrl()) && !AdBlock.blocked(request.getUrl().getHost());
                return ok ? null
                        : new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (!allowed(request.getUrl())) return true;
                if (AdBlock.blocked(request.getUrl().getHost())) return true;
                String current = v.getUrl();
                if (current == null || !request.isForMainFrame()) return false;
                return !AdBlock.sameSite(Uri.parse(current).getHost(), request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Object listener = v.getTag();
                if (listener instanceof PageListener) ((PageListener) listener).finished(v, url);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (!request.isForMainFrame()) return;
                Object listener = v.getTag();
                if (listener instanceof ErrorListener) {
                    ((ErrorListener) listener).failed(v, request.getUrl().toString(), String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int code = response.getStatusCode();
                if (code != 403 && code != 429 && code < 500) return;
                Object listener = v.getTag();
                if (listener instanceof ErrorListener) {
                    ((ErrorListener) listener).failed(v, request.getUrl().toString(), "HTTP " + code);
                }
            }
        });
        return view;
    }

    static boolean allowed(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null;
    }

    public static void destroy(WebView view) {
        if (view != null) {
            view.stopLoading();
            view.destroy();
        }
    }
}
