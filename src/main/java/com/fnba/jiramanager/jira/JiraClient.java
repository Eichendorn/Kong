package com.fnba.jiramanager.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fnba.jiramanager.config.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thin server-side wrapper around the Jira Cloud REST API (v3). All calls use
 * HTTP Basic auth built from the configured email + API token, so the token
 * never leaves the server. One instance is shared across the app.
 */
public class JiraClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String authHeader;

    /** Story Points custom field on fnba.atlassian.net (see project memory). */
    public static final String STORY_POINTS_FIELD = "customfield_10016";
    /** Compliance/Regulatory Attribute — a single-option (radio) custom field. */
    public static final String COMPLIANCE_FIELD = "customfield_11667";

    public JiraClient(Config cfg) {
        this.baseUrl = cfg.jiraBaseUrl().replaceAll("/+$", "");
        String creds = cfg.jiraEmail() + ":" + cfg.jiraToken();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Caching -----------------------------------------------------------
    // Board/sidebar list queries and slow-changing metadata (priorities,
    // resolutions, create-meta) are the expensive reads. List results carry a
    // short TTL and are dropped on any write; metadata gets a long TTL.

    private record Cached(Object value, long expiresAt) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long LIST_TTL_MS = 60_000;     // board + sidebar issue lists
    private static final long META_TTL_MS = 600_000;    // priorities, resolutions, create-meta

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ttlMs, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Cached e = cache.get(key);
        if (e != null && e.expiresAt() > now) return (T) e.value();
        T v = loader.get();
        cache.put(key, new Cached(v, now + ttlMs));
        return v;
    }

    /** Drop cached issue lists so the next board/sidebar load reflects a write. */
    public void invalidateLists() {
        cache.keySet().removeIf(k -> k.startsWith("list:"));
    }

    // ---- Reads -------------------------------------------------------------

    /** Run a JQL search and return up to {@code maxResults} issues, paginating as needed. */
    public List<Issue> search(String jql, int maxResults) {
        return cached("list:full:" + maxResults + ":" + jql, LIST_TTL_MS, () -> doSearch(jql, maxResults));
    }

    private List<Issue> doSearch(String jql, int maxResults) {
        // Only the fields the board table actually renders. "Days in status" uses
        // the cheap statuscategorychangedate field (expand=changelog was ~5x slower);
        // description/priority/reporter/story-points are omitted — they aren't shown
        // in the list and are loaded per-issue on the detail page.
        String fields = "summary,status,issuetype,assignee,updated,created,statuscategorychangedate,"
                + Issue.DEV_CHECKLISTS_FIELD + "," + Issue.SMART_CHECKLIST_FIELD
                + "," + Issue.DEV_TESTER_FIELD;
        List<Issue> out = new ArrayList<>();
        // The enhanced search endpoint (/search/jql) paginates with an opaque
        // nextPageToken and ignores startAt entirely — using startAt re-fetches
        // page 1 every time and floods the result with duplicates.
        String nextPageToken = null;
        while (out.size() < maxResults) {
            int pageSize = Math.min(100, maxResults - out.size());
            String url = baseUrl + "/rest/api/3/search/jql"
                    + "?jql=" + enc(jql)
                    + "&maxResults=" + pageSize
                    + "&fields=" + enc(fields)
                    + (nextPageToken == null ? "" : "&nextPageToken=" + enc(nextPageToken));
            JsonNode root = get(url);
            JsonNode issues = root.path("issues");
            if (!issues.isArray() || issues.isEmpty()) break;
            for (JsonNode n : issues) {
                out.add(Issue.from(n, STORY_POINTS_FIELD));
            }
            JsonNode token = root.path("nextPageToken");
            if (token.isMissingNode() || token.isNull() || token.asText("").isEmpty()) break;
            nextPageToken = token.asText();
        }
        return out;
    }

    /**
     * Lightweight search for the sidebar: key, summary and status only, no
     * changelog expand. Much cheaper than {@link #search} for big result sets.
     */
    public List<Issue> searchBrief(String jql, int maxResults) {
        return cached("list:brief:" + maxResults + ":" + jql, LIST_TTL_MS,
                () -> doSearchBrief(jql, maxResults));
    }

    private List<Issue> doSearchBrief(String jql, int maxResults) {
        String fields = "summary,status,issuetype,updated";
        List<Issue> out = new ArrayList<>();
        String nextPageToken = null;
        while (out.size() < maxResults) {
            int pageSize = Math.min(100, maxResults - out.size());
            String url = baseUrl + "/rest/api/3/search/jql"
                    + "?jql=" + enc(jql)
                    + "&maxResults=" + pageSize
                    + "&fields=" + enc(fields)
                    + (nextPageToken == null ? "" : "&nextPageToken=" + enc(nextPageToken));
            JsonNode root = get(url);
            JsonNode issues = root.path("issues");
            if (!issues.isArray() || issues.isEmpty()) break;
            for (JsonNode n : issues) out.add(Issue.from(n, STORY_POINTS_FIELD));
            JsonNode token = root.path("nextPageToken");
            if (token.isMissingNode() || token.isNull() || token.asText("").isEmpty()) break;
            nextPageToken = token.asText();
        }
        return out;
    }

    private static final DateTimeFormatter TXN_IN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final DateTimeFormatter TXN_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Recent status transitions across the issues matching {@code jql}, newest
     * first. Scans the changelog of up to {@code maxIssues} (most-recently-updated)
     * issues and pulls out every {@code status} change. Cached briefly.
     */
    public List<TransitionLog> recentTransitions(String jql, int maxIssues) {
        return cached("txnlog:" + maxIssues + ":" + jql, LIST_TTL_MS,
                () -> doRecentTransitions(jql, maxIssues));
    }

    private List<TransitionLog> doRecentTransitions(String jql, int maxIssues) {
        String url = baseUrl + "/rest/api/3/search/jql"
                + "?jql=" + enc(jql)
                + "&maxResults=" + Math.min(100, maxIssues)
                + "&fields=" + enc("summary")
                + "&expand=" + enc("changelog");
        JsonNode root = get(url);
        List<TransitionLog> out = new ArrayList<>();
        for (JsonNode issue : root.path("issues")) {
            String key = issue.path("key").asText("");
            String summary = issue.path("fields").path("summary").asText("");
            for (JsonNode h : issue.path("changelog").path("histories")) {
                OffsetDateTime odt = parseOdt(h.path("created").asText(""));
                String author = h.path("author").path("displayName").asText("");
                for (JsonNode it : h.path("items")) {
                    if (!"status".equals(it.path("field").asText())) continue;
                    out.add(new TransitionLog(key, summary,
                            it.path("fromString").asText(""), it.path("toString").asText(""),
                            author,
                            odt == null ? null : odt.toInstant(),
                            odt == null ? "" : odt.format(TXN_OUT)));
                }
            }
        }
        out.sort(Comparator.comparing(TransitionLog::at,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > 200 ? new ArrayList<>(out.subList(0, 200)) : out;
    }

    private static OffsetDateTime parseOdt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s, TXN_IN);
        } catch (Exception e) {
            return null;
        }
    }

    /** The authenticated user (used to default the Reporter on new issues). */
    public JiraUser currentUser() {
        JsonNode u = get(baseUrl + "/rest/api/3/myself");
        return new JiraUser(u.path("accountId").asText(""), u.path("displayName").asText(""));
    }

    /**
     * Active users assignable in a project, optionally narrowed by a typed
     * {@code query} (matches name/email) — backs the Reporter type-ahead.
     */
    public List<JiraUser> assignableUsers(String projectKey, String query, int max) {
        String url = baseUrl + "/rest/api/3/user/assignable/search"
                + "?maxResults=" + max + "&project=" + enc(projectKey);
        if (query != null && !query.isBlank()) url += "&query=" + enc(query.trim());
        JsonNode arr = get(url);
        List<JiraUser> out = new ArrayList<>();
        for (JsonNode u : arr) {
            if (!u.path("active").asBoolean(true)) continue;
            String id = u.path("accountId").asText("");
            if (!id.isEmpty()) out.add(new JiraUser(id, u.path("displayName").asText("")));
        }
        out.sort(Comparator.comparing(JiraUser::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Fetch a single issue with all fields. */
    public Issue getIssue(String key) {
        JsonNode node = get(baseUrl + "/rest/api/3/issue/" + enc(key));
        return Issue.from(node, STORY_POINTS_FIELD);
    }

    /**
     * Comments on an issue as a reply tree. Top-level comments come back newest
     * first; replies nest under their parent in chronological (oldest-first)
     * reading order. Issues with no replies render as a flat newest-first list.
     */
    public List<Comment> comments(String key) {
        JsonNode root = get(baseUrl + "/rest/api/3/issue/" + enc(key) + "/comment?maxResults=200");
        List<Comment> all = new ArrayList<>();
        for (JsonNode c : root.path("comments")) all.add(Comment.from(c));

        java.util.Map<String, Comment> byId = new java.util.HashMap<>();
        for (Comment c : all) byId.put(c.id(), c);
        List<Comment> roots = new ArrayList<>();
        for (Comment c : all) {
            Comment parent = c.parentId() == null ? null : byId.get(c.parentId());
            if (parent != null) parent.replies().add(c);
            else roots.add(c);
        }
        Comparator<Comment> byCreated = Comparator.comparing(Comment::created);
        roots.sort(byCreated.reversed());
        for (Comment c : all) c.replies().sort(byCreated);
        return roots;
    }

    /**
     * Projects the current user can create issues in, each with its allowed
     * (non-subtask) issue types. Restricted to {@code projectKeys} when given.
     */
    public List<CreateProject> createMeta(List<String> projectKeys) {
        String keys = projectKeys == null ? "" : String.join(",", projectKeys);
        return cached("meta:createmeta:" + keys, META_TTL_MS, () -> doCreateMeta(projectKeys));
    }

    private List<CreateProject> doCreateMeta(List<String> projectKeys) {
        String url = baseUrl + "/rest/api/3/issue/createmeta?expand=projects.issuetypes";
        if (projectKeys != null && !projectKeys.isEmpty()) {
            url += "&projectKeys=" + enc(String.join(",", projectKeys));
        }
        JsonNode root = get(url);
        List<CreateProject> out = new ArrayList<>();
        for (JsonNode p : root.path("projects")) {
            List<CreateProject.CreateIssueType> types = new ArrayList<>();
            for (JsonNode t : p.path("issuetypes")) {
                if (t.path("subtask").asBoolean(false)) continue;
                types.add(new CreateProject.CreateIssueType(
                        t.path("id").asText(), t.path("name").asText()));
            }
            out.add(new CreateProject(p.path("key").asText(), p.path("name").asText(), types));
        }
        return out;
    }

    /** The workflow transitions currently available on an issue. */
    public List<Transition> transitions(String key) {
        JsonNode root = get(baseUrl + "/rest/api/3/issue/" + enc(key) + "/transitions");
        List<Transition> out = new ArrayList<>();
        for (JsonNode t : root.path("transitions")) {
            out.add(new Transition(
                    t.path("id").asText(),
                    t.path("name").asText(),
                    t.path("to").path("name").asText()));
        }
        return out;
    }

    /**
     * Resolutions selectable for a given transition. Reads the transition
     * screen's {@code resolution} field allowedValues; if that transition
     * carries no resolution field, falls back to the globally-configured list.
     */
    public List<Resolution> resolutionOptions(String key, String transitionId) {
        JsonNode root = get(baseUrl + "/rest/api/3/issue/" + enc(key)
                + "/transitions?expand=transitions.fields");
        for (JsonNode t : root.path("transitions")) {
            if (!t.path("id").asText().equals(transitionId)) continue;
            List<Resolution> out = new ArrayList<>();
            for (JsonNode r : t.path("fields").path("resolution").path("allowedValues")) {
                out.add(new Resolution(r.path("id").asText(), r.path("name").asText()));
            }
            if (!out.isEmpty()) return out;
        }
        return allResolutions();
    }

    /** Every resolution configured on the Jira site. */
    public List<Resolution> allResolutions() {
        return cached("meta:resolutions", META_TTL_MS, () -> {
            JsonNode root = get(baseUrl + "/rest/api/3/resolution");
            List<Resolution> out = new ArrayList<>();
            for (JsonNode r : root) {
                out.add(new Resolution(r.path("id").asText(), r.path("name").asText()));
            }
            return out;
        });
    }

    /** All priorities configured on the site. */
    public List<Priority> priorities() {
        return cached("meta:priorities", META_TTL_MS, () -> {
            JsonNode arr = get(baseUrl + "/rest/api/3/priority");
            List<Priority> out = new ArrayList<>();
            for (JsonNode p : arr) out.add(new Priority(p.path("id").asText(), p.path("name").asText()));
            return out;
        });
    }

    /** The non-subtask issue types available in a project (for changing an issue's type). */
    public List<CreateProject.CreateIssueType> projectIssueTypes(String projectKey) {
        for (CreateProject p : createMeta(List.of(projectKey))) {
            if (p.key().equalsIgnoreCase(projectKey)) return p.issueTypes();
        }
        return List.of();
    }

    // ---- Writes ------------------------------------------------------------

    /**
     * Create an issue and return its new key. Only {@code projectKey},
     * {@code issueTypeId} and {@code summary} are required; every other argument
     * is sent only when non-blank, so an unset field is simply omitted.
     */
    public String createIssue(String projectKey, String issueTypeId, String summary,
                              String description, String reporterAccountId,
                              String specDetail, String complianceValue) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putObject("project").put("key", projectKey);
        fields.putObject("issuetype").put("id", issueTypeId);
        fields.put("summary", summary);
        if (notBlank(description)) fields.set("description", adf(description));
        if (notBlank(reporterAccountId)) fields.putObject("reporter").put("accountId", reporterAccountId.trim());
        if (notBlank(specDetail)) fields.set(Issue.SPEC_DETAIL_FIELD, adf(specDetail));
        if (notBlank(complianceValue)) fields.putObject(COMPLIANCE_FIELD).put("value", complianceValue.trim());
        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);
        JsonNode resp = post(baseUrl + "/rest/api/3/issue", body);
        invalidateLists();   // a new issue should show up in the board immediately
        return resp.path("key").asText("");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Change an issue's type. */
    public void setIssueType(String key, String issueTypeId) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putObject("issuetype").put("id", issueTypeId);
        editFields(key, fields);
    }

    /** Change an issue's priority. */
    public void setPriority(String key, String priorityId) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putObject("priority").put("id", priorityId);
        editFields(key, fields);
    }

    /** Set the assignee (blank account id clears it / unassigns). */
    public void setAssignee(String key, String accountId) {
        ObjectNode fields = mapper.createObjectNode();
        if (notBlank(accountId)) fields.putObject("assignee").put("accountId", accountId.trim());
        else fields.putNull("assignee");
        editFields(key, fields);
    }

    /** Set the reporter. */
    public void setReporter(String key, String accountId) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putObject("reporter").put("accountId", accountId.trim());
        editFields(key, fields);
    }

    /** Add a Dev Tester (multi-user field) via an incremental update operation. */
    public void addDevTester(String key, String accountId) {
        updateUserList(key, Issue.DEV_TESTER_FIELD, accountId, true);
    }

    /** Remove a Dev Tester (multi-user field). */
    public void removeDevTester(String key, String accountId) {
        updateUserList(key, Issue.DEV_TESTER_FIELD, accountId, false);
    }

    /**
     * Add or remove one user from a multi-user-picker field using the issue
     * "update" operations, so the rest of the list is left untouched.
     */
    private void updateUserList(String key, String fieldId, String accountId, boolean add) {
        ObjectNode op = mapper.createObjectNode();
        op.putObject(add ? "add" : "remove").put("accountId", accountId.trim());
        ObjectNode body = mapper.createObjectNode();
        body.putObject("update").putArray(fieldId).add(op);
        put(baseUrl + "/rest/api/3/issue/" + enc(key), body);
        invalidateLists();
    }

    /** Execute a workflow transition by id, without changing the resolution. */
    public void transition(String key, String transitionId) {
        transition(key, transitionId, null);
    }

    /**
     * Execute a workflow transition by id, optionally setting the resolution.
     * The resolution is only applied when {@code resolutionName} is non-blank
     * (and only succeeds if the transition screen exposes a resolution field).
     */
    public void transition(String key, String transitionId, String resolutionName) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("transition").put("id", transitionId);
        if (resolutionName != null && !resolutionName.isBlank()) {
            body.putObject("fields").putObject("resolution").put("name", resolutionName.trim());
        }
        post(baseUrl + "/rest/api/3/issue/" + enc(key) + "/transitions", body);
        invalidateLists();   // status/resolution changed — refresh list views
    }

    /** Update simple fields. Use {@link #STORY_POINTS_FIELD} for points. */
    public void editFields(String key, ObjectNode fields) {
        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);
        put(baseUrl + "/rest/api/3/issue/" + enc(key), body);
        invalidateLists();   // a list-visible field may have changed
    }

    /** Set the Description (plain text wrapped as ADF; blank clears it). */
    public void setDescription(String key, String text) {
        ObjectNode fields = mapper.createObjectNode();
        if (text == null || text.isBlank()) fields.putNull("description");
        else fields.set("description", adf(text));
        editFields(key, fields);
    }

    /** Set the Specification Details field (plain text wrapped as ADF; blank clears it). */
    public void setSpecDetail(String key, String text) {
        ObjectNode fields = mapper.createObjectNode();
        if (text == null || text.isBlank()) fields.putNull(Issue.SPEC_DETAIL_FIELD);
        else fields.set(Issue.SPEC_DETAIL_FIELD, adf(text));
        editFields(key, fields);
    }

    /** Set the Reason for Tracking field (plain text; blank clears it). */
    public void setReasonForTracking(String key, String text) {
        ObjectNode fields = mapper.createObjectNode();
        if (text == null || text.isBlank()) fields.putNull(Issue.REASON_FOR_TRACKING_FIELD);
        else fields.put(Issue.REASON_FOR_TRACKING_FIELD, text.trim());
        editFields(key, fields);
    }

    /** Convenience: set the Story Points value (null clears it). */
    public void setStoryPoints(String key, Double points) {
        ObjectNode fields = mapper.createObjectNode();
        if (points == null) fields.putNull(STORY_POINTS_FIELD);
        else fields.put(STORY_POINTS_FIELD, points);
        editFields(key, fields);
    }

    /** Add a plain-text comment (wrapped as minimal ADF). */
    public void addComment(String key, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.set("body", adf(text));
        post(baseUrl + "/rest/api/3/issue/" + enc(key) + "/comment", body);
    }

    /**
     * Log work against an issue.
     * @param timeSpent Jira duration syntax, e.g. "1h 30m", "2d"
     * @param comment   optional worklog note (may be null/blank)
     */
    public void addWorklog(String key, String timeSpent, String comment) {
        ObjectNode body = mapper.createObjectNode();
        body.put("timeSpent", timeSpent);
        if (comment != null && !comment.isBlank()) body.set("comment", adf(comment));
        post(baseUrl + "/rest/api/3/issue/" + enc(key) + "/worklog", body);
    }

    // ---- HTTP plumbing -----------------------------------------------------

    private JsonNode get(String url) {
        HttpRequest req = baseRequest(url).GET().build();
        return send(req, url);
    }

    private JsonNode post(String url, JsonNode body) {
        HttpRequest req = baseRequest(url)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(req, url);
    }

    private JsonNode put(String url, JsonNode body) {
        HttpRequest req = baseRequest(url)
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(req, url);
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
    }

    private JsonNode send(HttpRequest req, String url) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) {
                throw new JiraException(sc, req.method() + " " + url
                        + " -> HTTP " + sc + ": " + truncate(resp.body()));
            }
            String body = resp.body();
            if (body == null || body.isBlank()) return mapper.createObjectNode();
            return mapper.readTree(body);
        } catch (JiraException e) {
            throw e;
        } catch (Exception e) {
            throw new JiraException(0, "Request failed: " + req.method() + " " + url
                    + " (" + e.getMessage() + ")");
        }
    }

    /**
     * Wrap lightly-marked-up plain text into Atlassian Document Format:
     * <ul>
     *   <li>lines beginning {@code "- "} / {@code "* "} become bullet-list items;</li>
     *   <li>{@code **bold**} spans become strong-marked text;</li>
     *   <li>blank lines separate paragraphs; other single newlines are hard breaks.</li>
     * </ul>
     * Empty paragraphs are never emitted, which keeps the doc schema-valid.
     */
    private ObjectNode adf(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        ObjectNode doc = mapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");

        List<String> paragraph = new ArrayList<>();
        ArrayNode bulletItems = null;  // content array of the open bullet list, or null
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.stripLeading();
            boolean isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ");
            if (isBullet) {
                flushParagraph(content, paragraph);
                if (bulletItems == null) {
                    bulletItems = content.addObject().put("type", "bulletList").putArray("content");
                }
                ObjectNode itemPara = bulletItems.addObject().put("type", "listItem")
                        .putArray("content").addObject();
                itemPara.put("type", "paragraph");
                appendInline(itemPara.putArray("content"), trimmed.substring(2));
            } else if (line.isBlank()) {
                flushParagraph(content, paragraph);
                bulletItems = null;
            } else {
                bulletItems = null;          // a normal line ends any open bullet list
                paragraph.add(line);
            }
        }
        flushParagraph(content, paragraph);

        // Guard: callers only pass non-blank text, but never ship an empty doc.
        if (content.isEmpty()) {
            appendInline(content.addObject().put("type", "paragraph").putArray("content"), text);
        }
        return doc;
    }

    /** Emit the buffered lines as one paragraph (hard breaks between them), then clear. */
    private void flushParagraph(ArrayNode content, List<String> lines) {
        if (lines.isEmpty()) return;
        ArrayNode pc = content.addObject().put("type", "paragraph").putArray("content");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) pc.addObject().put("type", "hardBreak");
            appendInline(pc, lines.get(i));
        }
        lines.clear();
    }

    /** Append text nodes for one line, turning {@code **bold**} spans into strong marks. */
    private void appendInline(ArrayNode target, String text) {
        String[] parts = text.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            ObjectNode node = target.addObject().put("type", "text").put("text", parts[i]);
            if (i % 2 == 1) node.putArray("marks").addObject().put("type", "strong");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /** Thrown when Jira returns a non-2xx response or the request can't be sent. */
    public static class JiraException extends RuntimeException {
        public final int statusCode;
        public JiraException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
