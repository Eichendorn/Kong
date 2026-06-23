package com.fnba.jiramanager.jira;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A Jira comment for display: id/parentId carry the reply structure, plus the
 * author, created timestamp, plain-text body, and any nested {@link #replies}.
 */
public record Comment(String id, String parentId, String author, String created,
                      String body, List<Comment> replies) {

    private static final DateTimeFormatter IN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final DateTimeFormatter OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Build a (childless) Comment from a Jira REST comment node. */
    public static Comment from(JsonNode node) {
        String id = node.path("id").asText("");
        String parentId = node.hasNonNull("parentId") ? node.path("parentId").asText() : null;
        String author = node.path("author").path("displayName").asText("");
        String created = formatDate(node.path("created").asText(""));
        StringBuilder sb = new StringBuilder();
        collectText(node.path("body"), sb);
        return new Comment(id, parentId, author, created, sb.toString().strip(), new ArrayList<>());
    }

    public boolean hasReplies() {
        return replies != null && !replies.isEmpty();
    }

    private static String formatDate(String s) {
        if (s == null || s.isBlank()) return "";
        try {
            return OffsetDateTime.parse(s, IN).format(OUT);
        } catch (Exception e) {
            return s;
        }
    }

    /** Pull plain text out of an Atlassian Document Format (ADF) comment body. */
    private static void collectText(JsonNode node, StringBuilder sb) {
        if (node == null) return;
        if (node.has("text")) sb.append(node.path("text").asText());
        String type = node.path("type").asText("");
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode kid : content) collectText(kid, sb);
        }
        if (type.equals("paragraph") || type.equals("heading") || type.equals("listItem")) {
            sb.append("\n");
        }
    }
}
