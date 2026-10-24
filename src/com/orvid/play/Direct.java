package com.orvid.play;

public final class Direct {
    public final String title;
    public final String author;
    public final String url;
    public final boolean hls;
    public final String referer;
    public String circuit;
    public String cookie;
    public boolean dash;
    public String fallback;
    public boolean fallbackHls;
    public Storyboard.Spec sprite;
    public final java.util.List<Variant> variants = new java.util.ArrayList<>();
    public final java.util.List<Subtitle> subtitles = new java.util.ArrayList<>();

    public static final class Subtitle {
        public final String url;
        public final String language;
        public final String label;

        public Subtitle(String url, String language, String label) {
            this.url = url;
            this.language = language;
            this.label = label;
        }
    }

    public static final class Variant {
        public final int height;
        public final String url;
        public final boolean hls;

        public Variant(int height, String url, boolean hls) {
            this.height = height;
            this.url = url;
            this.hls = hls;
        }
    }

    public Direct variant(int height, String url) {
        if (height <= 0 || url == null) return this;
        for (Variant v : variants) if (v.height == height || v.url.equals(url)) return this;
        variants.add(new Variant(height, url, url.contains(".m3u8")));
        java.util.Collections.sort(variants, (a, b) -> b.height - a.height);
        return this;
    }

    public Direct(String title, String author, String url, boolean hls, String referer) {
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.url = url;
        this.hls = hls;
        this.referer = referer;
    }

    public Direct sprite(Storyboard.Spec spec) {
        this.sprite = spec;
        return this;
    }

    public Direct via(String circuit) {
        this.circuit = circuit;
        return this;
    }
}
