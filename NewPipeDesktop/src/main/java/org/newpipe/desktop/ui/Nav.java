package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Item;

import java.util.List;

/** Navigation / app level actions available to every page. */
public interface Nav {
    /** Pushes a page onto the navigation stack. */
    void push(Page page);

    void back();

    /** Opens a list entry: streams start playing, channels and playlists open their page. */
    void open(Item item);

    /** Plays the item (replacing the queue) and shows the video page. */
    void play(Item item);

    void playAll(List<Item> items, int start);

    void search(String query);

    void toast(String message);

    void showVideoPage();

    void download(Item item);

    void addToPlaylist(Item item);

    void openUrl(String url);
}
