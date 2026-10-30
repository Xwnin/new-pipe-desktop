package org.newpipe.desktop.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Material style vector icons (bundled SVGs) plus the NewPipe logo. */
public final class Icons {
    private static final Map<String, Icon> CACHE = new HashMap<>();

    private Icons() {}

    public static Icon get(String name, int size, Color color) {
        java.util.function.Supplier<Color> source;
        // Theme colours are compared by identity so icons keep following the theme after a live switch
        if (color == Theme.text) source = () -> Theme.text;
        else if (color == Theme.sub) source = () -> Theme.sub;
        else if (color == Theme.accent) source = () -> Theme.accent;
        else if (color == Theme.toolbarText) source = () -> Theme.toolbarText;
        else source = () -> color;
        return new ThemedIcon(name, size, source);
    }

    private static Icon svg(String name, int size, Color color) {
        String key = name + size + color.getRGB();
        return CACHE.computeIfAbsent(key, k -> {
            FlatSVGIcon icon = new FlatSVGIcon("icons/" + name + ".svg", size, size);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color));
            return icon;
        });
    }

    private static final class ThemedIcon implements Icon {
        private final String name;
        private final int size;
        private final java.util.function.Supplier<Color> color;

        ThemedIcon(String name, int size, java.util.function.Supplier<Color> color) {
            this.name = name;
            this.size = size;
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            svg(name, size, color.get()).paintIcon(c, g, x, y);
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    public static Icon get(String name, int size) {
        return get(name, size, Theme.text);
    }

    /** An icon drawn in the toolbar text colour (white on red, dark on yellow). */
    public static Icon onToolbar(String name, int size) {
        return get(name, size, Theme.toolbarText);
    }

    public static Icon light(String name, int size) {
        return get(name, size, Color.WHITE);
    }

    /** A round placeholder avatar: the first letter of {@code name} on a colour derived from the name. */
    public static Icon initial(String name, int size) {
        String n = name == null ? "" : name.replaceAll("^[@\\s]+", "");
        String letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
        float hue = (Math.abs(n.hashCode()) % 360) / 360f;
        int scale = 2;
        BufferedImage img = new BufferedImage(size * scale, size * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.getHSBColor(hue, 0.45f, 0.62f));
        g.fillOval(0, 0, size * scale, size * scale);
        g.setColor(Color.WHITE);
        g.setFont(Theme.font(size * 0.5f * scale, Font.BOLD));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(letter, (size * scale - fm.stringWidth(letter)) / 2, (size * scale - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    /** The NewPipe logo: red disc with a white play triangle. */
    public static BufferedImage logo(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size;
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(0, 0, s, s));
        g.setColor(new Color(0xCD201F));
        g.fill(new Ellipse2D.Double(s * 0.045, s * 0.045, s * 0.91, s * 0.91));
        // NewPipe's mark: a play triangle whose left edge is a "pipe"
        g.setColor(Color.WHITE);
        Path2D p = new Path2D.Double();
        p.moveTo(s * 0.34, s * 0.22);
        p.lineTo(s * 0.34, s * 0.78);
        p.lineTo(s * 0.46, s * 0.78);
        p.lineTo(s * 0.46, s * 0.62);
        p.lineTo(s * 0.79, s * 0.50);
        p.lineTo(s * 0.46, s * 0.38);
        p.lineTo(s * 0.46, s * 0.22);
        p.closePath();
        g.fill(p);
        Path2D tri = new Path2D.Double();
        tri.moveTo(s * 0.46, s * 0.30);
        tri.lineTo(s * 0.70, s * 0.50);
        tri.lineTo(s * 0.46, s * 0.70);
        tri.closePath();
        g.fill(tri);
        g.dispose();
        return img;
    }

    public static List<Image> appIcons() {
        return List.of(logo(16), logo(24), logo(32), logo(48), logo(64), logo(128), logo(256));
    }
}
