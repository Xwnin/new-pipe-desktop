package org.newpipe.desktop.ui;

import javax.swing.*;
import java.awt.*;

/** A screen that can be shown in the main content area. */
public abstract class Page extends JPanel {
    protected final Nav nav;
    private Runnable titleListener;

    protected Page(Nav nav) {
        super(new BorderLayout());
        this.nav = nav;
        setOpaque(true);
    }

    public abstract String title();

    public void setTitleListener(Runnable r) {
        titleListener = r;
    }

    /** Pages call this when {@link #title()} changed (e.g. after the channel name was loaded). */
    protected void titleChanged() {
        if (titleListener != null) titleListener.run();
    }

    /** Called every time the page becomes the visible one. */
    public void onShown() {}

    /** Called when the page is dropped from the navigation stack for good. */
    public void onDiscard() {}
}
