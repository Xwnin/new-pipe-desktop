package org.newpipe.desktop.data;

import com.grack.nanojson.JsonObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

/** A list entry (video / channel / playlist) in a serialisable, extractor independent shape. */
public final class Item {
    public enum Kind { STREAM, CHANNEL, PLAYLIST }

    public Kind kind = Kind.STREAM;
    public int serviceId;
    public String url = "";
    public String name = "";
    public String uploader = "";
    public String uploaderUrl = "";
    public String thumb = "";
    public String dateText = "";
    public long duration = -1;
    public long views = -1;
    /** subscribers for channels, stream count for playlists */
    public long count = -1;
    public boolean live;
    public String description = "";
    /** upload time in epoch millis, or 0 when unknown */
    public long time;

    public static String bestImage(List<Image> images) {
        if (images == null || images.isEmpty()) return "";
        Image best = null;
        for (Image i : images) {
            if (best == null) {
                best = i;
                continue;
            }
            // prefer something around 320-720 px wide; fall back to the largest
            int w = i.getWidth() == Image.WIDTH_UNKNOWN ? 0 : i.getWidth();
            int bw = best.getWidth() == Image.WIDTH_UNKNOWN ? 0 : best.getWidth();
            boolean iOk = w >= 300, bOk = bw >= 300;
            if (iOk && !bOk) best = i;
            else if (iOk && bOk && w < bw && w <= 800) best = i;
            else if (!iOk && !bOk && w > bw) best = i;
        }
        return best.getUrl();
    }

    public static Item from(InfoItem i) {
        Item it = new Item();
        it.serviceId = i.getServiceId();
        it.url = i.getUrl();
        it.name = i.getName() == null ? "" : i.getName();
        it.thumb = bestImage(i.getThumbnails());
        if (i instanceof StreamInfoItem s) {
            it.kind = Kind.STREAM;
            it.uploader = nz(s.getUploaderName());
            it.uploaderUrl = nz(s.getUploaderUrl());
            it.duration = s.getDuration();
            it.views = s.getViewCount();
            it.dateText = nz(s.getTextualUploadDate());
            if (s.getUploadDate() != null) it.time = s.getUploadDate().offsetDateTime().toInstant().toEpochMilli();
            it.live = s.getStreamType() == StreamType.LIVE_STREAM || s.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
        } else if (i instanceof ChannelInfoItem c) {
            it.kind = Kind.CHANNEL;
            it.count = c.getSubscriberCount();
            it.description = nz(c.getDescription());
            it.uploader = c.getStreamCount() >= 0 ? c.getStreamCount() + " videos" : "";
        } else if (i instanceof PlaylistInfoItem p) {
            it.kind = Kind.PLAYLIST;
            it.uploader = nz(p.getUploaderName());
            it.count = p.getStreamCount();
        }
        return it;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public com.grack.nanojson.JsonObject toJson() {
        return com.grack.nanojson.JsonObject.builder()
                .value("kind", kind.name()).value("service", serviceId).value("url", url)
                .value("name", name).value("uploader", uploader).value("uploaderUrl", uploaderUrl)
                .value("thumb", thumb).value("date", dateText).value("duration", duration)
                .value("views", views).value("count", count).value("live", live)
                .value("description", description).value("time", time)
                .done();
    }

    public static Item fromJson(JsonObject o) {
        Item it = new Item();
        try {
            it.kind = Kind.valueOf(o.getString("kind", "STREAM"));
        } catch (IllegalArgumentException e) {
            it.kind = Kind.STREAM;
        }
        it.serviceId = o.getInt("service", 0);
        it.url = o.getString("url", "");
        it.name = o.getString("name", "");
        it.uploader = o.getString("uploader", "");
        it.uploaderUrl = o.getString("uploaderUrl", "");
        it.thumb = o.getString("thumb", "");
        it.dateText = o.getString("date", "");
        it.duration = o.getLong("duration", -1);
        it.views = o.getLong("views", -1);
        it.count = o.getLong("count", -1);
        it.live = o.getBoolean("live", false);
        it.description = o.getString("description", "");
        it.time = o.getLong("time", 0);
        return it;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Item other && other.url.equals(url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }
}
