package com.fnba.kong.jira;

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
        List<JiraUser> devTesterUsers,
        String reporter,
        String releaseAuthorizedBy,
        String releaseManager,
        String priority,
        Double storyPoints,
        List<Link> devChecklists,
        String reasonForTracking,
        String demoScheduledDate,
        String specAuthor,
        List<JiraUser> specAuthorUsers,
        String specApprover,
        List<String> labels,
        String specDetail,
        String descriptionText,
        String descriptionHtml,
        String specDetailHtml,
        String updated,
        Instant statusSince,
        Instant categorySince,
        Instant boardSince,
        Instant createdAt,
        Instant resolutionDate,
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
    /** Release Authorized By — a single user-picker field on fnba.atlassian.net. */
    public static final String RELEASE_AUTHORIZED_BY_FIELD = "customfield_13330";
    /** Release Manager — a single user-picker field on fnba.atlassian.net. */
    public static final String RELEASE_MANAGER_FIELD = "customfield_10191";
    /** Demo Scheduled Date — a date-picker (yyyy-MM-dd) field on fnba.atlassian.net. */
    public static final String DEMO_SCHEDULED_DATE_FIELD = "customfield_14601";
    /** Specification Approver — a single user-picker field on fnba.atlassian.net. */
    public static final String SPEC_APPROVER_FIELD = "customfield_10094";
    /** Specification Author — a multi-user picker (array of user objects) on fnba.atlassian.net. */
    public static final String SPEC_AUTHOR_FIELD = "customfield_10079";

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
        Instant createdAt = parseInstant(f.path("created").asText(""));
        Instant categorySince = parseInstant(f.path("statuscategorychangedate").asText(""));
        if (categorySince == null) categorySince = createdAt;
        // Default board-since to created; the Kanban overlays the actual
        // Encompass On Deck entry date (which needs the changelog).
        Instant boardSince = createdAt;
        Instant resolvedAt = parseInstant(f.path("resolutiondate").asText(""));
        return new Issue(
                node.path("key").asText(""),
                f.path("summary").asText(""),
                f.path("status").path("name").asText(""),
                f.path("status").path("statusCategory").path("key").asText(""),
                f.path("resolution").path("name").asText(""),
                f.path("issuetype").path("name").asText(""),
                f.path("assignee").path("displayName").asText("Unassigned"),
                extractUserNames(f.path(DEV_TESTER_FIELD)),
                extractUsers(f.path(DEV_TESTER_FIELD)),
                f.path("reporter").path("displayName").asText(""),
                f.path(RELEASE_AUTHORIZED_BY_FIELD).path("displayName").asText(""),
                f.path(RELEASE_MANAGER_FIELD).path("displayName").asText(""),
                f.path("priority").path("name").asText(""),
                points,
                extractLinks(f.path(DEV_CHECKLISTS_FIELD)),
                extractDescription(f.path(REASON_FOR_TRACKING_FIELD)),
                f.path(DEMO_SCHEDULED_DATE_FIELD).asText(""),
                extractUserNames(f.path(SPEC_AUTHOR_FIELD)),
                extractUsers(f.path(SPEC_AUTHOR_FIELD)),
                f.path(SPEC_APPROVER_FIELD).path("displayName").asText(""),
                extractLabels(f.path("labels")),
                extractDescription(f.path(SPEC_DETAIL_FIELD)),
                extractDescription(f.path("description")),
                node.path("renderedFields").path("description").asText(""),
                node.path("renderedFields").path(SPEC_DETAIL_FIELD).asText(""),
                f.path("updated").asText(""),
                statusEntry(node),
                categorySince,
                boardSince,
                createdAt,
                resolvedAt,
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
            java.util.Set.of("Backlog", "Specify", "Specify Done", "Needs Spec Revision");

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

    /** Workflow rank of an arbitrary status name; -1 if unknown. */
    public static int rankOf(String status) {
        return STATUS_ORDER.indexOf(status);
    }

    /**
     * "Active" = in flight: neither resolved (done category) nor sitting in a
     * backlog/spec-queue status. Matches what the board hides by default.
     */
    public boolean isActive() {
        return !"done".equals(statusCategory) && !BACKLOG_STATUSES.contains(status);
    }

    /** A copy with changelog-derived timing overlaid (exact status-since and board-since). */
    public Issue withTiming(Instant statusSince, Instant boardSince) {
        return new Issue(key, summary, status, statusCategory, resolution, issueType, assignee,
                devTester, devTesterUsers, reporter, releaseAuthorizedBy, releaseManager, priority, storyPoints, devChecklists,
                reasonForTracking, demoScheduledDate, specAuthor, specAuthorUsers, specApprover, labels, specDetail, descriptionText,
                descriptionHtml, specDetailHtml, updated, statusSince, categorySince,
                boardSince, createdAt, resolutionDate, checklistsComplete, raw);
    }

    private static long daysSince(Instant t) {
        return t == null ? -1 : Duration.between(t, Instant.now()).toDays();
    }

    private static String dayDisplay(long d) {
        return d < 0 ? "—" : String.valueOf(d);
    }

    /** Whole days in the current status (exact), current column (status category), and on the board (since created). */
    public long daysInStatus() { return daysSince(statusSince); }
    public long daysInColumn() { return daysSince(categorySince); }
    public long daysOnBoard() { return daysSince(boardSince); }

    public String daysInStatusDisplay() { return dayDisplay(daysInStatus()); }
    public String daysInColumnDisplay() { return dayDisplay(daysInColumn()); }
    public String daysOnBoardDisplay() { return dayDisplay(daysOnBoard()); }

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault());

    /** Resolution date as yyyy-MM-dd (local), or an em dash if the issue is unresolved. */
    public String resolutionDateDisplay() {
        return resolutionDate == null ? "—" : DAY_FMT.format(resolutionDate);
    }

    /**
     * Lead time in whole days: created → resolved. A decision-neutral "days to
     * Done" that needs no changelog. -1 (shown as em dash) when either end is
     * unknown.
     */
    public long leadTimeDays() {
        if (createdAt == null || resolutionDate == null) return -1;
        return Duration.between(createdAt, resolutionDate).toDays();
    }

    public String leadTimeDisplay() { return dayDisplay(leadTimeDays()); }

    /**
     * When the issue last entered its CURRENT status: the most recent "status"
     * change in the changelog (requires the search to run with expand=changelog).
     * Falls back to statuscategorychangedate, then the created date, when no
     * changelog is present (e.g. a plain getIssue).
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
        if (best == null) {
            JsonNode f = node.path("fields");
            best = parseInstant(f.path("statuscategorychangedate").asText(""));
            if (best == null) best = parseInstant(f.path("created").asText(""));
        }
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

    /** Release Authorized By name, or an em dash when unset. */
    public String releaseAuthorizedByDisplay() {
        return (releaseAuthorizedBy == null || releaseAuthorizedBy.isBlank()) ? "—" : releaseAuthorizedBy;
    }

    /** Release Manager name, or an em dash when unset. */
    public String releaseManagerDisplay() {
        return (releaseManager == null || releaseManager.isBlank()) ? "—" : releaseManager;
    }

    /** Specification Approver name, or an em dash when unset. */
    public String specApproverDisplay() {
        return (specApprover == null || specApprover.isBlank()) ? "—" : specApprover;
    }
    public String labelsDisplay() {
        return (labels == null || labels.isEmpty()) ? "—" : String.join(", ", labels);
    }

    /** Specification Author name(s), or an em dash when unset. */
    public String specAuthorDisplay() {
        return (specAuthor == null || specAuthor.isBlank()) ? "—" : specAuthor;
    }

    /** Description as sanitized, proxy-linked HTML (Jira's own rendering); "" when empty. */
    public String descriptionRichHtml() { return RichText.render(descriptionHtml); }

    /** Specification Details as sanitized, proxy-linked HTML; "" when empty. */
    public String specDetailRichHtml() { return RichText.render(specDetailHtml); }

    /** Reason for Tracking text, or an em dash when unset. */
    public String reasonForTrackingDisplay() {
        return (reasonForTracking == null || reasonForTracking.isBlank()) ? "—" : reasonForTracking;
    }

    /** Demo Scheduled Date (yyyy-MM-dd), or an em dash when unset. */
    public String demoScheduledDateDisplay() {
        return (demoScheduledDate == null || demoScheduledDate.isBlank()) ? "—" : demoScheduledDate;
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

    /** The users in a multi-user picker array as (accountId, displayName) pairs. */
    private static List<JiraUser> extractUsers(JsonNode arr) {
        List<JiraUser> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode u : arr) {
                String id = u.path("accountId").asText("");
                if (!id.isEmpty()) out.add(new JiraUser(id, u.path("displayName").asText("")));
            }
        }
        return out;
    }

    /** The plain-string labels array. */
    private static List<String> extractLabels(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode l : arr) {
                String s = l.asText("");
                if (!s.isBlank()) out.add(s);
            }
        }
        return out;
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
