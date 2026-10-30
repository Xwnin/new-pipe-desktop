package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Backend;
import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.data.Store;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The toolbar search field with NewPipe style suggestions (recent searches + the service's suggestions). */
final class SearchBox extends JPanel {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "suggestions");
        t.setDaemon(true);
        return t;
    });

    private final Nav nav;
    private final JTextField field = new JTextField();
    private final JPopupMenu popup = new JPopupMenu();
    private final javax.swing.Timer debounce = new javax.swing.Timer(180, e -> suggest());
    private long generation;
    private boolean suppress;

    SearchBox(Nav nav) {
        super(new BorderLayout());
        this.nav = nav;
        setOpaque(false);
        setMaximumSize(new Dimension(640, 40));
        setPreferredSize(new Dimension(520, 38));
        debounce.setRepeats(false);

        field.putClientProperty("JTextField.placeholderText", "Search");
        field.putClientProperty("JTextField.showClearButton", true);
        field.putClientProperty("JTextField.leadingIcon", Icons.onToolbar("search", 18));
        field.putClientProperty("JTextField.showClearButton", true);
        restyle();
        Theme.addListener(this::restyle);
        field.setFont(Theme.font(15f, Font.PLAIN));
        add(field, BorderLayout.CENTER);

        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(Theme.divider));

        field.addActionListener(e -> {
            popup.setVisible(false);
            nav.search(field.getText());
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        // The field only takes keyboard focus when the user clicks it (or presses Ctrl+L), so the player
        // shortcuts (space, arrows ...) keep working after navigating.
        field.setFocusable(false);
        field.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (!field.isFocusable()) {
                    field.setFocusable(true);
                    field.requestFocusInWindow();
                }
            }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.selectAll();
                debounce.restart();
            }

            @Override
            public void focusLost(FocusEvent e) {
                popup.setVisible(false);
                EventQueue.invokeLater(() -> {
                    if (!field.hasFocus()) field.setFocusable(false);
                });
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    popup.setVisible(false);
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
                }
            }
        });
    }

    /** Translucent field on the toolbar, text colour follows the toolbar (white on red, dark on yellow). */
    private void restyle() {
        Color t = Theme.toolbarText;
        String hex = String.format("#%02X%02X%02X", t.getRed(), t.getGreen(), t.getBlue());
        boolean darkText = t.getRed() < 128;
        field.putClientProperty("FlatLaf.style",
                "background: " + (darkText ? "#00000022" : "#FFFFFF33") + "; focusedBackground: " + (darkText ? "#00000033" : "#FFFFFF4D")
                        + "; foreground: " + hex + "; caretColor: " + hex + "; placeholderForeground: " + hex + "B3;"
                        + "focusWidth: 0; innerFocusWidth: 0; borderWidth: 0; arc: 999; margin: 4,12,4,12; showClearButton: true");
    }

    private void changed() {
        if (!suppress && field.hasFocus()) debounce.restart();
    }

    void setText(String s) {
        suppress = true;
        field.setText(s);
        suppress = false;
        popup.setVisible(false);
    }

    void focusField() {
        field.setFocusable(true);
        field.requestFocusInWindow();
        field.selectAll();
    }

    private void suggest() {
        final String q = field.getText().trim();
        final long gen = ++generation;
        List<String> local = new ArrayList<>();
        for (String s : Store.searches()) {
            if (q.isEmpty() || s.toLowerCase().contains(q.toLowerCase())) local.add(s);
            if (local.size() >= 6) break;
        }
        show(local, List.of(), q);
        if (q.length() < 2) return;
        final int sid = Settings.serviceId();
        POOL.execute(() -> {
            List<String> remote = Backend.suggestions(sid, q);
            EventQueue.invokeLater(() -> {
                if (gen == generation && field.hasFocus()) show(local, remote, q);
            });
        });
    }

    private void show(List<String> history, List<String> remote, String q) {
        popup.removeAll();
        int n = 0;
        for (String s : history) {
            popup.add(entry(s, "history", true));
            n++;
        }
        for (String s : remote) {
            if (history.contains(s) || n >= 12) continue;
            popup.add(entry(s, "search", false));
            n++;
        }
        if (n == 0) {
            popup.setVisible(false);
            return;
        }
        popup.setPopupSize(Math.max(300, field.getWidth()), popup.getPreferredSize().height);
        if (!popup.isVisible() && field.isShowing()) popup.show(field, 0, field.getHeight() + 2);
        else {
            popup.revalidate();
            popup.repaint();
        }
    }

    private JComponent entry(String text, String icon, boolean deletable) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
        p.setOpaque(true);
        p.setBackground(Theme.surface);
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JLabel l = new JLabel(text, Icons.get(icon, 18, Theme.sub), SwingConstants.LEFT);
        l.setIconTextGap(12);
        p.add(l, BorderLayout.CENTER);
        if (deletable) {
            JButton x = new JButton(Icons.get("close", 14, Theme.sub));
            x.setFocusable(false);
            x.putClientProperty("JButton.buttonType", "toolBarButton");
            x.setToolTipText("Remove from history");
            x.addActionListener(e -> {
                Store.removeSearch(text);
                suggest();
            });
            p.add(x, BorderLayout.EAST);
        }
        p.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                p.setBackground(Theme.hover);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                p.setBackground(Theme.surface);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                popup.setVisible(false);
                setText(text);
                nav.search(text);
            }
        });
        return p;
    }
}
