package org.newpipe.desktop.data;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import okhttp3.Request;
import okhttp3.Response;
import org.newpipe.desktop.net.HttpDownloader;
import org.newpipe.desktop.util.Fmt;
import org.newpipe.desktop.util.Native;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Download queue. Progressive streams are fetched in 4 MB range requests (so googlevideo does not throttle them);
 * video-only + audio pairs and HLS audio are joined with the bundled ffmpeg using stream copy (no re-encoding).
 */
public final class Downloads {
    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

    public static final class Job {
        public final String title;
        public final File file;
        public volatile State state = State.QUEUED;
        public volatile long done, total = -1;
        public volatile String error = "";
        volatile boolean cancel;
        volatile Process process;

        Job(String title, File file) {
            this.title = title;
            this.file = file;
        }

        public void cancel() {
            cancel = true;
            Process p = process;
            if (p != null) p.destroyForcibly();
        }

        public double fraction() {
            return total > 0 ? Math.min(1.0, done / (double) total) : (state == State.DONE ? 1 : 0);
        }
    }

    /** One selectable download choice. */
    public static final class Option {
        public final String label;
        public final String ext;
        final String video, audio;
        final boolean hls;
        public final long size;

        Option(String label, String ext, String video, String audio, boolean hls, long size) {
            this.label = label;
            this.ext = ext;
            this.video = video;
            this.audio = audio;
            this.hls = hls;
            this.size = size;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Job> JOBS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "download");
        t.setDaemon(true);
        return t;
    });
    private static final File INDEX = new File(Settings.DIR, "downloads.json");

    static {
        loadIndex();
    }

    private Downloads() {}

    public static List<Job> jobs() {
        return JOBS;
    }

    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void fire() {
        for (Runnable r : LISTENERS) r.run();
    }

    // ---- options ---------------------------------------------------------------------------

    private static boolean ok(org.schabi.newpipe.extractor.stream.Stream s) {
        DeliveryMethod d = s.getDeliveryMethod();
        return s.isUrl() && (d == DeliveryMethod.PROGRESSIVE_HTTP || d == DeliveryMethod.HLS);
    }

    private static String audioExt(AudioStream a) {
        String f = a.getFormat() == null ? "" : a.getFormat().getSuffix();
        return f == null || f.isEmpty() ? "m4a" : f;
    }

    private static int rank(String codec) {
        if (codec == null) return 3;
        String c = codec.toLowerCase(Locale.ROOT);
        return c.startsWith("avc") ? 0 : c.startsWith("vp9") || c.startsWith("vp09") ? 1 : c.startsWith("av01") ? 2 : 3;
    }

    public static List<Option> audioOptions(StreamInfo si) {
        List<Option> out = new ArrayList<>();
        List<AudioStream> list = new ArrayList<>();
        for (AudioStream a : si.getAudioStreams()) if (ok(a)) list.add(a);
        list.sort(Comparator.comparingInt(AudioStream::getAverageBitrate).reversed());
        Set<String> seen = new HashSet<>();
        for (AudioStream a : list) {
            boolean hls = a.getDeliveryMethod() == DeliveryMethod.HLS;
            String ext = hls ? "mp3" : audioExt(a);
            String name = (a.getFormat() == null ? ext.toUpperCase() : a.getFormat().getName())
                    + (a.getAverageBitrate() > 0 ? "  " + a.getAverageBitrate() + " kbps" : "");
            if (a.getAudioTrackName() != null && !a.getAudioTrackName().isBlank()) name += "  (" + a.getAudioTrackName() + ")";
            if (!seen.add(name)) continue;
            out.add(new Option(name, ext, a.getContent(), null, hls, -1));
        }
        return out;
    }

    public static List<Option> videoOptions(StreamInfo si) {
        AudioStream best = null, bestM4a = null;
        for (AudioStream a : si.getAudioStreams()) {
            if (!ok(a) || a.getDeliveryMethod() == DeliveryMethod.HLS) continue;
            if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) best = a;
            if ("m4a".equals(audioExt(a)) && (bestM4a == null || a.getAverageBitrate() > bestM4a.getAverageBitrate())) bestM4a = a;
        }
        List<VideoStream> all = new ArrayList<>();
        for (VideoStream v : si.getVideoOnlyStreams()) if (ok(v) && v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) all.add(v);
        for (VideoStream v : si.getVideoStreams()) if (ok(v) && v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) all.add(v);
        // best stream per label
        Map<String, VideoStream> byLabel = new LinkedHashMap<>();
        all.sort(Comparator.comparingInt(VideoStream::getHeight).reversed()
                .thenComparingInt(v -> rank(v.getCodec())).thenComparingInt(v -> v.isVideoOnly() ? 0 : 1));
        for (VideoStream v : all) byLabel.putIfAbsent(org.newpipe.desktop.player.Player.label(v), v);
        List<Option> out = new ArrayList<>();
        for (var e : byLabel.entrySet()) {
            VideoStream v = e.getValue();
            String fmt = v.getFormat() == null ? "" : v.getFormat().getName();
            if (v.isVideoOnly()) {
                if (best == null) continue;
                boolean avc = rank(v.getCodec()) == 0;
                AudioStream pick = avc && bestM4a != null ? bestM4a : best;   // H.264 + AAC => a normal .mp4
                String ext = avc && "m4a".equals(audioExt(pick)) ? "mp4"
                        : "webm".equals(v.getFormat() == null ? "" : v.getFormat().getSuffix()) && "webm".equals(audioExt(pick)) ? "webm" : "mkv";
                out.add(new Option(e.getKey() + "  " + fmt + "  (video + audio)", ext, v.getContent(), pick.getContent(), false, -1));
            } else {
                String ext = v.getFormat() == null ? "mp4" : v.getFormat().getSuffix();
                out.add(new Option(e.getKey() + "  " + fmt, ext, v.getContent(), null, false, -1));
            }
        }
        return out;
    }

    // ---- running ---------------------------------------------------------------------------

    public static Job start(String title, Option o, File dir, String fileName) {
        dir.mkdirs();
        File f = unique(new File(dir, Fmt.safeFileName(fileName) + "." + o.ext));
        Job job = new Job(title, f);
        JOBS.add(0, job);
        fire();
        POOL.execute(() -> run(job, o));
        return job;
    }

    private static File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    private static void run(Job job, Option o) {
        job.state = State.RUNNING;
        fire();
        try {
            if (o.hls) hlsAudio(job, o);
            else if (o.audio != null) mux(job, o);
            else http(job, o.video);
            if (job.cancel) throw new InterruptedException("canceled");
            job.state = State.DONE;
        } catch (InterruptedException e) {
            job.state = State.CANCELED;
            job.file.delete();
        } catch (Throwable t) {
            job.state = job.cancel ? State.CANCELED : State.FAILED;
            job.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            job.file.delete();
        }
        saveIndex();
        fire();
    }

    /** Size of a remote file in bytes, or -1 (uses a 1 byte range request). */
    private static long probeSize(String url) {
        try {
            Request req = new Request.Builder().url(url).header("Range", "bytes=0-0")
                    .header("User-Agent", HttpDownloader.USER_AGENT).build();
            try (Response r = HttpDownloader.get().client().newCall(req).execute()) {
                String cr = r.header("Content-Range");
                if (cr != null && cr.contains("/")) return Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
                if (r.body() != null) return r.body().contentLength();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void http(Job job, String url) throws Exception {
        job.done = 0;
        job.total = probeSize(url);
        fetch(job, url, job.file);
    }

    /** Chunked range download of {@code url} into {@code dest}; adds the received bytes to {@code job.done}. */
    private static void fetch(Job job, String url, File dest) throws Exception {
        final long chunk = 4L << 20;
        long total = -1;
        try (RandomAccessFile out = new RandomAccessFile(dest, "rw")) {
            out.setLength(0);
            long pos = 0;
            while (!job.cancel) {
                long end = pos + chunk - 1;
                Request req = new Request.Builder().url(url).header("Range", "bytes=" + pos + "-" + end)
                        .header("User-Agent", HttpDownloader.USER_AGENT).build();
                try (Response r = HttpDownloader.get().client().newCall(req).execute()) {
                    if (!r.isSuccessful() && r.code() != 206) throw new java.io.IOException("HTTP " + r.code());
                    String cr = r.header("Content-Range");
                    if (cr != null && cr.contains("/")) {
                        try {
                            total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (r.code() == 200 && r.body() != null) {
                        total = r.body().contentLength();
                    }
                    try (InputStream in = r.body().byteStream()) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0 && !job.cancel) {
                            out.write(buf, 0, n);
                            pos += n;
                            job.done += n;
                            fireThrottled();
                        }
                    }
                    if (r.code() == 200) break; // server ignored the range: whole file received
                }
                if (total < 0 || pos >= total) break;
            }
        }
    }

    private static long lastFire;

    private static void fireThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastFire > 300) {
            lastFire = now;
            fire();
        }
    }

    /** Downloads an HLS audio playlist segment by segment (JDK trust store, no ffmpeg TLS) and repackages it locally. */
    private static void hlsAudio(Job job, Option o) throws Exception {
        File dir = job.file.getParentFile();
        File raw = File.createTempFile("np-hls", ".part", dir);
        try {
            job.done = 0;
            job.total = 1;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(raw)) {
                fetchHls(job, o.video, out, 0);
            }
            if (job.cancel) throw new InterruptedException();
            runFfmpeg(job, new ArrayList<>(List.of("-i", raw.getAbsolutePath(), "-vn", "-c", "copy", job.file.getAbsolutePath())));
            job.total = job.done = job.file.length();
        } finally {
            raw.delete();
        }
    }

    private static String get(String url) throws java.io.IOException {
        Request req = new Request.Builder().url(url).header("User-Agent", HttpDownloader.USER_AGENT).build();
        try (Response r = HttpDownloader.get().client().newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new java.io.IOException("HTTP " + r.code());
            return r.body().string();
        }
    }

    private static void fetchHls(Job job, String playlistUrl, java.io.OutputStream out, int depth) throws Exception {
        String body = get(playlistUrl);
        java.net.URI base = java.net.URI.create(playlistUrl);
        List<String> lines = Arrays.asList(body.split("\r?\n"));
        if (body.contains("#EXT-X-STREAM-INF")) {
            if (depth > 2) throw new java.io.IOException("nested HLS playlists");
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size()) {
                    fetchHls(job, base.resolve(lines.get(i + 1).trim()).toString(), out, depth + 1);
                    return;
                }
            }
            throw new java.io.IOException("empty HLS master playlist");
        }
        if (body.contains("#EXT-X-KEY") && !body.contains("METHOD=NONE")) throw new java.io.IOException("encrypted HLS streams are not supported");
        List<String> parts = new ArrayList<>();
        for (String l : lines) {
            l = l.trim();
            if (l.startsWith("#EXT-X-MAP:")) {
                int a = l.indexOf("URI=\"");
                if (a >= 0) parts.add(base.resolve(l.substring(a + 5, l.indexOf('"', a + 5))).toString());
            } else if (!l.isEmpty() && !l.startsWith("#")) {
                parts.add(base.resolve(l).toString());
            }
        }
        job.total = parts.size();
        job.done = 0;
        byte[] buf = new byte[64 * 1024];
        for (String seg : parts) {
            if (job.cancel) return;
            Request req = new Request.Builder().url(seg).header("User-Agent", HttpDownloader.USER_AGENT).build();
            try (Response r = HttpDownloader.get().client().newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) throw new java.io.IOException("HTTP " + r.code());
                try (InputStream in = r.body().byteStream()) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            job.done++;
            fireThrottled();
        }
    }

    /** Runs the bundled ffmpeg with the given arguments (after the common prefix); throws on failure or cancel. */
    private static void runFfmpeg(Job job, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Native.find("ffmpeg.exe").getAbsolutePath(), "-y", "-hide_banner",
                "-loglevel", "error", "-nostdin"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        job.process = p;
        StringBuilder log = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (log.length() < 4000) log.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }, "ffmpeg-log");
        reader.setDaemon(true);
        reader.start();
        try {
            while (!p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (job.cancel) {
                    p.destroyForcibly();
                    throw new InterruptedException();
                }
            }
            reader.join(1000);
            if (p.exitValue() != 0) throw new java.io.IOException("ffmpeg: " + log.toString().trim().replaceAll("\\s+", " "));
        } finally {
            job.process = null;
        }
    }

    /** Joins separately downloaded video and audio with ffmpeg using stream copy (no re-encoding). */
    private static void mux(Job job, Option o) throws Exception {
        File dir = job.file.getParentFile();
        File v = null, a = null;
        try {
            long vs = probeSize(o.video), as = probeSize(o.audio);
            job.done = 0;
            job.total = vs >= 0 && as >= 0 ? vs + as : -1;
            v = File.createTempFile("np-video", ".part", dir);
            a = File.createTempFile("np-audio", ".part", dir);
            fetch(job, o.video, v);
            if (job.cancel) throw new InterruptedException();
            fetch(job, o.audio, a);
            if (job.cancel) throw new InterruptedException();
            List<String> args = new ArrayList<>(List.of("-i", v.getAbsolutePath(), "-i", a.getAbsolutePath(),
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy"));
            if (o.ext.equals("mp4")) args.addAll(List.of("-movflags", "+faststart"));
            args.add(job.file.getAbsolutePath());
            runFfmpeg(job, args);
            job.total = job.done = job.file.length();
        } finally {
            if (v != null) v.delete();
            if (a != null) a.delete();
        }
    }

    public static void remove(Job j) {
        j.cancel();
        JOBS.remove(j);
        saveIndex();
        fire();
    }

    public static void clearFinished() {
        JOBS.removeIf(j -> j.state == State.DONE || j.state == State.FAILED || j.state == State.CANCELED);
        saveIndex();
        fire();
    }

    // ---- persistence of finished downloads -------------------------------------------------

    private static void loadIndex() {
        try {
            if (!INDEX.isFile()) return;
            JsonArray arr = JsonParser.array().from(Files.readString(INDEX.toPath(), StandardCharsets.UTF_8));
            for (Object o : arr) {
                JsonObject jo = (JsonObject) o;
                File f = new File(jo.getString("path", ""));
                if (!f.isFile()) continue;
                Job j = new Job(jo.getString("title", f.getName()), f);
                j.state = State.DONE;
                j.total = j.done = f.length();
                JOBS.add(j);
            }
        } catch (Exception e) {
            System.err.println("downloads index: " + e);
        }
    }

    private static synchronized void saveIndex() {
        try {
            JsonArray arr = new JsonArray();
            for (Job j : JOBS) {
                if (j.state != State.DONE) continue;
                JsonObject o = new JsonObject();
                o.put("title", j.title);
                o.put("path", j.file.getAbsolutePath());
                arr.add(o);
            }
            Files.writeString(INDEX.toPath(), JsonWriter.string(arr), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("downloads index save: " + e);
        }
    }
}
