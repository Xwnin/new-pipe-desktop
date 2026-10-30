package org.newpipe.desktop.ui;

import org.newpipe.desktop.player.Player;
import org.newpipe.desktop.util.Fmt;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;

/** The transport bar under the video: seek bar, play/pause, tempo, quality, captions, volume, repeat, shuffle... */
public class PlayerControls extends JPanel {
    public interface Host {
        void toggleFullscreen();

        void togglePopup();

        boolean isFullscreen();
    }

    private static final int ICON = 28, BIG = 40, SMALL = 24;

    private final Player player = Player.get();
    private boolean syncing;
    private final Host host;
    private final boolean compact;

    private final SeekBar seek = new SeekBar();
    private final JLabel timeLeft = new JLabel("0:00");
    private final JLabel timeRight = new JLabel("0:00");
    private final JButton prev = btn("skip_previous", "Previous");
    private final JButton play = btn("play", "Play / Pause (Space)");
    private final JButton next = btn("skip_next", "Next");
    private final JButton tempo = new JButton("1.00x");
    private final JButton quality = new JButton("Auto");
    private final JButton captions = btn("closed_caption", "Subtitles");
    private final JButton mute = btn("volume_up", "Mute (M)");
    private final JSlider volume = new JSlider(0, 100, 100);
    private final JButton repeat = btn("repeat", "Repeat");
    private final JButton shuffle = btn("shuffle", "Shuffle");
    private final JButton popup = btn("pip", "Popup player");
    private final JButton fullscreen = btn("fullscreen", "Fullscreen (F)");

    public PlayerControls(Host host, boolean compact) {
        super(new BorderLayout(0, 0));
        this.host = host;
        this.compact = compact;
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        Font tf = Theme.font(14f, Font.PLAIN);
        timeLeft.setFont(tf);
        timeRight.setFont(tf);
        Theme.fg(timeLeft, () -> Theme.text);
        Theme.fg(timeRight, () -> Theme.sub);
        top.add(timeLeft, BorderLayout.WEST);
        top.add(seek, BorderLayout.CENTER);
        top.add(timeRight, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.setOpaque(false);
        left.add(prev);
        left.add(play);
        left.add(next);
        play.setIcon(Icons.get("play", BIG));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.setOpaque(false);
        styleText(tempo, "speed", "Playback parameters: tempo, pitch (< and > change tempo)");
        styleText(quality, null, "Quality");
        volume.setPreferredSize(new Dimension(72, 28));
        volume.setOpaque(false);
        volume.setFocusable(false);
        right.add(tempo);
        if (!compact) {
            right.add(quality);
            right.add(captions);
            right.add(mute);
            right.add(volume);
            right.add(repeat);
            right.add(shuffle);
        }
        right.add(popup);
        right.add(fullscreen);
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        add(row, BorderLayout.CENTER);

        // Hide the least important buttons progressively when the player column is narrow.
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int w = getWidth();
                boolean full = !compact;
                shuffle.setVisible(full && w >= 700);
                repeat.setVisible(full && w >= 700);
                volume.setVisible(full && w >= 600);
                captions.setVisible(full && w >= 520);
                mute.setVisible(full && w >= 520);
                quality.setVisible(full && w >= 440);
                revalidate();
            }
        });
        seek.setOnSeek(player::seekTo);
        seek.setOnScrub(v -> timeLeft.setText(Fmt.time(v)));
        prev.addActionListener(e -> player.previous());
        next.addActionListener(e -> player.next());
        play.addActionListener(e -> player.togglePause());
        tempo.addActionListener(e -> PlaybackParamsDialog.show(this));
        quality.addActionListener(e -> qualityMenu());
        captions.addActionListener(e -> captionMenu());
        mute.addActionListener(e -> player.toggleMute());
        volume.addChangeListener(e -> {
            if (!syncing) player.setVolume(volume.getValue());
        });
        repeat.addActionListener(e -> player.cycleRepeat());
        shuffle.addActionListener(e -> player.toggleShuffle());
        popup.addActionListener(e -> host.togglePopup());
        fullscreen.addActionListener(e -> host.toggleFullscreen());

        player.addListener(new Player.Listener() {
            @Override
            public void onTrack() {
                refresh();
            }

            @Override
            public void onState() {
                refreshPlay();
                seek.setBuffering(player.isBuffering());
            }

            @Override
            public void onProgress(double pos, double dur) {
                if (!seek.isDragging()) {
                    seek.setProgress(pos, dur);
                    timeLeft.setText(Fmt.time(pos));
                }
                timeRight.setText(Fmt.time(dur));
            }

            @Override
            public void onParams() {
                refreshParams();
            }
        });
        refresh();
    }

    private JButton btn(String icon, String tip) {
        JButton b = new JButton(Icons.get(icon, ICON));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setMargin(new Insets(6, 8, 6, 8));
        return b;
    }

    private void styleText(JButton b, String icon, String tip) {
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFont(Theme.font(15f, Font.BOLD));
        if (icon != null) b.setIcon(Icons.get(icon, SMALL, Theme.accent));
        b.setMargin(new Insets(6, 10, 6, 10));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (play != null) refresh();
    }

    private void refresh() {
        boolean has = player.current() != null;
        prev.setEnabled(has);
        next.setEnabled(has && (player.hasNext() || player.info() != null));
        seek.setSeekEnabled(player.info() != null);
        if (!has) {
            seek.setProgress(0, 0);
            timeLeft.setText("0:00");
            timeRight.setText("0:00");
        }
        refreshPlay();
        refreshParams();
    }

    private void refreshPlay() {
        String name = player.isEnded() ? "refresh" : player.isPaused() || player.current() == null ? "play" : "pause";
        play.setIcon(Icons.get(name, BIG));
        next.setEnabled(player.current() != null);
    }

    private void refreshParams() {
        tempo.setText(String.format(Locale.ROOT, "%.2fx", player.tempo()));
        tempo.setIcon(Icons.get("speed", SMALL, Math.abs(player.tempo() - 1.0) > 0.001 ? Theme.accent : Theme.text));
        String q = player.isAudioOnly() ? "Audio" : player.currentQualityLabel();
        quality.setText(q.isEmpty() ? "Auto" : q);
        quality.setEnabled(player.info() == null || player.hasVideo());
        mute.setIcon(Icons.get(player.isMuted() || player.volume() == 0 ? "volume_off" : "volume_up", ICON));
        int shown = player.isMuted() ? 0 : player.volume();
        if (volume.getValue() != shown) {
            syncing = true;
            volume.setValue(shown);
            syncing = false;
        }
        Player.Repeat r = player.repeat();
        repeat.setIcon(Icons.get(r == Player.Repeat.ONE ? "repeat_one" : "repeat", ICON, r == Player.Repeat.OFF ? Theme.text : Theme.accent));
        shuffle.setIcon(Icons.get("shuffle", ICON, player.shuffle() ? Theme.accent : Theme.text));
        fullscreen.setIcon(Icons.get(host.isFullscreen() ? "fullscreen_exit" : "fullscreen", ICON));
        popup.setIcon(Icons.get("pip", ICON, player.isPopup() ? Theme.accent : Theme.text));
        captions.setIcon(Icons.get("closed_caption", ICON, player.subtitle() != null ? Theme.accent : Theme.text));
        captions.setEnabled(!player.subtitles().isEmpty());
    }

    public void refreshAll() {
        refresh();
    }

    private void qualityMenu() {
        JPopupMenu m = new JPopupMenu();
        List<String> qs = player.qualities();
        String cur = player.currentQualityLabel();
        for (String q : qs) {
            JMenuItem mi = new JCheckBoxMenuItem(q, q.equals(cur) && !player.isAudioOnly());
            mi.addActionListener(e -> player.setQuality(q));
            m.add(mi);
        }
        if (!qs.isEmpty()) m.addSeparator();
        JMenuItem audio = new JCheckBoxMenuItem("Audio only (background)", player.isAudioOnly());
        audio.addActionListener(e -> player.setAudioOnly(!player.isAudioOnly()));
        m.add(audio);
        m.show(quality, 0, -m.getPreferredSize().height);
    }

    private void captionMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem off = new JCheckBoxMenuItem("Off", player.subtitle() == null);
        off.addActionListener(e -> player.setSubtitle(null));
        m.add(off);
        for (SubtitlesStream s : player.subtitles()) {
            String name = s.getDisplayLanguageName() + (s.isAutoGenerated() ? " (auto-generated)" : "");
            JMenuItem mi = new JCheckBoxMenuItem(name, s == player.subtitle());
            mi.addActionListener(e -> player.setSubtitle(s));
            m.add(mi);
        }
        m.show(captions, 0, -m.getPreferredSize().height);
    }
}
