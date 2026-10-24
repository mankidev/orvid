package com.orvid.cast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CastItem {

    public static final class Track {
        public final String url;
        public final String language;
        public final String name;

        public Track(String url, String language, String name) {
            this.url = url;
            this.language = language == null ? "" : language;
            this.name = name == null || name.isEmpty() ? this.language : name;
        }
    }

    public final String url;
    public final boolean hls;
    public final String circuit;
    public String page;
    public String title = "";
    public String author = "";
    public String thumbnail;
    public boolean live;
    public long durationMs;
    public final Map<String, String> headers = new LinkedHashMap<>();
    public final List<Track> subtitles = new ArrayList<>();

    public CastItem(String url, boolean hls, String circuit) {
        this.url = url;
        this.hls = hls;
        this.circuit = circuit;
    }

    String contentType() {
        if (hls) return "application/x-mpegURL";
        String path = url.split("[?#]")[0].toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        return "video/mp4";
    }
}
