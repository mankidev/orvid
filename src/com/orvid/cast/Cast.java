package com.orvid.cast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Cast implements CastSession.Listener, Discovery.Listener {

    public interface Listener {
        void onCastUpdate();
    }

    private static Cast instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Discovery discovery;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orvid-cast");
        t.setDaemon(true);
        return t;
    });
    private List<CastDevice> devices = new ArrayList<>();
    private int discoveryUsers;
    private CastSession session;
    private CastItem media;
    private List<CastItem.Track> tracks = Collections.emptyList();
    private final List<String> manual = new ArrayList<>();
    private String error;

    private Cast(Context context) {
        app = context.getApplicationContext();
        discovery = new Discovery(app, this);
    }

    public static synchronized Cast get(Context context) {
        if (instance == null) instance = new Cast(context);
        return instance;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void startDiscovery() {
        if (discoveryUsers++ == 0) {
            discovery.start();
            for (String host : manual) discovery.addManual(host);
        }
    }

    public void stopDiscovery() {
        if (discoveryUsers > 0 && --discoveryUsers == 0) discovery.stop();
    }

    public List<CastDevice> devices() {
        return new ArrayList<>(devices);
    }

    public void addManual(String host) {
        if (!manual.contains(host)) manual.add(host);
        discovery.addManual(host);
    }

    public CastSession session() {
        return session;
    }

    public boolean active() {
        return session != null && session.state() != CastSession.State.CLOSED;
    }

    public boolean connected() {
        return session != null && session.state() == CastSession.State.CONNECTED;
    }

    public CastItem media() {
        return media;
    }

    public List<CastItem.Track> tracks() {
        return tracks;
    }

    public String takeError() {
        String e = error;
        error = null;
        return e;
    }

    public void connect(CastDevice device) {
        if (active() && session.device.equals(device)) return;
        end(false);
        error = null;
        session = new CastSession(device, this);
        session.start();
        CastService.start(app);
        notifyAll(null);
    }

    public void end(boolean stopTv) {
        if (session == null) return;
        CastSession s = session;
        session = null;
        media = null;
        tracks = Collections.emptyList();
        s.close(stopTv);
        CastService.stop(app);
        notifyAll(null);
    }

    public void cast(CastItem m, int active, long startMs) {
        List<CastItem.Track> list = m.subtitles;
        CastSession s = session;
        if (s == null) return;
        media = m;
        tracks = new ArrayList<>(list);
        notifyAll(null);
        worker.execute(() -> {
            try {
                CastProxy server = CastProxy.get();
                server.use(m);
                String host = CastProxy.lanAddress(s.device.host);
                s.load(CastMedia.build(m, list, server, host), startMs, active >= 0 ? new long[]{active + 1} : new long[0]);
            } catch (Exception e) {
                notifyAll(app.getString(com.orvid.R.string.cast_failed, String.valueOf(e.getMessage())));
            }
        });
    }

    @Override
    public void onCastChanged(CastSession changed) {
        main.post(() -> {
            if (changed != session) return;
            if (changed.state() == CastSession.State.CLOSED) {
                error = changed.error();
                session = null;
                media = null;
                tracks = Collections.emptyList();
                CastService.stop(app);
            }
            for (Listener l : listeners) l.onCastUpdate();
        });
    }

    @Override
    public void onDevices(List<CastDevice> list) {
        devices = list;
        for (Listener l : listeners) l.onCastUpdate();
    }

    private void notifyAll(String message) {
        main.post(() -> {
            if (message != null) error = message;
            for (Listener l : listeners) l.onCastUpdate();
        });
    }
}
