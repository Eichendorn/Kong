package com.fnba.jiramanager.jira;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A flattened, view-friendly snapshot of a Jira issue. Only the fields the UI
 * needs are pulled out of the raw REST JSON; {@link #raw} keeps the full node
 * around for anything ad-hoc.
 */
public record Issue(
        String key,
        String summary,
        String status,
        String statusCategory,
        String resolution,
        String issueType,
        String assignee,
        String devTester,
        String reporter,
        String priority,
        Double storyPoints,
        List<Link> devChecklists,
        String reasonForTracking,
        String specDetail,
        String descriptionText,
        String updated,
        Instant statusSince,
        boolean checklistsComplete,
        JsonNode raw
) {

    public static final String DEV_CHECKLISTS_FIELD = "customfield_14567";
    public static final String SMART_CHECKLIST_FIELD = "customfield_13097";
    /** Dev Tester — a multi-user picker (array of user objects) on fnba.atlassian.net. */
    public static final String DEV_TESTER_FIELD = "customfield_10071";
    /** Specification Details — a rich-text (ADF) field on fnba.atlassian.net. */
    public static final String SPEC_DETAIL_FIELD = "customfield_10075";
    /** Reason for Tracking — a plain-text field on fnba.atlassian.net. */
    public static final String REASON_FOR_TRACKING_FIELD = "customfield_13467";

    /** A hyperlink pulled out of a rich-text field (e.g. the Developer Checklists links). */
    public record Link(String text, String href) {}

    private static final List<String> REQUIRED_LINKS = List.of(
            "Developer Reminders", "Game-plan", "Developer Notes", "Release Plan");

    /** Build an Issue from a Jira REST issue node (the object with "key" and "fields"). */
    public static Issue from(JsonNode node, String storyPointsFieldId) {
        JsonNode f = node.path("fields");
        Double points = null;
        if (storyPointsFieldId != null) {
            JsonNode sp = f.path(storyPointsFieldId);
            if (sp.isNumber()) points = sp.asDouble();
        }
        boolean complete = hasAllChecklistLinks(f.path(DEV_CHECKLISTS_FIELD))
                && hasNoDelete(f.path(SMART_CHECKLIST_FIELD));
        return new Issue(
                node.path("key").asText(""),
                f.path("summary").asText(""),
                f.path("status").path("name").asText(""),
                f.path("status").path("statusCategory").path("key").asText(""),
                f.path("resolution").path("name").asText(""),
                f.path("issuetype").path("name").asText(""),
                f.path("assignee").path("displayName").asText("Unassigned"),
                extractUserNames(f.path(DEV_TESTER_FIELD)),
                f.path("reporter").path("displayName").asText(""),
                f.path("priority").path("name").asText(""),
                points,
                extractLinks(f.path(DEV_CHECKLISTS_FIELD)),
                extractDescription(f.path(REASON_FOR_TRACKING_FIELD)),
                extractDescription(f.path(SPEC_DETAIL_FIELD)),
                extractDescription(f.path("description")),
                f.path("updated").asText(""),
                statusEntry(node),
                complete,
                node
        );
    }

    private static boolean hasAllChecklistLinks(JsonNode dcNode) {
        if (dcNode == null || dcNode.isNull() || dcNode.isMissingNode()) return false;
        StringBuilder sb = new StringBuilder();
        collectText(dcNode, sb);
        String text = sb.toString();
        return REQUIRED_LINKS.stream().allMatch(text::contains);
    }

    private static boolean hasNoDelete(JsonNode scNode) {
        if (scNode == null || scNode.isNull() || scNode.isMissingNode()) return false;
        String v = scNode.path("v").asText("");
        return v.contains("(NO DELETE)");
    }

    private static final java.util.Set<String> BACKLOG_STATUSES =
            java.util.Set.of("Backlog", "Specify", "Specify Done");

    /**
     * Workflow progression for the Encompass boards, least-advanced first.
     * Derived from the "MIN - Encompass Work" board column order (left→right).
     * Used to rank issues so the list shows the most-advanced work at the top.
     */
    private static final List<String> STATUS_ORDER = List.of(
            "Backlog", "Specify", "Specify Done", "Needs Spec Revision",
            "Encompass On Deck", "Spec Review",
            "Implement", "Ready to Test", "Investigate", "Investigate Done",
            "Track",
            "Testing", "Revisions Pending", "Ready to Release", "Ready to Demo",
            "Releasing",
            "User Verification", "Verified",
            "Done", "Canceled");

    /** Rank by workflow progression; higher = more advanced. -1 if the status is unknown. */
    public int statusRank() {
        return STATUS_ORDER.indexOf(status);
    }

    public String rowClass() {
        StringBuilder sb = new StringBuilder();
        if ("done".equals(statusCategory)) sb.append("done");
        if (BACKLOG_STATUSES.contains(status)) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append("backlog");
        }
        return sb.toString();
    }

    /** Whole days since the issue last entered its current status; -1 if unknown. */
    public long daysInStatus() {
        if (statusSince == null) return -1;
        return Duration.between(statusSince, Instant.now()).toDays();
    }

    /** Days-in-status for display, or an em dash when unknown. */
    public String daysInStatusDisplay() {
        long d = daysInStatus();
        return d < 0 ? "—" : String.valueOf(d);
    }

    /**
     * When the issue last entered its current status: the most recent "status"
     * change in the changelog, or the created date if it never transitioned.
     * Requires the search to have been run with {@code expand=changelog}.
     */
    private static Instant statusEntry(JsonNode node) {
        Instant best = null;
        JsonNode histories = node.path("changelog").path("histories");
        if (histories.isArray()) {
            for (JsonNode h : histories) {
                boolean isStatusChange = false;
                for (JsonNode it : h.path("items")) {
                    if ("status".equals(it.path("field").asText())) { isStatusChange = true; break; }
                }
                if (!isStatusChange) continue;
                Instant created = parseInstant(h.path("created").asText(""));
                if (created != null && (best == null || created.isAfter(best))) best = created;
            }
        }
        if (best == null) best = parseInstant(node.path("fields").path("created").asText(""));
        return best;
    }

    /** Parse Jira timestamps like "2026-06-22T09:59:28.100-0400"; null on failure. */
    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Comma-separated Dev Tester names, or an em dash when none are set. */
    public String devTesterDisplay() {
        return (devTester == null || devTester.isBlank()) ? "—" : devTester;
    }

    /** Join the displayName of every user in a multi-user picker array field. */
    private static String extractUserNames(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (JsonNode u : arr) {
            String dn = u.path("displayName").asText("");
            if (!dn.isBlank()) names.add(dn);
        }
        return String.join(", ", names);
    }

    public String storyPointsDisplay() {
        if (storyPoints == null) return "—";
        return storyPoints == Math.floor(storyPoints)
                ? String.valueOf(storyPoints.intValue())
                : String.valueOf(storyPoints);
    }

    /**
     * Jira Cloud descriptions are Atlassian Document Format (ADF). Pull out the
     * plain text so the list/detail views have something readable without a
     * full ADF renderer.
     */
    private static String extractDescription(JsonNode desc) {
        if (desc == null || desc.isNull() || desc.isMissingNode()) return "";
        if (desc.isTextual()) return desc.asText();
        StringBuilder sb = new StringBuilder();
        collectText(desc, sb);
        return sb.toString().strip();
    }

    /** Collect every hyperlink (text + href) out of a rich-text (ADF) field. */
    private static List<Link> extractLinks(JsonNode node) {
        List<Link> out = new ArrayList<>();
        collectLinks(node, out);
        return out;
    }

    private static void collectLinks(JsonNode node, List<Link> out) {
        if (node == null) return;
        if ("text".equals(node.path("type").asText(""))) {
            for (JsonNode mark : node.path("marks")) {
                if ("link".equals(mark.path("type").asText(""))) {
                    String href = mark.path("attrs").path("href").asText("");
                    if (!href.isEmpty()) out.add(new Link(node.path("text").asText(""), href));
                }
            }
        }
        for (JsonNode kid : node.path("content")) collectLinks(kid, out);
    }

    private static void collectText(JsonNode node, StringBuilder sb) {
        if (node == null) return;
        if (node.has("text")) {
            sb.append(node.path("text").asText());
        }
        String type = node.path("type").asText("");
        JsonNode content = node.path("content");
        if (content.isArray()) {
            List<JsonNode> kids = new ArrayList<>();
            content.forEach(kids::add);
            for (JsonNode kid : kids) collectText(kid, sb);
        }
        // Block-level nodes get a newline so paragraphs/list items stay separated.
        if (type.equals("paragraph") || type.equals("heading") || type.equals("listItem")) {
            sb.append("\n");
        }
    }
}
