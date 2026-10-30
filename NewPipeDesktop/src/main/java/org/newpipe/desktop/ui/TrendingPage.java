package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Settings;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** The start page: a kiosk of the selected service (Trending on YouTube, Top 50 on SoundCloud ...). */
public class TrendingPage extends ListPage {
    private boolean started;
    private final JComboBox<String> kiosks = new JComboBox<>();
    private boolean filling;

    public TrendingPage(Nav nav) {
        super(nav, "Trending");
        list.setEmptyText("Nothing to show here. Use the search box to find videos.");
        kiosks.setLightWeightPopupEnabled(false);
        kiosks.setFocusable(false);
        kiosks.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v == null ? "" : pretty(v.toString()), i, s, f);
            }
        });
        kiosks.addActionListener(e -> {
            if (!filling && kiosks.getSelectedItem() != null) load((String) kiosks.getSelectedItem());
        });
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        left.setOpaque(false);
        left.add(kiosks);
        top().add(left, BorderLayout.WEST);
        addTool("Refresh", "refresh", () -> load((String) kiosks.getSelectedItem()));
    }

    private static String pretty(String id) {
        String s = id.replace('_', ' ').replace('-', ' ').trim();
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void load(String kioskId) {
        started = true;
        int sid = Settings.serviceId();
        if (kioskId == null) list.setLoader(Backend.trending(sid));
        else list.setLoader(Backend.kiosk(sid, kioskId));
    }

    @Override
    public void onShown() {
        if (started) return;
        started = true;
        List<String> ids = Backend.kioskIds(Settings.serviceId());
        filling = true;
        kiosks.removeAllItems();
        for (String id : ids) kiosks.addItem(id);
        filling = false;
        kiosks.setVisible(ids.size() > 1);
        load(ids.isEmpty() ? null : ids.get(0));
    }
}
