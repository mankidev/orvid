package com.orvid.app;

import com.orvid.net.Streams;
import android.app.Application;
import android.content.SharedPreferences;

import com.orvid.image.Images;
import com.orvid.net.Fetch;
import com.orvid.tor.Tor;
import com.orvid.yt.Visionos;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.File;
import java.util.Locale;

public final class OrvidApp extends Application {

    private static OrvidApp instance;
    private Images images;
    private SharedPreferences prefs;
    private AppLock lock;

    public File extRoot() {
        File root = new File(getFilesDir(), "ext");
        root.mkdirs();
        return root;
    }

    @Override
    public void onCreate() {
        System.setProperty("http.maxConnections", "48");
        System.setProperty("http.keepAliveDuration", "300000");
        super.onCreate();
        com.orvid.net.Diag.reset();
        com.orvid.net.Diag.mark("app start");
        instance = this;
        CrashLog.install(this);
        new Thread(() -> TempLogs.sweep(this), "orvid-sweep").start();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        lock = new AppLock(prefs);
        registerActivityLifecycleCallbacks(lock);
        registerActivityLifecycleCallbacks(new Background());
        NewPipe.init(Fetch.get());
        images = new Images(new File(getCacheDir(), "thumbs"));
        com.orvid.ext.Registry.reload(extRoot());
        Thread scripts = new Thread(() -> {
            for (com.orvid.ext.ExtSource s : com.orvid.ext.Registry.sources()) s.warm();
        }, "orvid-scripts");
        scripts.setPriority(Thread.MIN_PRIORITY);
        scripts.start();
        if (prefs.contains("dev_mode")) prefs.edit().remove("dev_mode").apply();
        if (prefs.contains("mode")) prefs.edit().remove("mode").apply();
        new File(getFilesDir(), "history.json").delete();
        File speech = new File(getFilesDir(), "speech");
        File[] models = speech.listFiles();
        if (models != null) for (File f : models) f.delete();
        speech.delete();
        images.clearDisk();
        Visionos.addWarmer(com.orvid.vk.Vk::warm);
        applyTor(!prefs.getBoolean("tor_off", false));
    }

    private static final class Background implements ActivityLifecycleCallbacks {
        private int started;

        @Override
        public void onActivityStarted(android.app.Activity activity) {
            started++;
        }

        @Override
        public void onActivityStopped(android.app.Activity activity) {
            started--;
            if (started > 0 || activity.isChangingConfigurations() || !com.orvid.play.Session.active()) return;
            com.orvid.play.Session.player().pause();
        }

        @Override
        public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {
        }

        @Override
        public void onActivityResumed(android.app.Activity activity) {
        }

        @Override
        public void onActivityPaused(android.app.Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) {
        }

        @Override
        public void onActivityDestroyed(android.app.Activity activity) {
        }
    }

    public static OrvidApp get() {
        return instance;
    }

    public Images images() {
        return images;
    }

    public AppLock lock() {
        return lock;
    }

    public boolean panicExit() {
        return prefs.getBoolean("panic_exit", true);
    }

    public void setPanicExit(boolean on) {
        prefs.edit().putBoolean("panic_exit", on).apply();
    }

    public void setDevMode(boolean on) {
        DevInfo.on = on;
    }

    public boolean autoplay() {
        return prefs.getBoolean("autoplay", true);
    }

    public void setAutoplay(boolean on) {
        prefs.edit().putBoolean("autoplay", on).apply();
    }

    public boolean debugOverlay() {
        return prefs.getBoolean("debug", false);
    }

    public void setDebugOverlay(boolean on) {
        prefs.edit().putBoolean("debug", on).apply();
    }

    public boolean bridges() {
        return prefs.getBoolean("tor_bridges", false);
    }

    public void setBridges(boolean on) {
        prefs.edit().putBoolean("tor_bridges", on).apply();
        if (!Fetch.torMode()) return;
        Tor.get().stop();
        Tor.dach().stop();
        applyTor(true);
    }

    public void restartTor() {
        applyTor(true);
    }

    public boolean torOn() {
        return Fetch.torMode();
    }

    public void setTor(boolean on) {
        prefs.edit().putBoolean("tor_off", !on).apply();
        if (on == Fetch.torMode()) return;
        applyTor(on);
    }

    public int layoutMode() {
        return prefs.getInt("layout", 0);
    }

    public void setLayoutMode(int mode) {
        prefs.edit().putInt("layout", mode).apply();
    }



    private void applyTor(boolean on) {
        Fetch.setTorMode(on);
        Locale locale = Locale.getDefault();
        String country = locale.getCountry().isEmpty() ? "US" : locale.getCountry();
        NewPipe.setupLocalization(Localization.fromLocale(locale),
                on ? ContentCountry.DEFAULT : new ContentCountry(country));
        if (on) {
            File dir = new File(getFilesDir(), "tor");
            dir.mkdirs();
            String binary = getApplicationInfo().nativeLibraryDir + "/libtor.so";
            com.orvid.tor.Bridges.configure(new File(getFilesDir(), "pt").getAbsolutePath(), rawText(com.orvid.R.raw.bridges));
            com.orvid.tor.Bridges.setEnabled(bridges());
            Tor.get().prefer(savedBridge());
            Tor.get().removeListener(bridgeWatcher);
            Tor.get().addListener(bridgeWatcher);
            Tor.get().start(dir, binary, android.os.Process.myPid());
            File dachDir = new File(getFilesDir(), "tor-dach");
            Tor.dach().prepare(dachDir, binary, android.os.Process.myPid(), () -> seedDach(dir, dachDir));
            warmCircuits();
        } else {
            Tor.get().stop();
            Tor.dach().stop();
        }
        Visionos.dropPool();
        Visionos.refill();
    }

    private final Tor.Listener bridgeWatcher = () -> {
        if (Tor.get().state() == Tor.State.READY && Tor.get().mode() != com.orvid.tor.Bridges.Mode.DIRECT) {
            prefs.edit().putString("tor_bridge", Tor.get().mode().name()).apply();
        }
    };

    private com.orvid.tor.Bridges.Mode savedBridge() {
        try {
            return com.orvid.tor.Bridges.Mode.valueOf(prefs.getString("tor_bridge", "DIRECT"));
        } catch (IllegalArgumentException e) {
            return com.orvid.tor.Bridges.Mode.DIRECT;
        }
    }

    private String rawText(int id) {
        try (java.io.InputStream in = getResources().openRawResource(id)) {
            return new String(Streams.readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "{}";
        }
    }

    private void seedDach(File main, File dach) {
        File data = new File(dach, "data");
        data.mkdirs();
        try (java.io.InputStream in = getResources().openRawResource(com.orvid.R.raw.geoip_dach);
             java.io.OutputStream out = new java.io.FileOutputStream(new File(dach, "geoip"))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            new java.io.FileOutputStream(new File(dach, "geoip6")).close();
        } catch (java.io.IOException ignored) {
        }
        for (String name : new String[]{"cached-certs", "cached-microdesc-consensus", "cached-microdescs"}) {
            File from = new File(main, "data/" + name);
            File to = new File(data, name);
            if (!from.isFile() || to.isFile() && to.lastModified() >= from.lastModified()) continue;
            try {
                java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException ignored) {
            }
        }
    }

    private static final int WARM_PORTS = 4;

    private static void warmCircuits() {
        new Thread(() -> {
            try {
                if (!Tor.get().awaitReady(120000)) return;
            } catch (InterruptedException e) {
                return;
            }
            com.orvid.net.Diag.mark("tor ready");
            new Thread(() -> {
                try {
                    com.orvid.yt.Suggest.warm();
                } catch (Exception ignored) {
                }
            }).start();
            int[] ports = Tor.get().ports();
            for (int i = 0; i < Math.min(WARM_PORTS, ports.length); i++) {
                int port = ports[i];
                new Thread(() -> {
                    try {
                        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                new java.net.InetSocketAddress("127.0.0.1", port));
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                new java.net.URL("https://check.torproject.org/api/ip").openConnection(proxy);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(15000);
                        c.setRequestProperty("User-Agent", Fetch.USER_AGENT);
                        try (java.io.InputStream in = c.getInputStream()) {
                            Streams.readAll(in);
                        }
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        }).start();
    }

    public void wipeAndExit() {
        try {
            com.orvid.play.Session.release();
        } catch (Exception ignored) {
        }
        try {
            com.orvid.tor.Tor.get().stop();
            com.orvid.tor.Tor.dach().stop();
        } catch (Exception ignored) {
        }
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) {
        }
        wipe(getCacheDir());
        wipe(getDir("webview", MODE_PRIVATE));
        wipe(getCodeCacheDir());
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    private static void wipe(java.io.File dir) {
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) wipe(f);
            f.delete();
        }
    }
}
