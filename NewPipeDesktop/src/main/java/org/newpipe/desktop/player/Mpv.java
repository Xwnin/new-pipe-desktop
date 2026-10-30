package org.newpipe.desktop.player;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thin JNA wrapper around libmpv. All properties are observed / read as strings which keeps the
 * native struct handling trivial.
 */
public final class Mpv {
    public interface Lib extends Library {
        Pointer mpv_create();
        int mpv_initialize(Pointer h);
        void mpv_terminate_destroy(Pointer h);
        int mpv_set_option_string(Pointer h, String name, String data);
        int mpv_set_property_string(Pointer h, String name, String data);
        Pointer mpv_get_property_string(Pointer h, String name);
        void mpv_free(Pointer data);
        int mpv_command(Pointer h, String[] args);
        int mpv_observe_property(Pointer h, long userdata, String name, int format);
        Pointer mpv_wait_event(Pointer h, double timeout);
        void mpv_wakeup(Pointer h);
        String mpv_error_string(int error);
    }

    @Structure.FieldOrder({"eventId", "error", "replyUserdata", "data"})
    public static class Event extends Structure {
        public int eventId;
        public int error;
        public long replyUserdata;
        public Pointer data;

        public Event(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"name", "format", "data"})
    public static class EventProperty extends Structure {
        public String name;
        public int format;
        public Pointer data;

        public EventProperty(Pointer p) {
            super(p);
            read();
        }
    }

    @Structure.FieldOrder({"reason", "error"})
    public static class EventEndFile extends Structure {
        public int reason;
        public int error;

        public EventEndFile(Pointer p) {
            super(p);
            read();
        }
    }

    public interface Listener {
        /** value is null when the property became unavailable. */
        void onProperty(String name, String value);

        void onFileLoaded();

        /** reason: 0 = eof, 2 = stop, 3 = quit, 4 = error */
        void onEndFile(int reason, int error);

        /** A {@code script-message} sent by an mpv key binding (used for mouse clicks on the video). */
        default void onClientMessage(java.util.List<String> args) {}
    }

    static final int EVENT_SHUTDOWN = 1;
    static final int EVENT_LOG = 2;
    static final int EVENT_END_FILE = 7;
    static final int EVENT_FILE_LOADED = 8;
    static final int EVENT_CLIENT_MESSAGE = 16;
    static final int EVENT_PROPERTY = 22;
    static final int FORMAT_STRING = 1;

    private static Lib lib;

    public static synchronized Lib lib() {
        if (lib == null) {
            System.setProperty("jna.encoding", "UTF-8");
            File dll = locateDll();
            lib = Native.load(dll.getAbsolutePath(), Lib.class);
        }
        return lib;
    }

    public static File locateDll() {
        return org.newpipe.desktop.util.Native.find("libmpv-2.dll");
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Pointer handle;
    private Thread eventThread;
    private volatile boolean closed;

    public Mpv(long wid, boolean video) {
        Lib l = lib();
        handle = l.mpv_create();
        if (handle == null) throw new IllegalStateException("mpv_create failed");
        opt("terminal", "no");
        opt("config", "no");
        opt("input-default-bindings", "no");
        opt("input-vo-keyboard", "no");
        opt("osc", "no");
        opt("input-cursor", "no"); // mouse is handled by the Java canvas under the pointer
        opt("osd-level", "0");
        opt("idle", "yes");
        opt("keep-open", "yes");
        opt("force-window", video ? "yes" : "no");
        opt("vo", video ? "gpu" : "null");
        opt("hwdec", "auto-safe");
        opt("audio-pitch-correction", "yes");
        opt("ytdl", "no");
        opt("cache", "yes");
        opt("demuxer-max-bytes", "64MiB");
        opt("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0");
        opt("audio-client-name", "NewPipe");
        opt("title", "NewPipe");
        String logFile = System.getProperty("newpipe.mpvlog");
        if (logFile != null) {
            opt("log-file", logFile);
            opt("msg-level", "all=v");
        }
        // high quality sample-rate conversion (the WASAPI device is often 96/192 kHz)
        opt("audio-resample-filter-size", "32");
        opt("audio-resample-phase-shift", "12");
        String extra = System.getProperty("newpipe.mpvopts");
        if (extra != null) {
            for (String kv : extra.split(";")) {
                int eq = kv.indexOf('=');
                if (eq > 0) opt(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        if (wid != 0) opt("wid", Long.toString(wid));
        int rc = l.mpv_initialize(handle);
        if (rc < 0) throw new IllegalStateException("mpv_initialize failed: " + l.mpv_error_string(rc));
        for (String p : new String[]{"time-pos", "duration", "pause", "core-idle", "paused-for-cache",
                "eof-reached", "speed", "volume", "mute", "demuxer-cache-time", "seeking", "idle-active"}) {
            l.mpv_observe_property(handle, 0, p, FORMAT_STRING);
        }
        eventThread = new Thread(this::eventLoop, "mpv-events");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void opt(String name, String value) {
        lib().mpv_set_option_string(handle, name, value);
    }

    public int set(String name, String value) {
        if (closed) return -1;
        return lib().mpv_set_property_string(handle, name, value);
    }

    public String get(String name) {
        if (closed) return null;
        Pointer p = lib().mpv_get_property_string(handle, name);
        if (p == null) return null;
        try {
            return p.getString(0, "UTF-8");
        } finally {
            lib().mpv_free(p);
        }
    }

    public double getDouble(String name, double def) {
        String s = get(name);
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int command(String... args) {
        if (closed) return -1;
        String[] a = new String[args.length + 1];
        System.arraycopy(args, 0, a, 0, args.length);
        return lib().mpv_command(handle, a);
    }

    private void eventLoop() {
        Lib l = lib();
        final Pointer h = handle;
        while (!closed) {
            Pointer ep = l.mpv_wait_event(h, 0.5);
            if (ep == null) continue;
            Event ev = new Event(ep);
            try {
                switch (ev.eventId) {
                    case EVENT_SHUTDOWN:
                        return;
                    case EVENT_PROPERTY: {
                        EventProperty prop = new EventProperty(ev.data);
                        String value = null;
                        if (prop.format == FORMAT_STRING && prop.data != null) {
                            Pointer sp = prop.data.getPointer(0);
                            if (sp != null) value = sp.getString(0, "UTF-8");
                        }
                        for (Listener li : listeners) li.onProperty(prop.name, value);
                        break;
                    }
                    case EVENT_CLIENT_MESSAGE: {
                        int n = ev.data.getInt(0);
                        Pointer argv = ev.data.getPointer(8);
                        java.util.List<String> args = new java.util.ArrayList<>();
                        for (int i = 0; i < n && argv != null; i++) args.add(argv.getPointer(8L * i).getString(0, "UTF-8"));
                        for (Listener li : listeners) li.onClientMessage(args);
                        break;
                    }
                    case EVENT_FILE_LOADED:
                        for (Listener li : listeners) li.onFileLoaded();
                        break;
                    case EVENT_END_FILE: {
                        EventEndFile ee = new EventEndFile(ev.data);
                        for (Listener li : listeners) li.onEndFile(ee.reason, ee.error);
                        break;
                    }
                    default:
                        break;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        Pointer h = handle;
        handle = null;
        Thread t = eventThread;
        // wake the event loop, then destroy
        new Thread(() -> {
            try {
                lib().mpv_wakeup(h);
                if (t != null) t.join(1500);
            } catch (InterruptedException ignored) {
            }
            lib().mpv_terminate_destroy(h);
        }, "mpv-destroy").start();
    }
}
