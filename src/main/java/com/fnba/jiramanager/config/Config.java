package com.fnba.jiramanager.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Application configuration. Values are resolved in this order:
 *   1. Environment variable (e.g. JIRA_TOKEN)
 *   2. config.local.properties in the working directory (gitignored)
 *   3. built-in default
 *
 * The Jira API token is read here and only ever used server-side by
 * {@link com.fnba.jiramanager.jira.JiraClient}; it is never sent to the browser.
 */
public final class Config {

    private final Properties props = new Properties();

    private Config() {
        Path local = Path.of("config.local.properties");
        if (Files.isReadable(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read config.local.properties", e);
            }
        }
    }

    public static Config load() {
        return new Config();
    }

    /**
     * The app version, read from the build-filtered {@code /jira-manager.properties}
     * on the classpath (Maven bakes in the pom {@code ${project.version}}). Falls
     * back to "dev" when running from an unfiltered classpath (e.g. a raw IDE run).
     */
    public static String appVersion() {
        try (InputStream in = Config.class.getResourceAsStream("/jira-manager.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank() && !v.startsWith("${")) return v.trim();
            }
        } catch (IOException ignored) {
            // fall through to the default
        }
        return "dev";
    }

    public String jiraBaseUrl() { return require("jira.baseUrl", "JIRA_BASE_URL", "https://fnba.atlassian.net"); }
    public String jiraEmail()   { return require("jira.email",   "JIRA_EMAIL",   null); }
    public String jiraToken()   { return require("jira.token",   "JIRA_TOKEN",   null); }
    public String claudeBin()   { return require("claude.bin",   "CLAUDE_BIN",   "/workspace/.local/bin/claude"); }
    public int    port()        { return Integer.parseInt(require("server.port", "PORT", "7070")); }

    /** Board definitions for the UI nav, parsed from the {@code jira.boards} setting. */
    public List<BoardDef> boards() {
        String raw = require("jira.boards", "JIRA_BOARDS",
                "MIN - Encompass Work|project = MIN ORDER BY updated DESC");
        List<BoardDef> out = new ArrayList<>();
        for (String entry : raw.split(";;")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int pipe = trimmed.indexOf('|');
            if (pipe < 0) continue;
            String label = trimmed.substring(0, pipe).trim();
            String jql = trimmed.substring(pipe + 1).trim();
            out.add(new BoardDef(slug(label), label, jql));
        }
        return out;
    }

    /** True only when the Jira credentials needed for live calls are present. */
    public boolean hasJiraCredentials() {
        return value("jira.email", "JIRA_EMAIL") != null
                && value("jira.token", "JIRA_TOKEN") != null;
    }

    private static String slug(String label) {
        return label.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String value(String propKey, String envKey) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env.trim();
        String prop = props.getProperty(propKey);
        if (prop != null && !prop.isBlank()) return prop.trim();
        return null;
    }

    private String require(String propKey, String envKey, String dflt) {
        String v = value(propKey, envKey);
        if (v != null) return v;
        if (dflt != null) return dflt;
        throw new IllegalStateException(
                "Missing required config '" + propKey + "'. Set env " + envKey
                        + " or add it to config.local.properties "
                        + "(copy config.local.properties.example to get started).");
    }
}
