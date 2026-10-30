package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Store;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** A remote playlist (loaded from the service) or one of the user's local playlists. */
public class PlaylistPage extends ListPage {
    private final JLabel heading = new JLabel();
    private final JLabel sub = new JLabel();

    /** Remote playlist. */
    public PlaylistPage(Nav nav, Item playlist) {
        super(nav, playlist.name.isEmpty() ? "Playlist" : playlist.name);
        header(playlist.name, playlist.uploader);
        list.setEmptyText("This playlist is empty");
        addTool("Play all", "play", () -> playAll(false));
        addTool("Shuffle", "shuffle", () -> playAll(true));
        JButton bm = addTool("", "bookmark", () -> {
            Store.toggleRemoteBookmark(playlist);
            nav.toast(Store.isRemoteBookmarked(playlist.url) ? "Playlist bookmarked" : "Bookmark removed");
        });
        bm.setToolTipText("Bookmark playlist");
        list.setLoader(new Backend.Loader() {
            int sid;

            @Override
            public Backend.Result first() throws Exception {
                PlaylistInfo pi = PlaylistInfo.getInfo(playlist.url);
                sid = pi.getServiceId();
                EventQueue.invokeLater(() -> header(pi.getName(),
                        (pi.getUploaderName() == null ? "" : pi.getUploaderName())
                                + (pi.getStreamCount() >= 0 ? "  •  " + pi.getStreamCount() + " videos" : "")));
                List<Item> items = new java.util.ArrayList<>();
                for (var i : pi.getRelatedItems()) items.add(Item.from(i));
                return new Backend.Result(items, pi.hasNextPage() ? pi.getNextPage() : null);
            }

            @Override
            public Backend.Result more(Object next) throws Exception {
                var p = PlaylistInfo.getMoreItems(Backend.service(sid), playlist.url, (Page) next);
                List<Item> items = new java.util.ArrayList<>();
                for (var i : p.getItems()) items.add(Item.from(i));
                return new Backend.Result(items, p.hasNextPage() ? p.getNextPage() : null);
            }
        });
    }

    /** Local playlist. */
    public PlaylistPage(Nav nav, Store.Playlist playlist) {
        super(nav, playlist.name);
        header(playlist.name, playlist.items.size() + " videos");
        list.setEmptyText("This playlist is empty. Use “Add to playlist” on any video.");
        addTool("Play all", "play", () -> playAll(false));
        addTool("Shuffle", "shuffle", () -> playAll(true));
        addTool("Rename", "edit", () -> {
            String n = JOptionPane.showInputDialog(this, "Playlist name", playlist.name);
            if (n != null && !n.isBlank()) {
                Store.renamePlaylist(playlist, n.trim());
                heading.setText(n.trim());
            }
        });
        addTool("Delete", "delete", () -> {
            if (JOptionPane.showConfirmDialog(this, "Delete the playlist “" + playlist.name + "”?", "Delete playlist",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                Store.deletePlaylist(playlist);
                nav.back();
            }
        });
        list.setExtraMenu(it -> List.of(ItemMenu.item("Remove from playlist", "delete", e -> {
            Store.removeFromPlaylist(playlist, it);
            list.setItems(new java.util.ArrayList<>(playlist.items));
            sub.setText(playlist.items.size() + " videos");
        })));
        refresh(() -> list.setItems(new java.util.ArrayList<>(playlist.items)));
        list.setItems(new java.util.ArrayList<>(playlist.items));
    }

    private void header(String name, String info) {
        if (heading.getParent() == null) {
            JPanel p = new JPanel();
            p.setOpaque(false);
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(BorderFactory.createEmptyBorder(8, 16, 4, 8));
            heading.setFont(Theme.font(18f, Font.BOLD));
            Theme.fg(sub, () -> Theme.sub);
            p.add(heading);
            p.add(sub);
            top().add(p, BorderLayout.WEST);
        }
        heading.setText(name == null ? "" : name);
        if (name != null && !name.isBlank()) setTitle(name);
        sub.setText(info == null ? "" : info);
    }
}
