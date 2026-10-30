package org.newpipe.desktop.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/** NewPipe's three themes (light / dark / black) and the red accent. */
public final class Theme {
    public static Color bg, surface, drawer, text, sub, divider, toolbar, toolbarText, accent, hover, selected, onAccent;
    public static boolean dark;

    private static final Map<JComponent, java.util.function.Supplier<Color>> BOUND =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Theme() {}

    /** Runs after every theme / service palette change (for components that style themselves with client properties). */
    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    /** SoundCloud (service id 1) gets a yellow palette, everything else NewPipe's red. */
    private static boolean yellow() {
        return org.newpipe.desktop.data.Settings.serviceId() == 1;
    }

    /** Sets the foreground now and again after every theme change. */
    public static <T extends JComponent> T fg(T c, java.util.function.Supplier<Color> color) {
        c.setForeground(color.get());
        BOUND.put(c, color);
        return c;
    }

    private static void reapplyBound() {
        java.util.List<Map.Entry<JComponent, java.util.function.Supplier<Color>>> all;
        synchronized (BOUND) {
            all = new java.util.ArrayList<>(BOUND.entrySet());
        }
        for (Map.Entry<JComponent, java.util.function.Supplier<Color>> e : all) {
            if (e.getKey() != null) e.getKey().setForeground(e.getValue().get());
        }
    }

    public static void apply(String name) {
        switch (name) {
            case "light" -> {
                dark = false;
                set("#F2F2F2", "#FFFFFF", "#FFFFFF", "#212121", "#6D6D6D", "#DDDDDD", "#CC0000", "#CC0000", "#EAEAEA", "#F8D6D6");
            }
            case "black" -> {
                dark = true;
                set("#000000", "#101010", "#000000", "#F2F2F2", "#9E9E9E", "#2A2A2A", "#000000", "#E53935", "#1C1C1C", "#3A1414");
            }
            default -> {
                dark = true;
                set("#212121", "#2B2B2B", "#1B1B1B", "#F2F2F2", "#A6A6A6", "#3A3A3A", "#992222", "#E53935", "#333333", "#4A2020");
            }
        }
        toolbarText = Color.WHITE;
        onAccent = Color.WHITE;
        if (yellow()) {
            switch (name) {
                case "light" -> {
                    toolbar = Color.decode("#FFC400");
                    accent = Color.decode("#B07D00");
                    selected = Color.decode("#FFF0BF");
                    toolbarText = Color.decode("#1A1A1A");
                }
                case "black" -> {
                    accent = Color.decode("#FFC107");
                    selected = Color.decode("#3A3000");
                }
                default -> {
                    toolbar = Color.decode("#D9A400");
                    accent = Color.decode("#FFC107");
                    selected = Color.decode("#4A3B0A");
                    toolbarText = Color.decode("#1A1A1A");
                }
            }
            onAccent = Color.decode("#1A1A1A");
        }
        Map<String, String> defaults = new HashMap<>();
        defaults.put("@background", hex(bg));
        defaults.put("@foreground", hex(text));
        defaults.put("@accentColor", hex(accent));
        defaults.put("@selectionBackground", hex(selected));
        defaults.put("@selectionForeground", hex(text));
        defaults.put("Component.focusColor", hex(accent));
        defaults.put("ScrollBar.thumbArc", "999");
        defaults.put("ScrollBar.width", "12");
        defaults.put("Button.arc", "8");
        defaults.put("Component.arc", "8");
        defaults.put("TextComponent.arc", "8");
        FlatLaf.setGlobalExtraDefaults(defaults);
        if (dark) FlatDarkLaf.setup();
        else FlatLightLaf.setup();
        FlatLaf.updateUI();
        reapplyBound();
        for (Runnable r : LISTENERS) r.run();
    }

    private static void set(String bg, String surface, String drawer, String text, String sub, String divider,
                            String toolbar, String accent, String hover, String selected) {
        Theme.bg = Color.decode(bg);
        Theme.surface = Color.decode(surface);
        Theme.drawer = Color.decode(drawer);
        Theme.text = Color.decode(text);
        Theme.sub = Color.decode(sub);
        Theme.divider = Color.decode(divider);
        Theme.toolbar = Color.decode(toolbar);
        Theme.accent = Color.decode(accent);
        Theme.hover = Color.decode(hover);
        Theme.selected = Color.decode(selected);
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static Font font(float size, int style) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font("Segoe UI", Font.PLAIN, 13);
        return base.deriveFont(style, size);
    }
}
