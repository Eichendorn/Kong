package com.fnba.kong;

import com.fnba.kong.config.Config;
import com.fnba.kong.config.Settings;
import com.fnba.kong.jira.JiraClient;
import com.fnba.kong.web.Routes;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Entry point. Wires config -> services -> routes and starts the embedded
 * Jetty server. All Jira work happens server-side; the browser only
 * ever sees rendered HTML (plus the vendored htmx.min.js).
 */
public class App {

    public static void main(String[] args) {
        Config cfg = Config.load();

        // JiraClient is only constructed when credentials exist, so the app can
        // still boot (and show a helpful banner) before the token is set.
        JiraClient jira = cfg.hasJiraCredentials() ? new JiraClient(cfg) : null;
        Settings settings = new Settings();

        Javalin app = Javalin.create(c -> {
            c.fileRenderer(new JavalinThymeleaf(buildTemplateEngine()));
            c.staticFiles.add(s -> {
                s.directory = "/public";
                s.location = Location.CLASSPATH;
                s.hostedPath = "/";
            });
            c.showJavalinBanner = false;
        });

        new Routes(cfg, jira, settings).register(app);

        int port = cfg.port();
        String url = "http://localhost:" + port;
        try {
            app.start(port);
        } catch (Exception e) {
            // Most likely the port is already taken because Kong is already
            // running (e.g. the user clicked the shortcut again). Rather than
            // failing silently with no window, just reopen the browser on the
            // running instance and exit cleanly.
            System.out.println("Kong could not bind port " + port
                    + " — assuming it's already running. Opening " + url);
            openBrowser(url);
            return;
        }
        System.out.println("Kong running at " + url);
        if (jira == null) {
            System.out.println("No Jira credentials yet — finish setup at " + url + "/setup");
        }
        openBrowser(url);
    }

    /**
     * Open the user's default browser at Kong's URL so the installed app (which
     * has no window of its own) lands the user straight on the board — or the
     * first-run setup screen. Best-effort: silently skipped when running
     * headless (containers, CI) or when the platform can't launch a browser, and
     * suppressible with KONG_NO_BROWSER=1 for people who run Kong as a service.
     */
    private static void openBrowser(String url) {
        if (isTruthy(System.getenv("KONG_NO_BROWSER"))) return;
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return;
            java.awt.Desktop d = java.awt.Desktop.getDesktop();
            if (d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                d.browse(java.net.URI.create(url));
            }
        } catch (Throwable ignored) {
            // A failed browser launch must never stop Kong from serving.
        }
    }

    private static boolean isTruthy(String v) {
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }

    private static TemplateEngine buildTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
