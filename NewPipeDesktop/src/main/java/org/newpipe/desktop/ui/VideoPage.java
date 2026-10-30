package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Store;
import org.newpipe.desktop.net.Images;
import org.newpipe.desktop.player.Player;
import org.newpipe.desktop.util.Fmt;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The stream detail page: video surface, controls, uploader, actions, description, related items and queue. */
public class VideoPage extends Page implements PlayerControls.Host {
    private static final int CONTROLS_H = 100;
    private static final int SIDE_W = 360;

    private final Player player = Player.get();
    private final Canvas canvas = new Canvas();
    private final ArtPanel art = new ArtPanel();
    private final CardLayout surfaceCards = new CardLayout();
    private final JPanel surface = new JPanel(surfaceCards);
    private final PlayerControls controls;

    private final ScrollablePanel info = new ScrollablePanel();
    private final JScrollPane infoScroll = new JScrollPane(info);
    private final JTextArea title = new JTextArea();
    private final JLabel avatar = new JLabel();
    private final JLabel uploader = new JLabel();
    private final JLabel subscribers = new JLabel();
    private final JButton subscribe = new JButton();
    private final JLabel stats = new JLabel();
    private final JTextArea description = new JTextArea();
    private final JButton bgButton = new JButton("Background", Icons.get("headphones", 18));
    private final JButton popupButton = new JButton("Popup", Icons.get("pip", 18));

    private final JTabbedPane side = new JTabbedPane();
    private final ItemList related;
    private final ItemList queue;
    private final CommentsPanel comments = new CommentsPanel();
    private String commentsUrl = "";

    private boolean fullscreen;
    private Consumer<Boolean> fullscreenHandler = b -> { };
    private PopupWindow popup;
    private Item shownItem;
    private StreamInfo shownInfo;

    public VideoPage(Nav nav) {
        super(nav);
        setLayout(null);
        controls = new PlayerControls(this, false);
        related = new ItemList(nav);
        related.setEmptyText("No related videos");
        queue = new ItemList(nav);
        queue.setEmptyText("The queue is empty");
        queue.setOnOpen(it -> {
            int i = player.queue().indexOf(it);
            if (i >= 0) player.jumpTo(i);
        });
        queue.setExtraMenu(it -> List.of(ItemMenu.item("Remove from queue", "delete", e -> {
            int i = player.queue().indexOf(it);
            if (i >= 0 && i != player.index()) player.removeFromQueue(i);
        })));

        player.setDoubleClickHandler(() -> {
            if (popup != null) popup.dispose();
            else toggleFullscreen();
        });
        canvas.setBackground(Color.BLACK);
        canvas.addMouseListener(videoClicks());
        surface.add(canvas, "video");
        surface.add(art, "art");
        surface.setBackground(Color.BLACK);

        buildInfo();
        side.addTab("Related", related);
        side.addTab("Queue", queue);
        side.addTab("Comments", comments);
        side.addChangeListener(e -> {
            if (side.getSelectedComponent() == comments) comments.ensureLoaded();
        });

        add(surface);
        add(controls);
        add(infoScroll);
        add(side);

        player.addListener(new Player.Listener() {
            @Override
            public void onTrack() {
                update();
            }

            @Override
            public void onState() {
                updateSurface();
            }

            @Override
            public void onParams() {
                updateSurface();
                updateButtons();
            }
        });
        update();
    }

    @Override
    public String title() {
        return "";
    }

    public void setFullscreenHandler(Consumer<Boolean> h) {
        fullscreenHandler = h;
    }

    private java.awt.event.MouseListener videoClicks() {
        return new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) player.onVideoClick(e.getClickCount());
            }
        };
    }

    /** Selects one of the side tabs (0 = related, 1 = queue, 2 = comments). */
    public void showSideTab(int i) {
        if (i >= 0 && i < side.getTabCount()) side.setSelectedIndex(i);
    }

    public void bindPlayer() {
        if (popup == null) player.attach(canvas);
    }

    @Override
    public void onShown() {
        bindPlayer();
        updateSurface();
    }

    // ---- Host --------------------------------------------------------------------------------

    @Override
    public void toggleFullscreen() {
        fullscreenHandler.accept(!fullscreen);
    }

    public void setFullscreen(boolean f) {
        fullscreen = f;
        controls.refreshAll();
        revalidate();
        repaint();
    }

    @Override
    public boolean isFullscreen() {
        return fullscreen;
    }

    @Override
    public void togglePopup() {
        if (popup != null) {
            popup.dispose();
            return;
        }
        if (fullscreen) toggleFullscreen();
        popup = new PopupWindow();
        popup.setVisible(true);
        EventQueue.invokeLater(() -> {
            player.setPopup(true);
            player.attach(popup.canvas);
            updateSurface();
            updateButtons();
        });
        nav.toast("Playing in popup mode");
    }

    /** Native window handle of the video canvas (diagnostics). */
    public long nativeHandle() {
        return canvas.isDisplayable() ? com.sun.jna.Native.getComponentID(canvas) : 0;
    }

    public Canvas canvasForDebug() {
        return canvas;
    }

    public boolean popupActive() {
        return popup != null;
    }

    // ---- layout ------------------------------------------------------------------------------

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        if (fullscreen) {
            surface.setBounds(0, 0, w, h - CONTROLS_H);
            controls.setBounds(0, h - CONTROLS_H, w, CONTROLS_H);
            infoScroll.setVisible(false);
            side.setVisible(false);
            return;
        }
        infoScroll.setVisible(true);
        side.setVisible(true);
        int sideW = w >= 1000 ? SIDE_W : 0;
        side.setVisible(sideW > 0);
        int leftW = w - sideW;
        int vh = Math.min(leftW * 9 / 16, Math.max(180, h - CONTROLS_H - 150));
        surface.setBounds(0, 0, leftW, vh);
        controls.setBounds(0, vh, leftW, CONTROLS_H);
        infoScroll.setBounds(0, vh + CONTROLS_H, leftW, Math.max(0, h - vh - CONTROLS_H));
        side.setBounds(leftW, 0, sideW, h);
    }

    // ---- content -----------------------------------------------------------------------------

    private void buildInfo() {
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
        infoScroll.setBorder(BorderFactory.createEmptyBorder());
        infoScroll.getVerticalScrollBar().setUnitIncrement(16);
        infoScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        title.setEditable(false);
        title.setLineWrap(true);
        title.setWrapStyleWord(true);
        title.setOpaque(false);
        title.setFont(Theme.font(18f, Font.BOLD));
        title.setBorder(null);
        title.setAlignmentX(LEFT_ALIGNMENT);
        info.add(title);
        info.add(Box.createVerticalStrut(6));

        Theme.fg(stats, () -> Theme.sub);
        stats.setAlignmentX(LEFT_ALIGNMENT);
        info.add(stats);
        info.add(Box.createVerticalStrut(10));

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        avatar.setPreferredSize(new Dimension(44, 44));
        avatar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        uploader.setFont(Theme.font(14f, Font.BOLD));
        Theme.fg(subscribers, () -> Theme.sub);
        subscribers.setFont(Theme.font(12f, Font.PLAIN));
        names.add(Box.createVerticalGlue());
        names.add(uploader);
        names.add(subscribers);
        names.add(Box.createVerticalGlue());
        names.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter open = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (shownItem != null && !shownItem.uploaderUrl.isEmpty()) nav.open(ItemMenu.channelOf(shownItem));
            }
        };
        avatar.addMouseListener(open);
        names.addMouseListener(open);
        subscribe.putClientProperty("JButton.buttonType", "roundRect");
        subscribe.addActionListener(e -> toggleSubscribe());
        row.add(avatar, BorderLayout.WEST);
        row.add(names, BorderLayout.CENTER);
        row.add(subscribe, BorderLayout.EAST);
        info.add(row);
        info.add(Box.createVerticalStrut(10));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        JButton addTo = new JButton("Add to", Icons.get("playlist_add", 18));
        JButton download = new JButton("Download", Icons.get("download", 18));
        JButton share = new JButton("Open in browser", Icons.get("open_in_new", 18));
        for (JButton b : new JButton[]{addTo, bgButton, popupButton, download, share}) {
            b.setFocusable(false);
            actions.add(b);
        }
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        addTo.addActionListener(e -> {
            if (shownItem != null) nav.addToPlaylist(shownItem);
        });
        bgButton.addActionListener(e -> player.setAudioOnly(!player.isAudioOnly()));
        popupButton.addActionListener(e -> togglePopup());
        download.addActionListener(e -> {
            if (shownItem != null) nav.download(shownItem);
        });
        share.addActionListener(e -> {
            if (shownItem != null) nav.openUrl(shownItem.url);
        });
        info.add(actions);
        info.add(Box.createVerticalStrut(12));

        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setBorder(null);
        description.setFont(Theme.font(13f, Font.PLAIN));
        description.setAlignmentX(LEFT_ALIGNMENT);
        info.add(description);
    }

    private void toggleSubscribe() {
        if (shownItem == null || shownItem.uploaderUrl.isEmpty()) return;
        if (Store.isSubscribed(shownItem.uploaderUrl)) {
            Store.unsubscribe(shownItem.uploaderUrl);
        } else {
            Item c = ItemMenu.channelOf(shownItem);
            if (shownInfo != null) {
                c.thumb = Item.bestImage(shownInfo.getUploaderAvatars());
                c.count = shownInfo.getUploaderSubscriberCount();
            }
            Store.subscribe(c);
        }
        org.newpipe.desktop.data.Backend.invalidateFeed();
        updateSubscribeButton();
    }

    private void updateSubscribeButton() {
        boolean sub = shownItem != null && Store.isSubscribed(shownItem.uploaderUrl);
        subscribe.setText(sub ? "Subscribed" : "Subscribe");
        subscribe.setVisible(shownItem != null && !shownItem.uploaderUrl.isEmpty());
        if (sub) {
            subscribe.setBackground(null);
            Theme.fg(subscribe, () -> Theme.text);
        } else {
            subscribe.setBackground(Theme.accent);
            subscribe.setForeground(Theme.onAccent);
        }
    }

    private void updateButtons() {
        boolean bg = player.isAudioOnly();
        bgButton.setIcon(Icons.get("headphones", 18, bg ? Theme.accent : Theme.text));
        popupButton.setIcon(Icons.get("pip", 18, popup != null ? Theme.accent : Theme.text));
    }

    private void update() {
        Item it = player.current();
        StreamInfo si = player.info();
        boolean newItem = it != shownItem || si != shownInfo;
        shownItem = it;
        shownInfo = si;
        if (newItem) {
            if (it == null) {
                title.setText("Nothing playing");
                stats.setText("");
                uploader.setText("");
                subscribers.setText("");
                avatar.setIcon(null);
                description.setText("");
                subscribe.setVisible(false);
                related.setItems(List.of());
            } else {
                title.setText(it.name);
                uploader.setText(it.uploader);
                StringBuilder sb = new StringBuilder();
                if (it.views >= 0) sb.append(Fmt.views(it.views));
                if (si != null) {
                    if (si.getLikeCount() >= 0) sb.append(sb.length() > 0 ? "  •  " : "").append(Fmt.compact(si.getLikeCount())).append(" likes");
                    String d = si.getTextualUploadDate();
                    if (si.getUploadDate() != null) {
                        d = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                                .format(si.getUploadDate().offsetDateTime());
                    }
                    if (d != null) d = Fmt.date(d);
                    if (d != null && !d.isBlank()) sb.append(sb.length() > 0 ? "  •  " : "").append(d);
                }
                stats.setText(sb.toString());
                subscribers.setText(si != null && si.getUploaderSubscriberCount() >= 0
                        ? Fmt.compact(si.getUploaderSubscriberCount()) + " subscribers" : "");
                description.setText(si == null ? "" : plain(si.getDescription()));
                description.setCaretPosition(0);
                String av = si == null ? "" : Item.bestImage(si.getUploaderAvatars());
                setAvatar(av);
                updateSubscribeButton();
                if (si != null) {
                    List<Item> rel = new ArrayList<>();
                    for (InfoItem r : si.getRelatedItems()) {
                        try {
                            rel.add(Item.from(r));
                        } catch (RuntimeException ignored) {
                        }
                    }
                    related.setItems(rel);
                } else {
                    related.setItems(List.of());
                }
            }
        }
        String curUrl = it == null ? "" : it.url;
        if (!curUrl.equals(commentsUrl)) {
            commentsUrl = curUrl;
            comments.reset(curUrl.isEmpty() ? null : curUrl);
        }
        if (side.getSelectedComponent() == comments) comments.ensureLoaded();
        queue.setItems(new ArrayList<>(player.queue()));
        if (player.index() >= 0) queue.jlist().setSelectedIndex(player.index());
        side.setTitleAt(1, "Queue (" + player.queue().size() + ")");
        updateSurface();
        updateButtons();
        controls.refreshAll();
        info.revalidate();
    }

    private void setAvatar(String url) {
        if (url == null || url.isEmpty()) {
            avatar.setIcon(null);
            return;
        }
        BufferedImage img = Images.get(url, 88, 88, true, () -> setAvatar(url));
        avatar.setIcon(img == null ? null : new ImageIcon(img.getScaledInstance(44, 44, Image.SCALE_SMOOTH)));
    }

    private static String plain(Description d) {
        if (d == null || d.getContent() == null) return "";
        String s = d.getContent();
        return (d.getType() == Description.HTML ? org.newpipe.desktop.util.Html.toPlain(s) : s).trim();
    }

    private void updateSurface() {
        boolean showArt = popup != null || player.current() == null || player.isLoading() || player.error() != null
                || player.isAudioOnly() || (player.info() != null && !player.hasVideo());
        art.setState(player.current(), statusText());
        surfaceCards.show(surface, showArt ? "art" : "video");
        if (!showArt) bindPlayer();
    }

    private String statusText() {
        if (popup != null) return "Playing in popup mode";
        if (player.current() == null) return "Search for something to play";
        if (player.error() != null) return player.error();
        if (player.isLoading()) return "Loading…";
        if (player.isAudioOnly()) return "Playing audio only";
        return "";
    }

    // ---- artwork / status panel --------------------------------------------------------------

    private final class ArtPanel extends JPanel {
        private Item item;
        private String text = "";

        void setState(Item it, String t) {
            item = it;
            text = t;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            if (item != null && !item.thumb.isEmpty()) {
                BufferedImage img = Images.get(item.thumb, 1280, 720, false, this::repaint);
                if (img != null) {
                    double sc = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight());
                    int dw = (int) (img.getWidth() * sc), dh = (int) (img.getHeight() * sc);
                    g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                }
            }
            if (!text.isEmpty()) {
                g.setColor(new Color(0, 0, 0, 150));
                g.fillRect(0, 0, w, h);
                g.setColor(Color.WHITE);
                g.setFont(Theme.font(16f, Font.BOLD));
                FontMetrics fm = g.getFontMetrics();
                String t = text;
                g.drawString(t, (w - fm.stringWidth(t)) / 2, h / 2 + fm.getAscent() / 2);
            }
            g.dispose();
        }
    }

    // ---- popup player ------------------------------------------------------------------------

    private final class PopupWindow extends JFrame implements PlayerControls.Host {
        final Canvas canvas = new Canvas();

        PopupWindow() {
            super("NewPipe");
            setIconImages(Icons.appIcons());
            setAlwaysOnTop(true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            canvas.setBackground(Color.BLACK);
            canvas.addMouseListener(videoClicks());
            PlayerControls pc = new PlayerControls(this, true);
            JPanel root = new JPanel(new BorderLayout());
            root.add(canvas, BorderLayout.CENTER);
            root.add(pc, BorderLayout.SOUTH);
            setContentPane(root);
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            setSize(480, 330);
            setLocation(screen.width - 500, screen.height - 400);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    popup = null;
                    player.setPopup(false);
                    player.attach(VideoPage.this.canvas);
                    updateSurface();
                    updateButtons();
                    controls.refreshAll();
                }
            });
        }

        @Override
        public void toggleFullscreen() {
            dispose();
            nav.showVideoPage();
        }

        @Override
        public void togglePopup() {
            dispose();
        }

        @Override
        public boolean isFullscreen() {
            return false;
        }
    }

    /** Panel whose width follows the scroll pane so wrapped text lays out correctly. */
    static final class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
