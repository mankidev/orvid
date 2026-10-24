package com.orvid.ext;

import com.orvid.net.Streams;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.orvid.net.Fetch;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Script {

    private static final long CPU_LIMIT_MS = 5000;
    private static final java.util.regex.Pattern GONE = java.util.regex.Pattern.compile(
            "unavailable|^(?:Error: )?not an? |no video in this post|members only");

    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final String PRELUDE =
            "var http = { json: function (u, h) { return JSON.parse(http.get(u, h)); }, get: __get, post: __post, check: function (u, h) { return JSON.parse(__check(u, h)); },"
            + " all: function (u, h) { return JSON.parse(__all(JSON.stringify(u), h)); },"
            + " page: function (u, h) { return JSON.parse(__page(u, h)); } };\n"
            + "var util = { duration: __duration, number: __number, unescape: __unescape, base64: __base64 };\n"
            + "var env = { tor: __tor() };\n"
            + "var cache = { get: __cacheGet, put: __cachePut };\n"
            + "function __call(name, a, b) { return JSON.stringify(typeof this[name] == \"function\" ? this[name](a, b) : null); }\n";

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected boolean hasFeature(Context cx, int feature) {
            if (feature == Context.FEATURE_ENABLE_JAVA_MAP_ACCESS) return false;
            return super.hasFeature(cx, feature);
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Object started = cx.getThreadLocal("started");
            Object waited = cx.getThreadLocal("waited");
            long host = waited instanceof Long ? (Long) waited : 0;
            if (started instanceof Long && System.currentTimeMillis() - (Long) started - host > CPU_LIMIT_MS) {
                throw new Error("script ran too long");
            }
        }
    };

    private final String name;
    private final String code;

    private final Map<String, Object[]> memory = new java.util.HashMap<>();

    Script(String name, String code) {
        this.name = name;
        this.code = code;
    }

    private static final Object PRELUDE_LOCK = new Object();
    private static volatile org.mozilla.javascript.Script preludeCompiled;
    private volatile org.mozilla.javascript.Script compiled;

    private static org.mozilla.javascript.Script prelude(Context cx) {
        org.mozilla.javascript.Script s = preludeCompiled;
        if (s != null) return s;
        synchronized (PRELUDE_LOCK) {
            if (preludeCompiled == null) preludeCompiled = cx.compileString(PRELUDE, "prelude", 1, null);
            return preludeCompiled;
        }
    }

    private org.mozilla.javascript.Script compiled(Context cx) {
        org.mozilla.javascript.Script s = compiled;
        if (s != null) return s;
        synchronized (this) {
            if (compiled == null) compiled = cx.compileString(code, name, 1, null);
            return compiled;
        }
    }

    void warm() {
        Context cx = FACTORY.enterContext();
        try {
            cx.setInterpretedMode(true);
            cx.setInstructionObserverThreshold(10000);
            prelude(cx);
            compiled(cx);
        } catch (RuntimeException ignored) {
        } finally {
            Context.exit();
        }
    }

    JsonObject call(String circuit, String function, String a, String b) throws IOException {
        Context cx = FACTORY.enterContext();
        try {
            cx.setInterpretedMode(true);
            cx.setClassShutter(className -> false);
            cx.setInstructionObserverThreshold(10000);
            cx.setMaximumInterpreterStackDepth(200);
            cx.putThreadLocal("started", System.currentTimeMillis());
            cx.putThreadLocal("waited", 0L);
            cx.removeThreadLocal("hostError");
            ScriptableObject scope = cx.initSafeStandardObjects();
            host(scope, circuit);
            cache(scope, circuit);
            prelude(cx).exec(cx, scope);
            compiled(cx).exec(cx, scope);
            Object fn = scope.get("__call", scope);
            if (!(fn instanceof Function)) throw new IOException(name + ": no entry");
            Object out = ((Function) fn).call(cx, scope, scope, new Object[] {function, a, b});
            String json = Context.toString(out);
            return "null".equals(json) ? null : JsonParser.object().from(json);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            Object host = cx.getThreadLocal("hostError");
            if (host instanceof IOException && e instanceof RhinoException
                    && String.valueOf(((RhinoException) e).details()).endsWith(text((IOException) host))) {
                throw (IOException) host;
            }
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (e instanceof org.mozilla.javascript.JavaScriptException
                    && GONE.matcher(String.valueOf(((RhinoException) e).details())).find()) {
                throw new ExtSource.Gone(name + ": " + ((RhinoException) e).details());
            }
            throw new IOException(name + ": " + e.getMessage(), e);
        } finally {
            Context.exit();
        }
    }

    private static void host(ScriptableObject scope, String circuit) {
        define(scope, "__get", args -> fetch(circuit, str(args, 0), null, null, headers(args, 1)));
        define(scope, "__post", args -> fetch(circuit, str(args, 0), str(args, 1), str(args, 2), headers(args, 3)));
        define(scope, "__duration", args -> (double) com.orvid.adult.Web.duration(str(args, 0)));
        define(scope, "__number", args -> (double) com.orvid.adult.Web.number(str(args, 0)));
        define(scope, "__unescape", args -> com.orvid.adult.Web.unescape(str(args, 0)));
        define(scope, "__base64", args -> new String(java.util.Base64.getMimeDecoder().decode(String.valueOf(str(args, 0))),
                StandardCharsets.UTF_8));
        define(scope, "__tor", args -> Fetch.torMode());
        define(scope, "__check", args -> check(circuit, str(args, 0), headers(args, 1)));
        define(scope, "__all", args -> all(circuit, str(args, 0), headers(args, 1)));
        define(scope, "__page", args -> page(circuit, str(args, 0), headers(args, 1)));
    }

    private void cache(ScriptableObject scope, String circuit) {
        define(scope, "__cacheGet", args -> {
            synchronized (memory) {
                Object[] hit = memory.get(circuit + "\n" + str(args, 0));
                return hit != null && System.currentTimeMillis() < (Long) hit[1] ? hit[0] : null;
            }
        });
        define(scope, "__cachePut", args -> {
            double ms = args.length > 2 && args[2] instanceof Number ? ((Number) args[2]).doubleValue() : 3600000;
            synchronized (memory) {
                if (memory.size() > 64) memory.clear();
                memory.put(circuit + "\n" + str(args, 0), new Object[] {str(args, 1), System.currentTimeMillis() + (long) ms});
            }
            return null;
        });
    }

    private interface Host {
        Object run(Object[] args) throws Exception;
    }

    private static void define(ScriptableObject scope, String name, Host host) {
        scope.defineProperty(name, new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable s, Scriptable thisObj, Object[] args) {
                long began = System.currentTimeMillis();
                try {
                    return host.run(args);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    cx.putThreadLocal("hostError", e);
                    throw ScriptRuntime.constructError("Error", text(e));
                } finally {
                    Object waited = cx.getThreadLocal("waited");
                    long before = waited instanceof Long ? (Long) waited : 0;
                    cx.putThreadLocal("waited", before + System.currentTimeMillis() - began);
                }
            }
        }, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
    }

    private static String text(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String str(Object[] args, int i) {
        if (i >= args.length || args[i] == null || args[i] instanceof org.mozilla.javascript.Undefined) return null;
        return Context.toString(args[i]);
    }

    private static NativeObject headers(Object[] args, int i) {
        return i < args.length && args[i] instanceof NativeObject ? (NativeObject) args[i] : null;
    }

    private static String check(String circuit, String url, NativeObject headers) throws Exception {
        if (url == null || !url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("bad url");
        return Fetch.on(circuit, () -> {
            HttpURLConnection c = Fetch.open(url);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            if (headers != null) {
                for (Map.Entry<Object, Object> e : headers.entrySet()) {
                    c.setRequestProperty(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            int code = c.getResponseCode();
            String to = c.getHeaderField("Location");
            c.disconnect();
            JsonObject out = new JsonObject();
            out.put("status", code);
            if (to != null) out.put("location", java.net.URI.create(url).resolve(to.replace(" ", "%20")).toString());
            return com.grack.nanojson.JsonWriter.string(out);
        });
    }

    private static final java.util.concurrent.ExecutorService PARALLEL = java.util.concurrent.Executors.newFixedThreadPool(6);
    private static final int MAX_ALL = 30;

    private static String all(String circuit, String list, NativeObject headers) throws Exception {
        JsonArray urls = JsonParser.array().from(list);
        if (urls.size() > MAX_ALL) throw new IOException("too many urls");
        java.util.List<java.util.concurrent.Future<String>> jobs = new java.util.ArrayList<>();
        com.orvid.net.Fetch.Scope scope = com.orvid.net.Fetch.scope();
        for (int i = 0; i < urls.size(); i++) {
            String u = urls.getString(i, null);
            jobs.add(PARALLEL.submit(() -> com.orvid.net.Fetch.within(scope, () -> fetch(circuit, u, null, null, headers))));
        }
        JsonArray out = new JsonArray();
        for (java.util.concurrent.Future<String> j : jobs) {
            try {
                out.add(j.get(40, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                j.cancel(true);
                out.add(null);
            }
        }
        return com.grack.nanojson.JsonWriter.string(out);
    }

    private static String page(String circuit, String url, NativeObject headers) throws Exception {
        if (url == null || !url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("bad url");
        return Fetch.on(circuit, () -> {
            HttpURLConnection c = Fetch.open(url);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
            if (headers != null) {
                for (Map.Entry<Object, Object> e : headers.entrySet()) {
                    c.setRequestProperty(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            int code = c.getResponseCode();
            StringBuilder cookies = new StringBuilder();
            for (Map.Entry<String, java.util.List<String>> h : c.getHeaderFields().entrySet()) {
                if (!"Set-Cookie".equalsIgnoreCase(h.getKey())) continue;
                for (String v : h.getValue()) {
                    if (cookies.length() > 0) cookies.append("; ");
                    cookies.append(v.split(";", 2)[0]);
                }
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new java.util.zip.GZIPInputStream(in);
                try (InputStream b = in) {
                    body = new String(Streams.readN(b, MAX_BODY), StandardCharsets.UTF_8);
                }
            }
            JsonObject out = new JsonObject();
            out.put("status", code);
            out.put("body", body);
            out.put("cookies", cookies.toString());
            out.put("url", c.getURL().toString());
            return com.grack.nanojson.JsonWriter.string(out);
        });
    }

    private static String fetch(String circuit, String url, String body, String type, NativeObject headers) throws Exception {
        if (url == null || !url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("bad url");
        return Fetch.on(circuit, () -> {
            HttpURLConnection c = Fetch.open(url);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
            if (headers != null) {
                for (Map.Entry<Object, Object> e : headers.entrySet()) {
                    c.setRequestProperty(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            if (body != null) {
                byte[] raw = body.getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", type == null ? "application/x-www-form-urlencoded" : type);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(raw.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(raw);
                }
            }
            int code = c.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("Captcha needed (HTTP " + code + ")");
            if (code >= 400) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new java.util.zip.GZIPInputStream(in);
            try (InputStream body2 = in) {
                byte[] got = Streams.readN(body2, MAX_BODY);
                return new String(got, StandardCharsets.UTF_8);
            }
        });
    }

    static JsonArray array(JsonObject o, String key) {
        JsonArray a = o.getArray(key);
        return a == null ? new JsonArray() : a;
    }
}
