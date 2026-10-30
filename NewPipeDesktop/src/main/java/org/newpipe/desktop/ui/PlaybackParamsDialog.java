package org.newpipe.desktop.ui;

import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.player.Player;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/**
 * NewPipe's "Playback parameters" dialog: tempo, pitch, step size and the "unhook" option that decouples pitch
 * from tempo. Changing tempo here never touches YouTube's own speed setting - it is time stretching done locally.
 */
public final class PlaybackParamsDialog extends JDialog {
    private static PlaybackParamsDialog instance;
    private static PlaybackParamsDialog floating;

    private final Player player = Player.get();
    private final JSlider tempoSlider = new JSlider(0, 1000, 500);
    private final JSlider pitchSlider = new JSlider(0, 1000, 500);
    private final JLabel tempoValue = new JLabel();
    private final JLabel pitchValue = new JLabel();
    private final JCheckBox unhook = new JCheckBox("Unhook (may cause distortion)");
    private final JCheckBox semitones = new JCheckBox("Show pitch as semitones");
    private final JCheckBox remember = new JCheckBox("Remember tempo and pitch");
    private final JComboBox<String> step = new JComboBox<>(new String[]{"1%", "5%", "10%", "25%", "100%"});
    private final JButton pitchMinus = new JButton(), pitchPlus = new JButton();
    private boolean updating;
    private final Runnable restore;
    private final JLabel nowPlaying = new JLabel();
    private final SeekBar seek = new SeekBar();
    private final JLabel timeLeft = new JLabel("0:00");
    private final JLabel timeRight = new JLabel("0:00");
    private final JButton playPause = new JButton();
    private Player.Listener listener;

    /**
     * Shows the controls in a small always-on-top window that is independent of the (minimized) main window, so
     * tempo and pitch can be changed while the video keeps playing in the background.
     */
    public static void showFloating(Runnable restoreMainWindow) {
        if (floating == null || !floating.isDisplayable()) {
            floating = new PlaybackParamsDialog(null, restoreMainWindow);
            Rectangle area = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            floating.setLocation(area.x + area.width - floating.getWidth() - 16, area.y + area.height - floating.getHeight() - 16);
        }
        floating.sync();
        floating.refreshHeader();
        floating.setVisible(true);
    }

    public static void hideFloating() {
        if (floating != null) {
            floating.dispose();
            floating = null;
        }
    }

    public static void show(Component owner) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new PlaybackParamsDialog(SwingUtilities.getWindowAncestor(owner), null);
        }
        instance.sync();
        instance.setLocationRelativeTo(owner);
        instance.setVisible(true);
        instance.toFront();
    }

    private PlaybackParamsDialog(Window owner, Runnable restore) {
        super(owner, "Playback parameters", ModalityType.MODELESS);
        this.restore = restore;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        if (restore != null) {
            setAlwaysOnTop(true);
            setTitle("NewPipe - playing");
            JPanel header = new JPanel(new BorderLayout(10, 0));
            header.setOpaque(false);
            header.setAlignmentX(LEFT_ALIGNMENT);
            playPause.setFocusable(false);
            playPause.putClientProperty("JButton.buttonType", "toolBarButton");
            playPause.addActionListener(e -> player.togglePause());
            nowPlaying.setFont(Theme.font(14f, Font.BOLD));
            nowPlaying.setPreferredSize(new Dimension(220, 28));
            JButton open = new JButton("Restore", Icons.get("open_in_new", 18));
            open.setFocusable(false);
            open.addActionListener(e -> restore.run());
            header.add(playPause, BorderLayout.WEST);
            header.add(nowPlaying, BorderLayout.CENTER);
            header.add(open, BorderLayout.EAST);
            root.add(header);
            root.add(Box.createVerticalStrut(8));

            // seek row: -10 s, position, seek bar, duration, +10 s (Left / Right arrow keys seek 5 s)
            JPanel seekRow = new JPanel(new BorderLayout(8, 0));
            seekRow.setOpaque(false);
            seekRow.setAlignmentX(LEFT_ALIGNMENT);
            JButton back = skipButton("fast_rewind", "Back 10 seconds (J)", -10);
            JButton fwd = skipButton("fast_forward", "Forward 10 seconds (L)", 10);
            JPanel mid = new JPanel(new BorderLayout(8, 0));
            mid.setOpaque(false);
            Font tf = Theme.font(13f, Font.PLAIN);
            timeLeft.setFont(tf);
            timeRight.setFont(tf);
            Theme.fg(timeRight, () -> Theme.sub);
            seek.setOnSeek(player::seekTo);
            seek.setOnScrub(v -> timeLeft.setText(org.newpipe.desktop.util.Fmt.time(v)));
            mid.add(timeLeft, BorderLayout.WEST);
            mid.add(seek, BorderLayout.CENTER);
            mid.add(timeRight, BorderLayout.EAST);
            seekRow.add(back, BorderLayout.WEST);
            seekRow.add(mid, BorderLayout.CENTER);
            seekRow.add(fwd, BorderLayout.EAST);
            root.add(seekRow);
            JLabel hint = new JLabel("\u2190 / \u2192 seek 5 s   \u2022   Space pause   \u2022   , / . tempo");
            Theme.fg(hint, () -> Theme.sub);
            hint.setFont(Theme.font(11.5f, Font.PLAIN));
            hint.setAlignmentX(LEFT_ALIGNMENT);
            root.add(Box.createVerticalStrut(2));
            root.add(hint);
            root.add(Box.createVerticalStrut(12));
        }

        root.add(title("Tempo"));
        root.add(sliderRow(tempoSlider, tempoValue, new JButton(Icons.get("remove", 18)), new JButton(Icons.get("add", 18)), true));
        root.add(Box.createVerticalStrut(6));
        root.add(presetRow());
        root.add(Box.createVerticalStrut(14));
        root.add(title("Pitch"));
        root.add(sliderRow(pitchSlider, pitchValue, pitchMinus, pitchPlus, false));
        root.add(Box.createVerticalStrut(14));

        JPanel stepRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        stepRow.setOpaque(false);
        stepRow.setAlignmentX(LEFT_ALIGNMENT);
        stepRow.add(new JLabel("Step size   "));
        stepRow.add(step);
        root.add(stepRow);
        root.add(Box.createVerticalStrut(10));
        for (JCheckBox c : new JCheckBox[]{unhook, semitones, remember}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            c.setOpaque(false);
            root.add(c);
        }
        root.add(Box.createVerticalStrut(12));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        JButton reset = new JButton("Reset");
        JButton close = new JButton("Close");
        close.putClientProperty("JButton.buttonType", "default");
        buttons.add(reset);
        buttons.add(close);
        root.add(buttons);
        setContentPane(root);

        tempoSlider.addChangeListener(e -> {
            if (updating) return;
            player.setTempo(tempoFromSlider(tempoSlider.getValue()));
        });
        pitchSlider.addChangeListener(e -> {
            if (updating) return;
            player.setPitch(pitchFromSlider(pitchSlider.getValue()));
        });
        unhook.addActionListener(e -> player.setUnhook(unhook.isSelected()));
        semitones.addActionListener(e -> {
            Settings.put("pitchSemitones", semitones.isSelected());
            sync();
        });
        remember.addActionListener(e -> {
            Settings.put("rememberTempo", remember.isSelected());
            if (remember.isSelected()) {
                Settings.put("tempo", player.tempo());
                Settings.put("pitch", player.pitch());
            }
        });
        step.addActionListener(e -> {
            if (updating) return;
            Settings.put("tempoStep", stepValue());
            sync();
        });
        reset.addActionListener(e -> player.resetTempo());
        close.addActionListener(e -> dispose());

        listener = new Player.Listener() {
            @Override
            public void onParams() {
                if (isShowing()) sync();
            }

            @Override
            public void onProgress(double pos, double dur) {
                if (restore != null && isShowing() && !seek.isDragging()) {
                    seek.setProgress(pos, dur);
                    timeLeft.setText(org.newpipe.desktop.util.Fmt.time(pos));
                    timeRight.setText(org.newpipe.desktop.util.Fmt.time(dur));
                }
            }

            @Override
            public void onTrack() {
                if (isShowing()) refreshHeader();
            }

            @Override
            public void onState() {
                if (isShowing()) refreshHeader();
            }
        };
        player.addListener(listener);
        pack();
        setMinimumSize(new Dimension(420, getHeight()));
        setResizable(false);
    }

    private JButton skipButton(String icon, String tip, double seconds) {
        JButton b = new JButton(Icons.get(icon, 24));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.addActionListener(e -> player.seekBy(seconds));
        return b;
    }

    /** True while the floating (minimized) control window is the active window. */
    public static boolean floatingActive() {
        return floating != null && floating.isActive();
    }

    private void refreshHeader() {
        if (restore == null) return;
        nowPlaying.setText(player.current() == null ? "Nothing playing" : player.current().name);
        seek.setProgress(player.position(), player.duration());
        timeLeft.setText(org.newpipe.desktop.util.Fmt.time(player.position()));
        timeRight.setText(org.newpipe.desktop.util.Fmt.time(player.duration()));
        playPause.setIcon(Icons.get(player.isPaused() || player.isEnded() ? "play" : "pause", 28));
    }

    @Override
    public void dispose() {
        if (listener != null) player.removeListener(listener);
        super.dispose();
    }

    // The tempo slider is piecewise linear so that 1x sits exactly in the middle:
    // left half 0.10x..1x, right half 1x..4x.
    private static double tempoFromSlider(int pos) {
        if (Math.abs(pos - 500) <= 6) return 1.0; // snap to 1x
        return pos <= 500 ? 0.1 + 0.9 * pos / 500.0 : 1.0 + 3.0 * (pos - 500) / 500.0;
    }

    private static int tempoToSlider(double tempo) {
        return (int) Math.round(tempo <= 1.0 ? (tempo - 0.1) / 0.9 * 500.0 : 500.0 + (tempo - 1.0) / 3.0 * 500.0);
    }

    // Pitch slider: 100% in the middle, left half 25%..100%, right half 100%..400%.
    private static double pitchFromSlider(int pos) {
        if (Math.abs(pos - 500) <= 6) return 1.0; // snap to 100%
        return pos <= 500 ? 0.25 + 0.75 * pos / 500.0 : 1.0 + 3.0 * (pos - 500) / 500.0;
    }

    private static int pitchToSlider(double pitch) {
        return (int) Math.round(pitch <= 1.0 ? (pitch - 0.25) / 0.75 * 500.0 : 500.0 + (pitch - 1.0) / 3.0 * 500.0);
    }

    private double stepValue() {
        return switch (step.getSelectedIndex()) {
            case 0 -> 0.01;
            case 1 -> 0.05;
            case 2 -> 0.10;
            case 3 -> 0.25;
            default -> 1.0;
        };
    }

    private JLabel title(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Theme.font(13f, Font.BOLD));
        Theme.fg(l, () -> Theme.accent);
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return l;
    }

    private JPanel sliderRow(JSlider slider, JLabel value, JButton minus, JButton plus, boolean tempo) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        value.setFont(Theme.font(18f, Font.BOLD));
        value.setPreferredSize(new Dimension(86, 28));
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        for (JButton b : new JButton[]{minus, plus}) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
        }
        minus.addActionListener(e -> nudge(tempo, -1));
        plus.addActionListener(e -> nudge(tempo, +1));
        JPanel mid = new JPanel(new BorderLayout(4, 0));
        mid.setOpaque(false);
        mid.add(minus, BorderLayout.WEST);
        mid.add(slider, BorderLayout.CENTER);
        mid.add(plus, BorderLayout.EAST);
        p.add(mid, BorderLayout.CENTER);
        p.add(value, BorderLayout.EAST);
        slider.setOpaque(false);
        return p;
    }

    private JPanel presetRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        for (double v : new double[]{0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0}) {
            JButton b = new JButton(trim(v) + "x");
            b.setMargin(new Insets(2, 8, 2, 8));
            b.setFocusable(false);
            b.addActionListener(e -> player.setTempo(v));
            p.add(b);
        }
        return p;
    }

    private void nudge(boolean tempo, int dir) {
        double s = tempo ? stepValue() : stepValue();
        if (tempo) player.setTempo(player.tempo() + dir * s);
        else player.setPitch(player.pitch() + dir * s);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private void sync() {
        updating = true;
        if (!tempoSlider.getValueIsAdjusting()) tempoSlider.setValue(tempoToSlider(player.tempo()));
        if (!pitchSlider.getValueIsAdjusting()) pitchSlider.setValue(pitchToSlider(player.pitch()));
        tempoValue.setText(String.format(Locale.ROOT, "%.2fx", player.tempo()));
        boolean semi = Settings.bool("pitchSemitones", false);
        semitones.setSelected(semi);
        if (semi) {
            double st = 12 * Math.log(player.pitch()) / Math.log(2);
            pitchValue.setText(String.format(Locale.ROOT, "%+.1f st", st));
        } else {
            pitchValue.setText(Math.round(player.pitch() * 100) + "%");
        }
        unhook.setSelected(player.unhook());
        remember.setSelected(Settings.rememberTempo());
        pitchSlider.setEnabled(player.unhook());
        pitchMinus.setEnabled(player.unhook());
        pitchPlus.setEnabled(player.unhook());
        pitchMinus.setIcon(Icons.get("remove", 18));
        pitchPlus.setIcon(Icons.get("add", 18));
        double sv = Settings.tempoStep();
        int idx = sv <= 0.011 ? 0 : sv <= 0.051 ? 1 : sv <= 0.101 ? 2 : sv <= 0.251 ? 3 : 4;
        step.setSelectedIndex(idx);
        updating = false;
    }
}
