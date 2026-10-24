package com.orvid.cast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Discovery {

    private static final String TYPE = "_googlecast._tcp";

    interface Listener {
        void onDevices(List<CastDevice> devices);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final Map<String, CastDevice> found = new LinkedHashMap<>();
    private final Map<String, String> byService = new LinkedHashMap<>();
    private final Deque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock lock;

    Discovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        nsd = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    void start() {
        if (discovery != null) return;
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            lock = wifi.createMulticastLock("orvid");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                main.post(() -> discovery = null);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    pending.add(info);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                main.post(() -> {
                    String id = byService.remove(info.getServiceName());
                    if (id != null && found.remove(id) != null) publish();
                });
            }
        };
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    void stop() {
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (IllegalArgumentException ignored) {
            }
            discovery = null;
        }
        pending.clear();
        if (lock != null && lock.isHeld()) lock.release();
    }

    List<CastDevice> devices() {
        return new ArrayList<>(found.values());
    }

    void addManual(String host) {
        new Thread(() -> {
            String name = host;
            String model = "";
            try {
                JsonObject info = JsonParser.object().from(eureka(host));
                name = info.getString("name", host);
                model = info.getObject("device_info").getString("model_name", "");
            } catch (Exception ignored) {
            }
            CastDevice d = new CastDevice("ip:" + host, name, model, host, 8009);
            main.post(() -> {
                found.put(d.id, d);
                publish();
            });
        }, "orvid-manual").start();
    }

    private static String eureka(String host) throws java.io.IOException {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, 8008), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /setup/eureka_info?params=name,device_info HTTP/1.0\r\nHost: " + host
                    + ":8008\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            java.io.InputStream in = socket.getInputStream();
            for (int n = in.read(chunk); n > 0 && buf.size() < 262144; n = in.read(chunk)) buf.write(chunk, 0, n);
            String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            int body = text.indexOf("\r\n\r\n");
            if (!text.startsWith("HTTP/1.") || !text.contains(" 200 ") || body < 0) throw new java.io.IOException("no device info");
            return text.substring(body + 4);
        }
    }

    private void resolveNext() {
        if (resolving || pending.isEmpty()) return;
        resolving = true;
        NsdServiceInfo next = pending.poll();
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                main.post(() -> {
                    resolving = false;
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) pending.add(info);
                    main.postDelayed(Discovery.this::resolveNext, 200);
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                main.post(() -> {
                    resolving = false;
                    add(info);
                    resolveNext();
                });
            }
        });
    }

    private void add(NsdServiceInfo info) {
        if (info.getHost() == null) return;
        Map<String, byte[]> attrs = info.getAttributes();
        String id = text(attrs, "id");
        if (id.isEmpty()) id = info.getServiceName();
        String name = text(attrs, "fn");
        if (name.isEmpty()) name = info.getServiceName();
        CastDevice d = new CastDevice(id, name, text(attrs, "md"), info.getHost().getHostAddress(), info.getPort());
        byService.put(info.getServiceName(), id);
        found.put(id, d);
        publish();
    }

    private static String text(Map<String, byte[]> attrs, String key) {
        byte[] v = attrs == null ? null : attrs.get(key);
        return v == null ? "" : new String(v, StandardCharsets.UTF_8);
    }

    private void publish() {
        listener.onDevices(devices());
    }
}
