package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Downloads;
import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.util.Fmt;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;

public class DownloadsPage extends Page {
    private final JPanel rows = new JPanel();
    private final javax.swing.Timer timer = new javax.swing.Timer(500, this::tick);
    private int lastSignature;

    public DownloadsPage(Nav nav) {
        super(nav);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bar.setOpaque(false);
        JButton folder = new JButton("Open folder", Icons.get("folder_open", 18));
        JButton clear = new JButton("Clear finished", Icons.get("clear_all", 18));
        folder.setFocusable(false);
        clear.setFocusable(false);
        folder.addActionListener(e -> open(Settings.downloadDir()));
        clear.addActionListener(e -> Downloads.clearFinished());
        bar.add(folder);
        bar.add(clear);
        add(bar, BorderLayout.NORTH);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(rows, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);
        Downloads.onChange(() -> EventQueue.invokeLater(this::rebuildIfNeeded));
        rebuild();
    }

    @Override
    public String title() {
        return "Downloads";
    }

    @Override
    public void onShown() {
        rebuild();
        timer.start();
    }

    @Override
    public void onDiscard() {
        timer.stop();
    }

    private void tick(ActionEvent e) {
        if (!isShowing()) {
            timer.stop();
            return;
        }
        rebuild();
    }

    private void rebuildIfNeeded() {
        if (isShowing()) rebuild();
    }

    private static void open(File f) {
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception ignored) {
        }
    }

    private void rebuild() {
        int sig = 17;
        for (Downloads.Job j : Downloads.jobs()) sig = sig * 31 + j.state.hashCode() + (int) (j.fraction() * 200) + System.identityHashCode(j);
        if (sig == lastSignature) return;
        lastSignature = sig;
        rows.removeAll();
        if (Downloads.jobs().isEmpty()) {
            JLabel l = new JLabel("No downloads yet. Use “Download” on a video.", SwingConstants.CENTER);
            Theme.fg(l, () -> Theme.sub);
            l.setBorder(BorderFactory.createEmptyBorder(60, 0, 0, 0));
            l.setAlignmentX(CENTER_ALIGNMENT);
            rows.add(l);
        }
        for (Downloads.Job j : Downloads.jobs()) rows.add(row(j));
        rows.revalidate();
        rows.repaint();
    }

    private JComponent row(Downloads.Job j) {
        JPanel p = new JPanel(new BorderLayout(12, 4));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.divider),
                BorderFactory.createEmptyBorder(10, 16, 10, 12)));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(j.title);
        title.setFont(Theme.font(14f, Font.BOLD));
        String status = switch (j.state) {
            case QUEUED -> "Queued";
            case RUNNING -> j.total > 0 ? Math.round(j.fraction() * 100) + "%" : "Downloading…";
            case DONE -> "Finished • " + Fmt.size(j.file.length()) + " • " + j.file.getName();
            case FAILED -> "Failed: " + j.error;
            case CANCELED -> "Canceled";
        };
        JLabel st = new JLabel(status);
        st.setForeground(j.state == Downloads.State.FAILED ? Theme.accent : Theme.sub);
        st.setFont(Theme.font(12f, Font.PLAIN));
        text.add(title);
        text.add(Box.createVerticalStrut(2));
        text.add(st);
        if (j.state == Downloads.State.RUNNING || j.state == Downloads.State.QUEUED) {
            JProgressBar bar = new JProgressBar(0, 1000);
            if (j.total > 0) bar.setValue((int) (j.fraction() * 1000));
            else bar.setIndeterminate(true);
            bar.setPreferredSize(new Dimension(10, 5));
            text.add(Box.createVerticalStrut(6));
            text.add(bar);
        }
        p.add(text, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        btns.setOpaque(false);
        if (j.state == Downloads.State.DONE) {
            JButton play = tool("play", "Open file");
            play.addActionListener(e -> open(j.file));
            JButton show = tool("folder_open", "Show in folder");
            show.addActionListener(e -> open(j.file.getParentFile()));
            btns.add(play);
            btns.add(show);
        }
        JButton del = tool(j.state == Downloads.State.RUNNING || j.state == Downloads.State.QUEUED ? "close" : "delete",
                j.state == Downloads.State.RUNNING ? "Cancel" : "Remove from list");
        del.addActionListener(e -> Downloads.remove(j));
        btns.add(del);
        p.add(btns, BorderLayout.EAST);
        return p;
    }

    private JButton tool(String icon, String tip) {
        JButton b = new JButton(Icons.get(icon, 20));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        return b;
    }
}
