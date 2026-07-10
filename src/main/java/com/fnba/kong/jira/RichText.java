package com.fnba.kong.jira;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Jira's server-rendered rich-text HTML (from {@code expand=renderedFields}
 * / {@code renderedBody}) into HTML that is safe to drop into a Kong page and
 * whose images load through Kong's own attachment proxy.
 *
 * <p>Two passes:
 * <ol>
 *   <li><b>Rewrite</b> — attachment {@code <img>}/{@code <a>} URLs (which point at
 *       authenticated Jira endpoints the browser can't reach) are pointed at
 *       {@code /attachment/{id}}; any non-attachment {@code <img>} is dropped so
 *       we never make the browser fetch an arbitrary third-party URL.</li>
 *   <li><b>Sanitize</b> — an allowlist clean (defence in depth; Jira already
 *       sanitizes its own rendered output) that keeps formatting, tables, links
 *       and the rewritten relative image URLs.</li>
 * </ol>
 */
public final class RichText {

    private RichText() {}

    /** Matches Jira attachment URLs: capture (thumbnail|content) and the numeric id. */
    private static final Pattern ATTACHMENT =
            Pattern.compile("/rest/api/[23]/attachment/(thumbnail|content)/(\\d+)");

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("span", "font", "hr", "del", "ins", "sub", "sup", "u", "s")
            .addAttributes(":all", "class", "style", "title", "align")
            .addAttributes("font", "color", "size", "face")
            .addAttributes("a", "href", "name", "target", "rel")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("col", "span", "width");

    /**
     * Render Jira rich-text HTML into safe, proxy-linked HTML. Returns "" for
     * null/blank input so callers can treat empty fields uniformly.
     */
    public static String render(String jiraHtml) {
        if (jiraHtml == null || jiraHtml.isBlank()) return "";

        // 1. Sanitize while attachment image srcs are still absolute https Jira
        //    URLs — the allowlist's protocol check passes them (it would strip a
        //    relative /attachment/{id}, so rewriting must come *after* the clean).
        String safe = Jsoup.clean(jiraHtml, SAFELIST);

        // 2. Rewrite surviving attachment images to load through Kong's proxy;
        //    drop any non-attachment image so the browser never fetches a
        //    third-party URL. Not re-cleaned, so the relative proxy URL stays.
        Document doc = Jsoup.parseBodyFragment(safe);
        for (Element img : doc.select("img")) {
            String id = attachmentId(img.attr("src"));
            if (id == null) { img.remove(); continue; }
            // Full-resolution source: Jira sizes inline images with a width
            // attribute, so serving the thumbnail (~200px) here upscales and
            // blurs it. The width attribute keeps the displayed size intact.
            img.attr("src", "/attachment/" + id + "?full=1");
            img.removeAttr("srcset");
            // The thumbnail is usually wrapped in an <a> — point it at the
            // full-size image so a click opens the original.
            Element parent = img.parent();
            if (parent != null && "a".equals(parent.normalName())) {
                parent.attr("href", "/attachment/" + id + "?full=1");
                parent.attr("target", "_blank");
                parent.attr("rel", "noopener noreferrer");
            }
        }

        // Jira's wiki-markup renderer wraps bracketed text it couldn't resolve as
        // a link — e.g. "[CX.VLINDICATOR]" or "[14]" — in <span class="error">,
        // which collides with Kong's own ".error" banner style and shows the text
        // as red "highlighted". Those brackets are literal text, so unwrap the
        // span (keeping the text) to render them plainly.
        for (Element err : doc.select("span.error")) {
            err.unwrap();
        }

        // External links open safely in a new tab.
        for (Element a : doc.select("a[href]")) {
            if (!a.attr("href").startsWith("/attachment/")) {
                a.attr("target", "_blank");
                a.attr("rel", "noopener noreferrer");
            }
        }

        // Jira's colours are picked for a white page; on Kong's dark rich-text
        // panel a dark text colour (e.g. its blue #0747a6) or an ill-matched
        // highlight can sink into the background. Nudge any low-contrast colour
        // until it's legible, keeping its hue.
        for (Element el : doc.getAllElements()) {
            fixContrast(el);
        }

        return doc.body().html();
    }

    // ---- Contrast repair for inline colours --------------------------------

    /** Background of the .rich-text container (CSS var --panel-2, #34363c). */
    private static final int[] PANEL_BG = {0x34, 0x36, 0x3c};
    /** Default rendered text colour (CSS var --text, #e3e5e8). */
    private static final int[] DEFAULT_TEXT = {0xe3, 0xe5, 0xe8};
    /** WCAG AA contrast for normal text. */
    private static final double MIN_CONTRAST = 4.5;

    private static final Pattern RGB_FN =
            Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");

    /**
     * If this element's text colour is too faint against the background it sits
     * on, replace it with a hue-preserving, contrast-safe variant. Handles both
     * an explicit text colour (dark on our dark panel) and the inverse — the
     * default light text landing on a light highlight.
     */
    private static void fixContrast(Element el) {
        int[] highlight = bgColorOf(el);
        int[] textColor = textColorOf(el);
        if (highlight == null && textColor == null) return;   // nothing coloured here
        int[] bg = highlight != null ? highlight : effectiveBg(el);
        int[] fg = textColor != null ? textColor : DEFAULT_TEXT;
        if (contrast(fg, bg) >= MIN_CONTRAST) return;
        setTextColor(el, adjustForContrast(fg, bg));
    }

    /** The background an element's text renders on: nearest set background, else the panel. */
    private static int[] effectiveBg(Element el) {
        for (Element e = el; e != null; e = e.parent()) {
            int[] c = bgColorOf(e);
            if (c != null) return c;
        }
        return PANEL_BG;
    }

    private static int[] textColorOf(Element el) {
        if ("font".equals(el.normalName()) && el.hasAttr("color")) {
            int[] c = parseColor(el.attr("color"));
            if (c != null) return c;
        }
        return parseColor(styleProp(el.attr("style"), "color"));
    }

    private static int[] bgColorOf(Element el) {
        if (el.hasAttr("bgcolor")) {
            int[] c = parseColor(el.attr("bgcolor"));
            if (c != null) return c;
        }
        String style = el.attr("style");
        int[] c = parseColor(styleProp(style, "background-color"));
        return c != null ? c : parseColor(styleProp(style, "background"));
    }

    private static void setTextColor(Element el, int[] rgb) {
        String hex = toHex(rgb);
        if ("font".equals(el.normalName())) el.attr("color", hex);
        el.attr("style", upsertProp(el.attr("style"), "color", hex));
    }

    /** Read one CSS property from an inline style string (won't confuse "color" with "background-color"). */
    private static String styleProp(String style, String prop) {
        if (style == null || style.isBlank()) return null;
        Matcher m = Pattern.compile("(?:^|;)\\s*" + Pattern.quote(prop) + "\\s*:\\s*([^;]+)",
                Pattern.CASE_INSENSITIVE).matcher(style);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Replace (or append) one CSS property in an inline style string. */
    private static String upsertProp(String style, String prop, String value) {
        String decl = prop + ": " + value;
        if (style == null || style.isBlank()) return decl;
        String cleaned = style.replaceAll("(?i)(?:^|;)\\s*" + Pattern.quote(prop) + "\\s*:[^;]*", "")
                              .replaceAll("^\\s*;+", "").trim();
        if (cleaned.isEmpty()) return decl;
        if (!cleaned.endsWith(";")) cleaned += ";";
        return cleaned + " " + decl;
    }

    private static int[] parseColor(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase();
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length() == 3) {
                    return new int[]{
                            Integer.parseInt(h.substring(0, 1), 16) * 17,
                            Integer.parseInt(h.substring(1, 2), 16) * 17,
                            Integer.parseInt(h.substring(2, 3), 16) * 17};
                }
                if (h.length() == 6) {
                    return new int[]{
                            Integer.parseInt(h.substring(0, 2), 16),
                            Integer.parseInt(h.substring(2, 4), 16),
                            Integer.parseInt(h.substring(4, 6), 16)};
                }
                return null;
            }
            if (s.startsWith("rgb")) {
                Matcher m = RGB_FN.matcher(s);
                if (m.find()) {
                    return new int[]{clamp255(Integer.parseInt(m.group(1))),
                                     clamp255(Integer.parseInt(m.group(2))),
                                     clamp255(Integer.parseInt(m.group(3)))};
                }
            }
        } catch (RuntimeException ignore) { /* unparseable colour → leave it alone */ }
        return null;
    }

    /** Shift lightness (away from the background, hue preserved) until contrast is met. */
    private static int[] adjustForContrast(int[] fg, int[] bg) {
        double[] hsl = rgbToHsl(fg);
        boolean lighten = luminance(bg) < 0.5;
        double l = hsl[2];
        int[] best = fg;
        for (int i = 0; i < 40; i++) {
            l += lighten ? 0.025 : -0.025;
            if (l > 1) l = 1;
            if (l < 0) l = 0;
            best = hslToRgb(hsl[0], hsl[1], l);
            if (contrast(best, bg) >= MIN_CONTRAST) return best;
            if (l >= 1 || l <= 0) break;
        }
        return best;
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    private static String toHex(int[] rgb) {
        return String.format("#%02x%02x%02x", clamp255(rgb[0]), clamp255(rgb[1]), clamp255(rgb[2]));
    }

    private static double luminance(int[] rgb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = rgb[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(int[] a, int[] b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double[] rgbToHsl(int[] rgb) {
        double r = rgb[0] / 255.0, g = rgb[1] / 255.0, b = rgb[2] / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double h, s, l = (max + min) / 2;
        if (max == min) {
            h = 0; s = 0;
        } else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    private static int[] hslToRgb(double h, double s, double l) {
        double r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hue2rgb(p, q, h + 1.0 / 3);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1.0 / 3);
        }
        return new int[]{(int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255)};
    }

    private static double hue2rgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 1.0 / 2) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    /** The numeric attachment id in a Jira attachment URL, or null if it isn't one. */
    private static String attachmentId(String url) {
        if (url == null || url.isEmpty()) return null;
        Matcher m = ATTACHMENT.matcher(url);
        return m.find() ? m.group(2) : null;
    }
}
