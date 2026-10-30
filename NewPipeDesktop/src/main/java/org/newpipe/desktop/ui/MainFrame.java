package org.newpipe.desktop.ui;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.data.Store;
import org.newpipe.desktop.player.Player;
import org.schabi.newpipe.extractor.StreamingService;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The application window: toolbar, drawer, page stack, mini player and global shortcuts. */
public final class MainFrame extends JFrame implements Nav {
    private final Player player = Player.get();

    private final JPanel content = new JPanel(new CardLayout());
    private final Deque<Page> stack = new ArrayDeque<>();
    private Page current;
    private int pageCounter;
    private final Map<Page, String> cardNames = new LinkedHashMap<>();

    private final VideoPage videoPage;
    private final Map<String, Page> roots = new LinkedHashMap<>();
    private String rootKey = "trending";

    private final Toolbar toolbar = new Toolbar();
    private final Drawer drawer = new Drawer();
    private final MiniPlayer mini = new MiniPlayer();
    private final JLabel toast = new JLabel(" ", SwingConstants.CENTER);
    private final JPanel bottom = new JPanel(new BorderLayout());
    private final javax.swing.Timer toastTimer = new javax.swing.Timer(3500, e -> hideToast());

    private boolean fullscreen;
    private int stateBeforeMinimize = NORMAL;
    private int paletteService = Settings.serviceId();
    private Rectangle savedBounds;
    private int savedStyle;
    private int savedState;
    private int savedDecoration = JRootPane.FRAME;

    public MainFrame() {
        super("NewPipe");
        setIconImages(Icons.appIcons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 640));
        restoreBounds();
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

        videoPage = new VideoPage(this);
        videoPage.setFullscreenHandler(this::setFullscreen);

        JPanel root = new JPanel(new BorderLayout());
        setContentPane(root);
        root.add(toolbar, BorderLayout.NORTH);
        root.add(drawer, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);

        toast.setOpaque(true);
        toast.setForeground(Color.WHITE);
        toast.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        toast.setBackground(new Color(0x323232));
        toast.setVisible(false);
        bottom.add(toast, BorderLayout.NORTH);
        bottom.add(mini, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        content.add(videoPage, "video");

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                saveBounds();
                player.shutdown();
                dispose();
                System.exit(0);
            }
        });

        // While the window is minimized, keep the tempo / pitch controls reachable in a floating window.
        addWindowStateListener(e -> {
            boolean minimized = (e.getNewState() & ICONIFIED) != 0, wasMinimized = (e.getOldState() & ICONIFIED) != 0;
            if (minimized && !wasMinimized) {
                stateBeforeMinimize = e.getOldState();
                if (Settings.bool("floatingControls", true) && player.current() != null) {
                    PlaybackParamsDialog.showFloating(() -> {
                        setExtendedState(stateBeforeMinimize);
                        toFront();
                    });
                }
            } else if (!minimized && wasMinimized) {
                PlaybackParamsDialog.hideFloating();
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::dispatchKey);
        Settings.onChange(() -> EventQueue.invokeLater(() -> {
            if (Settings.serviceId() != paletteService) {
                paletteService = Settings.serviceId();
                Theme.apply(Settings.theme());
                toolbar.update();
            }
            drawer.refreshService();
            drawer.repaint();
        }));
        Store.onChange(() -> EventQueue.invokeLater(() -> { }));
        player.addListener(new Player.Listener() {
            @Override
            public void onTrack() {
                updateMini();
            }

            @Override
            public void onState() {
                mini.refresh();
            }

            @Override
            public void onProgress(double pos, double dur) {
                mini.progress(pos, dur);
            }

            @Override
            public void onParams() {
                mini.refresh();
            }

            @Override
            public void onMessage(String message) {
                toast(message);
            }
        });
        goRoot("trending");
    }

    // ---- window state ----------------------------------------------------------------------

    private void restoreBounds() {
        Rectangle b = new Rectangle((int) Settings.num("win.x", Integer.MIN_VALUE), (int) Settings.num("win.y", 0),
                (int) Settings.num("win.w", 1320), (int) Settings.num("win.h", 820));
        boolean visible = false;
        if (b.x != Integer.MIN_VALUE) {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle s = d.getDefaultConfiguration().getBounds();
                Rectangle inter = s.intersection(b);
                if (inter.width >= 200 && inter.height >= 100) visible = true;
            }
        }
        if (visible) {
            setBounds(b.x, b.y, Math.max(b.width, 1000), Math.max(b.height, 640));
        } else {
            setSize(1320, 820);
            setLocationRelativeTo(null);
        }
        if (Settings.bool("win.max", false)) setExtendedState(MAXIMIZED_BOTH);
    }

    private void saveBounds() {
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        Settings.put("win.max", max);
        if (!max && !fullscreen && getExtendedState() == NORMAL) {
            Rectangle b = getBounds();
            Settings.put("win.x", b.x);
            Settings.put("win.y", b.y);
            Settings.put("win.w", b.width);
            Settings.put("win.h", b.height);
        }
    }

    // ---- pages -----------------------------------------------------------------------------

    private Page rootPage(String key) {
        return roots.computeIfAbsent(key, k -> switch (k) {
            case "trending" -> new TrendingPage(this);
            case "subscriptions" -> LibraryPages.subscriptions(this);
            case "feed" -> LibraryPages.feed(this);
            case "bookmarks" -> LibraryPages.bookmarks(this);
            case "history" -> LibraryPages.history(this);
            case "downloads" -> new DownloadsPage(this);
            case "settings" -> new SettingsPage(this);
            default -> new AboutPage(this);
        });
    }

    private void goRoot(String key) {
        rootKey = key;
        Page p = rootPage(key);
        while (!stack.isEmpty()) discardIfTransient(stack.pop());
        if (current != null && current != p) discardIfTransient(current);
        current = null;
        display(p);
        drawer.repaint();
    }

    private boolean persistent(Page p) {
        return p == videoPage || roots.containsValue(p);
    }

    private void discardIfTransient(Page p) {
        if (p == null || persistent(p)) return;
        p.onDiscard();
        String name = cardNames.remove(p);
        if (name != null) content.remove(p);
    }

    private void display(Page p) {
        String name = p == videoPage ? "video" : cardNames.get(p);
        if (name == null) {
            name = "p" + (pageCounter++);
            cardNames.put(p, name);
            content.add(p, name);
        }
        current = p;
        p.setTitleListener(toolbar::update);
        ((CardLayout) content.getLayout()).show(content, name);
        p.onShown();
        toolbar.update();
        updateMini();
        content.revalidate();
    }

    @Override
    public void push(Page page) {
        if (current != null && current != page) stack.push(current);
        display(page);
    }

    @Override
    public void back() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (stack.isEmpty()) return;
        Page leaving = current;
        Page prev = stack.pop();
        display(prev);
        discardIfTransient(leaving);
    }

    @Override
    public void showVideoPage() {
        if (current == videoPage) return;
        stack.remove(videoPage);
        push(videoPage);
    }

    @Override
    public void open(Item item) {
        switch (item.kind) {
            case STREAM -> play(item);
            case CHANNEL -> push(new ChannelPage(this, item));
            case PLAYLIST -> {
                if (item.url.startsWith("local://")) {
                    String name = item.url.substring("local://".length());
                    for (Store.Playlist p : Store.playlists()) if (p.name.equals(name)) push(new PlaylistPage(this, p));
                } else {
                    push(new PlaylistPage(this, item));
                }
            }
        }
    }

    @Override
    public void play(Item item) {
        showVideoPage();
        player.playNow(item);
    }

    @Override
    public void playAll(List<Item> items, int start) {
        showVideoPage();
        player.playQueue(items, start);
    }

    @Override
    public void search(String query) {
        query = query.trim();
        if (query.isEmpty()) return;
        // pasted links open directly, like NewPipe
        if (query.startsWith("http://") || query.startsWith("https://")) {
            try {
                var service = org.schabi.newpipe.extractor.NewPipe.getServiceByUrl(query);
                if (service != null) {
                    var lt = service.getLinkTypeByUrl(query);
                    Item it = new Item();
                    it.url = query;
                    it.serviceId = service.getServiceId();
                    switch (lt) {
                        case STREAM -> {
                            it.kind = Item.Kind.STREAM;
                            play(it);
                            return;
                        }
                        case CHANNEL -> {
                            it.kind = Item.Kind.CHANNEL;
                            push(new ChannelPage(this, it));
                            return;
                        }
                        case PLAYLIST -> {
                            it.kind = Item.Kind.PLAYLIST;
                            push(new PlaylistPage(this, it));
                            return;
                        }
                        default -> { }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        Store.addSearch(query);
        push(new SearchPage(this, query));
        toolbar.setSearchText(query);
    }

    @Override
    public void toast(String message) {
        toast.setText(message);
        toast.setVisible(true);
        toastTimer.restart();
        bottom.revalidate();
    }

    private void hideToast() {
        toastTimer.stop();
        toast.setVisible(false);
        bottom.revalidate();
    }

    @Override
    public void download(Item item) {
        DownloadDialog.open(this, item, this);
    }

    @Override
    public void addToPlaylist(Item item) {
        List<Store.Playlist> pls = Store.playlists();
        JPanel p = new JPanel(new BorderLayout(0, 8));
        DefaultListModel<String> m = new DefaultListModel<>();
        for (Store.Playlist pl : pls) m.addElement(pl.name + "  (" + pl.items.size() + ")");
        JList<String> list = new JList<>(m);
        list.setVisibleRowCount(6);
        if (!pls.isEmpty()) list.setSelectedIndex(0);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(320, 150));
        p.add(new JLabel("Add “" + item.name + "” to:"), BorderLayout.NORTH);
        p.add(pls.isEmpty() ? new JLabel("You have no playlists yet.") : sp, BorderLayout.CENTER);
        Object[] options = {"Add", "New playlist…", "Cancel"};
        int r = JOptionPane.showOptionDialog(this, p, "Add to playlist", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[pls.isEmpty() ? 1 : 0]);
        if (r == 0 && list.getSelectedIndex() >= 0) {
            Store.Playlist pl = pls.get(list.getSelectedIndex());
            Store.addToPlaylist(pl, item);
            toast("Added to “" + pl.name + "”");
        } else if (r == 1) {
            String name = JOptionPane.showInputDialog(this, "Playlist name", "New playlist");
            if (name != null && !name.isBlank()) {
                Store.Playlist pl = Store.createPlaylist(name.trim());
                Store.addToPlaylist(pl, item);
                toast("Added to “" + pl.name + "”");
            }
        }
    }

    @Override
    public void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            toast("Could not open the browser");
        }
    }

    // ---- fullscreen --------------------------------------------------------------------------

    private void setFullscreen(boolean f) {
        if (f == fullscreen) return;
        if (f) {
            showVideoPage();
            savedBounds = getBounds();
            savedState = getExtendedState();
        }
        fullscreen = f;
        if (f) savedDecoration = getRootPane().getWindowDecorationStyle();
        getRootPane().setWindowDecorationStyle(f ? JRootPane.NONE : savedDecoration);
        toolbar.setVisible(!f);
        drawer.setVisible(!f);
        bottom.setVisible(!f);
        videoPage.setFullscreen(f);
        try {
            WinDef.HWND hwnd = new WinDef.HWND(Native.getComponentPointer(this));
            User32 u = User32.INSTANCE;
            if (f) {
                if (getExtendedState() != NORMAL) setExtendedState(NORMAL);
                savedStyle = u.GetWindowLong(hwnd, WinUser.GWL_STYLE);
                u.SetWindowLong(hwnd, WinUser.GWL_STYLE, savedStyle & ~(WinUser.WS_CAPTION | WinUser.WS_THICKFRAME));
                GraphicsConfiguration gc = getGraphicsConfiguration();
                Rectangle b = gc.getBounds();
                double sx = gc.getDefaultTransform().getScaleX(), sy = gc.getDefaultTransform().getScaleY();
                u.SetWindowPos(hwnd, new WinDef.HWND(), (int) Math.round(b.x * sx), (int) Math.round(b.y * sy),
                        (int) Math.round(b.width * sx), (int) Math.round(b.height * sy),
                        WinUser.SWP_FRAMECHANGED | WinUser.SWP_SHOWWINDOW);
            } else {
                u.SetWindowLong(hwnd, WinUser.GWL_STYLE, savedStyle);
                u.SetWindowPos(hwnd, new WinDef.HWND(), 0, 0, 0, 0,
                        WinUser.SWP_FRAMECHANGED | WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER);
                if (savedBounds != null) setBounds(savedBounds);
                setExtendedState(savedState);
            }
        } catch (Throwable t) {
            // fall back to a maximized window
            t.printStackTrace();
            setExtendedState(f ? MAXIMIZED_BOTH : NORMAL);
        }
        revalidate();
        repaint();
    }

    // ---- keyboard ----------------------------------------------------------------------------

    private boolean dispatchKey(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;
        if (!isActive() && !PlaybackParamsDialog.floatingActive()) return false;
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean typing = owner instanceof JTextComponent || owner instanceof JComboBox || owner instanceof JSpinner;
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_ESCAPE && fullscreen) {
            setFullscreen(false);
            return true;
        }
        if (e.getKeyCode() == KeyEvent.VK_L && e.isControlDown()) {
            toolbar.focusSearch();
            return true;
        }
        if (typing || e.isControlDown() || e.isAltDown() || player.current() == null) return false;
        double step = Settings.tempoStep();
        switch (k) {
            case KeyEvent.VK_SPACE -> player.togglePause();
            case KeyEvent.VK_LEFT -> player.seekBy(-5);
            case KeyEvent.VK_RIGHT -> player.seekBy(5);
            case KeyEvent.VK_J -> player.seekBy(-10);
            case KeyEvent.VK_L -> player.seekBy(10);
            case KeyEvent.VK_UP -> player.setVolume(player.volume() + 5);
            case KeyEvent.VK_DOWN -> player.setVolume(player.volume() - 5);
            case KeyEvent.VK_M -> player.toggleMute();
            case KeyEvent.VK_F -> videoPage.toggleFullscreen();
            case KeyEvent.VK_COMMA -> player.setTempo(player.tempo() - step);
            case KeyEvent.VK_PERIOD -> player.setTempo(player.tempo() + step);
            case KeyEvent.VK_N -> player.next();
            case KeyEvent.VK_P -> player.previous();
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- mini player -------------------------------------------------------------------------

    private void updateMini() {
        mini.refresh();
        boolean show = player.current() != null && current != videoPage;
        mini.setVisible(show);
        bottom.revalidate();
    }

    public void showRoot(String key) {
        goRoot(key);
    }

    public VideoPage videoPage() {
        return videoPage;
    }

    public void focusSearch() {
        toolbar.focusSearch();
    }

    public void init() {
        videoPage.bindPlayer();
        content.setFocusable(true);
        content.requestFocusInWindow();
    }

    // =========================================================================================

    private final class Toolbar extends JPanel {
        private final JButton nav = new JButton();
        private final JLabel title = new JLabel();
        private final SearchBox search = new SearchBox(MainFrame.this);
        private final JLabel logo = new JLabel(new ImageIcon(Icons.logo(32)));

        Toolbar() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 14));
            setPreferredSize(new Dimension(100, 52));
            nav.setFocusable(false);
            nav.putClientProperty("JButton.buttonType", "toolBarButton");
            nav.addActionListener(e -> back());
            Theme.fg(title, () -> Theme.toolbarText);
            title.setFont(Theme.font(18f, Font.PLAIN));
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(nav);
            left.add(logo);
            left.add(title);
            title.setPreferredSize(new Dimension(250, 28));
            left.setPreferredSize(new Dimension(380, 40));
            add(left, BorderLayout.WEST);
            JPanel mid = new JPanel(new BorderLayout());
            mid.setOpaque(false);
            mid.add(search, BorderLayout.CENTER);
            mid.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            JPanel wrap = new JPanel(new GridBagLayout());
            wrap.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
            gc.insets = new Insets(0, 0, 0, 0);
            wrap.add(mid, gc);
            add(wrap, BorderLayout.CENTER);
            JPanel right = new JPanel();
            right.setOpaque(false);
            right.setPreferredSize(new Dimension(60, 40));
            add(right, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.toolbar);
            g.fillRect(0, 0, getWidth(), getHeight());
        }

        void update() {
            boolean canBack = !stack.isEmpty();
            nav.setIcon(Icons.onToolbar(canBack ? "arrow_back" : "menu", 22));
            nav.setEnabled(canBack);
            nav.setVisible(canBack);
            logo.setVisible(!canBack);
            String t = current == null ? "NewPipe" : current.title();
            if (current == videoPage || current instanceof ChannelPage || current instanceof SearchPage || current instanceof PlaylistPage) {
                title.setText(current == videoPage ? "" : t);
            } else {
                title.setText(rootKey.equals("trending") ? "NewPipe" : t);
            }
            repaint();
        }

        void setSearchText(String q) {
            search.setText(q);
        }

        void focusSearch() {
            search.focusField();
        }
    }

    // ---- drawer ------------------------------------------------------------------------------

    private final class Drawer extends JPanel {
        private final String[][] items = {
                {"trending", "Trending", "whatshot"},
                {"subscriptions", "Subscriptions", "subscriptions"},
                {"feed", "What's new", "rss_feed"},
                {"bookmarks", "Bookmarked playlists", "bookmark"},
                {"history", "History", "history"},
                {null, null, null},
                {"downloads", "Downloads", "download"},
                {"settings", "Settings", "settings"},
                {"about", "About", "info"},
        };
        private final JButton service = new JButton();

        Drawer() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setPreferredSize(new Dimension(236, 100));
            setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
            service.setHorizontalAlignment(SwingConstants.LEFT);
            service.setFocusable(false);
            service.putClientProperty("JButton.buttonType", "roundRect");
            service.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            service.addActionListener(e -> serviceMenu());
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
            wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            wrap.add(service, BorderLayout.CENTER);
            add(wrap);
            refreshService();
            for (String[] it : items) {
                if (it[0] == null) {
                    add(Box.createVerticalStrut(6));
                    JSeparator s = new JSeparator();
                    s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
                    add(s);
                    add(Box.createVerticalStrut(6));
                    continue;
                }
                add(new DrawerItem(it[0], it[1], it[2]));
            }
            add(Box.createVerticalGlue());
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.drawer);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Theme.divider);
            g.fillRect(getWidth() - 1, 0, 1, getHeight());
        }

        void refreshService() {
            StreamingService s = Backend.service(Settings.serviceId());
            service.setText(s.getServiceInfo().getName());
            service.setIcon(Icons.get("expand_more", 18));
            service.setHorizontalTextPosition(SwingConstants.LEFT);
        }

        private void serviceMenu() {
            JPopupMenu m = new JPopupMenu();
            for (StreamingService s : Backend.services()) {
                JCheckBoxMenuItem mi = new JCheckBoxMenuItem(s.getServiceInfo().getName(), s.getServiceId() == Settings.serviceId());
                mi.addActionListener(e -> {
                    Settings.put("service", s.getServiceId());
                    refreshService();
                    roots.remove("trending");
                    goRoot("trending");
                });
                m.add(mi);
            }
            m.show(service, 0, service.getHeight());
        }

        private final class DrawerItem extends JComponent {
            private final String key, text, icon;
            private boolean hover;

            DrawerItem(String key, String text, String icon) {
                this.key = key;
                this.text = text;
                this.icon = icon;
                setPreferredSize(new Dimension(100, 44));
                setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        hover = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        if (fullscreen) return;
                        goRoot(key);
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                boolean sel = key.equals(rootKey) && stack.isEmpty() && current != videoPage;
                boolean selAny = key.equals(rootKey);
                if (selAny || hover) {
                    g.setColor(selAny ? Theme.selected : Theme.hover);
                    g.fillRoundRect(8, 2, getWidth() - 16, getHeight() - 4, 12, 12);
                }
                Color c = selAny ? Theme.accent : Theme.text;
                Icons.get(icon, 22, c).paintIcon(this, g, 22, (getHeight() - 22) / 2);
                g.setColor(c);
                g.setFont(Theme.font(14f, selAny ? Font.BOLD : Font.PLAIN));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(text, 60, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g.dispose();
            }
        }
    }

    // ---- mini player -------------------------------------------------------------------------

    private final class MiniPlayer extends JPanel {
        private final JLabel thumb = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel by = new JLabel();
        private final JButton prev = tool("skip_previous");
        private final JButton play = tool("pause");
        private final JButton nxt = tool("skip_next");
        private final JButton tempo = new JButton("1.00x");
        private final JButton close = tool("close");
        private final SeekBar bar = new SeekBar();
        private String thumbUrl = "";

        MiniPlayer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 8));
            setPreferredSize(new Dimension(100, 70));
            setVisible(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            bar.setSeekEnabled(true);
            bar.setOnSeek(player::seekTo);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.setPreferredSize(new Dimension(10, 14));
            top.add(bar, BorderLayout.CENTER);
            bar.setPreferredSize(new Dimension(10, 14));
            add(top, BorderLayout.NORTH);
            thumb.setPreferredSize(new Dimension(96, 54));
            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            name.setFont(Theme.font(13f, Font.BOLD));
            by.setFont(Theme.font(12f, Font.PLAIN));
            Theme.fg(by, () -> Theme.sub);
            text.add(Box.createVerticalGlue());
            text.add(name);
            text.add(by);
            text.add(Box.createVerticalGlue());
            JPanel left = new JPanel(new BorderLayout(10, 0));
            left.setOpaque(false);
            left.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            left.add(thumb, BorderLayout.WEST);
            left.add(text, BorderLayout.CENTER);
            add(left, BorderLayout.CENTER);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 8));
            right.setOpaque(false);
            tempo.setFocusable(false);
            tempo.putClientProperty("JButton.buttonType", "toolBarButton");
            tempo.setFont(Theme.font(13f, Font.BOLD));
            right.add(prev);
            right.add(play);
            right.add(nxt);
            right.add(tempo);
            right.add(close);
            add(right, BorderLayout.EAST);
            prev.addActionListener(e -> player.previous());
            play.addActionListener(e -> player.togglePause());
            nxt.addActionListener(e -> player.next());
            tempo.addActionListener(e -> PlaybackParamsDialog.show(this));
            close.addActionListener(e -> {
                player.stop();
                updateMini();
            });
            java.awt.event.MouseAdapter open = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    showVideoPage();
                }
            };
            thumb.addMouseListener(open);
            text.addMouseListener(open);
        }

        private JButton tool(String icon) {
            JButton b = new JButton(Icons.get(icon, 24));
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            return b;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.surface);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Theme.divider);
            g.fillRect(0, 0, getWidth(), 1);
        }

        void progress(double pos, double dur) {
            if (isVisible()) bar.setProgress(pos, dur);
        }

        void refresh() {
            Item it = player.current();
            if (it == null) return;
            name.setText(it.name);
            by.setText(it.uploader);
            play.setIcon(Icons.get(player.isPaused() || player.isEnded() ? "play" : "pause", 24));
            tempo.setText(String.format(java.util.Locale.ROOT, "%.2fx", player.tempo()));
            if (!it.thumb.equals(thumbUrl) || thumb.getIcon() == null) {
                thumbUrl = it.thumb;
                loadThumb(it.thumb);
            }
        }

        private void loadThumb(String url) {
            java.awt.image.BufferedImage img = org.newpipe.desktop.net.Images.get(url, 192, 108, false, () -> loadThumb(url));
            thumb.setIcon(img == null ? null : new ImageIcon(img.getScaledInstance(96, 54, Image.SCALE_SMOOTH)));
        }
    }
}
