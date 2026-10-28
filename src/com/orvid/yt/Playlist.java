package com.orvid.yt;

import com.grack.nanojson.JsonObject;

import java.io.IOException;

public final class Playlist {

    private static final String BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";

    private Playlist() {
    }

    public static Search.Page first(String circuit, String playlistId) throws IOException {
        JsonObject body = new JsonObject();
        body.put("browseId", "VL" + playlistId);
        return page(Related.post(circuit, BROWSE_URL, body, "playlist"));
    }

    public static Search.Page more(String circuit, String token) throws IOException {
        JsonObject body = new JsonObject();
        body.put("continuation", token);
        return page(Related.post(circuit, BROWSE_URL, body, "playlist more"));
    }

    public static String uploads(String channelId) {
        return channelId != null && channelId.startsWith("UC") ? "UU" + channelId.substring(2) : null;
    }

    private static Search.Page page(String text) throws IOException {
        Object root = Related.parse(text);
        Search.Page p = new Search.Page();
        Related.collect(root, p.items);
        p.continuation = Search.token(root);
        return p;
    }
}
