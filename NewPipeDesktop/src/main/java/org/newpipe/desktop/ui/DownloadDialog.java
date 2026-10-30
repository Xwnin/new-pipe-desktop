package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Downloads;
import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.player.Player;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/** NewPipe's download dialog: pick video or audio, the quality / format and the file name. */
final class DownloadDialog {
    private DownloadDialog() {}

    static void open(JFrame owner, Item item, Nav nav) {
        nav.toast("Preparing download…");
        Thread t = new Thread(() -> {
            try {
                StreamInfo si = null;
                Player p = Player.get();
                if (p.info() != null && p.current() != null && p.current().url.equals(item.url)) si = p.info();
                if (si == null) si = StreamInfo.getInfo(item.url);
                final StreamInfo info = si;
                EventQueue.invokeLater(() -> show(owner, item, info, nav));
            } catch (Throwable ex) {
                EventQueue.invokeLater(() -> nav.toast("Could not prepare the download: "
                        + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().split("\n")[0])));
            }
        }, "download-prepare");
        t.setDaemon(true);
        t.start();
    }

    private static void show(JFrame owner, Item item, StreamInfo info, Nav nav) {
        List<Downloads.Option> video = Downloads.videoOptions(info);
        List<Downloads.Option> audio = Downloads.audioOptions(info);
        if (video.isEmpty() && audio.isEmpty()) {
            nav.toast("No downloadable streams for this item");
            return;
        }
        JDialog d = new JDialog(owner, "Download", true);
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JLabel name = new JLabel("File name");
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextField file = new JTextField(info.getName() == null ? item.name : info.getName(), 34);
        file.setAlignmentX(Component.LEFT_ALIGNMENT);
        file.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JRadioButton rv = new JRadioButton("Video", !video.isEmpty());
        JRadioButton ra = new JRadioButton("Audio", video.isEmpty());
        rv.setEnabled(!video.isEmpty());
        ra.setEnabled(!audio.isEmpty());
        ButtonGroup g = new ButtonGroup();
        g.add(rv);
        g.add(ra);
        JPanel kind = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        kind.setAlignmentX(Component.LEFT_ALIGNMENT);
        kind.add(rv);
        kind.add(ra);

        JComboBox<Downloads.Option> options = new JComboBox<>();
        options.setLightWeightPopupEnabled(false);
        options.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        Runnable fill = () -> {
            options.removeAllItems();
            for (Downloads.Option o : (rv.isSelected() ? video : audio)) options.addItem(o);
        };
        fill.run();
        rv.addActionListener(e -> fill.run());
        ra.addActionListener(e -> fill.run());

        JLabel dir = new JLabel("Saving to " + Settings.downloadDir().getAbsolutePath());
        Theme.fg(dir, () -> Theme.sub);
        dir.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton ok = new JButton("Download");
        ok.putClientProperty("JButton.buttonType", "default");
        JButton cancel = new JButton("Cancel");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(cancel);
        buttons.add(ok);

        root.add(name);
        root.add(Box.createVerticalStrut(4));
        root.add(file);
        root.add(Box.createVerticalStrut(12));
        root.add(kind);
        root.add(Box.createVerticalStrut(6));
        root.add(options);
        root.add(Box.createVerticalStrut(10));
        root.add(dir);
        root.add(Box.createVerticalStrut(16));
        root.add(buttons);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(ok);

        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            Downloads.Option o = (Downloads.Option) options.getSelectedItem();
            if (o == null) return;
            File dirF = Settings.downloadDir();
            Downloads.start(file.getText().trim().isEmpty() ? item.name : file.getText().trim(), o, dirF, file.getText().trim());
            d.dispose();
            nav.toast("Download started");
        });
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
