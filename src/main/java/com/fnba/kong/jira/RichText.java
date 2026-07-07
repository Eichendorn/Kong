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

        // External links open safely in a new tab.
        for (Element a : doc.select("a[href]")) {
            if (!a.attr("href").startsWith("/attachment/")) {
                a.attr("target", "_blank");
                a.attr("rel", "noopener noreferrer");
            }
        }

        return doc.body().html();
    }

    /** The numeric attachment id in a Jira attachment URL, or null if it isn't one. */
    private static String attachmentId(String url) {
        if (url == null || url.isEmpty()) return null;
        Matcher m = ATTACHMENT.matcher(url);
        return m.find() ? m.group(2) : null;
    }
}
