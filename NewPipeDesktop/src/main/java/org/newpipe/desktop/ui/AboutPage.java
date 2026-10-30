package org.newpipe.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.net.URI;

public class AboutPage extends Page {
    public AboutPage(Nav nav) {
        super(nav);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));

        JLabel logo = new JLabel(new ImageIcon(Icons.logo(96)));
        logo.setAlignmentX(LEFT_ALIGNMENT);
        JLabel name = new JLabel("NewPipe for Windows");
        name.setFont(Theme.font(24f, Font.BOLD));
        name.setAlignmentX(LEFT_ALIGNMENT);
        JLabel tag = new JLabel("A lightweight YouTube / SoundCloud experience with local tempo and pitch control.");
        Theme.fg(tag, () -> Theme.sub);
        tag.setAlignmentX(LEFT_ALIGNMENT);
        JTextArea text = new JTextArea(
                "Streams are extracted on this PC with NewPipeExtractor, the same library that powers NewPipe on Android, "
                        + "and played with libmpv. No Google account, no ads, no Google services.\n\n"
                        + "Tempo and pitch are applied locally by time-stretching the audio, so changing the speed does "
                        + "not use YouTube's own playback speed and keeps the voice natural.\n\n"
                        + "Shortcuts:  Space play/pause  •  ← → seek 5 s  •  J L seek 10 s  •  "
                        + "< > (comma, period) tempo down / up  •  ↑ ↓ volume  •  M mute  •  F fullscreen  "
                        + "•  N P next / previous  •  Ctrl+L search");
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setAlignmentX(LEFT_ALIGNMENT);
        text.setMaximumSize(new Dimension(640, 220));
        text.setFont(Theme.font(14f, Font.PLAIN));
        JButton site = new JButton("NewPipe project website");
        site.setAlignmentX(LEFT_ALIGNMENT);
        site.addActionListener(e -> nav.openUrl("https://newpipe.net"));
        JButton src = new JButton("NewPipeExtractor on GitHub");
        src.setAlignmentX(LEFT_ALIGNMENT);
        src.addActionListener(e -> nav.openUrl("https://github.com/TeamNewPipe/NewPipeExtractor"));
        box.add(logo);
        box.add(Box.createVerticalStrut(14));
        box.add(name);
        box.add(Box.createVerticalStrut(4));
        box.add(tag);
        box.add(Box.createVerticalStrut(20));
        box.add(text);
        box.add(Box.createVerticalStrut(20));
        box.add(site);
        box.add(Box.createVerticalStrut(8));
        box.add(src);
        add(box, BorderLayout.NORTH);
    }

    @Override
    public String title() {
        return "About";
    }
}
