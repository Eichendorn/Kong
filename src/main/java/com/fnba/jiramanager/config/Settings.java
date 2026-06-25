package com.fnba.jiramanager.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-editable settings persisted to {@code settings.local.json} in the working
 * directory (gitignored, like config.local.properties). Currently just per-column
 * Kanban WIP limits. Reads are cheap (in-memory); writes persist immediately.
 */
public final class Settings {

    private static final Path FILE = Path.of("settings.local.json");
    private static final String WIP_KEY = "wipLimits";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> wipLimits = new LinkedHashMap<>();

    public Settings() {
        load();
    }

    private synchronized void load() {
        if (!Files.isReadable(FILE)) return;
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(FILE));
            JsonNode wip = root.path(WIP_KEY);
            wip.fields().forEachRemaining(e -> {
                if (e.getValue().isInt() || e.getValue().canConvertToInt()) {
                    wipLimits.put(e.getKey(), e.getValue().asInt());
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + FILE, e);
        }
    }

    private synchronized void save() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode wip = root.putObject(WIP_KEY);
        wipLimits.forEach(wip::put);
        try {
            Files.writeString(FILE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + FILE, e);
        }
    }

    /** The configured WIP limit for a column, or {@code dflt} if none is set. */
    public synchronized int wipLimit(String column, int dflt) {
        Integer v = wipLimits.get(column);
        return v == null ? dflt : v;
    }

    /** Replace all WIP limits and persist. */
    public synchronized void setWipLimits(Map<String, Integer> limits) {
        wipLimits.clear();
        wipLimits.putAll(limits);
        save();
    }
}
