package org.newpipe.desktop;

import org.newpipe.desktop.data.Settings;
import org.newpipe.desktop.net.HttpDownloader;
import org.newpipe.desktop.ui.MainFrame;
import org.newpipe.desktop.ui.Theme;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import javax.swing.*;
import java.awt.EventQueue;
import java.util.Locale;

public class App {
    public static void main(String[] args) throws Exception {
        System.setProperty("jna.encoding", "UTF-8");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("flatlaf.menuBarEmbedded", "false");

        Locale locale = Locale.getDefault();
        Localization loc = Localization.fromLocale(locale);
        String cc = locale.getCountry().isEmpty() ? "GB" : locale.getCountry();
        NewPipe.init(HttpDownloader.get(), loc, new ContentCountry(cc));

        SwingUtilities.invokeAndWait(() -> {
            Theme.apply(Settings.theme());
            MainFrame f = new MainFrame();
            f.setVisible(true);
            EventQueue.invokeLater(f::init);
            Debug.start(f, args);
        });
    }
}
