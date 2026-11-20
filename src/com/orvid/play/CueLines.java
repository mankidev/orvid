package com.orvid.play;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CueLines {

    private List<String> previous = Collections.emptyList();

    public static List<String> split(List<CharSequence> texts) {
        List<String> out = new ArrayList<>();
        for (CharSequence text : texts) {
            if (text == null) continue;
            for (String line : text.toString().split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    public List<String> next(List<String> lines) {
        if (lines.isEmpty()) {
            previous = Collections.emptyList();
            return lines;
        }
        List<String> fresh = new ArrayList<>();
        for (String line : lines) if (!previous.contains(line)) fresh.add(line);
        if (fresh.isEmpty()) return null;
        previous = lines;
        return fresh;
    }

    public void reset() {
        previous = Collections.emptyList();
    }
}
