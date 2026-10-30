package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Store;
import org.newpipe.desktop.net.Images;
import org.newpipe.desktop.util.Fmt;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Scrollable, lazily paged list of videos / channels / playlists in NewPipe's row layout. */
public class ItemList extends JPanel {
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "list-loader");
        t.setDaemon(true);
        return t;
    });

    private static final int ROW_H = 104;

    private final Nav nav;
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JButton retry = new JButton("Retry");
    private final JProgressBar busy = new JProgressBar();
    private final JProgressBar more = new JProgressBar();

    private Backend.Loader loader;
    private Object next;
    private boolean loading, exhausted;
    private long generation;
    private String emptyText = "Nothing here";
    private Function<Item, List<JMenuItem>> extraMenu;
    private int hover = -1;
    private Runnable onLoaded;
    private java.util.function.Consumer<Item> onOpen;

    public ItemList(Nav nav) {
        super(new BorderLayout());
        this.nav = nav;
        setOpaque(false);

        list.setFixedCellHeight(ROW_H);
        list.setCellRenderer(new Row());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.setFocusable(true);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(ROW_H / 3);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> maybeLoadMore());

        JPanel msg = new JPanel(new GridBagLayout());
        msg.setOpaque(false);
        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        message.setAlignmentX(CENTER_ALIGNMENT);
        busy.setIndeterminate(true);
        busy.setMaximumSize(new Dimension(180, 6));
        busy.setAlignmentX(CENTER_ALIGNMENT);
        retry.setAlignmentX(CENTER_ALIGNMENT);
        retry.addActionListener(e -> reload());
        inner.add(message);
        inner.add(Box.createVerticalStrut(12));
        inner.add(busy);
        inner.add(retry);
        msg.add(inner);

        body.setOpaque(false);
        body.add(scroll, "list");
        body.add(msg, "msg");
        add(body, BorderLayout.CENTER);
        more.setIndeterminate(true);
        more.setPreferredSize(new Dimension(10, 3));
        more.setVisible(false);
        add(more, BorderLayout.SOUTH);

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = indexAt(e);
                if (i != hover) {
                    hover = i;
                    list.repaint();
                }
                list.setCursor(i >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                list.repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = indexAt(e);
                if (i < 0) return;
                Item it = model.get(i);
                if (SwingUtilities.isRightMouseButton(e) || e.getX() > list.getWidth() - 48) {
                    menu(it, e.getComponent(), e.getX(), e.getY());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    if (onOpen != null) onOpen.accept(it);
                    else nav.open(it);
                }
            }
        };
        list.addMouseListener(ma);
        list.addMouseMotionListener(ma);
        Store.onChange(() -> EventQueue.invokeLater(list::repaint));
        showMessage(emptyText, false, false);
    }

    private int indexAt(MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        if (i < 0) return -1;
        Rectangle r = list.getCellBounds(i, i);
        return r != null && r.contains(e.getPoint()) ? i : -1;
    }

    private void menu(Item it, Component c, int x, int y) {
        List<JMenuItem> extra = extraMenu == null ? null : extraMenu.apply(it);
        ItemMenu.build(it, nav, extra).show(c, x, y);
    }

    public void setExtraMenu(Function<Item, List<JMenuItem>> f) {
        extraMenu = f;
    }

    public void setOnOpen(java.util.function.Consumer<Item> c) {
        onOpen = c;
    }

    public void setEmptyText(String s) {
        emptyText = s;
    }

    public void setOnLoaded(Runnable r) {
        onLoaded = r;
    }

    public List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) out.add(model.get(i));
        return out;
    }

    public void setItems(List<Item> items) {
        generation++;
        loader = null;
        model.clear();
        for (Item i : items) model.addElement(i);
        exhausted = true;
        if (items.isEmpty()) showMessage(emptyText, false, false);
        else cards.show(body, "list");
    }

    public void setLoader(Backend.Loader l) {
        loader = l;
        reload();
    }

    public void reload() {
        if (loader == null) return;
        final long gen = ++generation;
        model.clear();
        next = null;
        exhausted = false;
        loading = true;
        showMessage("Loading…", true, false);
        final Backend.Loader ld = loader;
        POOL.execute(() -> {
            try {
                Backend.Result r = ld.first();
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    append(r);
                    if (model.isEmpty()) showMessage(emptyText, false, false);
                    else cards.show(body, "list");
                    if (onLoaded != null) onLoaded.run();
                    SwingUtilities.invokeLater(this::maybeLoadMore);
                });
            } catch (Throwable t) {
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    showMessage(errorText(t), false, true);
                });
            }
        });
    }

    private void append(Backend.Result r) {
        for (Item i : r.items()) model.addElement(i);
        next = r.next();
        exhausted = next == null;
    }

    private void maybeLoadMore() {
        if (loader == null || loading || exhausted || model.isEmpty()) return;
        JScrollBar sb = scroll.getVerticalScrollBar();
        if (sb.getValue() + sb.getVisibleAmount() < sb.getMaximum() - 600) return;
        loading = true;
        more.setVisible(true);
        final long gen = generation;
        final Object n = next;
        final Backend.Loader ld = loader;
        POOL.execute(() -> {
            try {
                Backend.Result r = ld.more(n);
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    more.setVisible(false);
                    append(r);
                    SwingUtilities.invokeLater(this::maybeLoadMore);
                });
            } catch (Throwable t) {
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    exhausted = true;
                    more.setVisible(false);
                });
            }
        });
    }

    private static String errorText(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        return "Could not load content: " + m;
    }

    private void showMessage(String text, boolean spinner, boolean retryable) {
        message.setText("<html><div style='text-align:center;width:360px'>" + escape(text) + "</div></html>");
        Theme.fg(message, () -> Theme.sub);
        busy.setVisible(spinner);
        retry.setVisible(retryable);
        cards.show(body, "msg");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public JList<Item> jlist() {
        return list;
    }

    // ---------------------------------------------------------------------------------------

    private final class Row extends JComponent implements ListCellRenderer<Item> {
        private Item it;
        private boolean hovered, selected;
        private final Font titleFont = Theme.font(14f, Font.BOLD);
        private final Font metaFont = Theme.font(12f, Font.PLAIN);
        private final Font badgeFont = Theme.font(11f, Font.BOLD);

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item value, int index, boolean sel, boolean focus) {
            it = value;
            hovered = index == hover;
            selected = sel;
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(100, ROW_H);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            if (hovered || selected) {
                g.setColor(selected ? Theme.selected : Theme.hover);
                g.fill(new RoundRectangle2D.Float(4, 2, w - 8, h - 4, 10, 10));
            }
            int pad = 12, tw = 160, th = 90, ty = (h - th) / 2;
            boolean channel = it.kind == Item.Kind.CHANNEL;
            int tx = pad + (channel ? 35 : 0);
            Runnable repaint = list::repaint;
            if (channel) {
                BufferedImage img = Images.get(it.thumb, th * 2, th * 2, true, repaint);
                if (img != null) g.drawImage(img, tx, ty, th, th, null);
                else {
                    g.setColor(Theme.divider);
                    g.fillOval(tx, ty, th, th);
                }
                tx += th - tw + 0; // text starts after the avatar
            } else {
                BufferedImage img = Images.get(it.thumb, tw * 2, th * 2, false, repaint);
                Shape clip = new RoundRectangle2D.Float(tx, ty, tw, th, 8, 8);
                g.setColor(Theme.divider);
                g.fill(clip);
                if (img != null) {
                    Shape old = g.getClip();
                    g.clip(clip);
                    g.drawImage(img, tx, ty, tw, th, null);
                    g.setClip(old);
                }
                // badges
                if (it.kind == Item.Kind.STREAM) {
                    if (it.live) badge(g, "LIVE", tx + tw - 6, ty + th - 6, new Color(0xCC0000));
                    else if (it.duration > 0) badge(g, Fmt.time(it.duration), tx + tw - 6, ty + th - 6, new Color(0, 0, 0, 200));
                    double pos = Store.position(it.url);
                    if (pos > 5 && it.duration > 0) {
                        int bw = (int) Math.min(tw, tw * (pos / it.duration));
                        g.setColor(Theme.accent);
                        g.fillRect(tx, ty + th - 3, bw, 3);
                    }
                } else if (it.kind == Item.Kind.PLAYLIST) {
                    g.setColor(new Color(0, 0, 0, 170));
                    g.fillRect(tx + tw - 48, ty, 48, th);
                    Icon ic = Icons.light("playlist_play", 22);
                    ic.paintIcon(this, g, tx + tw - 36, ty + th / 2 - 18);
                    g.setColor(Color.WHITE);
                    g.setFont(badgeFont);
                    String cnt = it.count >= 0 ? Long.toString(it.count) : "";
                    int cw = g.getFontMetrics().stringWidth(cnt);
                    g.drawString(cnt, tx + tw - 24 - cw / 2, ty + th / 2 + 18);
                }
                tx += 0;
            }
            int textX = (channel ? tx + th : tx + tw) + 14;
            if (channel) textX = pad + 35 + th + 14;
            int textW = w - textX - 44;
            if (textW < 40) {
                g.dispose();
                return;
            }
            // title (max 2 lines)
            g.setFont(titleFont);
            g.setColor(Theme.text);
            FontMetrics fm = g.getFontMetrics();
            int y = ty + fm.getAscent() + 2;
            List<String> lines = wrap(it.name, fm, textW, channel ? 1 : 2);
            for (String ln : lines) {
                g.drawString(ln, textX, y);
                y += fm.getHeight();
            }
            g.setFont(metaFont);
            g.setColor(Theme.sub);
            fm = g.getFontMetrics();
            String meta = Fmt.meta(it);
            y += 2;
            for (String ln : wrap(meta, fm, textW, channel ? 1 : 2)) {
                g.drawString(ln, textX, y);
                y += fm.getHeight();
            }
            if (channel && !it.description.isEmpty()) {
                for (String ln : wrap(it.description, fm, textW, 2)) {
                    g.drawString(ln, textX, y);
                    y += fm.getHeight();
                }
            }
            // overflow icon
            Icons.get("more_vert", 20, Theme.sub).paintIcon(this, g, w - 34, h / 2 - 10);
            g.dispose();
        }

        private void badge(Graphics2D g, String text, int right, int bottom, Color bg) {
            g.setFont(badgeFont);
            FontMetrics fm = g.getFontMetrics();
            int bw = fm.stringWidth(text) + 10, bh = fm.getHeight() + 2;
            g.setColor(bg);
            g.fill(new RoundRectangle2D.Float(right - bw, bottom - bh, bw, bh, 6, 6));
            g.setColor(Color.WHITE);
            g.drawString(text, right - bw + 5, bottom - bh + fm.getAscent() + 1);
        }
    }

    /** Greedy word wrap that ellipsizes the last allowed line. */
    public static List<String> wrap(String text, FontMetrics fm, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] words = text.trim().split("\\s+");
        String line = "";
        for (int i = 0; i < words.length; i++) {
            String trial = line.isEmpty() ? words[i] : line + " " + words[i];
            if (fm.stringWidth(trial) <= width) {
                line = trial;
                continue;
            }
            if (out.size() == maxLines - 1) {
                StringBuilder rest = new StringBuilder(line);
                for (int k = i; k < words.length; k++) rest.append(rest.length() > 0 ? " " : "").append(words[k]);
                out.add(cut(rest.toString(), fm, width));
                return out;
            }
            if (!line.isEmpty()) out.add(line);
            line = fm.stringWidth(words[i]) <= width ? words[i] : cut(words[i], fm, width);
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }
    private static String cut(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String ell = "…";
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n) + ell) > width) n--;
        return s.substring(0, Math.max(0, n)) + ell;
    }
}
