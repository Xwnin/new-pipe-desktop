package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Settings;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Search results with NewPipe's content filter chips (All / Videos / Channels / Playlists ...). */
public class SearchPage extends ListPage {
    private final String query;

    public SearchPage(Nav nav, String query) {
        super(nav, "“" + query + "”");
        this.query = query;
        list.setEmptyText("No results for “" + query + "”");

        int sid = Settings.serviceId();
        List<String> filters = Backend.searchFilters(sid);
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        chips.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        addChip(chips, group, "All", "", sid, true);
        for (String f : filters) {
            if (f.equals("all")) continue;
            addChip(chips, group, label(f), f, sid, false);
        }
        top().add(chips, BorderLayout.WEST);
        list.setLoader(Backend.search(sid, query, ""));
    }

    private static String label(String f) {
        String s = f.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void addChip(JPanel panel, ButtonGroup group, String text, String filter, int sid, boolean selected) {
        JToggleButton b = new JToggleButton(text, selected);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "roundRect");
        group.add(b);
        b.addActionListener(e -> list.setLoader(Backend.search(sid, query, filter)));
        panel.add(b);
    }
}
