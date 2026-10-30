package org.newpipe.desktop.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTML to plain text conversion for descriptions and comments. */
public final class Html {
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private Html() {}

    public static String toPlain(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n\n").replaceAll("<[^>]+>", "");
        return decode(s);
    }

    public static String decode(String s) {
        Matcher m = NUMERIC.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(out);
        return out.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
