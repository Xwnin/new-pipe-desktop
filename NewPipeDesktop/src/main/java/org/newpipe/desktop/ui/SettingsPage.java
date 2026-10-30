package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.data.Store;
import org.newpipe.desktop.data.SubscriptionIO;
import org.schabi.newpipe.extractor.StreamingService;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

public class SettingsPage extends Page {
    private final JPanel body = new JPanel();

    public SettingsPage(Nav nav) {
        super(nav);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(8, 24, 24, 24));

        section("Appearance");
        combo("Theme", new String[]{"Light", "Dark", "Black"}, indexOf(Settings.theme(), "light", "dark", "black"), i -> {
            String t = new String[]{"light", "dark", "black"}[i];
            Settings.put("theme", t);
            Theme.apply(t);
            SwingUtilities.invokeLater(() -> nav.toast("Theme changed"));
        });

        section("Content");
        List<StreamingService> services = Backend.services();
        String[] names = services.stream().map(s -> s.getServiceInfo().getName()).toArray(String[]::new);
        int cur = 0;
        for (int i = 0; i < services.size(); i++) if (services.get(i).getServiceId() == Settings.serviceId()) cur = i;
        combo("Default service", names, cur, i -> Settings.put("service", services.get(i).getServiceId()));
        String[] res = {"2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p"};
        combo("Default resolution", res, indexOf(Settings.resolution(), res), i -> Settings.put("resolution", res[i]));
        check("Play audio only by default (background mode)", "audioOnly", Settings.audioOnly());
        check("Autoplay the next related video when the queue ends", "autoplay", Settings.autoplay());

        section("Player");
        check("Remember tempo and pitch between videos", "rememberTempo", Settings.rememberTempo());
        check("Resume videos where you left off", "resumePosition", Settings.resumePosition());
        check("Show tempo and pitch controls in a floating window while NewPipe is minimized", "floatingControls",
                Settings.bool("floatingControls", true));
        note("Tempo and pitch are changed from the speed button in the player (or with the < and > keys).");

        section("History");
        check("Save watch history", "watchHistory", Settings.watchHistory());
        check("Save search history", "searchHistory", Settings.searchHistory());
        JPanel hb = row();
        JButton clearWatch = new JButton("Clear watch history");
        clearWatch.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Delete the whole watch history?", "Clear watch history",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                Store.clearHistory();
                nav.toast("Watch history cleared");
            }
        });
        JButton clearSearch = new JButton("Clear search history");
        clearSearch.addActionListener(e -> {
            Store.clearSearches();
            nav.toast("Search history cleared");
        });
        hb.add(clearWatch);
        hb.add(Box.createHorizontalStrut(8));
        hb.add(clearSearch);

        section("Subscriptions");
        note("Uses the same file format as NewPipe on Android, so subscriptions can be moved in both directions.");
        JPanel sb = row();
        JButton exp = new JButton("Export subscriptions\u2026");
        exp.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("newpipe_subscriptions.json"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    SubscriptionIO.export(fc.getSelectedFile());
                    nav.toast("Exported " + Store.subscriptions().size() + " subscriptions");
                } catch (Exception ex) {
                    nav.toast("Export failed: " + ex.getMessage());
                }
            }
        });
        JButton imp = new JButton("Import subscriptions\u2026");
        imp.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    int n = SubscriptionIO.importFrom(fc.getSelectedFile());
                    nav.toast("Imported " + n + " new subscriptions");
                } catch (Exception ex) {
                    nav.toast("Import failed: " + ex.getMessage());
                }
            }
        });
        sb.add(exp);
        sb.add(Box.createHorizontalStrut(8));
        sb.add(imp);

        section("Downloads");
        JPanel dr = row();
        JLabel dir = new JLabel(Settings.downloadDir().getAbsolutePath());
        Theme.fg(dir, () -> Theme.sub);
        JButton change = new JButton("Change…");
        change.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(Settings.downloadDir());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                Settings.put("downloadDir", f.getAbsolutePath());
                dir.setText(f.getAbsolutePath());
            }
        });
        dr.add(dir);
        dr.add(Box.createHorizontalStrut(10));
        dr.add(change);

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);
    }

    @Override
    public String title() {
        return "Settings";
    }

    private static int indexOf(String v, String... all) {
        for (int i = 0; i < all.length; i++) if (all[i].equalsIgnoreCase(v)) return i;
        return 0;
    }

    private JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        body.add(p);
        return p;
    }

    private void section(String name) {
        body.add(Box.createVerticalStrut(18));
        JLabel l = new JLabel(name);
        l.setFont(Theme.font(13f, Font.BOLD));
        Theme.fg(l, () -> Theme.accent);
        l.setAlignmentX(LEFT_ALIGNMENT);
        body.add(l);
        body.add(Box.createVerticalStrut(6));
    }

    private void note(String text) {
        JLabel l = new JLabel(text);
        Theme.fg(l, () -> Theme.sub);
        l.setFont(Theme.font(12f, Font.PLAIN));
        l.setAlignmentX(LEFT_ALIGNMENT);
        body.add(l);
    }

    private void check(String text, String key, boolean value) {
        JCheckBox c = new JCheckBox(text, value);
        c.setOpaque(false);
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.addActionListener(e -> Settings.put(key, c.isSelected()));
        body.add(c);
    }

    private void combo(String label, String[] items, int selected, java.util.function.IntConsumer onChange) {
        JPanel p = row();
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(160, 24));
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setSelectedIndex(Math.max(0, selected));
        cb.setLightWeightPopupEnabled(false);
        cb.addActionListener(e -> onChange.accept(cb.getSelectedIndex()));
        p.add(l);
        p.add(cb);
    }
}
