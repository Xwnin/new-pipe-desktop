package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.net.Images;
import org.newpipe.desktop.util.Fmt;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

/** Comments of the current stream (YouTube, SoundCloud, PeerTube ...), loaded lazily and paged. */
final class CommentsPanel extends JPanel {
    private final VideoPage.ScrollablePanel list = new VideoPage.ScrollablePanel();
    private final JScrollPane scroll = new JScrollPane(list);
    private final JLabel status = new JLabel("", SwingConstants.CENTER);
    private final JButton more = new JButton("Load more comments");
    private String url;
    private boolean loaded, loading;
    private CommentsInfo info;
    private Page next;
    private long generation;

    CommentsPanel() {
        super(new BorderLayout());
        setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        Theme.fg(status, () -> Theme.sub);
        status.setBorder(BorderFactory.createEmptyBorder(24, 12, 24, 12));
        more.setFocusable(false);
        more.addActionListener(e -> loadMore());
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.NORTH);
        status.setVisible(false);
    }

    /** Forget everything and prepare for a new stream. Nothing is fetched until {@link #ensureLoaded()}. */
    void reset(String streamUrl) {
        generation++;
        url = streamUrl;
        loaded = false;
        loading = false;
        info = null;
        next = null;
        list.removeAll();
        showStatus(null);
        list.revalidate();
        list.repaint();
    }

    void ensureLoaded() {
        if (loaded || loading || url == null) return;
        loading = true;
        showStatus("Loading comments…");
        final long gen = generation;
        final String u = url;
        Thread t = new Thread(() -> {
            try {
                CommentsInfo ci = CommentsInfo.getInfo(u);
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    loaded = true;
                    info = ci;
                    if (ci.isCommentsDisabled()) {
                        showStatus("Comments are disabled");
                        return;
                    }
                    add(ci.getRelatedItems(), ci.hasNextPage() ? ci.getNextPage() : null);
                    if (ci.getRelatedItems().isEmpty()) showStatus("No comments");
                });
            } catch (Throwable ex) {
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    showStatus("Could not load comments: " + brief(ex));
                });
            }
        }, "comments");
        t.setDaemon(true);
        t.start();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    private void loadMore() {
        if (loading || next == null || info == null) return;
        loading = true;
        more.setEnabled(false);
        final long gen = generation;
        final Page page = next;
        final CommentsInfo ci = info;
        Thread t = new Thread(() -> {
            try {
                ListExtractor.InfoItemsPage<CommentsInfoItem> p = CommentsInfo.getMoreItems(ci, page);
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    add(p.getItems(), p.hasNextPage() ? p.getNextPage() : null);
                });
            } catch (Throwable ex) {
                EventQueue.invokeLater(() -> {
                    if (gen != generation) return;
                    loading = false;
                    more.setEnabled(true);
                    showStatus("Could not load more comments");
                });
            }
        }, "comments-more");
        t.setDaemon(true);
        t.start();
    }

    private void showStatus(String text) {
        status.setText(text == null ? "" : text);
        status.setVisible(text != null);
    }

    private void add(List<CommentsInfoItem> items, Page nextPage) {
        showStatus(null);
        boolean first = list.getComponentCount() == 0;
        list.remove(more);
        for (CommentsInfoItem c : items) list.add(card(c));
        if (first) SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
        next = nextPage;
        if (nextPage != null) {
            more.setEnabled(true);
            more.setAlignmentX(Component.CENTER_ALIGNMENT);
            JPanel wrap = new JPanel(new FlowLayout(FlowLayout.CENTER));
            wrap.setOpaque(false);
            wrap.add(more);
            wrap.setName("more");
            list.add(wrap);
        }
        list.revalidate();
        list.repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (status != null) Theme.fg(status, () -> Theme.sub);
    }

    private JComponent card(CommentsInfoItem c) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        p.setAlignmentX(LEFT_ALIGNMENT);

        JLabel avatar = new JLabel();
        avatar.setPreferredSize(new Dimension(36, 36));
        avatar.setVerticalAlignment(SwingConstants.TOP);
        String avatarUrl = Item.bestImage(c.getUploaderAvatars());
        if (avatarUrl.isEmpty()) avatar.setIcon(Icons.initial(c.getUploaderName(), 36));
        else setAvatar(avatar, avatarUrl);
        p.add(avatar, BorderLayout.WEST);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        StringBuilder head = new StringBuilder(c.getUploaderName() == null ? "" : c.getUploaderName());
        if (c.isPinned()) head.append("  📌");
        JLabel name = new JLabel(head.toString());
        name.setFont(Theme.font(12.5f, Font.BOLD));
        name.setAlignmentX(LEFT_ALIGNMENT);
        StringBuilder meta = new StringBuilder();
        String date = c.getUploadDate() != null
                ? java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).format(c.getUploadDate().offsetDateTime())
                : Fmt.date(c.getTextualUploadDate());
        if (date != null && !date.isBlank()) meta.append(date);
        if (c.getLikeCount() >= 0) meta.append(meta.length() > 0 ? "  •  " : "").append("👍 ").append(Fmt.compact(c.getLikeCount()));
        else if (c.getTextualLikeCount() != null && !c.getTextualLikeCount().isBlank()) {
            meta.append(meta.length() > 0 ? "  •  " : "").append("👍 ").append(c.getTextualLikeCount());
        }
        if (c.getReplyCount() > 0) meta.append(meta.length() > 0 ? "  •  " : "").append(c.getReplyCount()).append(" replies");
        JLabel metaLabel = new JLabel(meta.toString());
        Theme.fg(metaLabel, () -> Theme.sub);
        metaLabel.setFont(Theme.font(11.5f, Font.PLAIN));
        metaLabel.setAlignmentX(LEFT_ALIGNMENT);

        JTextArea text = new JTextArea(plain(c.getCommentText()));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        text.setFont(Theme.font(13f, Font.PLAIN));
        text.setAlignmentX(LEFT_ALIGNMENT);
        body.add(name);
        body.add(metaLabel);
        body.add(text);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static String plain(Description d) {
        if (d == null || d.getContent() == null) return "";
        String s = d.getContent();
        return (d.getType() == Description.HTML ? org.newpipe.desktop.util.Html.toPlain(s) : org.newpipe.desktop.util.Html.decode(s)).trim();
    }

    private void setAvatar(JLabel label, String url) {
        if (url == null || url.isEmpty()) return;
        BufferedImage img = Images.get(url, 72, 72, true, () -> setAvatar(label, url));
        if (img != null) label.setIcon(new ImageIcon(img.getScaledInstance(36, 36, Image.SCALE_SMOOTH)));
    }
}
