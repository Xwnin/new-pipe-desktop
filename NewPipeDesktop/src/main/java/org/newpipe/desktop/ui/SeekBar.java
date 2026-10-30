package org.newpipe.desktop.ui;

import org.newpipe.desktop.util.Fmt;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.DoubleConsumer;

/** NewPipe style progress bar: thin track, red progress, round thumb that grows while dragging. */
public class SeekBar extends JComponent {
    private double value, max;
    private boolean dragging, hover;
    private double dragValue;
    private int hoverX = -1;
    private DoubleConsumer onSeek;
    private DoubleConsumer onScrub;
    private boolean enabledSeek = true;
    private boolean buffering;
    private float phase;
    private final javax.swing.Timer pulse = new javax.swing.Timer(40, e -> {
        phase = (phase + 0.02f) % 1f;
        repaint();
    });

    public SeekBar() {
        setPreferredSize(new Dimension(100, 30));
        setMinimumSize(new Dimension(40, 30));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!enabledSeek || max <= 0 || !SwingUtilities.isLeftMouseButton(e)) return;
                dragging = true;
                dragValue = at(e.getX());
                if (onScrub != null) onScrub.accept(dragValue);
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!dragging) return;
                dragValue = at(e.getX());
                hoverX = e.getX();
                if (onScrub != null) onScrub.accept(dragValue);
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!dragging) return;
                dragging = false;
                value = dragValue;
                if (onSeek != null) onSeek.accept(dragValue);
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover = true;
                hoverX = e.getX();
                setToolTipText(max > 0 ? Fmt.time(at(e.getX())) : null);
                repaint();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                hoverX = -1;
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    private double at(int x) {
        int pad = 8;
        double f = (x - pad) / (double) Math.max(1, getWidth() - 2 * pad);
        return Math.max(0, Math.min(1, f)) * max;
    }

    public void setOnSeek(DoubleConsumer c) {
        onSeek = c;
    }

    public void setOnScrub(DoubleConsumer c) {
        onScrub = c;
    }

    public void setBuffering(boolean b) {
        if (b == buffering) return;
        buffering = b;
        if (b) pulse.start();
        else pulse.stop();
        repaint();
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setSeekEnabled(boolean b) {
        enabledSeek = b;
    }

    public void setProgress(double v, double m) {
        if (dragging) {
            max = m;
            return;
        }
        if (v == value && m == max) return;
        value = v;
        max = m;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int pad = 8, w = getWidth() - 2 * pad, h = getHeight();
        int th = (hover || dragging) ? 6 : 4, y = h / 2 - th / 2;
        g.setColor(Theme.dark ? new Color(255, 255, 255, 60) : new Color(0, 0, 0, 50));
        g.fill(new RoundRectangle2D.Float(pad, y, w, th, th, th));
        double v = dragging ? dragValue : value;
        double f = max > 0 ? Math.max(0, Math.min(1, v / max)) : 0;
        int pw = (int) Math.round(w * f);
        if (buffering) {
            // a soft highlight sweeping along the track while the player waits for data
            int seg = Math.max(40, w / 6);
            int x0 = pad + (int) ((w + seg) * phase) - seg;
            Shape old = g.getClip();
            g.clipRect(pad, y - 2, w, th + 4);
            g.setPaint(new GradientPaint(x0, 0, new Color(255, 255, 255, 0), x0 + seg / 2f, 0, new Color(255, 255, 255, 110), true));
            g.fillRect(x0, y, seg, th);
            g.setClip(old);
        }
        g.setColor(Theme.accent);
        g.fill(new RoundRectangle2D.Float(pad, y, pw, th, th, th));
        if (max > 0) {
            int r = (hover || dragging) ? 9 : 6;
            g.fill(new Ellipse2D.Float(pad + pw - r, h / 2f - r, 2 * r, 2 * r));
        }
        g.dispose();
    }
}
