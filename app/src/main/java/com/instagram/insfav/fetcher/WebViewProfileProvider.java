package com.instagram.insfav.fetcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One bounded, unattached WebView at a time; all WebView/CookieManager calls use the main looper. */
final class WebViewProfileProvider implements ProfileProvider {
    private final Context context;
    private final CookieJar cookies;
    private final PublicProfileProvider downloader = new PublicProfileProvider();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Diagnostics log;
    private final String probe;
    private volatile Operation active;
    private volatile boolean cancelled;
    WebViewProfileProvider(Context context, CookieJar cookies) throws Failure {
        this(context, cookies, false);
    }
    WebViewProfileProvider(Context context, CookieJar cookies, boolean highResolution) throws Failure {
        this.context = context.getApplicationContext(); this.cookies = cookies; log = new Diagnostics(this.context);
        try (InputStream input = context.getAssets().open(highResolution ? "profile-probe-hd.js" : "profile-probe.js"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { if (output.size() + n > 32768) throw new IOException(); output.write(buffer, 0, n); }
            probe = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new Failure("WEBVIEW_PROBE_UNAVAILABLE", 0, 0, true); }
    }
    @Override public void authenticate() throws Failure { resolve(""); }
    @Override public byte[] fetch(String user) throws Failure {
        if (!Identity.valid(user) || !Identity.normalize(user).equals(user)) throw new Failure("PROFILE_IDENTITY_UNVERIFIED");
        JSONObject result = resolve(user);
        URL image = WebProfilePolicy.image(result, user);
        if (cancelled) throw new Failure("REQUEST_CANCELLED");
        return downloader.download(image);
    }
    private JSONObject resolve(String user) throws Failure {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new Failure("WEBVIEW_WORKER_REQUIRED", 0, 0, true);
        if (cancelled) throw new Failure("REQUEST_CANCELLED");
        if (cookies.value("sessionid", System.currentTimeMillis()).isEmpty()) throw new Failure("COOKIE_EXPIRED", 0, 0, true);
        Operation operation = new Operation(user, cookies.value("ds_user_id", System.currentTimeMillis()));
        active = operation; main.post(operation::start);
        try {
            if (!operation.done.await(user.isEmpty() ? 25 : 65, TimeUnit.SECONDS)) {
                main.post(() -> operation.fail(new Failure("WEBVIEW_TIMEOUT", 0, 0, true)));
                throw new Failure("WEBVIEW_TIMEOUT", 0, 0, true);
            }
            if (operation.failure != null) throw operation.failure;
            return operation.result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); main.post(() -> operation.fail(new Failure("REQUEST_CANCELLED")));
            throw new Failure("REQUEST_CANCELLED");
        } finally { active = null; }
    }
    @Override public void cancel() {
        cancelled = true; downloader.cancel();
        Operation operation = active;
        if (operation != null) main.post(() -> operation.fail(new Failure("REQUEST_CANCELLED")));
    }
    @Override public void close() { cancel(); }

    private final class Operation {
        final String user, expectedId;
        final CountDownLatch done = new CountDownLatch(1);
        final Object token = new Object();
        volatile Failure failure; volatile JSONObject result;
        WebView view;
        boolean finishing, completed, started;
        long deadline;
        Operation(String user, String expectedId) { this.user = user; this.expectedId = expectedId; }
        void later(Runnable action, long delay) { main.postAtTime(action, token, SystemClock.uptimeMillis() + delay); }
        @SuppressLint("SetJavaScriptEnabled")
        void start() {
            if (finishing) return;
            if (cancelled) { fail(new Failure("REQUEST_CANCELLED")); return; }
            // Includes creation/cookie setup; no indefinitely pending platform callback.
            later(() -> fail(new Failure("WEBVIEW_TIMEOUT", 0, 0, true)), user.isEmpty() ? 18000 : 55000);
            try {
                WebView.setWebContentsDebuggingEnabled(false);
                view = new WebView(context);
                view.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                view.setSaveEnabled(false);
                // Provide a viewport without attaching a window or showing browser content.
                int w = 1080, h = 1920;
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
                view.layout(0, 0, w, h);
                WebSettings settings = view.getSettings();
                settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                settings.setCacheMode(WebSettings.LOAD_NO_CACHE); settings.setMediaPlaybackRequiresUserGesture(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
                settings.setGeolocationEnabled(false); settings.setOffscreenPreRaster(false);
                view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);
                ServiceWorkerWebSettings workerSettings = ServiceWorkerController.getInstance().getServiceWorkerWebSettings();
                workerSettings.setAllowFileAccess(false); workerSettings.setAllowContentAccess(false);
                workerSettings.setBlockNetworkLoads(true);
                view.setWebViewClient(client());
                view.setWebChromeClient(new WebChromeClient() {
                    @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
                    @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
                    @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) { callback.invoke(origin, false, false); }
                    @Override public boolean onJsAlert(WebView v, String url, String message, JsResult result) { result.cancel(); return true; }
                    @Override public boolean onJsConfirm(WebView v, String url, String message, JsResult result) { result.cancel(); return true; }
                    @Override public boolean onJsPrompt(WebView v, String url, String message, String initial, JsPromptResult result) { result.cancel(); return true; }
                    @Override public boolean onShowFileChooser(WebView v, ValueCallback<android.net.Uri[]> callback, FileChooserParams params) { callback.onReceiveValue(null); return true; }
                });
                view.setDownloadListener((url, agent, disposition, type, length) -> fail(new Failure("WEBVIEW_DOWNLOAD_BLOCKED", 0, 0, true)));
                CookieManager manager = CookieManager.getInstance();
                manager.setAcceptCookie(true); manager.setAcceptThirdPartyCookies(view, false);
                manager.removeAllCookies(ignored -> { if (!finishing) applyCookies(manager, 0); });
                log.log("WEBVIEW", "CREATED", 0);
            } catch (RuntimeException | LinkageError error) { fail(new Failure("WEBVIEW_UNAVAILABLE", 0, 0, true)); }
        }
        void applyCookies(CookieManager manager, int index) {
            if (finishing) return;
            if (cancelled) { fail(new Failure("REQUEST_CANCELLED")); return; }
            if (index == cookies.entries.size()) {
                log.log("WEBVIEW", "COOKIES_APPLIED", 0);
                started = true; deadline = SystemClock.uptimeMillis() + (user.isEmpty() ? 10000 : 45000);
                view.loadUrl("https://www.instagram.com/" + (user.isEmpty() ? "" : user + "/"));
                later(this::poll, 500); return;
            }
            CookieJar.Entry entry = cookies.entries.get(index);
            if (entry.expires != 0 && entry.expires <= System.currentTimeMillis() / 1000
                || !(entry.domain.equals("www.instagram.com") || entry.domain.equals("instagram.com") && entry.subdomains)) {
                applyCookies(manager, index + 1); return;
            }
            if (!entry.path.matches("/[A-Za-z0-9_./%~\\-]*")) { fail(new Failure("COOKIE_INVALID", 0, 0, true)); return; }
            // Host-only and session-only copies. CSRF cookies remain readable by Instagram's own JS.
            String value = entry.name + "=" + entry.value + "; Path=" + entry.path + "; Secure; SameSite=Lax" + (entry.name.equals("sessionid") ? "; HttpOnly" : "");
            manager.setCookie("https://www.instagram.com/", value, accepted -> {
                if (finishing) return;
                if (!Boolean.TRUE.equals(accepted)) fail(new Failure("COOKIE_WEBVIEW_REJECTED", 0, 0, true));
                else applyCookies(manager, index + 1);
            });
        }
        WebViewClient client() { return new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (finishing) return true;
                if (!request.isForMainFrame()) return !WebProfilePolicy.resource(request.getUrl().toString());
                String code = WebProfilePolicy.navigation(request.getUrl().toString(), user);
                if (!code.isEmpty()) { fail(new Failure(code, 0, 0, true)); return true; }
                return false;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (finishing || !started) return;
                String code = WebProfilePolicy.navigation(url, user);
                if (!code.isEmpty()) fail(new Failure(code, 0, 0, true));
            }
            @Override public void onPageFinished(WebView v, String url) {
                // No success here: poll checks identity, DOM and image completion separately.
                if (!finishing) log.log("WEBVIEW", "PAGE_FINISHED", 0);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return WebProfilePolicy.resource(request.getUrl().toString()) ? null : new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                String host = request.getUrl().getHost();
                if (finishing || !("www.instagram.com".equals(host) || "instagram.com".equals(host))) return;
                String retry = null;
                if (response.getResponseHeaders() != null) for (Map.Entry<String,String> entry : response.getResponseHeaders().entrySet())
                    if (entry.getKey().equalsIgnoreCase("Retry-After")) retry = entry.getValue();
                Failure error = WebProfilePolicy.http(response.getStatusCode(), request.isForMainFrame(), retry);
                if (error != null) fail(error);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !finishing) fail(new Failure("WEBVIEW_NETWORK_ERROR", 0, 0, true));
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel(); fail(new Failure("TLS_ERROR", 0, 0, true));
            }
            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler handler, String host, String realm) {
                handler.cancel(); fail(new Failure("ACCESS_BLOCKED", 0, 0, true));
            }
            @Override public void onReceivedClientCertRequest(WebView v, ClientCertRequest request) {
                request.cancel(); fail(new Failure("TLS_ERROR", 0, 0, true));
            }
            @android.annotation.TargetApi(27)
            @Override public void onSafeBrowsingHit(WebView v, WebResourceRequest request, int type, SafeBrowsingResponse response) {
                response.backToSafety(false); fail(new Failure("WEBVIEW_SAFE_BROWSING_BLOCKED", 0, 0, true));
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                fail(new Failure("WEBVIEW_RENDERER_GONE", 0, 0, true)); return true;
            }
        }; }
        void poll() {
            if (finishing) return;
            if (cancelled) { fail(new Failure("REQUEST_CANCELLED")); return; }
            if (SystemClock.uptimeMillis() >= deadline) {
                fail(new Failure(user.isEmpty() ? "COOKIE_AUTH_UNVERIFIED" : "PROFILE_IMAGE_URL_MISSING", 200, 0, user.isEmpty())); return;
            }
            try {
                String url = view.getUrl();
                if (url == null || url.equals("about:blank")) { later(this::poll, 500); return; }
                String code = WebProfilePolicy.navigation(url, user);
                if (!code.isEmpty()) { fail(new Failure(code, 0, 0, true)); return; }
                view.evaluateJavascript("(" + probe + ")(" + JSONObject.quote(user) + "," + JSONObject.quote(expectedId) + ")", encoded -> {
                    if (finishing) return;
                    try {
                        JSONObject data = WebProfilePolicy.snapshot(encoded);
                        String wall = data.optString("wall");
                        if (!wall.isEmpty()) {
                            // Only this fixed vocabulary may cross into logs.
                            for (String allowed : new String[]{"CHALLENGE_REQUIRED","COOKIE_INVALID_EXPIRED","PROFILE_PAGE_UNAVAILABLE","PROFILE_DEFAULT_AVATAR","PROFILE_DATA_AMBIGUOUS","UNTRUSTED_SOURCE"})
                                if (wall.equals(allowed)) { fail(new Failure(allowed, 200, 0, !allowed.equals("PROFILE_DEFAULT_AVATAR") && !allowed.equals("PROFILE_DATA_AMBIGUOUS") && !allowed.equals("PROFILE_PAGE_UNAVAILABLE"))); return; }
                            fail(new Failure("WEBVIEW_SNAPSHOT_INVALID", 0, 0, true)); return;
                        }
                        if (user.isEmpty() && data.optBoolean("authenticated")) { finish(data, null); return; }
                        if (!user.isEmpty() && data.has("url")) { WebProfilePolicy.image(data, user); log.log("WEBVIEW", "PROFILE_IMAGE_RESOLVED", 0); finish(data, null); return; }
                        later(this::poll, 500);
                    } catch (Failure error) { fail(error); }
                });
            } catch (RuntimeException error) { fail(new Failure("WEBVIEW_SCRIPT_ERROR", 0, 0, true)); }
        }
        void fail(Failure error) { finish(null, error); }
        void finish(JSONObject data, Failure error) {
            if (finishing) return;
            finishing = true; result = data; failure = error; main.removeCallbacksAndMessages(token);
            if (error != null) log.log("WEBVIEW", error.code, error.http);
            try {
                if (view != null) {
                    view.setWebViewClient(new WebViewClient()); view.setWebChromeClient(null); view.setDownloadListener(null);
                    view.stopLoading(); view.getSettings().setJavaScriptEnabled(false);
                    view.clearHistory(); view.clearCache(true); view.destroy(); view = null;
                }
                WebStorage.getInstance().deleteAllData();
                later(() -> { failure = new Failure("WEBVIEW_CLEANUP_FAILED", 0, 0, true); complete(); }, 4000);
                CookieManager.getInstance().removeAllCookies(ignored -> complete());
            } catch (RuntimeException | LinkageError cleanup) { failure = new Failure("WEBVIEW_CLEANUP_FAILED", 0, 0, true); complete(); }
        }
        void complete() {
            if (completed) return;
            completed = true; main.removeCallbacksAndMessages(token); log.log("WEBVIEW", "CLEANED", 0); done.countDown();
        }
    }
}
