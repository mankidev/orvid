package com.orvid.cast;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CastSession implements CastChannel.Listener {

    public static final String MEDIA_RECEIVER = "CC1AD845";
    private static final long POLL_MS = 3000;

    public enum State { CONNECTING, CONNECTED, CLOSED }

    public interface Listener {
        void onCastChanged(CastSession session);
    }

    public final CastDevice device;
    private final Listener listener;
    private final CastChannel channel = new CastChannel(this);
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orvid-send");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "orvid-poll");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger requests = new AtomicInteger(1);

    private volatile State state = State.CONNECTING;
    private volatile String transportId;
    private volatile String sessionId;
    private volatile JsonObject pendingLoad;
    private volatile int loadRequest = -1;
    private volatile long mediaSessionId = -1;
    private volatile String playerState = "IDLE";
    private volatile String idleReason;
    private volatile double position;
    private volatile long positionAt;
    private volatile double rate = 1;
    private volatile double duration;
    private volatile double volume = 1;
    private volatile boolean muted;
    private volatile long[] activeTracks = new long[0];
    private volatile String error;
    private volatile boolean everLoaded;

    public CastSession(CastDevice device, Listener listener) {
        this.device = device;
        this.listener = listener;
    }

    public void start() {
        sender.execute(() -> {
            try {
                channel.connect(device.host, device.port);
                JsonObject launch = request("LAUNCH");
                launch.put("appId", MEDIA_RECEIVER);
                channel.send(CastChannel.RECEIVER, CastChannel.NS_RECEIVER, launch);
                poller.scheduleWithFixedDelay(this::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
            } catch (IOException e) {
                fail("Could not reach " + device.name + ": " + e.getMessage());
            }
        });
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    public String playerState() {
        return playerState;
    }

    public String idleReason() {
        return idleReason;
    }

    public boolean hasMedia() {
        return mediaSessionId >= 0 && !"IDLE".equals(playerState) || loadRequest >= 0 || pendingLoad != null;
    }

    public boolean loading() {
        return loadRequest >= 0 || pendingLoad != null;
    }

    public boolean playing() {
        return "PLAYING".equals(playerState) || "BUFFERING".equals(playerState);
    }

    public long positionMs() {
        double p = position;
        if ("PLAYING".equals(playerState)) p += (System.currentTimeMillis() - positionAt) / 1000.0 * rate;
        if (duration > 0) p = Math.min(p, duration);
        return Math.max(0, Math.round(p * 1000));
    }

    public long durationMs() {
        return Math.round(duration * 1000);
    }

    public double volume() {
        return volume;
    }

    public boolean muted() {
        return muted;
    }

    public long[] activeTracks() {
        return activeTracks.clone();
    }

    public void load(JsonObject media, long startMs, long[] active) {
        JsonObject load = request("LOAD");
        load.put("media", media);
        load.put("autoplay", true);
        load.put("currentTime", startMs / 1000.0);
        JsonArray ids = new JsonArray();
        for (long id : active) ids.add(id);
        load.put("activeTrackIds", ids);
        error = null;
        idleReason = null;
        everLoaded = false;
        position = startMs / 1000.0;
        positionAt = System.currentTimeMillis();
        duration = media.getDouble("duration", 0);
        pendingLoad = load;
        sender.execute(this::flushLoad);
        changed();
    }

    public void play() {
        mediaCommand("PLAY", null);
    }

    public void pause() {
        mediaCommand("PAUSE", null);
    }

    public void seek(long ms) {
        position = ms / 1000.0;
        positionAt = System.currentTimeMillis();
        JsonObject extra = new JsonObject();
        extra.put("currentTime", ms / 1000.0);
        mediaCommand("SEEK", extra);
        changed();
    }

    public void stopMedia() {
        mediaCommand("STOP", null);
    }

    public void setTracks(long[] ids) {
        JsonObject extra = new JsonObject();
        JsonArray list = new JsonArray();
        for (long id : ids) list.add(id);
        extra.put("activeTrackIds", list);
        activeTracks = ids.clone();
        mediaCommand("EDIT_TRACKS_INFO", extra);
        changed();
    }

    public void setVolume(double level) {
        volume = Math.max(0, Math.min(1, level));
        JsonObject v = new JsonObject();
        v.put("level", volume);
        receiverVolume(v);
    }

    public void setMuted(boolean on) {
        muted = on;
        JsonObject v = new JsonObject();
        v.put("muted", on);
        receiverVolume(v);
    }

    public void close(boolean stopReceiver) {
        if (state == State.CLOSED) return;
        String sid = sessionId;
        sender.execute(() -> {
            if (stopReceiver && sid != null && channel.isOpen()) {
                try {
                    JsonObject stop = request("STOP");
                    stop.put("sessionId", sid);
                    channel.send(CastChannel.RECEIVER, CastChannel.NS_RECEIVER, stop);
                } catch (IOException ignored) {
                }
            }
            shutdown();
        });
        state = State.CLOSED;
        changed();
    }

    private void receiverVolume(JsonObject v) {
        JsonObject r = request("SET_VOLUME");
        r.put("volume", v);
        sendAsync(CastChannel.RECEIVER, CastChannel.NS_RECEIVER, r);
        changed();
    }

    private void mediaCommand(String type, JsonObject extra) {
        if (mediaSessionId < 0 || transportId == null) return;
        JsonObject r = request(type);
        r.put("mediaSessionId", mediaSessionId);
        if (extra != null) for (String k : extra.keySet()) r.put(k, extra.get(k));
        sendAsync(transportId, CastChannel.NS_MEDIA, r);
    }

    private void sendAsync(String destination, String namespace, JsonObject payload) {
        sender.execute(() -> {
            try {
                channel.send(destination, namespace, payload);
            } catch (IOException e) {
                fail("Lost connection to " + device.name);
            }
        });
    }

    private JsonObject request(String type) {
        JsonObject o = CastChannel.type(type);
        o.put("requestId", requests.getAndIncrement());
        return o;
    }

    private void flushLoad() {
        JsonObject load = pendingLoad;
        String target = transportId;
        if (load == null || target == null) return;
        pendingLoad = null;
        load.put("sessionId", sessionId);
        loadRequest = load.getInt("requestId");
        try {
            channel.send(target, CastChannel.NS_MEDIA, load);
        } catch (IOException e) {
            fail("Lost connection to " + device.name);
        }
    }

    private void poll() {
        if (state == State.CLOSED || !channel.isOpen()) return;
        try {
            channel.send(CastChannel.RECEIVER, CastChannel.NS_RECEIVER, request("GET_STATUS"));
            if (transportId != null) channel.send(transportId, CastChannel.NS_MEDIA, request("GET_STATUS"));
        } catch (IOException e) {
            fail("Lost connection to " + device.name);
        }
    }

    @Override
    public void onMessage(String namespace, String source, JsonObject payload) {
        String type = payload.getString("type", "");
        if (CastChannel.NS_RECEIVER.equals(namespace)) {
            if ("RECEIVER_STATUS".equals(type)) receiverStatus(payload.getObject("status"));
            else if ("LAUNCH_ERROR".equals(type)) fail("The TV refused to open the player (" + payload.getString("reason", "error") + ")");
            return;
        }
        if (!CastChannel.NS_MEDIA.equals(namespace)) return;
        switch (type) {
            case "MEDIA_STATUS":
                mediaStatus(payload.getArray("status"));
                break;
            case "LOAD_FAILED":
            case "LOAD_CANCELLED":
            case "INVALID_REQUEST":
            case "ERROR":
                if (payload.getInt("requestId", -2) == loadRequest || "LOAD_FAILED".equals(type)) {
                    loadRequest = -1;
                    error = "The TV could not play this video (" + type.toLowerCase().replace('_', ' ')
                            + reason(payload) + ")";
                    changed();
                }
                break;
            default:
                break;
        }
    }

    private static String reason(JsonObject payload) {
        String r = payload.getString("reason", payload.getString("detailedErrorCode", null));
        if (r == null && payload.get("detailedErrorCode") != null) r = String.valueOf(payload.get("detailedErrorCode"));
        return r == null ? "" : ": " + r;
    }

    private void receiverStatus(JsonObject status) {
        JsonObject v = status.getObject("volume", null);
        if (v != null) {
            volume = v.getDouble("level", volume);
            muted = v.getBoolean("muted", muted);
        }
        JsonArray apps = status.getArray("applications");
        JsonObject ours = null;
        for (int i = 0; i < apps.size(); i++) {
            JsonObject app = apps.getObject(i);
            if (MEDIA_RECEIVER.equals(app.getString("appId", ""))) ours = app;
        }
        if (ours == null) {
            if (transportId != null) {
                transportId = null;
                fail(null);
            }
            changed();
            return;
        }
        String tid = ours.getString("transportId", null);
        if (tid != null && !tid.equals(transportId)) {
            transportId = tid;
            sessionId = ours.getString("sessionId", null);
            try {
                channel.send(tid, CastChannel.NS_CONNECTION, CastChannel.type("CONNECT"));
                channel.send(tid, CastChannel.NS_MEDIA, request("GET_STATUS"));
            } catch (IOException e) {
                fail("Lost connection to " + device.name);
                return;
            }
            state = State.CONNECTED;
            sender.execute(this::flushLoad);
        }
        changed();
    }

    private void mediaStatus(JsonArray list) {
        if (list.isEmpty()) {
            if (loadRequest < 0 && pendingLoad == null) {
                mediaSessionId = -1;
                playerState = "IDLE";
            }
            changed();
            return;
        }
        JsonObject s = list.getObject(0);
        mediaSessionId = s.getLong("mediaSessionId", mediaSessionId);
        String newState = s.getString("playerState", playerState);
        if (loadRequest >= 0 && !"IDLE".equals(newState)) loadRequest = -1;
        if (!"IDLE".equals(newState)) everLoaded = true;
        playerState = newState;
        idleReason = s.getString("idleReason", null);
        if ("IDLE".equals(newState) && "ERROR".equals(idleReason) && error == null) {
            error = "The TV stopped with a playback error";
            loadRequest = -1;
        }
        if (s.get("currentTime") != null) {
            position = s.getDouble("currentTime", position);
            positionAt = System.currentTimeMillis();
        }
        rate = s.getDouble("playbackRate", rate);
        JsonObject media = s.getObject("media", null);
        if (media != null && media.getDouble("duration", 0) > 0) duration = media.getDouble("duration", 0);
        JsonArray tracks = s.getArray("activeTrackIds", null);
        if (tracks != null) {
            long[] ids = new long[tracks.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = tracks.getLong(i);
            activeTracks = ids;
        }
        JsonObject v = s.getObject("volume", null);
        if (v != null) {
            volume = v.getDouble("level", volume);
            muted = v.getBoolean("muted", muted);
        }
        changed();
    }

    public boolean finished() {
        return everLoaded && "IDLE".equals(playerState) && "FINISHED".equals(idleReason);
    }

    @Override
    public void onClosed(Exception reason) {
        if (state == State.CLOSED) return;
        fail("Lost connection to " + device.name);
    }

    private void fail(String message) {
        if (message != null) error = message;
        state = State.CLOSED;
        shutdown();
        changed();
    }

    private void shutdown() {
        poller.shutdownNow();
        channel.close();
        sender.shutdown();
    }

    private void changed() {
        listener.onCastChanged(this);
    }
}
