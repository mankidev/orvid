package com.orvid.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Query {

    public static final String SITE_ALL = "";
    public static final String SITE_YOUTUBE = "youtube";
    public static final String SITE_DAILYMOTION = "dailymotion";

    private static final Pattern PHRASE = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "ve", "ile", "bir", "bu", "de", "da", "mi", "mu", "icin", "nasil",
            "the", "a", "an", "of", "and", "to", "in", "for", "on", "with", "how"));
    private static final int STEM = 4;

    public final String raw;
    public final String platformQuery;
    public final List<String> terms = new ArrayList<>();
    public final List<String> phrases = new ArrayList<>();
    public final List<String> excluded = new ArrayList<>();
    public final List<String> required = new ArrayList<>();
    public final List<String> inTitle = new ArrayList<>();
    public final java.util.Map<String, Integer> repeats = new java.util.HashMap<>();
    public String plainQuery;
    public boolean movie;
    public boolean anyOf;
    public int beforeYear;
    public int afterYear;
    public String channel;
    public String site = SITE_ALL;

    private Query(String raw, String platformQuery) {
        this.raw = raw;
        this.platformQuery = platformQuery;
    }

    public boolean siteIs(String key, String name) {
        if (site.isEmpty()) return true;
        if (key.toLowerCase(Locale.ROOT).startsWith(site)) return true;
        return fold(name).replaceAll("[^\\p{L}\\p{N}]+", "").startsWith(site);
    }

    public static final String LATIN = "latin";
    public static final String CYRILLIC = "cyrillic";
    public static final String ARABIC = "arabic";
    public static final String HANGUL = "hangul";
    public static final String KANA = "kana";
    public static final String HAN = "han";

    public String script() {
        return script(platformQuery);
    }

    public static double foreignShare(String text, String script) {
        int letters = 0, foreign = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.LATIN || sc == Character.UnicodeScript.COMMON) continue;
            if (!script(new String(Character.toChars(cp))).equals(script)) foreign++;
        }
        return letters == 0 ? 0 : foreign / (double) letters;
    }

    public static String script(String text) {
        int latin = 0, cyrillic = 0, arabic = 0, hangul = 0, kana = 0, han = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.LATIN) latin++;
            else if (sc == Character.UnicodeScript.CYRILLIC) cyrillic++;
            else if (sc == Character.UnicodeScript.ARABIC) arabic++;
            else if (sc == Character.UnicodeScript.HANGUL) hangul++;
            else if (sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA) kana++;
            else if (sc == Character.UnicodeScript.HAN) han++;
        }
        if (hangul > 0) return HANGUL;
        if (kana > 0) return KANA;
        if (han > 0) return HAN;
        if (cyrillic > latin && cyrillic >= arabic) return CYRILLIC;
        if (arabic > latin) return ARABIC;
        return LATIN;
    }

    public static Query parse(String input) {
        String text = input.trim();
        List<String> phrases = new ArrayList<>();
        Matcher m = PHRASE.matcher(text);
        while (m.find()) phrases.add(m.group(1).trim());
        String rest = PHRASE.matcher(text).replaceAll(" ");

        StringBuilder forPlatform = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        for (String p : phrases) {
            forPlatform.append('"').append(p).append("\" ");
            plain.append(p).append(' ');
        }
        List<String> excluded = new ArrayList<>();
        List<String> required = new ArrayList<>();
        List<String> inTitle = new ArrayList<>();
        String channel = null;
        String site = SITE_ALL;
        boolean anyOf = false;
        int before = 0;
        int after = 0;
        for (String token : rest.split("\\s+")) {
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("kanal:") || lower.startsWith("channel:")) {
                channel = token.substring(token.indexOf(':') + 1);
            } else if (lower.startsWith("site:")) {
                site = lower.substring(5).replaceFirst("^(https?://)?(www\\.|m\\.)?", "")
                        .replaceFirst("\\.[a-z.]+$", "").replaceAll("[^\\p{L}\\p{N}]+", "");
            } else if (lower.matches("(before|after):\\d{4}.*")) {
                int year = Integer.parseInt(lower.substring(lower.indexOf(':') + 1, lower.indexOf(':') + 5));
                if (lower.startsWith("before")) before = year;
                else after = year;
            } else if (lower.startsWith("intitle:") && token.length() > 8) {
                String word = token.substring(8);
                inTitle.add(fold(word));
                forPlatform.append(word).append(' ');
                plain.append(word).append(' ');
            } else if (token.equals("OR") || token.equals("|")) {
                anyOf = true;
                forPlatform.append("OR ");
            } else if (token.startsWith("-") && token.length() > 1) {
                excluded.add(fold(token.substring(1)));
                forPlatform.append(token).append(' ');
            } else if (token.startsWith("+") && token.length() > 1) {
                required.add(fold(token.substring(1)));
                forPlatform.append(token.substring(1)).append(' ');
                plain.append(token.substring(1)).append(' ');
            } else {
                forPlatform.append(token).append(' ');
                plain.append(token).append(' ');
            }
        }
        String platform = forPlatform.toString().trim();
        if (platform.isEmpty() && channel != null) platform = channel;

        Query q = new Query(text, platform);
        q.plainQuery = plain.toString().trim();
        if (q.plainQuery.isEmpty()) q.plainQuery = platform;
        q.channel = channel == null ? null : fold(channel);
        q.site = site;
        q.anyOf = anyOf;
        q.beforeYear = before;
        q.afterYear = after;
        q.required.addAll(required);
        q.inTitle.addAll(inTitle);
        q.excluded.addAll(excluded);
        for (String p : phrases) q.phrases.add(fold(p));
        for (String w : words(fold(platform))) {
            if (anyOf && w.equals("or")) continue;
            if (Movies.WORDS.contains(w)) {
                q.movie = true;
                continue;
            }
            if (STOP.contains(w) || q.excluded.contains(w)) continue;
            if (q.terms.contains(w)) q.repeats.merge(w, 2, (old, one) -> old + 1);
            else q.terms.add(w);
        }
        return q;
    }

    public static String fold(String s) {
        String lower = s.toLowerCase(new Locale("tr"));
        lower = lower.replace('ı', 'i');
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "");
    }

    static List<String> words(String folded) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(folded.replace("'", "").replace("\u2019", "").replace("\u00b4", ""));
        while (m.find()) out.add(m.group());
        return out;
    }

    private static final Set<String> SUFFIXES = new HashSet<>(Arrays.asList(
            "nin", "nun", "in", "un", "de", "da", "te", "ta", "den", "dan", "ten", "tan",
            "ler", "lar", "leri", "lari", "i", "u", "e", "a", "ye", "ya", "yi", "yu", "yla", "yle",
            "la", "le", "s", "es", "ed", "ing"));

    private static final Set<String> VOWEL_ENDINGS = new HashSet<>(Arrays.asList("i", "u", "e", "a"));

    public static boolean wordMatches(String term, String word) {
        if (word.equals(term)) return true;
        if (word.startsWith(term) && (term.length() >= STEM || SUFFIXES.contains(word.substring(term.length())))) {
            return true;
        }
        if (word.length() < STEM || !term.startsWith(word)) return false;
        String ending = term.substring(word.length());
        return SUFFIXES.contains(ending) && !VOWEL_ENDINGS.contains(ending);
    }

    public static boolean acronym(String term, List<String> words) {
        if (term.length() < 3 || term.length() > 8 || words.size() < term.length()) return false;
        StringBuilder initials = new StringBuilder(words.size());
        for (String w : words) initials.append(w.charAt(0));
        return initials.indexOf(term) >= 0;
    }

    static boolean isStop(String w) {
        return STOP.contains(w);
    }

    public boolean isEmpty() {
        return terms.isEmpty() && phrases.isEmpty() && channel == null;
    }
}
