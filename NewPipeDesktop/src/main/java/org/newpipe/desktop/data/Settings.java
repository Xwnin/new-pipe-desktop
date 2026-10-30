package org.newpipe.desktop.data;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent key/value preferences stored in %APPDATA%\NewPipe\settings.json. */
public final class Settings {
    public static final File DIR = dataDir();
    private static final File FILE = new File(DIR, "settings.json");
    private static final Map<String, Object> values = new LinkedHashMap<>();
    private static final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        load();
    }

    private Settings() {}

    private static File dataDir() {
        // development / test runs set newpipe.data so the real user data is never touched
        String override = System.getProperty("newpipe.data");
        String appData = System.getenv("APPDATA");
        File d = override != null && !override.isEmpty() ? new File(override)
                : new File(appData != null ? appData : System.getProperty("user.home"), "NewPipe");
        d.mkdirs();
        return d;
    }

    private static void load() {
        try {
            if (FILE.isFile()) {
                JsonObject o = JsonParser.object().from(Files.readString(FILE.toPath(), StandardCharsets.UTF_8));
                values.putAll(o);
            }
        } catch (Exception e) {
            System.err.println("settings: " + e);
        }
    }

    private static synchronized void save() {
        try {
            Files.writeString(FILE.toPath(), JsonWriter.string(values), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("settings save: " + e);
        }
    }

    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    public static synchronized String str(String key, String def) {
        Object v = values.get(key);
        return v instanceof String s ? s : def;
    }

    public static synchronized boolean bool(String key, boolean def) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public static synchronized double num(String key, double def) {
        Object v = values.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public static synchronized int integer(String key, int def) {
        return (int) num(key, def);
    }

    public static void put(String key, Object v) {
        synchronized (Settings.class) {
            values.put(key, v);
            save();
        }
        for (Runnable r : listeners) r.run();
    }

    // ---- typed accessors -------------------------------------------------------------------

    public static String theme() { return str("theme", "dark"); }
    public static int serviceId() { return integer("service", 0); }
    public static String resolution() { return str("resolution", "720p"); }
    public static boolean audioOnly() { return bool("audioOnly", false); }
    public static boolean rememberTempo() { return bool("rememberTempo", true); }
    public static double tempo() { return num("tempo", 1.0); }
    public static double pitch() { return num("pitch", 1.0); }
    public static boolean unhook() { return bool("unhook", true); }
    public static double tempoStep() { return num("tempoStep", 0.05); }
    public static int volume() { return integer("volume", 100); }
    public static boolean autoplay() { return bool("autoplay", true); }
    public static boolean watchHistory() { return bool("watchHistory", true); }
    public static boolean searchHistory() { return bool("searchHistory", true); }
    public static boolean resumePosition() { return bool("resumePosition", true); }
    public static boolean skipSilence() { return bool("skipSilence", false); }

    public static File downloadDir() {
        String d = str("downloadDir", "");
        File f = d.isEmpty()
                ? new File(new File(System.getProperty("user.home"), "Downloads"), "NewPipe")
                : new File(d);
        f.mkdirs();
        return f;
    }
}
