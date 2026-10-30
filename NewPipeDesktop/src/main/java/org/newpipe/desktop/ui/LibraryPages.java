package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Store;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/** Pages backed by the local library: subscriptions, feed, bookmarks and history. */
final class LibraryPages {
    private LibraryPages() {}

    static ListPage subscriptions(Nav nav) {
        ListPage p = new ListPage(nav, "Subscriptions");
        p.itemList().setEmptyText("You have no subscriptions yet. Open a channel and press Subscribe.");
        p.refresh(() -> p.itemList().setItems(Store.subscriptions()));
        p.itemList().setExtraMenu(it -> List.of(ItemMenu.item("Unsubscribe", "close", e -> {
            Store.unsubscribe(it.url);
            Backend.invalidateFeed();
            p.itemList().setItems(Store.subscriptions());
        })));
        return p;
    }

    static ListPage feed(Nav nav) {
        ListPage p = new ListPage(nav, "What's new");
        p.itemList().setEmptyText("Nothing new. Subscribe to some channels first.");
        p.addTool("Refresh", "refresh", () -> {
            Backend.invalidateFeed();
            p.itemList().setLoader(Backend.feed());
        });
        p.refresh(() -> {
            if (p.itemList().items().isEmpty()) p.itemList().setLoader(Backend.feed());
        });
        return p;
    }

    static ListPage bookmarks(Nav nav) {
        ListPage p = new ListPage(nav, "Bookmarked playlists");
        p.itemList().setEmptyText("No playlists yet. Create one, or bookmark a playlist from a channel or search.");
        p.addTool("New playlist", "add", () -> {
            String name = JOptionPane.showInputDialog(p, "Playlist name", "New playlist");
            if (name != null && !name.isBlank()) {
                Store.createPlaylist(name.trim());
                reload(p);
            }
        });
        p.refresh(() -> reload(p));
        p.itemList().setExtraMenu(it -> {
            if (it.url.startsWith("local://")) {
                return List.of(ItemMenu.item("Delete playlist", "delete", e -> {
                    String name = it.url.substring("local://".length());
                    for (Store.Playlist pl : Store.playlists()) if (pl.name.equals(name)) Store.deletePlaylist(pl);
                    reload(p);
                }));
            }
            return List.of();
        });
        return p;
    }

    private static void reload(ListPage p) {
        List<Item> items = new ArrayList<>();
        for (Store.Playlist pl : Store.playlists()) {
            Item i = new Item();
            i.kind = Item.Kind.PLAYLIST;
            i.url = "local://" + pl.name;
            i.name = pl.name;
            i.uploader = "Local playlist";
            i.count = pl.items.size();
            if (!pl.items.isEmpty()) i.thumb = pl.items.get(0).thumb;
            items.add(i);
        }
        items.addAll(Store.remotePlaylists());
        p.itemList().setItems(items);
    }

    static ListPage history(Nav nav) {
        ListPage p = new ListPage(nav, "History");
        p.itemList().setEmptyText("Your watch history is empty.");
        p.refresh(() -> p.itemList().setItems(Store.history()));
        p.addTool("Clear history", "delete", () -> {
            if (JOptionPane.showConfirmDialog(p, "Delete the whole watch history?", "Clear history",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                Store.clearHistory();
                p.itemList().setItems(List.of());
            }
        });
        p.itemList().setExtraMenu(it -> List.of(ItemMenu.item("Remove from history", "delete", e -> {
            Store.removeHistory(it);
            p.itemList().setItems(Store.history());
        })));
        return p;
    }
}
