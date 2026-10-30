package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Item;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** A titled page whose body is an {@link ItemList}, with an optional header and a row of tool buttons. */
public class ListPage extends Page {
    protected final ItemList list;
    private String title;
    private final JPanel top = new JPanel(new BorderLayout());
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
    private Runnable refresher;

    public ListPage(Nav nav, String title) {
        super(nav);
        this.title = title;
        list = new ItemList(nav);
        top.setOpaque(false);
        tools.setOpaque(false);
        top.add(tools, BorderLayout.EAST);
        top.setVisible(false);
        add(top, BorderLayout.NORTH);
        add(list, BorderLayout.CENTER);
    }

    @Override
    public String title() {
        return title;
    }

    protected void setTitle(String t) {
        title = t;
        titleChanged();
    }

    public ItemList itemList() {
        return list;
    }

    protected JPanel top() {
        top.setVisible(true);
        return top;
    }

    public JButton addTool(String text, String icon, Runnable r) {
        JButton b = new JButton(text, icon == null ? null : Icons.get(icon, 18));
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        tools.add(b);
        top.setVisible(true);
        return b;
    }

    public ListPage loader(Backend.Loader l) {
        list.setLoader(l);
        return this;
    }

    public ListPage items(List<Item> items) {
        list.setItems(items);
        return this;
    }

    /** Runs {@code r} every time the page is shown (used by pages backed by local data). */
    public ListPage refresh(Runnable r) {
        refresher = r;
        return this;
    }

    @Override
    public void onShown() {
        if (refresher != null) refresher.run();
    }

    /** Streams currently in the list, in order. */
    public List<Item> streams() {
        return list.items().stream().filter(i -> i.kind == Item.Kind.STREAM).toList();
    }

    public void playAll(boolean shuffle) {
        List<Item> s = new java.util.ArrayList<>(streams());
        if (s.isEmpty()) return;
        if (shuffle) java.util.Collections.shuffle(s);
        nav.playAll(s, 0);
    }
}
