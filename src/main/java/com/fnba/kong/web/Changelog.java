package com.fnba.kong.web;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Renders the bundled {@code CHANGELOG.md} (the app's revision history) to HTML
 * for the /history page. The changelog is packaged into the jar as a classpath
 * resource by the build; commonmark turns it into HTML and escapes any literal
 * markup in the text, so the output is safe to emit with th:utext.
 */
public final class Changelog {

    private Changelog() { }

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    /** The changelog rendered to HTML, or a small placeholder if it can't be read. */
    public static String html() {
        return RENDERER.render(PARSER.parse(read()));
    }

    private static String read() {
        try (InputStream in = Changelog.class.getResourceAsStream("/CHANGELOG.md")) {
            if (in == null) return "# Revision history\n\n_Changelog not bundled in this build._";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# Revision history\n\n_Could not load the changelog: " + e.getMessage() + "_";
        }
    }
}
