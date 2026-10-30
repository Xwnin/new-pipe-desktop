package org.newpipe.desktop.data;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Subscription import / export. The JSON format is the one NewPipe on Android writes ("Export subscriptions"), so
 * subscriptions can be moved between the phone and the PC in both directions. YouTube Takeout CSV is read as well.
 */
public final class SubscriptionIO {
    private SubscriptionIO() {}

    public static void export(File file) throws Exception {
        JsonArray subs = new JsonArray();
        for (Item c : Store.subscriptions()) {
            JsonObject o = new JsonObject();
            o.put("service_id", c.serviceId);
            o.put("url", c.url);
            o.put("name", c.name);
            subs.add(o);
        }
        JsonObject root = new JsonObject();
        root.put("app_version", "0.29.1");
        root.put("app_version_int", 1000);
        root.put("subscriptions", subs);
        Files.writeString(file.toPath(), JsonWriter.indent("  ").string().object(root).done(), StandardCharsets.UTF_8);
    }

    /** Returns the number of newly added subscriptions. */
    public static int importFrom(File file) throws Exception {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        List<Item> found = new ArrayList<>();
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonArray arr;
            if (trimmed.startsWith("{")) arr = JsonParser.object().from(trimmed).getArray("subscriptions");
            else arr = JsonParser.array().from(trimmed);
            for (Object o : arr) {
                if (!(o instanceof JsonObject jo)) continue;
                Item c = new Item();
                c.kind = Item.Kind.CHANNEL;
                c.serviceId = jo.getInt("service_id", 0);
                c.url = jo.getString("url", "");
                c.name = jo.getString("name", c.url);
                if (!c.url.isEmpty()) found.add(c);
            }
        } else {
            found.addAll(fromTakeoutCsv(trimmed));
        }
        int added = 0;
        for (Item c : found) {
            if (!Store.isSubscribed(c.url)) {
                Store.subscribe(c);
                added++;
            }
        }
        Backend.invalidateFeed();
        return added;
    }

    private static List<Item> fromTakeoutCsv(String csv) {
        List<Item> out = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) { // first line is the header
            List<String> cols = splitCsv(lines[i]);
            if (cols.size() < 3) continue;
            String id = cols.get(0).trim();
            String url = cols.get(1).trim();
            if (url.isEmpty() && !id.isEmpty()) url = "https://www.youtube.com/channel/" + id;
            if (url.isEmpty()) continue;
            Item c = new Item();
            c.kind = Item.Kind.CHANNEL;
            c.serviceId = 0;
            c.url = url;
            c.name = cols.get(2).trim();
            out.add(c);
        }
        return out;
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
