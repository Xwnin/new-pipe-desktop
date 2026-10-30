package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Store;
import org.newpipe.desktop.net.Images;
import org.newpipe.desktop.util.Fmt;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/** A channel: avatar, subscribe button and the channel's tabs (videos, shorts, playlists ...). */
public class ChannelPage extends ListPage {
    private final Item channel;
    private final JLabel avatar = new JLabel();
    private final JLabel name = new JLabel();
    private final JLabel subs = new JLabel();
    private final JTextArea about = new JTextArea();
    private final JButton subscribe = new JButton("Subscribe");
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
    private ChannelInfo loaded;

    public ChannelPage(Nav nav, Item channel) {
        super(nav, channel.name.isEmpty() ? "Channel" : channel.name);
        this.channel = channel;
        list.setEmptyText("This channel has no content in this tab");

        JPanel head = new JPanel(new BorderLayout(14, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(12, 16, 4, 16));
        avatar.setPreferredSize(new Dimension(72, 72));
        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        name.setFont(Theme.font(20f, Font.BOLD));
        name.setText(channel.name);
        Theme.fg(subs, () -> Theme.sub);
        about.setEditable(false);
        about.setLineWrap(true);
        about.setWrapStyleWord(true);
        about.setOpaque(false);
        Theme.fg(about, () -> Theme.sub);
        about.setRows(2);
        about.setBorder(null);
        name.setAlignmentX(LEFT_ALIGNMENT);
        subs.setAlignmentX(LEFT_ALIGNMENT);
        about.setAlignmentX(LEFT_ALIGNMENT);
        mid.add(Box.createVerticalGlue());
        mid.add(name);
        mid.add(subs);
        mid.add(Box.createVerticalStrut(4));
        mid.add(about);
        mid.add(Box.createVerticalGlue());
        subscribe.putClientProperty("JButton.buttonType", "roundRect");
        subscribe.setFocusable(false);
        subscribe.addActionListener(e -> toggleSubscribe());
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        east.setOpaque(false);
        JButton playAll = new JButton("Play all", Icons.get("play", 18));
        playAll.setFocusable(false);
        playAll.addActionListener(e -> playAll(false));
        east.add(playAll);
        east.add(subscribe);
        head.add(avatar, BorderLayout.WEST);
        head.add(mid, BorderLayout.CENTER);
        head.add(east, BorderLayout.EAST);

        tabs.setOpaque(false);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(head, BorderLayout.CENTER);
        north.add(tabs, BorderLayout.SOUTH);
        top().add(north, BorderLayout.CENTER);
        updateSubscribe();
        setAvatar(channel.thumb);

        Store.onChange(() -> EventQueue.invokeLater(this::updateSubscribe));
        Thread t = new Thread(this::load, "channel-load");
        t.setDaemon(true);
        t.start();
        list.setItems(java.util.List.of());
    }

    private void load() {
        try {
            ChannelInfo ci = Backend.channelInfo(channel.url);
            EventQueue.invokeLater(() -> show(ci));
        } catch (Throwable ex) {
            EventQueue.invokeLater(() -> {
                String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                list.setEmptyText("Could not load channel: " + m.split("\n")[0]);
                list.setItems(java.util.List.of());
            });
        }
    }

    private void show(ChannelInfo ci) {
        loaded = ci;
        channel.name = ci.getName();
        channel.count = ci.getSubscriberCount();
        channel.thumb = Item.bestImage(ci.getAvatars());
        channel.serviceId = ci.getServiceId();
        if (ci.getUrl() != null && !ci.getUrl().isEmpty()) channel.url = ci.getUrl();
        name.setText(ci.getName());
        setTitle(ci.getName());
        subs.setText(ci.getSubscriberCount() >= 0 ? Fmt.compact(ci.getSubscriberCount()) + " subscribers" : "");
        about.setText(ci.getDescription() == null ? "" : ci.getDescription());
        setAvatar(channel.thumb);
        updateSubscribe();
        tabs.removeAll();
        ButtonGroup g = new ButtonGroup();
        boolean first = true;
        ListLinkHandler videos = Backend.videosTab(ci);
        for (ListLinkHandler h : ci.getTabs()) {
            JToggleButton b = new JToggleButton(Backend.tabName(h), h == videos || (videos == null && first));
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.addActionListener(e -> list.setLoader(Backend.channelTab(ci.getServiceId(), h)));
            g.add(b);
            tabs.add(b);
            first = false;
        }
        tabs.revalidate();
        tabs.repaint();
        if (videos != null) list.setLoader(Backend.channelTab(ci.getServiceId(), videos));
        else {
            list.setEmptyText("This channel has no content");
            list.setItems(java.util.List.of());
        }
    }

    private void setAvatar(String url) {
        if (url == null || url.isEmpty()) return;
        BufferedImage img = Images.get(url, 144, 144, true, () -> setAvatar(url));
        if (img != null) avatar.setIcon(new ImageIcon(img.getScaledInstance(72, 72, Image.SCALE_SMOOTH)));
    }

    private void toggleSubscribe() {
        if (Store.isSubscribed(channel.url)) Store.unsubscribe(channel.url);
        else Store.subscribe(channel);
        Backend.invalidateFeed();
        updateSubscribe();
    }

    private void updateSubscribe() {
        boolean sub = Store.isSubscribed(channel.url);
        subscribe.setText(sub ? "Subscribed" : "Subscribe");
        subscribe.setBackground(sub ? null : Theme.accent);
        subscribe.setForeground(sub ? Theme.text : Theme.onAccent);
    }
}
