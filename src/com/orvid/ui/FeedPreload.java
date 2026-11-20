package com.orvid.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;

import com.orvid.net.Diag;
import com.orvid.net.Fetch;
import com.orvid.yt.Visionos;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class FeedPreload {

    private static final long WAIT_S = 30;
    private static final long TOR_HOLD_MS = 4000;
    private static final long DIRECT_HOLD_MS = 10000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orvid-feed-preload");
        t.setDaemon(true);
        return t;
    });
    private static String url;
    private static ExoPlayer player;
    private static HeldLoad load;
    private static int token;

    private FeedPreload() {
    }

    static boolean start(Context app, StreamInfoItem item) {
        String u = item.getUrl();
        if (u == null) return false;
        if (u.equals(url)) return true;
        String id = Visionos.videoId(u);
        Future<?> f = id != null ? Visionos.prepare(id) : com.orvid.play.Resolve.prepare(u);
        if (f == null) return false;
        dropPreload();
        url = u;
        int mine = token;
        WORK.execute(() -> {
            List<MediaSource> sources;
            try {
                if (id != null) {
                    Visionos.Prepared p = (Visionos.Prepared) f.get(WAIT_S, TimeUnit.SECONDS);
                    if (p.video.live) return;
                    sources = PlayerActivity.youtubeSources(app, p.video, p.circuit, u);
                } else {
                    com.orvid.play.Resolve.Stream s = (com.orvid.play.Resolve.Stream) f.get(WAIT_S, TimeUnit.SECONDS);
                    if (s == null || s.direct == null || s.direct.url == null) return;
                    com.orvid.ext.ExtSource ext = com.orvid.ext.Registry.forUrl(u);
                    sources = PlayerActivity.directSources(s.direct, s.circuit, ext != null && ext.hedged(), u);
                }
            } catch (Exception e) {
                Diag.mark("feed preload FAIL " + e.getClass().getSimpleName());
                return;
            }
            if (sources.isEmpty()) return;
            MediaSource first = sources.get(0);
            MAIN.post(() -> {
                if (mine != token || !u.equals(url)) return;
                load = new HeldLoad(Fetch.torMode() ? TOR_HOLD_MS : DIRECT_HOLD_MS);
                player = PlayerActivity.newPlayer(app, load);
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setMaxVideoSize(PRELOAD_MAX_W, PRELOAD_MAX_H).build());
                player.setMediaSource(first);
                player.setPlayWhenReady(false);
                player.prepare();
                Diag.mark("feed preload started");
            });
        });
        return true;
    }

    private static final int PARK_LIMIT = 1;
    private static final int PRELOAD_MAX_W = 1280;
    private static final int PRELOAD_MAX_H = 720;
    private static final java.util.LinkedHashMap<String, ExoPlayer> PARKED = new java.util.LinkedHashMap<>();

    private static final int LAST_LIMIT = 6;

    private static final class Last {
        final android.graphics.Bitmap frame;
        final long positionMs;

        Last(android.graphics.Bitmap frame, long positionMs) {
            this.frame = frame;
            this.positionMs = positionMs;
        }
    }

    private static final java.util.LinkedHashMap<String, Last> LAST = new java.util.LinkedHashMap<String, Last>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Last> eldest) {
            return size() > LAST_LIMIT;
        }
    };

    static void remember(String pageUrl, android.graphics.Bitmap frame, long positionMs) {
        if (pageUrl == null) return;
        if (frame == null) LAST.remove(pageUrl);
        else LAST.put(pageUrl, new Last(frame, positionMs));
    }

    static android.graphics.Bitmap lastFrame(String pageUrl) {
        Last l = pageUrl == null ? null : LAST.get(pageUrl);
        return l == null ? null : l.frame;
    }

    static long lastPosition(String pageUrl) {
        Last l = pageUrl == null ? null : LAST.get(pageUrl);
        return l == null ? 0 : l.positionMs;
    }

    static boolean parked(String pageUrl) {
        return pageUrl != null && PARKED.containsKey(pageUrl);
    }

    static void park(String pageUrl, ExoPlayer p, String upcoming) {
        if (pageUrl == null || p == null) return;
        p.setPlayWhenReady(false);
        p.stop();
        ExoPlayer old = PARKED.remove(pageUrl);
        if (old != null && old != p) old.release();
        PARKED.put(pageUrl, p);
        while (PARKED.size() > PARK_LIMIT) {
            String evict = null;
            for (String k : PARKED.keySet()) {
                if (!k.equals(upcoming) && !k.equals(pageUrl)) {
                    evict = k;
                    break;
                }
            }
            if (evict == null) break;
            PARKED.remove(evict).release();
        }
    }

    static ExoPlayer take(String pageUrl) {
        if (pageUrl == null) return null;
        ExoPlayer kept = PARKED.remove(pageUrl);
        if (kept != null) {
            if (kept.getPlayerError() == null) return kept;
            kept.release();
        }
        if (!pageUrl.equals(url)) return null;
        if (player == null) {
            dropPreload();
            return null;
        }
        ExoPlayer p = player;
        HeldLoad l = load;
        player = null;
        load = null;
        url = null;
        token++;
        if (p.getPlayerError() != null) {
            p.release();
            return null;
        }
        l.release();
        return p;
    }

    private static android.graphics.Bitmap frame;
    private static String frameUrl;

    static ExoPlayer peek(String pageUrl) {
        if (pageUrl == null) return null;
        return player != null && pageUrl.equals(url) ? player : null;
    }

    static void handFrame(String pageUrl, android.graphics.Bitmap bitmap) {
        frameUrl = bitmap == null ? null : pageUrl;
        frame = bitmap;
    }

    static android.graphics.Bitmap takeFrame(String pageUrl) {
        android.graphics.Bitmap b = pageUrl != null && pageUrl.equals(frameUrl) ? frame : null;
        frame = null;
        frameUrl = null;
        return b;
    }

    static boolean wake(String pageUrl) {
        ExoPlayer p = pageUrl == null ? null : PARKED.get(pageUrl);
        if (p == null || p.getPlayerError() != null) return false;
        if (p.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE) {
            p.setPlayWhenReady(false);
            p.prepare();
        }
        return true;
    }

    static void freeDecoders() {
        dropPreload();
        for (ExoPlayer p : PARKED.values()) p.stop();
    }

    public static void drop() {
        for (ExoPlayer p : PARKED.values()) p.release();
        PARKED.clear();
        LAST.clear();
        dropPreload();
    }

    private static void dropPreload() {
        token++;
        url = null;
        load = null;
        if (player != null) player.release();
        player = null;
    }
}
