package com.orvid.ext;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.orvid.adult.HtmlTube;
import com.orvid.net.Fetch;
import com.orvid.net.Hedge;
import com.orvid.play.Direct;
import com.orvid.search.Filters;
import com.orvid.search.Source;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ExtSource extends Source {

    public void warm() {
        if (script != null) script.warm();
    }

    public static final class Gone extends com.orvid.net.Hedge.Final {
        public Gone(String message) {
            super(message);
        }
    }

    public final String repo;
    public final int version;
    public final boolean webPlayback;
    private final Pattern match;
    private final HtmlTube html;
    private final Script script;
    private final int hedge;
    private volatile String lucky;

    private static final long LUCKY_HEAD_START_MS = 4000;

    ExtSource(String repo, String key, String name, int serviceId, double weight, boolean adult, int version,
              Pattern match, boolean webPlayback, HtmlTube html, Script script, int hedge) {
        super(key, name, 0, serviceId, weight, !adult, adult);
        this.repo = repo;
        this.version = version;
        this.match = match;
        this.webPlayback = webPlayback;
        this.html = html;
        this.script = script;
        this.hedge = hedge;
    }

    private <T> T spread(String circuit, Hedge.Task<T> task, String[] won) throws Exception {
        won[0] = circuit;
        if (hedge < 2 || !Fetch.torMode()) return task.run(circuit);
        String known = lucky;
        List<String> lanes = new ArrayList<>();
        lanes.add(known != null ? known : circuit);
        while (lanes.size() < hedge) lanes.add(Fetch.newCircuit(key));
        try {
            Hedge.Result<T> r = Hedge.race(lanes, known != null ? LUCKY_HEAD_START_MS : 0, false, task);
            lucky = r.circuit;
            won[0] = r.circuit;
            return r.value;
        } catch (Exception e) {
            lucky = null;
            throw e;
        }
    }

    int minQuery;
    boolean browse;
    public boolean portal;
    public boolean torless;

    @Override
    public boolean browses() {
        return browse;
    }

    @Override
    public boolean wanted(com.orvid.search.Filters f, com.orvid.search.Query q) {
        String typed = q.plainQuery == null ? "" : q.plainQuery.replace(" ", "");
        if (minQuery > 0 && !typed.isEmpty() && typed.length() < minQuery) return false;
        if (torless && Fetch.torMode()) return false;
        return super.wanted(f, q);
    }

    public String host() {
        if (html != null) return html.host();
        return com.orvid.net.Prewarm.host(key);
    }

    public boolean hedged() {
        return hedge > 1;
    }

    @Override
    public boolean slow() {
        return hedged();
    }

    private static boolean playable(String url) {
        if (!url.startsWith("http")) return false;
        if (com.orvid.yt.Visionos.videoId(url) != null || Registry.forUrl(url) != null) return true;
        return com.orvid.play.Resolve.preparable(url);
    }

    public boolean handles(String url) {
        return !portal && url != null && match.matcher(url).find();
    }

    @Override
    public boolean usesWebView() {
        return webPlayback;
    }

    @Override
    public Page search(String circuit, String query, Filters f, String next) throws Exception {
        if (html != null) {
            HtmlTube.Page p = spread(circuit, c -> html.search(c, query, next), new String[1]);
            return new Page(p.items, p.next);
        }
        JsonObject out = spread(circuit, c -> script.call(c, "search", query, next), new String[1]);
        if (out == null) throw new IOException(key + ": no search");
        Page p = new Page();
        JsonArray items = Script.array(out, "items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject o = items.getObject(i);
            String url = o.getString("url", null);
            if (url == null || !(portal ? playable(url) : handles(url))) continue;
            p.items.add(item(o, url));
        }
        p.next = out.getString("next", null);
        return p;
    }

    private StreamInfoItem item(JsonObject o, String url) {
        StreamInfoItem item = new StreamInfoItem(serviceId, url, o.getString("title", ""), StreamType.VIDEO_STREAM);
        item.setUploaderName(o.getString("author", ""));
        item.setDuration(Math.round(o.getDouble("duration", -1)));
        item.setViewCount(Math.round(o.getDouble("views", -1)));
        String thumb = o.getString("thumb", null);
        List<Image> thumbs = new ArrayList<>();
        if (thumb != null && thumb.startsWith("http")) thumbs.add(new Image(thumb, 180, 320, Image.ResolutionLevel.MEDIUM));
        item.setThumbnails(thumbs);
        List<Image> avatars = new ArrayList<>();
        String avatar = o.getString("avatar", null);
        if (avatar != null && avatar.startsWith("http")) avatars.add(new Image(avatar, 68, 68, Image.ResolutionLevel.LOW));
        item.setUploaderAvatars(avatars);
        if ("movie".equals(o.getString("kind", null))) com.orvid.search.Movies.mark(url);
        Object date = o.get("date");
        if (date instanceof Number) com.orvid.search.Dates.set(item, ((Number) date).longValue());
        else if (date instanceof String) com.orvid.search.Dates.set(item, (String) date);
        return item;
    }

    public Direct resolve(String circuit, String url) throws IOException {
        if (html != null) {
            String[] won = new String[1];
            HtmlTube.Media m;
            try {
                m = spread(circuit, c -> html.resolve(c, url), won);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
            Direct d = new Direct(m.title, "", m.mp4, m.mp4.contains(".m3u8"), m.referer);
            if (m.qualities.size() > 1) {
                for (java.util.Map.Entry<Integer, String> q : m.qualities.entrySet()) d.variant(q.getKey(), q.getValue());
            }
            return won[0].equals(circuit) ? d : d.via(won[0]);
        }
        String[] won = new String[1];
        JsonObject out;
        try {
            out = spread(circuit, c -> script.call(c, "resolve", url, null), won);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (out == null) throw new IOException(key + ": no resolve");
        String media = out.getString("url", null);
        if (media == null) throw new IOException("video unavailable");
        Direct d = new Direct(out.getString("title", ""), out.getString("author", ""), media,
                out.getBoolean("hls", media.contains(".m3u8")), out.getString("referer", null));
        d.cookie = out.getString("cookie", null);
        d.dash = !d.hls && out.getBoolean("dash", media.contains(".mpd"));
        JsonObject fb = out.getObject("fallback");
        if (fb != null && fb.getString("url", null) != null) {
            d.fallback = fb.getString("url");
            d.fallbackHls = fb.getBoolean("hls", d.fallback.contains(".m3u8"));
        }
        JsonArray qualities = out.getArray("qualities");
        if (qualities != null && qualities.size() > 1) {
            for (int i = 0; i < qualities.size(); i++) {
                JsonObject q = qualities.getObject(i);
                if (q != null) d.variant(q.getInt("height", 0), q.getString("url", null));
            }
        }
        JsonArray subs = out.getArray("subtitles");
        if (subs != null) {
            for (int i = 0; i < subs.size(); i++) {
                JsonObject t = subs.getObject(i);
                String u = t == null ? null : t.getString("url", null);
                if (u != null) d.subtitles.add(new com.orvid.play.Direct.Subtitle(u, t.getString("lang", null), t.getString("label", null)));
            }
        }
        JsonObject sp = out.getObject("sprite");
        if (sp != null && sp.getString("url", null) != null) {
            d.sprite(new com.orvid.play.Storyboard.Spec(sp.getString("url"), sp.getInt("width", 160), sp.getInt("height", 90),
                    sp.getInt("cols", 10), sp.getInt("rows", 10), sp.getInt("count", 100)));
        }
        return won[0].equals(circuit) ? d : d.via(won[0]);
    }

    public List<String> tags(String circuit, String url) throws IOException {
        List<String> out = new ArrayList<>();
        if (script == null) return out;
        JsonObject o = script.call(circuit, "tags", url, null);
        if (o == null) return out;
        JsonArray tags = Script.array(o, "tags");
        for (int i = 0; i < tags.size(); i++) {
            String t = tags.getString(i, "");
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }
}
