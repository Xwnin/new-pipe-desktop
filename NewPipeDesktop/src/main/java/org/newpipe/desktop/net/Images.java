package org.newpipe.desktop.net;

import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous thumbnail loader with an in-memory LRU cache. Results are cover-cropped to the requested size. */
public final class Images {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "image-loader");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > 500;
        }
    };
    private static final Set<String> PENDING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();

    private Images() {}

    /**
     * Returns the image if cached, otherwise null and schedules a load; {@code onLoaded} is invoked on the
     * Swing thread when the image becomes available.
     */
    public static BufferedImage get(String url, int w, int h, boolean circle, Runnable onLoaded) {
        if (url == null || url.isEmpty() || w <= 0 || h <= 0) return null;
        String key = url + "|" + w + "x" + h + (circle ? "c" : "");
        synchronized (CACHE) {
            BufferedImage img = CACHE.get(key);
            if (img != null) return img;
            if (FAILED.contains(url) || !PENDING.add(key)) return null;
        }
        POOL.execute(() -> {
            BufferedImage out = null;
            for (String candidate : candidates(url)) {
                try {
                    Request req = new Request.Builder().url(candidate).header("User-Agent", HttpDownloader.USER_AGENT).build();
                    try (Response resp = HttpDownloader.get().client().newCall(req).execute()) {
                        if (resp.isSuccessful() && resp.body() != null) {
                            BufferedImage src = ImageIO.read(new ByteArrayInputStream(resp.body().bytes()));
                            if (src != null) {
                                out = scale(src, w, h, circle);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            synchronized (CACHE) {
                PENDING.remove(key);
                if (out != null) CACHE.put(key, out);
                else FAILED.add(url);
            }
            if (out != null && onLoaded != null) EventQueue.invokeLater(onLoaded);
        });
        return null;
    }

    /** YouTube serves WebP (which ImageIO cannot read) for URLs carrying crop hints; plain URLs give JPEG. */
    private static java.util.List<String> candidates(String url) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String u = url;
        if (u.contains("ytimg.com") && u.contains("?")) u = u.substring(0, u.indexOf('?'));
        if (u.contains("-rw")) u = u.replace("-rw", "-rj");
        out.add(u);
        if (u.contains("/hq720.jpg")) out.add(u.replace("/hq720.jpg", "/hqdefault.jpg"));
        if (u.contains("/maxresdefault.jpg")) out.add(u.replace("/maxresdefault.jpg", "/hqdefault.jpg"));
        if (!u.equals(url)) out.add(url);
        return out;
    }

    private static BufferedImage scale(BufferedImage src, int w, int h, boolean circle) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (circle) g.setClip(new Ellipse2D.Float(0, 0, w, h));
        double sc = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
        int dw = (int) Math.ceil(src.getWidth() * sc), dh = (int) Math.ceil(src.getHeight() * sc);
        // two-step downscale keeps thumbnails crisp
        BufferedImage cur = src;
        while (cur.getWidth() > dw * 2 && cur.getHeight() > dh * 2) {
            BufferedImage half = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null);
            hg.dispose();
            cur = half;
        }
        g.drawImage(cur, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        return out;
    }
}
