package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Store;
import org.newpipe.desktop.player.Player;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/** The context menu NewPipe shows when long-pressing a list entry. */
public final class ItemMenu {
    private ItemMenu() {}

    public static JPopupMenu build(Item it, Nav nav, List<JMenuItem> extra) {
        JPopupMenu m = new JPopupMenu();
        switch (it.kind) {
            case STREAM -> {
                add(m, "Play", "play", e -> nav.play(it));
                add(m, "Enqueue", "queue_music", e -> {
                    Player.get().enqueue(it);
                    nav.toast("Added to queue");
                });
                add(m, "Play next", "playlist_play", e -> {
                    Player.get().enqueueNext(it);
                    nav.toast("Will play next");
                });
                add(m, "Add to playlist…", "playlist_add", e -> nav.addToPlaylist(it));
                add(m, "Download…", "download", e -> nav.download(it));
                if (!it.uploaderUrl.isEmpty()) {
                    m.addSeparator();
                    add(m, "Show channel details", "person", e -> nav.open(channelOf(it)));
                }
            }
            case CHANNEL -> {
                add(m, "Open channel", "person", e -> nav.open(it));
                boolean sub = Store.isSubscribed(it.url);
                add(m, sub ? "Unsubscribe" : "Subscribe", "subscriptions", e -> {
                    if (sub) Store.unsubscribe(it.url);
                    else Store.subscribe(it);
                    org.newpipe.desktop.data.Backend.invalidateFeed();
                });
            }
            case PLAYLIST -> {
                add(m, "Open playlist", "playlist_play", e -> nav.open(it));
                boolean bm = Store.isRemoteBookmarked(it.url);
                add(m, bm ? "Remove bookmark" : "Bookmark playlist", "bookmark", e -> Store.toggleRemoteBookmark(it));
            }
        }
        if (extra != null && !extra.isEmpty()) {
            m.addSeparator();
            for (JMenuItem x : extra) m.add(x);
        }
        m.addSeparator();
        add(m, "Copy link", "content_copy", e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(it.url), null);
            nav.toast("Link copied");
        });
        add(m, "Open in browser", "open_in_new", e -> nav.openUrl(it.url));
        return m;
    }

    public static Item channelOf(Item stream) {
        Item c = new Item();
        c.kind = Item.Kind.CHANNEL;
        c.serviceId = stream.serviceId;
        c.url = stream.uploaderUrl;
        c.name = stream.uploader;
        return c;
    }

    public static JMenuItem add(JPopupMenu m, String text, String icon, java.awt.event.ActionListener l) {
        JMenuItem mi = item(text, icon, l);
        m.add(mi);
        return mi;
    }

    public static JMenuItem item(String text, String icon, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(text);
        if (icon != null) mi.setIcon(Icons.get(icon, 18));
        mi.addActionListener(l);
        return mi;
    }
}
