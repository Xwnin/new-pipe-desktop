package org.newpipe.desktop;

import org.newpipe.desktop.data.Item;
import org.newpipe.desktop.player.Player;
import org.newpipe.desktop.ui.MainFrame;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Developer aid: runs a scripted scenario (-Dnewpipe.script=file) against the live UI and takes screenshots. */
final class Debug {
    private Debug() {}

    static void start(MainFrame f, String[] args) {
        String script = System.getProperty("newpipe.script");
        if (script == null) return;
        String log = System.getProperty("newpipe.scriptlog");
        if (log != null) {
            try {
                System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(log), true));
                System.setErr(System.out);
            } catch (Exception ignored) {
            }
        }
        Thread t = new Thread(() -> run(f, script), "debug-script");
        t.setDaemon(true);
        t.start();
    }

    /** user32 call missing from jna-platform's User32 interface. */
    interface HitApi extends com.sun.jna.win32.StdCallLibrary {
        com.sun.jna.platform.win32.WinDef.HWND WindowFromPoint(com.sun.jna.platform.win32.WinDef.POINT.ByValue p);
    }

    private static Item sample(String url, String name) {
        Item c = new Item();
        c.kind = Item.Kind.CHANNEL;
        c.url = url;
        c.name = name;
        return c;
    }

    private static void ui(Runnable r) throws Exception {
        EventQueue.invokeAndWait(r);
    }

    private static void run(MainFrame f, String script) {
        try {
            Player p = Player.get();
            List<String> lines = Files.readAllLines(new File(script).toPath());
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] a = line.split("\\s+", 2);
                String arg = a.length > 1 ? a[1] : "";
                System.out.println("[script] " + line);
                switch (a[0]) {
                    case "sleep" -> Thread.sleep(Long.parseLong(arg));
                    case "size" -> {
                        String[] wh = arg.split("\\s+");
                        ui(() -> f.setSize(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])));
                    }
                    case "root" -> ui(() -> f.showRoot(arg));
                    case "search" -> ui(() -> f.search(arg));
                    case "back" -> ui(f::back);
                    case "play" -> ui(() -> {
                        Item it = new Item();
                        it.url = arg;
                        f.play(it);
                    });
                    case "tempo" -> ui(() -> p.setTempo(Double.parseDouble(arg)));
                    case "pitch" -> ui(() -> p.setPitch(Double.parseDouble(arg)));
                    case "unhook" -> ui(() -> p.setUnhook(Boolean.parseBoolean(arg)));
                    case "quality" -> ui(() -> p.setQuality(arg));
                    case "audioonly" -> ui(() -> p.setAudioOnly(Boolean.parseBoolean(arg)));
                    case "pause" -> ui(p::pause);
                    case "resume" -> ui(p::play);
                    case "seek" -> ui(() -> p.seekTo(Double.parseDouble(arg)));
                    case "next" -> ui(p::next);
                    case "popup" -> ui(() -> f.videoPage().togglePopup());
                    case "fullscreen" -> ui(() -> f.videoPage().toggleFullscreen());
                    case "dialog" -> ui(() -> org.newpipe.desktop.ui.PlaybackParamsDialog.show(f.videoPage()));
                    case "download" -> ui(() -> {
                        Item it = new Item();
                        it.url = arg;
                        it.name = arg;
                        f.download(it);
                    });
                    case "open" -> {
                        // open <stream|channel|playlist> <url>
                        String[] x = arg.split("\s+", 2);
                        ui(() -> {
                            Item it = new Item();
                            it.kind = Item.Kind.valueOf(x[0].toUpperCase());
                            it.url = x[1];
                            it.name = x[1];
                            f.open(it);
                        });
                    }
                    case "openfirst" -> {
                        // openfirst <filter> <query>
                        String[] x = arg.split("\s+", 2);
                        var res = org.newpipe.desktop.data.Backend.search(0, x[1], x[0]).first();
                        Item first = res.items().get(0);
                        System.out.println("   opening " + first.kind + " " + first.name + " " + first.url);
                        ui(() -> f.open(first));
                    }
                    case "subscribe" -> {
                        Item c = new Item();
                        c.kind = Item.Kind.CHANNEL;
                        String[] x = arg.split("\s+", 2);
                        c.url = x[0];
                        c.name = x[1];
                        org.newpipe.desktop.data.Store.subscribe(c);
                    }
                    case "service" -> ui(() -> {
                        org.newpipe.desktop.data.Settings.put("service", Integer.parseInt(arg));
                        f.showRoot("trending");
                    });
                    case "dltest" -> {
                        // dltest <audio|video> <index> <url>
                        String[] x = arg.split("\\s+", 3);
                        var si = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(x[2]);
                        var opts = x[0].equals("audio") ? org.newpipe.desktop.data.Downloads.audioOptions(si)
                                : org.newpipe.desktop.data.Downloads.videoOptions(si);
                        for (var o : opts) System.out.println("   option: " + o.label + " ." + o.ext);
                        var job = org.newpipe.desktop.data.Downloads.start(si.getName(), opts.get(Integer.parseInt(x[1])),
                                new File("build/dl"), si.getName());
                        long end = System.currentTimeMillis() + 120000;
                        while (System.currentTimeMillis() < end && (job.state == org.newpipe.desktop.data.Downloads.State.QUEUED
                                || job.state == org.newpipe.desktop.data.Downloads.State.RUNNING)) {
                            Thread.sleep(1000);
                            System.out.printf("   %s %d/%d%n", job.state, job.done, job.total);
                        }
                        System.out.println("   result: " + job.state + " " + job.file + " " + (job.file.exists() ? job.file.length() : -1) + " bytes " + job.error);
                    }
                    case "state" -> {
                        String[] s = new String[1];
                        ui(() -> s[0] = String.format("pos=%.2f dur=%.2f paused=%s buffering=%s tempo=%.2f pitch=%.3f unhook=%s q=%s audioOnly=%s err=%s loading=%s",
                                p.position(), p.duration(), p.isPaused(), p.isBuffering(), p.tempo(), p.pitch(), p.unhook(),
                                p.currentQualityLabel(), p.isAudioOnly(), p.error(), p.isLoading()));
                        System.out.println("[state] " + s[0] + " ao=" + p.mpvProperty("current-ao") + " audio=" + p.mpvProperty("audio-params")
                                + " vo=" + p.mpvProperty("current-vo") + " hwdec=" + p.mpvProperty("hwdec-current")
                                + " af=" + p.mpvProperty("af") + " speed=" + p.mpvProperty("speed"));
                    }
                    case "click" -> {
                        // click [dbl]  - posts mouse messages to the mpv window inside the video canvas
                        long[] h = new long[1];
                        ui(() -> h[0] = f.videoPage().nativeHandle());
                        var u = com.sun.jna.platform.win32.User32.INSTANCE;
                        var parent = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(h[0]));
                        var child = u.FindWindowEx(parent, null, null, null);
                        var r = new com.sun.jna.platform.win32.WinDef.RECT();
                        u.GetClientRect(parent, r);
                        int x = (r.right - r.left) / 2, y = (r.bottom - r.top) / 2;
                        var lp = new com.sun.jna.platform.win32.WinDef.LPARAM((long) y << 16 | x);
                        var z = new com.sun.jna.platform.win32.WinDef.WPARAM(1);
                        var zero = new com.sun.jna.platform.win32.WinDef.WPARAM(0);
                        System.out.println("   mpv child window: " + child);
                        if (arg.contains("move")) {
                            // what a real mouse does: hover, press, wiggle by a pixel or two, release
                            var lp2 = new com.sun.jna.platform.win32.WinDef.LPARAM((long) (y + 1) << 16 | (x + 2));
                            u.PostMessage(child, 0x0200, zero, lp);
                            Thread.sleep(40);
                            u.PostMessage(child, 0x0201, z, lp);
                            Thread.sleep(30);
                            u.PostMessage(child, 0x0200, z, lp2);
                            Thread.sleep(30);
                            u.PostMessage(child, 0x0202, zero, lp2);
                        } else {
                            u.PostMessage(child, 0x0201, z, lp);
                            u.PostMessage(child, 0x0202, zero, lp);
                        }
                        if (arg.equals("dbl")) {
                            Thread.sleep(60);
                            u.PostMessage(child, 0x0201, z, lp);
                            u.PostMessage(child, 0x0202, zero, lp);
                        }
                    }
                    case "jclick" -> ui(() -> {
                        var c = f.videoPage().canvasForDebug();
                        int n = arg.isEmpty() ? 1 : Integer.parseInt(arg);
                        for (int i = 1; i <= n; i++) {
                            c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                                    c.getWidth() / 2, c.getHeight() / 2, i, false, java.awt.event.MouseEvent.BUTTON1));
                        }
                    });
                    case "key" -> ui(() -> {
                        // key <VK name> : sends a key press through the focus manager (so the global shortcuts see it)
                        int code = java.awt.event.KeyEvent.class.cast(null) == null ? 0 : 0;
                        try {
                            code = java.awt.event.KeyEvent.class.getField("VK_" + arg).getInt(null);
                        } catch (Exception ignored) {
                        }
                        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                        System.out.println("   key " + arg + " active window=" + (w == null ? null : w.getClass().getSimpleName()));
                        java.awt.Component target = w == null ? f : w;
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new java.awt.event.KeyEvent(target,
                                java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED));
                    });
                    case "hit" -> {
                        // what does Windows say is under the middle of the video canvas?
                        long[] h = new long[1];
                        int[] pt = new int[4];
                        ui(() -> {
                            h[0] = f.videoPage().nativeHandle();
                            var c = f.videoPage().canvasForDebug();
                            var loc = c.getLocationOnScreen();
                            var sc = c.getGraphicsConfiguration().getDefaultTransform();
                            pt[0] = (int) ((loc.x + c.getWidth() / 2) * sc.getScaleX());
                            pt[1] = (int) ((loc.y + c.getHeight() / 2) * sc.getScaleY());
                            pt[2] = (int) (c.getWidth() * sc.getScaleX());
                            pt[3] = (int) (c.getHeight() * sc.getScaleY());
                        });
                        var u = com.sun.jna.platform.win32.User32.INSTANCE;
                        var parent = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(h[0]));
                        var child = u.FindWindowEx(parent, null, null, null);
                        var under = com.sun.jna.Native.load("user32", HitApi.class).WindowFromPoint(new com.sun.jna.platform.win32.WinDef.POINT.ByValue(pt[0], pt[1]));
                        var rc = new com.sun.jna.platform.win32.WinDef.RECT();
                        u.GetClientRect(child, rc);
                        var rp = new com.sun.jna.platform.win32.WinDef.RECT();
                        u.GetClientRect(parent, rp);
                        System.out.println("   canvas=" + parent + " (client " + (rp.right - rp.left) + "x" + (rp.bottom - rp.top) + ") mpvChild=" + child
                                + " (client " + (rc.right - rc.left) + "x" + (rc.bottom - rc.top) + ") underCenterPoint=" + under
                                + " visible(child)=" + u.IsWindowVisible(child) + " point=" + pt[0] + "," + pt[1]);
                    }
                    case "avatars" -> {
                        var ci = org.schabi.newpipe.extractor.comments.CommentsInfo.getInfo(arg);
                        for (int i = 0; i < Math.min(3, ci.getRelatedItems().size()); i++) {
                            var c = ci.getRelatedItems().get(i);
                            String u = Item.bestImage(c.getUploaderAvatars());
                            System.out.println("   avatar url=" + u + " (" + c.getUploaderAvatars().size() + " candidates)");
                            try (var resp = org.newpipe.desktop.net.HttpDownloader.get().client().newCall(
                                    new okhttp3.Request.Builder().url(u).build()).execute()) {
                                System.out.println("     -> " + resp.code() + " " + resp.header("Content-Type"));
                            } catch (Exception e) {
                                System.out.println("     -> " + e);
                            }
                        }
                    }
                    case "pcm" -> {
                        // pcm <input.wav> <out.wav> <tempo> <pitch> <none|default|finer>
                        String[] x = arg.split("\\s+");
                        double tempo = Double.parseDouble(x[2]), pitch = Double.parseDouble(x[3]);
                        System.setProperty("newpipe.mpvopts", "ao=pcm;ao-pcm-file=" + x[1] + ";ao-pcm-waveheader=yes");
                        var m = new org.newpipe.desktop.player.Mpv(0, false);
                        System.clearProperty("newpipe.mpvopts");
                        m.set("speed", Double.toString(tempo));
                        int rc = 0;
                        if (x[4].equals("finer")) rc = m.command("af", "add", org.newpipe.desktop.player.Player.filterSpec(pitch));
                        else if (x[4].equals("default")) rc = m.command("af", "add", "@np:rubberband=pitch-scale=" + pitch);
                        else if (x[4].startsWith("raw:")) rc = m.command("af", "add", "@np:rubberband=" + x[4].substring(4) + ":pitch-scale=" + pitch);
                        m.command("loadfile", x[0]);
                        long end = System.currentTimeMillis() + 40000;
                        Thread.sleep(1500);
                        while (System.currentTimeMillis() < end && !"yes".equals(m.get("eof-reached"))) Thread.sleep(200);
                        Thread.sleep(500);
                        System.out.println("   af rc=" + rc + " af=" + m.get("af") + " rendered " + x[1]);
                        m.close();
                        Thread.sleep(1500);
                    }
                    case "minimize" -> ui(() -> f.setExtendedState(Frame.ICONIFIED));
                    case "restorewin" -> ui(() -> f.setExtendedState(Frame.NORMAL));
                    case "windows" -> ui(() -> {
                        StringBuilder sb = new StringBuilder("   windows:");
                        for (Window w : Window.getWindows()) {
                            if (w.isShowing()) sb.append(" [").append(w instanceof Frame fr ? fr.getTitle() : w instanceof Dialog d ? d.getTitle() : w.getName())
                                    .append(" onTop=").append(w.isAlwaysOnTop()).append("]");
                        }
                        System.out.println(sb);
                    });
                    case "shotwin" -> {
                        // shotwin <title text>|<file>  - renders a window of this app by title
                        String[] x = arg.split("\\|");
                        BufferedImage[] img = new BufferedImage[1];
                        ui(() -> {
                            for (Window w : Window.getWindows()) {
                                String t = w instanceof Frame fr ? fr.getTitle() : w instanceof Dialog d ? d.getTitle() : "";
                                if (w.isShowing() && t != null && t.contains(x[0])) {
                                    img[0] = new BufferedImage(w.getWidth(), w.getHeight(), BufferedImage.TYPE_INT_RGB);
                                    Graphics2D g = img[0].createGraphics();
                                    ((javax.swing.RootPaneContainer) w).getContentPane().paint(g);
                                    g.dispose();
                                    break;
                                }
                            }
                        });
                        if (img[0] == null) System.out.println("   no such window: " + x[0]);
                        else {
                            File out = new File(x[1]);
                            out.getParentFile().mkdirs();
                            ImageIO.write(img[0], "png", out);
                        }
                    }
                    case "hooktest" -> {
                        // hooktest <in.wav> <out.wav> <order>   plays 3 s at 1x, then switches to hooked 0.75x at runtime
                        String[] x = arg.split("\\s+");
                        System.setProperty("newpipe.mpvopts", "ao=pcm;ao-pcm-file=" + x[1] + ";ao-pcm-waveheader=yes");
                        var m = new org.newpipe.desktop.player.Mpv(0, false);
                        System.clearProperty("newpipe.mpvopts");
                        m.set("audio-pitch-correction", "yes");
                        m.command("loadfile", x[0]);
                        Thread.sleep(3000);
                        if (x[2].equals("speedfirst")) {
                            m.set("speed", "0.75");
                            m.set("audio-pitch-correction", "no");
                        } else {
                            m.set("audio-pitch-correction", "no");
                            m.set("speed", "0.75");
                        }
                        long end = System.currentTimeMillis() + 40000;
                        while (System.currentTimeMillis() < end && !"yes".equals(m.get("eof-reached"))) Thread.sleep(200);
                        Thread.sleep(500);
                        m.close();
                        Thread.sleep(1500);
                        System.out.println("   hooktest done " + x[1]);
                    }
                    case "hooktest2" -> {
                        // hooktest2 <in.wav> <out.wav> <hookedFromStart|switchAtRuntime>
                        String[] x = arg.split("\\s+");
                        System.setProperty("newpipe.mpvopts", "ao=pcm;ao-pcm-file=" + x[1] + ";ao-pcm-waveheader=yes");
                        var m = new org.newpipe.desktop.player.Mpv(0, false);
                        System.clearProperty("newpipe.mpvopts");
                        m.set("pause", "yes");
                        if (x[2].equals("hookedFromStart")) {
                            m.set("audio-pitch-correction", "no");
                            m.set("speed", "0.75");
                            m.command("loadfile", x[0]);
                            Thread.sleep(1500);
                        } else {
                            m.set("audio-pitch-correction", "yes");
                            m.command("loadfile", x[0]);
                            Thread.sleep(1500);
                            // same sequence the player performs: unhooked 1.25x (stretcher on), then hook + 0.75x
                            m.command("af", "add", org.newpipe.desktop.player.Player.filterSpec(1.0));
                            m.set("speed", "1.25");
                            Thread.sleep(400);
                            m.command("af", "remove", "@np");
                            m.set("audio-pitch-correction", "no");
                            m.set("speed", "0.75");
                            Thread.sleep(400);
                        }
                        m.set("pause", "no");
                        long end = System.currentTimeMillis() + 40000;
                        Thread.sleep(1000);
                        while (System.currentTimeMillis() < end && !"yes".equals(m.get("eof-reached"))) Thread.sleep(200);
                        Thread.sleep(500);
                        m.close();
                        Thread.sleep(1500);
                        System.out.println("   hooktest2 done " + x[1]);
                    }
                    case "tab" -> ui(() -> f.videoPage().showSideTab(Integer.parseInt(arg)));
                    case "live" -> {
                        var res = org.newpipe.desktop.data.Backend.trending(0).first();
                        Item first = null;
                        for (Item i : res.items()) if (i.live) { first = i; break; }
                        final Item pick = first == null ? res.items().get(0) : first;
                        System.out.println("   playing " + pick.name + " live=" + pick.live + " " + pick.url);
                        ui(() -> f.play(pick));
                    }
                    case "subio" -> {
                        File tmp = new File(arg);
                        org.newpipe.desktop.data.Store.subscribe(sample("https://www.youtube.com/channel/UCSMOQeBJ2RAnuFungnQOxLg", "Blender Guru"));
                        org.newpipe.desktop.data.SubscriptionIO.export(tmp);
                        System.out.println("   exported: " + Files.readString(tmp.toPath()).replaceAll("\\s+", " "));
                        org.newpipe.desktop.data.Store.unsubscribe("https://www.youtube.com/channel/UCSMOQeBJ2RAnuFungnQOxLg");
                        int n = org.newpipe.desktop.data.SubscriptionIO.importFrom(tmp);
                        System.out.println("   imported back: " + n + " new, subscribed=" + org.newpipe.desktop.data.Store.isSubscribed("https://www.youtube.com/channel/UCSMOQeBJ2RAnuFungnQOxLg"));
                        File csv = new File(tmp.getParentFile(), "takeout.csv");
                        Files.writeString(csv.toPath(), "Channel Id,Channel Url,Channel Title\nUCabc123,http://www.youtube.com/channel/UCabc123,\"Some, Channel\"\n");
                        System.out.println("   csv import: " + org.newpipe.desktop.data.SubscriptionIO.importFrom(csv) + " new");
                        org.newpipe.desktop.data.Store.unsubscribe("http://www.youtube.com/channel/UCabc123");
                        org.newpipe.desktop.data.Store.unsubscribe("https://www.youtube.com/channel/UCSMOQeBJ2RAnuFungnQOxLg");
                    }
                    case "playlistall" -> {
                        var res = org.newpipe.desktop.data.Backend.playlist(arg).first();
                        System.out.println("   playlist items: " + res.items().size());
                        ui(() -> f.playAll(res.items(), 0));
                    }
                    case "names" -> ui(() -> System.out.println("   queue index=" + p.index() + "/" + p.queue().size() + " current="
                            + (p.current() == null ? null : p.current().name) + " loading=" + p.isLoading() + " err=" + p.error()));
                    case "sub" -> ui(() -> {
                        var subs = p.subtitles();
                        System.out.println("   subtitles available: " + subs.size());
                        if (!subs.isEmpty()) p.setSubtitle(subs.get(Math.min(Integer.parseInt(arg), subs.size() - 1)));
                    });
                    case "prev" -> ui(p::previous);
                    case "theme" -> ui(() -> {
                        org.newpipe.desktop.data.Settings.put("theme", arg);
                        org.newpipe.desktop.ui.Theme.apply(arg);
                    });
                    case "frame" -> ui(() -> System.out.println("   frame showing=" + f.isShowing() + " bounds=" + f.getBounds()
                            + " state=" + f.getExtendedState() + " onTop=" + f.isAlwaysOnTop() + " active=" + f.isActive()));
                    case "shot" -> {
                        // Renders only this application's Swing components (the native video surface stays black).
                        BufferedImage[] img = new BufferedImage[1];
                        ui(() -> {
                            img[0] = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = img[0].createGraphics();
                            f.getContentPane().paint(g);
                            g.dispose();
                        });
                        File out = new File(arg);
                        out.getParentFile().mkdirs();
                        ImageIO.write(img[0], "png", out);
                    }
                    case "exit" -> {
                        p.shutdown();
                        System.exit(0);
                    }
                    default -> System.out.println("[script] unknown command");
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
