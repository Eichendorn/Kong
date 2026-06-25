package com.fnba.jiramanager;

import com.fnba.jiramanager.claude.ClaudeService;
import com.fnba.jiramanager.config.Config;
import com.fnba.jiramanager.config.Settings;
import com.fnba.jiramanager.jira.JiraClient;
import com.fnba.jiramanager.web.Routes;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Entry point. Wires config -> services -> routes and starts the embedded
 * Jetty server. All Jira and Claude work happens server-side; the browser only
 * ever sees rendered HTML (plus the vendored htmx.min.js).
 */
public class App {

    public static void main(String[] args) {
        Config cfg = Config.load();
        long boot = System.currentTimeMillis();

        ClaudeService claude = new ClaudeService(cfg, boot);
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

        new Routes(cfg, jira, claude, settings).register(app);

        app.start(cfg.port());
        System.out.println("Jira Manager running at http://localhost:" + cfg.port());
        if (jira == null) {
            System.out.println("WARNING: no Jira credentials configured — set jira.email/jira.token "
                    + "in config.local.properties (copy config.local.properties.example).");
        }
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
