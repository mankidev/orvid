package com.orvid.play;

import android.content.Context;

import com.orvid.R;
import com.orvid.yt.Visionos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Subtitles {

    private Subtitles() {
    }

    public static List<Visionos.Caption> withTranslation(Context context, List<Visionos.Caption> captions) {
        List<Visionos.Caption> all = new ArrayList<>(captions);
        String mine = Locale.getDefault().getLanguage();
        Visionos.Caption base = null;
        for (Visionos.Caption c : captions) {
            if (c.language.equals(mine) || c.language.startsWith(mine + "-")) return all;
            if (base == null || base.auto && !c.auto) base = c;
        }
        if (base != null) {
            Visionos.Caption t = new Visionos.Caption();
            t.language = mine;
            t.name = translatedName(context);
            t.url = com.orvid.net.Fetch.torMode() ? SubTranslate.uri(base.url, mine) : base.url + "&tlang=" + mine;
            all.add(t);
        }
        return all;
    }

    public static void addTranslation(Context context, Direct d) {
        if (d.subtitles.isEmpty()) return;
        String mine = Locale.getDefault().getLanguage();
        Direct.Subtitle base = null;
        for (Direct.Subtitle s : d.subtitles) {
            String l = s.language == null ? "" : s.language;
            if (l.equals(mine) || l.startsWith(mine + "-") || SubTranslate.handles(s.url)) return;
            if (base == null || "en".equals(l) && !"en".equals(base.language)) base = s;
        }
        d.subtitles.add(new Direct.Subtitle(SubTranslate.uri(base.url, mine), mine, translatedName(context)));
    }

    private static String translatedName(Context context) {
        return context.getString(R.string.subtitle_translated, Locale.getDefault().getDisplayLanguage(Locale.getDefault()));
    }
}
