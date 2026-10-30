package org.newpipe.desktop.util;

import org.newpipe.desktop.data.Item;

/** Formatting helpers that follow NewPipe's conventions (1.2M views, 1:02:03, ...). */
public final class Fmt {
    private Fmt() {}

    public static String time(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) seconds = 0;
        long s = (long) seconds;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    public static String compact(long n) {
        if (n < 0) return "";
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return trim(n / 1000.0) + "K";
        if (n < 1_000_000_000L) return trim(n / 1_000_000.0) + "M";
        return trim(n / 1_000_000_000.0) + "B";
    }

    private static String trim(double v) {
        String s = v < 10 ? String.format("%.1f", v) : String.format("%.0f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public static String views(long n) {
        return n < 0 ? "" : compact(n) + " views";
    }

    /** Second line of a list row: "Uploader • 1.2M views • 3 years ago". */
    public static String meta(Item it) {
        StringBuilder sb = new StringBuilder();
        switch (it.kind) {
            case CHANNEL:
                if (it.count >= 0) sb.append(compact(it.count)).append(" subscribers");
                if (!it.uploader.isEmpty()) sb.append(sb.length() > 0 ? " • " : "").append(it.uploader);
                return sb.toString();
            case PLAYLIST:
                if (!it.uploader.isEmpty()) sb.append(it.uploader);
                if (it.count >= 0) sb.append(sb.length() > 0 ? " • " : "").append(it.count).append(" videos");
                return sb.toString();
            default:
                if (!it.uploader.isEmpty()) sb.append(it.uploader);
                if (it.live) sb.append(sb.length() > 0 ? " • " : "").append("LIVE");
                else {
                    if (it.views >= 0) sb.append(sb.length() > 0 ? " • " : "").append(views(it.views));
                    if (!it.dateText.isEmpty()) sb.append(sb.length() > 0 ? " • " : "").append(date(it.dateText));
                }
                return sb.toString();
        }
    }

    /** Turns ISO-8601 timestamps (SoundCloud, PeerTube) into a readable date; other text is returned as is. */
    public static String date(String s) {
        if (s == null || s.length() < 10 || !Character.isDigit(s.charAt(0))) return s == null ? "" : s;
        try {
            java.time.OffsetDateTime d = s.length() > 10 ? java.time.OffsetDateTime.parse(s)
                    : java.time.LocalDate.parse(s).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
            return java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).format(d);
        } catch (Exception e) {
            return s;
        }
    }

    public static String size(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        if (v < 1024) return String.format("%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format("%.1f MB", v);
        return String.format("%.2f GB", v / 1024);
    }

    public static String safeFileName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (r.length() > 120) r = r.substring(0, 120).trim();
        return r.isEmpty() ? "video" : r;
    }
}
