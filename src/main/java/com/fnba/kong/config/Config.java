package com.fnba.kong.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Application configuration. Values are resolved in this order:
 *   1. Environment variable (e.g. JIRA_TOKEN)
 *   2. config.local.properties in the data directory (see {@link #dataDir()})
 *   3. built-in default
 *
 * The Jira API token is read here and only ever used server-side by
 * {@link com.fnba.kong.jira.JiraClient}; it is never sent to the browser.
 */
public final class Config {

    static final String CONFIG_FILE = "config.local.properties";

    private final Properties props = new Properties();

    private Config() {
        Path local = dataDir().resolve(CONFIG_FILE);
        if (Files.isReadable(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + local, e);
            }
        }
    }

    public static Config load() {
        return new Config();
    }

    // ---- Data directory ----------------------------------------------------

    private static volatile Path dataDir;

    /**
     * Where Kong reads/writes its per-user files (config + settings). Resolved
     * once, in this order:
     *   1. {@code KONG_HOME} env var, if set — an explicit override.
     *   2. The working directory, if it already holds a {@code config.local.properties}
     *      — this keeps a source/dev checkout (and any "portable" unzip-and-run)
     *      self-contained.
     *   3. The OS per-user application-data location, so an installed copy under
     *      Program Files (read-only) still has a writable home:
     *        Windows  → %APPDATA%\Kong
     *        macOS    → ~/Library/Application Support/Kong
     *        other    → $XDG_CONFIG_HOME/kong  (or ~/.config/kong)
     * The directory is created if it doesn't exist.
     */
    public static Path dataDir() {
        Path d = dataDir;
        if (d == null) {
            synchronized (Config.class) {
                d = dataDir;
                if (d == null) {
                    d = resolveDataDir();
                    try {
                        Files.createDirectories(d);
                    } catch (IOException ignored) {
                        // Fall back to the working directory if the chosen home
                        // can't be created — better a running app than a crash.
                        d = Path.of(".").toAbsolutePath().normalize();
                    }
                    dataDir = d;
                }
            }
        }
        return d;
    }

    private static Path resolveDataDir() {
        String home = System.getenv("KONG_HOME");
        if (home != null && !home.isBlank()) return Path.of(home.trim());

        Path cwdConfig = Path.of(CONFIG_FILE);
        if (Files.isReadable(cwdConfig)) return Path.of(".").toAbsolutePath().normalize();

        String os = System.getProperty("os.name", "").toLowerCase();
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        } else if (os.contains("mac")) {
            base = Path.of(System.getProperty("user.home"), "Library", "Application Support");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            base = (xdg != null && !xdg.isBlank())
                    ? Path.of(xdg)
                    : Path.of(System.getProperty("user.home"), ".config");
        }
        return base.resolve("Kong");
    }

    /** The config file Kong reads and the setup screen writes. */
    public static Path configFile() {
        return dataDir().resolve(CONFIG_FILE);
    }

    /**
     * Persist the Jira credentials entered on the first-run setup screen,
     * preserving any existing board/port settings. Written atomically (temp file
     * + rename) so a crash mid-write can't leave a half-written config.
     */
    public static synchronized void writeCredentials(String baseUrl, String email, String token)
            throws IOException {
        Path dir = dataDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(CONFIG_FILE);

        Properties existing = new Properties();
        if (Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                existing.load(in);
            }
        }
        String boards = existing.getProperty("jira.boards",
                "MIN - Encompass Work|project = MIN ORDER BY updated DESC");
        String port = existing.getProperty("server.port", "7070");

        String content = ""
                + "# Kong configuration — written by the first-run setup screen.\n"
                + "# Delete this file to run setup again.\n"
                + "jira.baseUrl=" + baseUrl + "\n"
                + "jira.email=" + email + "\n"
                + "jira.token=" + token + "\n"
                + "jira.boards=" + boards + "\n"
                + "server.port=" + port + "\n";

        Path tmp = Files.createTempFile(dir, "config", ".tmp");
        try {
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignore) { /* best effort */ }
        }
    }

    /**
     * The app version, read from the build-filtered {@code /kong.properties}
     * on the classpath (Maven bakes in the pom {@code ${project.version}}). Falls
     * back to "dev" when running from an unfiltered classpath (e.g. a raw IDE run).
     */
    public static String appVersion() {
        try (InputStream in = Config.class.getResourceAsStream("/kong.properties")) {
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
    public int    port()        { return Integer.parseInt(require("server.port", "PORT", "7070")); }

    /** Entity id of the workflow drawn on the Maintenance → Workflow Diagram screen. */
    public String jiraWorkflowId() {
        return require("jira.workflowId", "JIRA_WORKFLOW_ID",
                "0a0385d5-45f4-410b-86ad-0218f4829c46");   // 2026-05 Encompass Minor Project Workflow
    }

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
