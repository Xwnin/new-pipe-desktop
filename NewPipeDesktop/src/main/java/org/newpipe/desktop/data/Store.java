package org.newpipe.desktop.data;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local library: watch history, subscriptions, bookmarked playlists, search history, resume positions. */
public final class Store {
    public static final class Playlist {
        public String name;
        public final List<Item> items = new ArrayList<>();

        public Playlist(String name) {
            this.name = name;
        }
    }

    private static final File FILE = new File(Settings.DIR, "library.json");

    private static final List<Item> history = new ArrayList<>();
    private static final List<Item> subscriptions = new ArrayList<>();
    private static final List<Playlist> playlists = new ArrayList<>();
    private static final List<Item> remotePlaylists = new ArrayList<>();
    private static final List<String> searches = new ArrayList<>();
    private static final Map<String, Double> positions = new LinkedHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    static {
        load();
    }

    private Store() {}

    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    private static void fire() {
        save();
        for (Runnable r : listeners) r.run();
    }

    // ---- history -------------------------------------------------------------------------

    public static synchronized void addHistory(Item it) {
        if (!Settings.watchHistory() || it.kind != Item.Kind.STREAM) return;
        history.remove(it);
        history.add(0, it);
        while (history.size() > 1000) history.remove(history.size() - 1);
        fire();
    }

    public static synchronized List<Item> history() {
        return new ArrayList<>(history);
    }

    public static synchronized void clearHistory() {
        history.clear();
        positions.clear();
        fire();
    }

    public static synchronized void removeHistory(Item it) {
        history.remove(it);
        fire();
    }

    // ---- resume positions ------------------------------------------------------------------

    public static synchronized void savePosition(String url, double pos) {
        if (pos < 5) positions.remove(url);
        else positions.put(url, pos);
        while (positions.size() > 500) positions.remove(positions.keySet().iterator().next());
        save();
    }

    public static synchronized double position(String url) {
        return positions.getOrDefault(url, 0.0);
    }

    // ---- search history --------------------------------------------------------------------

    public static synchronized void addSearch(String q) {
        if (!Settings.searchHistory() || q.isBlank()) return;
        searches.remove(q);
        searches.add(0, q);
        while (searches.size() > 60) searches.remove(searches.size() - 1);
        save();
    }

    public static synchronized List<String> searches() {
        return new ArrayList<>(searches);
    }

    public static synchronized void removeSearch(String q) {
        searches.remove(q);
        save();
    }

    public static synchronized void clearSearches() {
        searches.clear();
        save();
    }

    // ---- subscriptions ---------------------------------------------------------------------

    public static synchronized boolean isSubscribed(String url) {
        for (Item c : subscriptions) if (c.url.equals(url)) return true;
        return false;
    }

    public static synchronized void subscribe(Item channel) {
        if (isSubscribed(channel.url)) return;
        subscriptions.add(channel);
        fire();
    }

    public static synchronized void unsubscribe(String url) {
        subscriptions.removeIf(c -> c.url.equals(url));
        fire();
    }

    public static synchronized List<Item> subscriptions() {
        return new ArrayList<>(subscriptions);
    }

    // ---- local playlists -------------------------------------------------------------------

    public static synchronized List<Playlist> playlists() {
        return new ArrayList<>(playlists);
    }

    public static synchronized Playlist createPlaylist(String name) {
        Playlist p = new Playlist(name);
        playlists.add(p);
        fire();
        return p;
    }

    public static synchronized void deletePlaylist(Playlist p) {
        playlists.remove(p);
        fire();
    }

    public static synchronized void renamePlaylist(Playlist p, String name) {
        p.name = name;
        fire();
    }

    public static synchronized void addToPlaylist(Playlist p, Item it) {
        if (!p.items.contains(it)) p.items.add(it);
        fire();
    }

    public static synchronized void removeFromPlaylist(Playlist p, Item it) {
        p.items.remove(it);
        fire();
    }

    public static synchronized List<Item> remotePlaylists() {
        return new ArrayList<>(remotePlaylists);
    }

    public static synchronized boolean isRemoteBookmarked(String url) {
        return remotePlaylists.stream().anyMatch(i -> i.url.equals(url));
    }

    public static synchronized void toggleRemoteBookmark(Item it) {
        if (!remotePlaylists.removeIf(i -> i.url.equals(it.url))) remotePlaylists.add(it);
        fire();
    }

    // ---- persistence -----------------------------------------------------------------------

    private static void load() {
        try {
            if (!FILE.isFile()) return;
            JsonObject o = JsonParser.object().from(Files.readString(FILE.toPath(), StandardCharsets.UTF_8));
            readItems(o.getArray("history"), history);
            readItems(o.getArray("subscriptions"), subscriptions);
            readItems(o.getArray("remotePlaylists"), remotePlaylists);
            for (Object s : o.getArray("searches")) searches.add(String.valueOf(s));
            JsonObject pos = o.getObject("positions");
            for (String k : pos.keySet()) positions.put(k, pos.getDouble(k, 0));
            for (Object po : o.getArray("playlists")) {
                JsonObject pj = (JsonObject) po;
                Playlist p = new Playlist(pj.getString("name", "Playlist"));
                readItems(pj.getArray("items"), p.items);
                playlists.add(p);
            }
        } catch (Exception e) {
            System.err.println("library: " + e);
        }
    }

    private static void readItems(JsonArray arr, List<Item> into) {
        for (Object o : arr) if (o instanceof JsonObject jo) into.add(Item.fromJson(jo));
    }

    private static JsonArray items(List<Item> list) {
        JsonArray a = new JsonArray();
        for (Item i : list) a.add(i.toJson());
        return a;
    }

    private static synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.put("history", items(history));
            o.put("subscriptions", items(subscriptions));
            o.put("remotePlaylists", items(remotePlaylists));
            o.put("searches", new JsonArray(searches));
            JsonObject pos = new JsonObject();
            pos.putAll(positions);
            o.put("positions", pos);
            JsonArray pls = new JsonArray();
            for (Playlist p : playlists) {
                JsonObject pj = new JsonObject();
                pj.put("name", p.name);
                pj.put("items", items(p.items));
                pls.add(pj);
            }
            o.put("playlists", pls);
            Files.writeString(FILE.toPath(), JsonWriter.string(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("library save: " + e);
        }
    }
}
