package com.orvid.yt;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Search {

    private static final String SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";

    private Search() {
    }

    public static final class Page {
        public final List<StreamInfoItem> items = new ArrayList<>();
        public String continuation;
        public String corrected;
        public String artistId;
        public String artistName;
        public final List<String> albums = new ArrayList<>();
        public final List<String> playlists = new ArrayList<>();
        public final List<String> playlistTitles = new ArrayList<>();
    }

    private static final String FIRST_MASK = "contents.sectionListRenderer.contents(continuationItemRenderer,"
            + "universalWatchCardRenderer.header.watchCardRichHeaderRenderer(title,titleNavigationEndpoint),"
            + "itemSectionRenderer.contents(showingResultsForRenderer,compactChannelRenderer(channelId,displayName),"
            + "compactPlaylistRenderer(playlistId,title),"
            + "horizontalCardListRenderer.cards.searchRefinementCardRenderer(query,searchEndpoint),"
            + "gridShelfViewModel.contents.shortsLockupViewModel(entityId,overlayMetadata,accessibilityText),"
            + Related.VIDEO_FIELDS + "))";
    private static final String MORE_MASK = "onResponseReceivedCommands.appendContinuationItemsAction.continuationItems("
            + "continuationItemRenderer,itemSectionRenderer.contents(" + Related.VIDEO_FIELDS + "))";

    public static Page first(String circuit, String query, String params) throws IOException {
        JsonObject body = new JsonObject();
        body.put("query", query);
        if (params != null) body.put("params", params);
        return Related.masked(circuit, SEARCH_URL, body, "search", FIRST_MASK, Search::page);
    }

    public static Page more(String circuit, String continuation) throws IOException {
        JsonObject body = new JsonObject();
        body.put("continuation", continuation);
        return Related.masked(circuit, SEARCH_URL, body, "search more", MORE_MASK, Search::page);
    }

    private static Page page(String text) throws IOException {
        Object root = Related.parse(text);
        Page p = new Page();
        Related.collect(root, p.items);
        p.continuation = token(root);
        p.corrected = corrected(root);
        music(root, p);
        return p;
    }

    private static void music(Object node, Page p) {
        if (node instanceof JsonObject) {
            JsonObject o = (JsonObject) node;
            JsonObject card = o.getObject("watchCardRichHeaderRenderer", null);
            if (card != null && p.artistId == null) {
                String id = card.getObject("titleNavigationEndpoint").getObject("browseEndpoint").getString("browseId", null);
                if (id != null && id.startsWith("UC")) {
                    p.artistId = id;
                    p.artistName = runs(card.getObject("title"));
                }
            }
            JsonObject channel = o.getObject("compactChannelRenderer", null);
            if (channel != null && p.artistId == null) {
                String name = runs(channel.getObject("displayName"));
                if (name.endsWith(" - Topic")) {
                    p.artistId = channel.getString("channelId", null);
                    p.artistName = name.substring(0, name.length() - " - Topic".length());
                }
            }
            JsonObject refine = o.getObject("searchRefinementCardRenderer", null);
            if (refine != null) {
                String id = refine.getObject("searchEndpoint").getObject("watchPlaylistEndpoint").getString("playlistId", null);
                if (id != null && !p.albums.contains(id)) p.albums.add(id);
                return;
            }
            JsonObject playlist = o.getObject("compactPlaylistRenderer", null);
            if (playlist != null) {
                String id = playlist.getString("playlistId", null);
                if (id != null && !p.playlists.contains(id)) {
                    p.playlists.add(id);
                    p.playlistTitles.add(runs(playlist.getObject("title")));
                }
                return;
            }
            for (Object child : o.values()) music(child, p);
        } else if (node instanceof JsonArray) {
            for (Object child : (JsonArray) node) music(child, p);
        }
    }

    private static String runs(JsonObject text) {
        String simple = text.getString("simpleText", null);
        if (simple != null) return simple.trim();
        StringBuilder sb = new StringBuilder();
        JsonArray runs = text.getArray("runs");
        for (int i = 0; i < runs.size(); i++) sb.append(runs.getObject(i).getString("text", ""));
        return sb.toString().trim();
    }

    private static String corrected(Object node) {
        if (node instanceof JsonObject) {
            JsonObject o = (JsonObject) node;
            JsonObject r = o.getObject("showingResultsForRenderer", null);
            if (r != null) {
                StringBuilder sb = new StringBuilder();
                JsonArray runs = r.getObject("correctedQuery").getArray("runs");
                for (int i = 0; i < runs.size(); i++) sb.append(runs.getObject(i).getString("text", ""));
                String q = sb.toString().trim();
                return q.isEmpty() ? null : q;
            }
            for (Object child : o.values()) {
                String t = corrected(child);
                if (t != null) return t;
            }
        } else if (node instanceof JsonArray) {
            for (Object child : (JsonArray) node) {
                String t = corrected(child);
                if (t != null) return t;
            }
        }
        return null;
    }

    static String token(Object node) {
        if (node instanceof JsonObject) {
            JsonObject o = (JsonObject) node;
            JsonObject item = o.getObject("continuationItemRenderer", null);
            if (item != null) {
                String t = item.getObject("continuationEndpoint").getObject("continuationCommand")
                        .getString("token", null);
                if (t != null) return t;
            }
            JsonObject view = o.getObject("continuationItemViewModel", null);
            if (view != null) {
                String t = view.getObject("continuationCommand").getObject("innertubeCommand")
                        .getObject("continuationCommand").getString("token", null);
                if (t != null) return t;
            }
            for (Object child : o.values()) {
                String t = token(child);
                if (t != null) return t;
            }
        } else if (node instanceof JsonArray) {
            for (Object child : (JsonArray) node) {
                String t = token(child);
                if (t != null) return t;
            }
        }
        return null;
    }
}
