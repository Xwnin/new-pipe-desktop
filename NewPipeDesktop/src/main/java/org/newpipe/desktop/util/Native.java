package org.newpipe.desktop.util;

import java.io.File;

/** Finds bundled native helpers (libmpv-2.dll, ffmpeg.exe) next to the application or in ./native. */
public final class Native {
    private Native() {}

    public static File find(String name) {
        String override = System.getProperty("newpipe.native");
        if (override != null && !override.isEmpty()) {
            File f = new File(override, name);
            if (f.isFile()) return f;
        }
        File exeDir = null;
        try {
            exeDir = new File(Native.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        } catch (Exception ignored) {
        }
        String[] rel = {"native/" + name, "app/native/" + name, "../native/" + name, name};
        for (String r : rel) {
            File f = new File(r);
            if (f.isFile()) return f;
            for (File base = exeDir; base != null; base = base.getParentFile()) {
                File g = new File(base, r);
                if (g.isFile()) return g;
            }
        }
        throw new IllegalStateException(name + " not found (looked next to the application and in ./native)");
    }
}
