package com.orvid.ui;

import com.orvid.R;
import com.orvid.ia.Archive;
import com.orvid.more.Site;
import com.orvid.more.Sites;
import com.orvid.pt.PeerTube;
import com.orvid.search.Filters;
import com.orvid.search.Source;
import com.orvid.tw.Twitch;
import com.orvid.web.WebSites;

import java.util.Arrays;
import java.util.List;

public final class Sources {

    private Sources() {
    }

    public static List<Source> current() {
        List<Source> out = new java.util.ArrayList<>();
        for (Source s : BUILTIN) if (com.orvid.ext.Registry.engineOn(s.key)) out.add(s);
        out.addAll(com.orvid.ext.Registry.sources());
        java.util.Collections.sort(out, (a, b) -> a.adult != b.adult ? Boolean.compare(a.adult, b.adult)
                : Integer.compare(com.orvid.ext.Registry.rank(a.key), com.orvid.ext.Registry.rank(b.key)));
        return out;
    }

    private static final List<Source> BUILTIN = Arrays.asList(
            new Source("twitch", "Twitch", R.drawable.ic_src_twitch, Twitch.SERVICE_ID, 0.8, true, false) {
                @Override
                public Page search(String circuit, String query, Filters f, String next) throws Exception {
                    Twitch.Page p = Twitch.search(circuit, query, next);
                    return new Page(p.items, p.next);
                }
            },
            new Source("peertube", "PeerTube", R.drawable.ic_src_peertube, PeerTube.SERVICE_ID, 0.7, true, false) {
                @Override
                public Page search(String circuit, String query, Filters f, String next) throws Exception {
                    PeerTube.Page p = PeerTube.search(circuit, query, next, f.hideAdult);
                    return new Page(p.items, p.next);
                }
            },
            new Source("archive", "Internet Archive", R.drawable.ic_src_archive, Archive.SERVICE_ID, 0.7, true, false) {
                @Override
                public Page search(String circuit, String query, Filters f, String next) throws Exception {
                    Archive.Page p = Archive.search(circuit, query, next);
                    return new Page(p.items, p.next);
                }
            },
            site("odysee", R.drawable.ic_src_odysee, Sites.ODYSEE, 0.6),
            site("rutube", R.drawable.ic_src_rutube, Sites.RUTUBE, 0.6).script(com.orvid.search.Query.CYRILLIC),
            site("bitchute", R.drawable.ic_src_bitchute, Sites.BITCHUTE, 0.5),
            site("aparat", R.drawable.ic_src_aparat, Sites.APARAT, 0.5),
            site("mediathek", R.drawable.ic_src_mediathek, Sites.MEDIATHEK, 0.6),
            site("ccc", R.drawable.ic_src_ccc, Sites.CCC, 0.6),
            site("commons", R.drawable.ic_src_commons, Sites.COMMONS, 0.5),
            site("nasa", R.drawable.ic_src_nasa, Sites.NASA, 0.5),
            site("ninegag", R.drawable.ic_src_ninegag, Sites.NINEGAG, 0.5),
            site("coub", R.drawable.ic_src_coub, Sites.COUB, 0.5),
            site("steam", R.drawable.ic_src_steam, Sites.STEAM, 0.5),
            site("ted", R.drawable.ic_src_ted, Sites.TED, 0.6),
            site("imgur", R.drawable.ic_src_imgur, Sites.IMGUR, 0.5),
            site("mastodon", R.drawable.ic_src_mastodon, Sites.MASTODON, 0.4),
            site("mixkit", R.drawable.ic_src_mixkit, Sites.MIXKIT, 0.4),
            site("chzzk", R.drawable.ic_src_chzzk, Sites.CHZZK, 0.5).script(com.orvid.search.Query.HANGUL),
            site("europeana", R.drawable.ic_src_europeana, Sites.EUROPEANA, 0.5),
            site("ok", R.drawable.ic_src_ok, Sites.OK, 0.5).script(com.orvid.search.Query.CYRILLIC),
            site("soop", R.drawable.ic_src_soop, Sites.SOOP, 0.5).script(com.orvid.search.Query.HANGUL),
            web("spankbang", "SpankBang (18+)", R.drawable.ic_src_spankbang, WebSites.SPANKBANG),
            web("fourkporn", "4kporn (18+)", R.drawable.ic_src_fourkporn, WebSites.FOURKPORN),
            web("jable", "Jable (18+)", R.drawable.ic_src_jable, WebSites.JABLE),
            web("missav", "MissAV (18+)", R.drawable.ic_src_missav, WebSites.MISSAV));

    private static Source site(String key, int icon, Site site, double weight) {
        return new Source(key, site.name, icon, site.serviceId, weight, true, false) {
            @Override
            public Page search(String circuit, String query, Filters f, String next) throws Exception {
                return site.search(circuit, query, next);
            }
        };
    }

    private static Source web(String key, String name, int icon, WebSites.Site site) {
        return new Source(key, name, icon, site.serviceId, 0.6, false, true) {
            @Override
            public Page search(String circuit, String query, Filters f, String next) throws Exception {
                int page = next == null ? 1 : Integer.parseInt(next);
                java.util.List<org.schabi.newpipe.extractor.stream.StreamInfoItem> items =
                        WebSites.search(com.orvid.app.OrvidApp.get(), site, query, page);
                return new Page(items, !items.isEmpty() && site.pages() ? Integer.toString(page + 1) : null);
            }
        };
    }

    static Source forUrl(String url) {
        if (url == null) return null;
        Source ext = com.orvid.ext.Registry.forUrl(url);
        if (ext != null) return ext;
        if (com.orvid.tw.Twitch.vodId(url) != null || com.orvid.tw.Twitch.clipSlug(url) != null) return byService(Twitch.SERVICE_ID);
        if (PeerTube.handles(url)) return byService(PeerTube.SERVICE_ID);
        if (Archive.itemId(url) != null) return byService(Archive.SERVICE_ID);
        Site site = Sites.forUrl(url);
        if (site != null) return byService(site.serviceId);
        return null;
    }

    static Source byService(int serviceId) {
        for (Source s : current()) if (s.serviceId == serviceId) return s;
        return null;
    }
}
