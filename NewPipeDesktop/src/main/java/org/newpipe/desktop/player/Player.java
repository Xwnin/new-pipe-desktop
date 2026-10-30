package org.newpipe.desktop.player;

import com.sun.jna.Native;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.data.Store;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The playback engine: owns the play queue and the libmpv instance and implements NewPipe's tempo / pitch
 * ("unhook") behaviour. All public methods must be called on the Swing event thread.
 */
public final class Player {
    public enum Repeat { OFF, ALL, ONE }

    public interface Listener {
        /** The current track, its metadata or the loading state changed. */
        default void onTrack() {}

        /** Pause / buffering / ended state changed. */
        default void onState() {}

        default void onProgress(double pos, double dur) {}

        /** Tempo, pitch, volume, repeat, quality ... changed. */
        default void onParams() {}

        default void onMessage(String message) {}
    }

    /** What actually gets handed to mpv. */
    private static final class Plan {
        String main;
        String audio;
        String label;
        boolean videoless;
    }

    private static final Player INSTANCE = new Player();

    public static Player get() {
        return INSTANCE;
    }

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "player-worker");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Mpv mpv;
    private Canvas canvas;

    private final List<Item> queue = new ArrayList<>();
    private int index = -1;
    private Item current;
    private StreamInfo info;
    private volatile long loadToken;
    private int retries;
    private boolean loading;
    private String error;
    private Plan plan;
    private boolean ended;
    private boolean fileLoaded;
    private double pendingStart;

    // mpv mirrors
    private volatile double pos, dur;
    private volatile boolean paused, buffering, seeking;
    private volatile boolean eof;

    // user parameters
    private double tempo = 1.0, pitch = 1.0;
    private boolean unhook = true;
    private int volume = 100;
    private boolean muted;
    private Repeat repeat = Repeat.OFF;
    private boolean shuffle;
    private boolean audioOnly;
    private String quality;
    private SubtitlesStream subtitle;
    private boolean popup;
    private final Set<String> played = new HashSet<>();
    private int ticks;

    private Player() {
        Settings.integer("volume", 100);
        volume = Settings.volume();
        audioOnly = Settings.audioOnly();
        quality = Settings.resolution();
        unhook = Settings.unhook();
        if (Settings.rememberTempo()) {
            tempo = Settings.tempo();
            pitch = Settings.pitch();
        }
        new javax.swing.Timer(250, e -> tick()).start();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- surface ---------------------------------------------------------------------------

    /** Binds the video to a heavyweight canvas that is already displayable. Restarts playback on the new target. */
    public void attach(Canvas c) {
        if (c == canvas && mpv != null) return;
        boolean wasPlaying = mpv != null && current != null && fileLoaded;
        double at = pos;
        boolean wasPaused = paused;
        destroyMpv();
        canvas = c;
        if (wasPlaying && info != null) {
            pendingStart = at;
            ensureMpv();
            startPlayback(at, wasPaused);
        }
    }

    public void setPopup(boolean p) {
        popup = p;
    }

    public boolean isPopup() {
        return popup;
    }

    private void ensureMpv() {
        if (mpv != null) return;
        long wid = 0;
        if (canvas != null && canvas.isDisplayable()) {
            try {
                wid = Native.getComponentID(canvas);
            } catch (Throwable t) {
                wid = 0;
            }
        }
        mpv = new Mpv(wid, true);
        appliedCorrection = "yes";
        mpv.addListener(new Mpv.Listener() {
            @Override
            public void onProperty(String name, String value) {
                handleProperty(name, value);
            }

            @Override
            public void onFileLoaded() {
                EventQueue.invokeLater(Player.this::fileLoaded);
            }

            @Override
            public void onEndFile(int reason, int err) {
                if (reason == 4) EventQueue.invokeLater(() -> loadFailed(err));
            }
        });
        applyVolume();
        applyAudioParams();
    }

    private Runnable doubleClickHandler;
    private long ignoreClicksUntil;
    // A single click toggles pause, but only after the double-click interval has passed without a second click.
    private final javax.swing.Timer clickTimer = new javax.swing.Timer(250, e -> togglePause());

    {
        clickTimer.setRepeats(false);
    }

    /** A left click on the video surface: 1 click toggles pause (after the double-click interval), 2 clicks = fullscreen. */
    public void onVideoClick(int clickCount) {
        if (clickCount == 1) videoClicked();
        else if (clickCount == 2) videoDoubleClicked();
    }

    private void videoClicked() {
        if (System.currentTimeMillis() < ignoreClicksUntil) return; // the release of a double click
        clickTimer.restart();
    }

    private void videoDoubleClicked() {
        clickTimer.stop();
        ignoreClicksUntil = System.currentTimeMillis() + 600;
        if (doubleClickHandler != null) doubleClickHandler.run();
    }

    /** What a double click on the video does (fullscreen); a single click toggles pause. */
    public void setDoubleClickHandler(Runnable r) {
        doubleClickHandler = r;
    }

    private void destroyMpv() {
        if (mpv != null) {
            mpv.close();
            mpv = null;
        }
        fileLoaded = false;
        pitchFilterOn = false;
    }

    // ---- mpv events ------------------------------------------------------------------------

    private void handleProperty(String name, String value) {
        switch (name) {
            case "time-pos" -> pos = parse(value, pos);
            case "duration" -> dur = parse(value, 0);
            case "pause" -> {
                boolean p = "yes".equals(value);
                if (p != paused) {
                    paused = p;
                    EventQueue.invokeLater(() -> fire(Listener::onState));
                }
            }
            case "paused-for-cache", "seeking" -> {
                boolean b = "yes".equals(value);
                if (name.equals("seeking")) seeking = b;
                else buffering = b;
                EventQueue.invokeLater(() -> fire(Listener::onState));
            }
            case "eof-reached" -> {
                boolean e = "yes".equals(value);
                if (e && !eof) EventQueue.invokeLater(this::reachedEnd);
                eof = e;
            }
            default -> { }
        }
    }

    private static double parse(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void fileLoaded() {
        fileLoaded = true;
        loading = false;
        error = null;
        retries = 0;
        applyVolume();
        applyAudioParams();
        if (subtitle != null) addSubtitle(subtitle);
        fire(Listener::onTrack);
        fire(Listener::onState);
    }

    private void loadFailed(int err) {
        if (info == null || current == null) return;
        if (retries < 2) {
            retries++;
            double at = pos;
            Item it = current;
            fire(l -> l.onMessage("Retrying..."));
            load(index, at, false);
            return;
        }
        loading = false;
        error = "Playback failed (mpv error " + err + ")";
        fire(Listener::onTrack);
        fire(Listener::onState);
    }

    private void reachedEnd() {
        if (ended || current == null) return;
        ended = true;
        Store.savePosition(current.url, 0);
        if (repeat == Repeat.ONE) {
            seekTo(0);
            mpvSet("pause", "no");
            ended = false;
            return;
        }
        if (!next(true)) {
            fire(Listener::onState);
        }
    }

    // ---- queue -----------------------------------------------------------------------------

    public List<Item> queue() {
        return Collections.unmodifiableList(queue);
    }

    public int index() {
        return index;
    }

    public Item current() {
        return current;
    }

    public StreamInfo info() {
        return info;
    }

    public boolean isLoading() {
        return loading;
    }

    public String error() {
        return error;
    }

    public void playNow(Item item) {
        queue.clear();
        played.clear();
        queue.add(item);
        load(0, -1, true);
    }

    public void playQueue(List<Item> items, int start) {
        if (items.isEmpty()) return;
        queue.clear();
        played.clear();
        queue.addAll(items);
        load(Math.max(0, Math.min(start, queue.size() - 1)), -1, true);
    }

    public void enqueue(Item item) {
        if (queue.isEmpty()) {
            playNow(item);
            return;
        }
        queue.add(item);
        fire(Listener::onTrack);
    }

    public void enqueueNext(Item item) {
        if (queue.isEmpty()) {
            playNow(item);
            return;
        }
        queue.add(Math.min(index + 1, queue.size()), item);
        fire(Listener::onTrack);
    }

    public void removeFromQueue(int i) {
        if (i < 0 || i >= queue.size()) return;
        if (i == index) return;
        queue.remove(i);
        if (i < index) index--;
        fire(Listener::onTrack);
    }

    public void jumpTo(int i) {
        if (i >= 0 && i < queue.size()) load(i, -1, true);
    }

    public boolean hasNext() {
        return index + 1 < queue.size() || repeat == Repeat.ALL;
    }

    public boolean hasPrevious() {
        return index > 0 || pos > 3;
    }

    public boolean next() {
        return next(false);
    }

    private boolean next(boolean auto) {
        if (queue.isEmpty()) return false;
        if (shuffle && queue.size() > 1) {
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) if (i != index && !played.contains(queue.get(i).url)) pool.add(i);
            if (pool.isEmpty()) {
                if (repeat != Repeat.ALL && auto) return autoplayRelated();
                played.clear();
                for (int i = 0; i < queue.size(); i++) if (i != index) pool.add(i);
            }
            load(pool.get(new Random().nextInt(pool.size())), -1, true);
            return true;
        }
        if (index + 1 < queue.size()) {
            load(index + 1, -1, true);
            return true;
        }
        if (repeat == Repeat.ALL) {
            load(0, -1, true);
            return true;
        }
        return auto && autoplayRelated();
    }

    private boolean autoplayRelated() {
        if (!Settings.autoplay() || info == null) return false;
        for (InfoItem r : info.getRelatedItems()) {
            if (r instanceof StreamInfoItem s && !played.contains(s.getUrl())
                    && queue.stream().noneMatch(q -> q.url.equals(s.getUrl()))) {
                queue.add(Item.from(s));
                load(queue.size() - 1, -1, true);
                return true;
            }
        }
        return false;
    }

    public void previous() {
        if (pos > 3 || index <= 0) {
            seekTo(0);
            return;
        }
        load(index - 1, -1, true);
    }

    public void stop() {
        loadToken++;
        destroyMpv();
        queue.clear();
        index = -1;
        current = null;
        info = null;
        loading = false;
        pos = 0;
        dur = 0;
        fire(Listener::onTrack);
        fire(Listener::onState);
    }

    // ---- loading ---------------------------------------------------------------------------

    private void load(int idx, double startPos, boolean fresh) {
        if (current != null && info != null && fresh) Store.savePosition(current.url, ended ? 0 : pos);
        index = idx;
        current = queue.get(idx);
        played.add(current.url);
        info = null;
        plan = null;
        ended = false;
        eof = false;
        loading = true;
        error = null;
        pos = 0;
        dur = Math.max(0, current.duration);
        fileLoaded = false;
        subtitle = null;
        if (fresh) retries = 0;
        if (mpv != null) mpv.command("stop");
        final long token = ++loadToken;
        fire(Listener::onTrack);
        fire(Listener::onState);
        final Item it = current;
        pool.execute(() -> {
            try {
                StreamInfo si = StreamInfo.getInfo(it.url);
                EventQueue.invokeLater(() -> {
                    if (token != loadToken) return;
                    info = si;
                    updateItem(it, si);
                    Store.addHistory(it);
                    double start = startPos;
                    if (start < 0) {
                        start = 0;
                        double saved = Store.position(it.url);
                        if (Settings.resumePosition() && saved > 5 && si.getDuration() > 0 && saved < si.getDuration() - 10)
                            start = saved;
                    }
                    ensureMpv();
                    startPlayback(start, false);
                });
            } catch (Throwable t) {
                EventQueue.invokeLater(() -> {
                    if (token != loadToken) return;
                    loading = false;
                    error = describe(t);
                    fire(Listener::onTrack);
                    fire(Listener::onState);
                });
            }
        });
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    private static void updateItem(Item it, StreamInfo si) {
        if (si.getName() != null && !si.getName().isBlank()) it.name = si.getName();
        if (si.getUploaderName() != null) it.uploader = si.getUploaderName();
        if (si.getUploaderUrl() != null && !si.getUploaderUrl().isBlank()) it.uploaderUrl = si.getUploaderUrl();
        if (si.getDuration() > 0) it.duration = si.getDuration();
        if (si.getViewCount() >= 0) it.views = si.getViewCount();
        String thumb = Item.bestImage(si.getThumbnails());
        if (!thumb.isEmpty()) it.thumb = thumb;
        it.serviceId = si.getServiceId();
        it.live = si.getStreamType() == StreamType.LIVE_STREAM || si.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
    }

    private void startPlayback(double start, boolean startPaused) {
        if (info == null || mpv == null) return;
        plan = pick(info);
        if (plan == null) {
            loading = false;
            error = "No playable stream found";
            fire(Listener::onTrack);
            return;
        }
        pendingStart = start;
        StringBuilder opts = new StringBuilder();
        if (start > 1) opts.append("start=").append(String.format(Locale.ROOT, "%.2f", start));
        if (plan.audio != null) {
            if (opts.length() > 0) opts.append(',');
            opts.append("audio-file=%").append(plan.audio.length()).append('%').append(plan.audio);
        }
        if (opts.length() > 0) opts.append(',');
        opts.append("pause=").append(startPaused ? "yes" : "no");
        mpv.command("loadfile", plan.main, "replace", "-1", opts.toString());
        fire(Listener::onTrack);
    }

    // ---- stream selection ------------------------------------------------------------------

    private static int codecRank(String codec) {
        if (codec == null) return 3;
        String c = codec.toLowerCase(Locale.ROOT);
        if (c.startsWith("avc")) return 0;
        if (c.startsWith("vp9") || c.startsWith("vp09")) return 1;
        if (c.startsWith("av01") || c.startsWith("av1")) return 2;
        return 3;
    }

    private static boolean usable(org.schabi.newpipe.extractor.stream.Stream s) {
        DeliveryMethod d = s.getDeliveryMethod();
        return s.isUrl() && (d == DeliveryMethod.PROGRESSIVE_HTTP || d == DeliveryMethod.HLS);
    }

    public static String label(VideoStream v) {
        String r = v.getResolution();
        if (r == null || r.equals(VideoStream.RESOLUTION_UNKNOWN)) r = v.getHeight() > 0 ? v.getHeight() + "p" : "?";
        if (v.getFps() > 30 && !r.contains("60") && !r.contains(String.valueOf(v.getFps()))) {
            return r.replaceAll("p\\d*$", "p") + v.getFps();
        }
        return r;
    }

    private static int heightOf(String label) {
        try {
            return Integer.parseInt(label.replaceAll("^(\\d+).*$", "$1"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static AudioStream bestAudio(StreamInfo si) {
        List<AudioStream> all = new ArrayList<>();
        for (AudioStream a : si.getAudioStreams()) if (usable(a)) all.add(a);
        if (all.isEmpty()) return null;
        List<AudioStream> original = new ArrayList<>();
        for (AudioStream a : all) {
            AudioTrackType t = a.getAudioTrackType();
            if (t == null || t == AudioTrackType.ORIGINAL) original.add(a);
        }
        if (!original.isEmpty()) all = original;
        AudioStream best = null;
        for (AudioStream a : all) {
            if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) best = a;
        }
        return best;
    }

    /** Distinct resolution labels offered for the current stream, highest first. */
    public List<String> qualities() {
        if (info == null) return List.of();
        Map<Integer, String> byHeight = new TreeMap<>(Comparator.reverseOrder());
        List<VideoStream> all = new ArrayList<>(info.getVideoOnlyStreams());
        all.addAll(info.getVideoStreams());
        for (VideoStream v : all) {
            if (!usable(v)) continue;
            int h = v.getHeight() > 0 ? v.getHeight() : heightOf(label(v));
            if (h <= 0) continue;
            String l = label(v);
            byHeight.merge(h, l, (a, b) -> a.length() >= b.length() ? a : b);
        }
        return new ArrayList<>(byHeight.values());
    }

    public boolean hasVideo() {
        return info != null && !qualities().isEmpty();
    }

    private Plan pick(StreamInfo si) {
        Plan p = new Plan();
        AudioStream audio = bestAudio(si);
        List<VideoStream> candidates = new ArrayList<>();
        for (VideoStream v : si.getVideoOnlyStreams()) if (usable(v)) candidates.add(v);
        List<VideoStream> combined = new ArrayList<>();
        for (VideoStream v : si.getVideoStreams()) if (usable(v)) combined.add(v);

        boolean live = si.getStreamType() == StreamType.LIVE_STREAM || si.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
        if ((candidates.isEmpty() && combined.isEmpty() && audio == null) || (live && si.getHlsUrl() != null && !si.getHlsUrl().isBlank())) {
            if (si.getHlsUrl() != null && !si.getHlsUrl().isBlank()) {
                p.main = si.getHlsUrl();
                p.label = "Live";
                return p;
            }
            if (si.getDashMpdUrl() != null && !si.getDashMpdUrl().isBlank()) {
                p.main = si.getDashMpdUrl();
                p.label = "Live";
                return p;
            }
            return null;
        }

        if (audioOnly || (candidates.isEmpty() && combined.isEmpty())) {
            if (audio == null) {
                // no audio-only stream: fall back to the smallest combined stream
                if (combined.isEmpty()) return null;
                combined.sort(Comparator.comparingInt(VideoStream::getHeight));
                p.main = combined.get(0).getContent();
                p.label = label(combined.get(0));
                return p;
            }
            p.main = audio.getContent();
            p.videoless = true;
            p.label = "Audio";
            return p;
        }

        int want = "auto".equalsIgnoreCase(quality) ? 1080 : heightOf(quality == null ? "720p" : quality);
        List<VideoStream> all = new ArrayList<>(candidates);
        all.addAll(combined);
        // pick the best height that does not exceed the wish (or the lowest above it)
        int bestH = -1, lowestAbove = Integer.MAX_VALUE;
        for (VideoStream v : all) {
            int h = v.getHeight() > 0 ? v.getHeight() : heightOf(label(v));
            if (h <= want && h > bestH) bestH = h;
            if (h > want && h < lowestAbove) lowestAbove = h;
        }
        int height = bestH > 0 ? bestH : lowestAbove;
        VideoStream chosen = null;
        for (VideoStream v : all) {
            int h = v.getHeight() > 0 ? v.getHeight() : heightOf(label(v));
            if (h != height) continue;
            if (chosen == null) {
                chosen = v;
                continue;
            }
            // prefer exact label match for the wish (e.g. 60fps), then codec rank, then video-only (better audio), then bitrate
            boolean vMatch = label(v).equalsIgnoreCase(quality), cMatch = label(chosen).equalsIgnoreCase(quality);
            if (vMatch != cMatch) {
                if (vMatch) chosen = v;
                continue;
            }
            if (v.getFps() != chosen.getFps() && !vMatch) {
                if (v.getFps() < chosen.getFps()) chosen = v;
                continue;
            }
            int rv = codecRank(v.getCodec()), rc = codecRank(chosen.getCodec());
            if (rv != rc) {
                if (rv < rc) chosen = v;
                continue;
            }
            if (v.isVideoOnly() && !chosen.isVideoOnly()) chosen = v;
            else if (v.isVideoOnly() == chosen.isVideoOnly() && v.getBitrate() > chosen.getBitrate()) chosen = v;
        }
        if (chosen == null) return null;
        p.main = chosen.getContent();
        p.label = label(chosen);
        if (chosen.isVideoOnly()) {
            if (audio == null) return p;
            p.audio = audio.getContent();
        }
        return p;
    }

    public String currentQualityLabel() {
        if (plan == null) return "";
        return plan.label == null ? "" : plan.label;
    }

    public String qualityPreference() {
        return quality;
    }

    public void setQuality(String q) {
        quality = q;
        Settings.put("resolution", q);
        if (audioOnly) {
            audioOnly = false;
            Settings.put("audioOnly", false);
        }
        reloadHere();
        fire(Listener::onParams);
    }

    public boolean isAudioOnly() {
        return audioOnly || (plan != null && plan.videoless);
    }

    public void setAudioOnly(boolean b) {
        audioOnly = b;
        Settings.put("audioOnly", b);
        reloadHere();
        fire(Listener::onParams);
    }

    private void reloadHere() {
        if (info == null || mpv == null) return;
        double at = pos;
        boolean wasPaused = paused;
        startPlayback(at, wasPaused);
    }

    // ---- subtitles -------------------------------------------------------------------------

    public List<SubtitlesStream> subtitles() {
        if (info == null) return List.of();
        List<SubtitlesStream> out = new ArrayList<>();
        for (SubtitlesStream s : info.getSubtitles()) if (s.isUrl()) out.add(s);
        return out;
    }

    public SubtitlesStream subtitle() {
        return subtitle;
    }

    public void setSubtitle(SubtitlesStream s) {
        subtitle = s;
        if (mpv == null) return;
        if (s == null) mpv.set("sid", "no");
        else addSubtitle(s);
        fire(Listener::onParams);
    }

    private void addSubtitle(SubtitlesStream s) {
        if (mpv != null) mpv.command("sub-add", s.getContent(), "select", s.getDisplayLanguageName(), s.getLanguageTag());
    }

    // ---- transport -------------------------------------------------------------------------

    public boolean isPaused() {
        return paused;
    }

    public boolean isBuffering() {
        return buffering || seeking || loading;
    }

    public boolean isPlaying() {
        return fileLoaded && !paused && !ended;
    }

    public boolean isEnded() {
        return ended;
    }

    public double position() {
        return pos;
    }

    public double duration() {
        return dur > 0 ? dur : (info != null ? info.getDuration() : 0);
    }

    public void togglePause() {
        if (mpv == null) return;
        if (ended) {
            ended = false;
            eof = false;
            seekTo(0);
            mpvSet("pause", "no");
            return;
        }
        mpvSet("pause", paused ? "no" : "yes");
    }

    public void play() {
        if (paused) togglePause();
    }

    public void pause() {
        if (!paused) togglePause();
    }

    public void seekTo(double seconds) {
        if (mpv == null || !fileLoaded) return;
        double d = duration();
        if (d > 0) seconds = Math.min(seconds, d);
        seconds = Math.max(0, seconds);
        pos = seconds;
        ended = false;
        mpv.command("seek", String.format(Locale.ROOT, "%.3f", seconds), "absolute+exact");
    }

    public void seekBy(double delta) {
        seekTo(pos + delta);
    }

    private void mpvSet(String name, String value) {
        if (mpv != null) mpv.set(name, value);
    }

    // ---- tempo / pitch ---------------------------------------------------------------------

    public double tempo() {
        return tempo;
    }

    public double pitch() {
        return pitch;
    }

    public boolean unhook() {
        return unhook;
    }

    public static double clampTempo(double v) {
        return Math.max(0.1, Math.min(4.0, Math.round(v * 100) / 100.0));
    }

    public static double clampPitch(double v) {
        return Math.max(0.25, Math.min(4.0, Math.round(v * 1000) / 1000.0));
    }

    public void setTempo(double t) {
        tempo = clampTempo(t);
        if (!unhook) pitch = tempo;
        persistAudio();
        applyAudioParams();
        fire(Listener::onParams);
    }

    public void setPitch(double p) {
        pitch = clampPitch(p);
        if (!unhook) tempo = clampTempo(pitch);
        persistAudio();
        applyAudioParams();
        fire(Listener::onParams);
    }

    public void setUnhook(boolean u) {
        unhook = u;
        Settings.put("unhook", u);
        if (!u) {
            // hooked: the classic "speed" behaviour, pitch follows tempo
            pitch = tempo;
        } else {
            pitch = 1.0;
        }
        persistAudio();
        applyAudioParams();
        fire(Listener::onParams);
    }

    public void resetTempo() {
        tempo = 1.0;
        pitch = 1.0;
        persistAudio();
        applyAudioParams();
        fire(Listener::onParams);
    }

    private void persistAudio() {
        if (Settings.rememberTempo()) {
            Settings.put("tempo", tempo);
            Settings.put("pitch", pitch);
        }
    }

    private boolean pitchFilterOn;

    /**
     * Rubber Band time stretcher / pitch shifter. For tempo changes it measurably beats mpv's default scaletempo2
     * (about 16 dB less transient smearing and cleaner tones in a synthetic test). Formants are preserved so voices
     * keep their character when the pitch is shifted, and stereo channels are processed together. Most other options
     * have no effect in Rubber Band's real-time mode; they are kept explicit so behaviour does not depend on defaults.
     */
    public static String filterSpec(double pitch) {
        return String.format(Locale.ROOT, "@np:rubberband=engine=finer:pitch=quality:transients=crisp:detector=compound:"
                + "phase=laminar:window=standard:smoothing=off:formant=preserved:channels=together:pitch-scale=%.4f", pitch);
    }

    private boolean applyPending;
    private String appliedCorrection = "yes";
    // Slider drags produce dozens of updates per second; each one makes mpv reconfigure its resampler. Apply the first
    // immediately, then at most every 60 ms (the last value always wins).
    private final javax.swing.Timer applyTimer = new javax.swing.Timer(60, e -> {
        if (applyPending) {
            applyPending = false;
            doApplyAudioParams();
            ((javax.swing.Timer) e.getSource()).start();
        }
    });

    {
        applyTimer.setRepeats(false);
    }

    private void applyAudioParams() {
        if (mpv == null) return;
        if (applyTimer.isRunning()) {
            applyPending = true;
            return;
        }
        doApplyAudioParams();
        applyTimer.start();
    }

    private void setCorrection(String value) {
        if (!value.equals(appliedCorrection) && mpv != null) {
            mpv.set("audio-pitch-correction", value);
            appliedCorrection = value;
        }
    }

    private void doApplyAudioParams() {
        if (mpv == null) return;
        mpv.set("speed", String.format(Locale.ROOT, "%.3f", tempo));
        if (!unhook) {
            // hooked: plain resampling, pitch moves with the speed
            setCorrection("no");
            removePitchFilter();
            return;
        }
        setCorrection("yes");
        boolean stretch = Math.abs(tempo - 1.0) > 0.001 || Math.abs(pitch - 1.0) > 0.001;
        if (!stretch) {
            removePitchFilter(); // untouched, bit-perfect audio at 1x / 100%
        } else if (!pitchFilterOn) {
            // the high quality engine handles tempo (mpv routes the speed to it) and pitch
            if (mpv.command("af", "add", filterSpec(pitch)) < 0) {
                mpv.command("af", "add", "@np:rubberband=pitch-scale=" + String.format(Locale.ROOT, "%.4f", pitch));
            }
            pitchFilterOn = true;
        } else {
            mpv.command("af-command", "np", "set-pitch", String.format(Locale.ROOT, "%.4f", pitch));
        }
    }

    private void removePitchFilter() {
        if (pitchFilterOn && mpv != null) {
            mpv.command("af", "remove", "@np");
        }
        pitchFilterOn = false;
    }

    // ---- volume / modes --------------------------------------------------------------------

    public int volume() {
        return volume;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setVolume(int v) {
        volume = Math.max(0, Math.min(130, v));
        if (volume > 0) muted = false;
        Settings.put("volume", volume);
        applyVolume();
        fire(Listener::onParams);
    }

    public void toggleMute() {
        muted = !muted;
        applyVolume();
        fire(Listener::onParams);
    }

    private void applyVolume() {
        if (mpv == null) return;
        mpv.set("volume", Integer.toString(volume));
        mpv.set("mute", muted ? "yes" : "no");
    }

    public Repeat repeat() {
        return repeat;
    }

    public void cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length];
        fire(Listener::onParams);
    }

    public boolean shuffle() {
        return shuffle;
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        played.clear();
        if (current != null) played.add(current.url);
        fire(Listener::onParams);
    }

    // ---- misc ------------------------------------------------------------------------------

    private void tick() {
        if (mpv == null || current == null) return;
        fire(l -> l.onProgress(pos, duration()));
        if (++ticks % 20 == 0 && fileLoaded && !ended && dur > 30) Store.savePosition(current.url, pos);
    }

    private void fire(java.util.function.Consumer<Listener> c) {
        for (Listener l : listeners) {
            try {
                c.accept(l);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    /** Raw mpv property, for diagnostics. */
    public String mpvProperty(String name) {
        return mpv == null ? null : mpv.get(name);
    }

    public void shutdown() {
        if (current != null && fileLoaded && dur > 30) Store.savePosition(current.url, ended ? 0 : pos);
        if (mpv != null) mpv.close();
        mpv = null;
    }
}
