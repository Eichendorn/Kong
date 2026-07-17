package com.fnba.kong.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fnba.kong.config.Config;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin server-side wrapper around the Jira Cloud REST API (v3). All calls use
 * HTTP Basic auth built from the configured email + API token, so the token
 * never leaves the server. One instance is shared across the app.
 */
public class JiraClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    /**
     * Separate client for attachment bytes: Jira 303-redirects attachment
     * requests to a signed media-CDN URL, so this one follows redirects (the
     * signed URL needs no auth of its own).
     */
    private final HttpClient httpRedirect = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String authHeader;
    private static final System.Logger LOG = System.getLogger(JiraClient.class.getName());

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
    /**
     * Hard cap on cached entries. Each distinct key is kept until overwritten,
     * and {@code /search?jql=...} mints a new key per unique query, so without a
     * bound the map grows forever. Past this size we sweep expired entries, then
     * evict the soonest-to-expire ones until back under the cap.
     */
    private static final int MAX_CACHE_ENTRIES = 500;

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ttlMs, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Cached e = cache.get(key);
        if (e != null && e.expiresAt() > now) return (T) e.value();
        T v = loader.get();
        cache.put(key, new Cached(v, now + ttlMs));
        if (cache.size() > MAX_CACHE_ENTRIES) evict(now);
        return v;
    }

    /** Reap expired entries; if still over the cap, drop the soonest-to-expire ones. */
    private void evict(long now) {
        cache.entrySet().removeIf(en -> en.getValue().expiresAt() <= now);
        int over = cache.size() - MAX_CACHE_ENTRIES;
        if (over <= 0) return;
        cache.entrySet().stream()
                .sorted(Comparator.comparingLong(en -> en.getValue().expiresAt()))
                .limit(over)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }

    /** Drop cached issue lists so the next board/sidebar load reflects a write. */
    public void invalidateLists() {
        cache.keySet().removeIf(k -> k.startsWith("list:"));
    }

    // ---- Reads -------------------------------------------------------------

    /** Issues from a search, plus whether the {@code maxResults} cap hid further matches. */
    public record SearchResult(List<Issue> issues, boolean truncated) {}

    /** Run a JQL search and return up to {@code maxResults} issues, paginating as needed. */
    public SearchResult search(String jql, int maxResults) {
        return cached("list:full:" + maxResults + ":" + jql, LIST_TTL_MS, () -> doSearch(jql, maxResults));
    }

    private SearchResult doSearch(String jql, int maxResults) {
        // Only the fields the board table renders — no changelog (it was ~5x slower
        // over a 500-issue board). The list's "Days in status" uses the cheap
        // statuscategorychangedate; the Kanban fetches exact per-status timing for
        // its (far fewer) active cards via statusSinceByKeys.
        String fields = "summary,status,issuetype,priority,assignee,reporter,updated,created,statuscategorychangedate,"
                + Issue.DEV_CHECKLISTS_FIELD + "," + Issue.SMART_CHECKLIST_FIELD
                + "," + Issue.DEV_TESTER_FIELD + "," + Issue.REASON_FOR_TRACKING_FIELD
                + "," + Issue.DEMO_SCHEDULED_DATE_FIELD + "," + Issue.RELEASE_MANAGER_FIELD
                + "," + Issue.SPEC_APPROVER_FIELD + "," + Issue.SPEC_AUTHOR_FIELD;
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
            if (!issues.isArray() || issues.isEmpty()) { nextPageToken = null; break; }
            for (JsonNode n : issues) {
                out.add(Issue.from(n, STORY_POINTS_FIELD));
            }
            nextPageToken = nextToken(root);
            if (nextPageToken == null) break;
        }
        // We stopped either because Jira ran out of pages (token null) or because
        // we hit the cap with a page still pending — the latter means matches were
        // dropped, which would otherwise make a partial board look complete.
        boolean truncated = out.size() >= maxResults && nextPageToken != null;
        if (truncated) {
            LOG.log(System.Logger.Level.WARNING,
                    "Search hit the " + maxResults + "-result cap; later matches are hidden. JQL: " + jql);
        }
        return new SearchResult(out, truncated);
    }

    /** The opaque next-page token from a search response, or null when there are no more pages. */
    private static String nextToken(JsonNode root) {
        JsonNode token = root.path("nextPageToken");
        if (token.isMissingNode() || token.isNull() || token.asText("").isEmpty()) return null;
        return token.asText();
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
            if (!issues.isArray() || issues.isEmpty()) { nextPageToken = null; break; }
            for (JsonNode n : issues) out.add(Issue.from(n, STORY_POINTS_FIELD));
            nextPageToken = nextToken(root);
            if (nextPageToken == null) break;
        }
        if (out.size() >= maxResults && nextPageToken != null) {
            LOG.log(System.Logger.Level.WARNING,
                    "Sidebar search hit the " + maxResults + "-result cap. JQL: " + jql);
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
        // "list:" prefix so invalidateLists() sweeps it on a write — a transition
        // you just made should show up in the log immediately, not 60s later.
        return cached("list:txnlog:" + maxIssues + ":" + jql, LIST_TTL_MS,
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

    /**
     * Changelog-derived timing per issue, for the given keys only (so the heavy
     * changelog expand stays bounded to the Kanban's active cards): exact
     * status-since, plus when the issue first entered {@code boardEntryStatus}.
     * Cached; cleared on writes.
     */
    public Map<String, Timing> issueTimings(List<String> keys, String boardEntryStatus) {
        if (keys == null || keys.isEmpty()) return Map.of();
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(null);
        return cached("list:timings:" + boardEntryStatus + ":" + String.join(",", sorted), LIST_TTL_MS, () -> {
            Map<String, Timing> out = new HashMap<>();
            String jql = "key in (" + String.join(",", keys) + ")";
            String url = baseUrl + "/rest/api/3/search/jql"
                    + "?jql=" + enc(jql)
                    + "&maxResults=" + keys.size()
                    + "&fields=" + enc("status")
                    + "&expand=" + enc("changelog");
            JsonNode root = get(url);
            for (JsonNode n : root.path("issues")) {
                Issue iss = Issue.from(n, STORY_POINTS_FIELD);
                out.put(iss.key(), new Timing(iss.statusSince(), earliestEntryInto(n, boardEntryStatus)));
            }
            return out;
        });
    }

    /** Earliest time the issue transitioned INTO {@code status}, from the changelog; null if never. */
    private static Instant earliestEntryInto(JsonNode node, String status) {
        Instant best = null;
        for (JsonNode h : node.path("changelog").path("histories")) {
            for (JsonNode it : h.path("items")) {
                if ("status".equals(it.path("field").asText())
                        && status.equals(it.path("toString").asText())) {
                    OffsetDateTime o = parseOdt(h.path("created").asText(""));
                    if (o != null && (best == null || o.toInstant().isBefore(best))) best = o.toInstant();
                }
            }
        }
        return best;
    }

    private static OffsetDateTime parseOdt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s, TXN_IN);
        } catch (Exception e) {
            return null;
        }
    }

    /** The authenticated user (used to default the Reporter on new issues and
     *  shown in the top bar). Cached — the token's identity doesn't change. */
    public JiraUser currentUser() {
        return cached("meta:myself", META_TTL_MS, () -> {
            JsonNode u = get(baseUrl + "/rest/api/3/myself");
            return new JiraUser(u.path("accountId").asText(""), u.path("displayName").asText(""));
        });
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

    /**
     * Any active user in the instance whose name/email matches {@code query} —
     * backs the @-mention autocomplete in comments. Unlike {@link #assignableUsers},
     * this isn't scoped to a project, so you can mention anyone (a manager, QA,
     * etc.), matching how Jira's own editor behaves.
     */
    public List<JiraUser> searchUsers(String query, int max) {
        if (query == null || query.isBlank()) return List.of();
        String url = baseUrl + "/rest/api/3/user/search"
                + "?maxResults=" + max + "&query=" + enc(query.trim());
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
        // renderedFields gives us Jira's own HTML rendering of the rich-text
        // fields (description, spec details) — used read-only on the detail screen.
        JsonNode node = get(baseUrl + "/rest/api/3/issue/" + enc(key) + "?expand=renderedFields");
        return Issue.from(node, STORY_POINTS_FIELD);
    }

    /**
     * Comments on an issue as a reply tree. Top-level comments come back newest
     * first; replies nest under their parent in chronological (oldest-first)
     * reading order. Issues with no replies render as a flat newest-first list.
     */
    public List<Comment> comments(String key) {
        JsonNode root = get(baseUrl + "/rest/api/3/issue/" + enc(key)
                + "/comment?maxResults=200&expand=renderedBody");
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

    /**
     * Fetch a workflow definition (statuses + transitions) by its entity id,
     * via the bulk-read API. Cached like other slow-changing metadata.
     */
    public Workflow workflow(String workflowId) {
        return cached("meta:workflow:" + workflowId, META_TTL_MS, () -> doWorkflow(workflowId));
    }

    private Workflow doWorkflow(String workflowId) {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("workflowIds").add(workflowId);
        JsonNode root = post(baseUrl + "/rest/api/3/workflows", body);

        // Status refs are resolved against the response's shared status list.
        Map<String, Workflow.Status> byRef = new HashMap<>();
        for (JsonNode s : root.path("statuses")) {
            String ref = s.path("statusReference").asText();
            byRef.put(ref, new Workflow.Status(ref, s.path("name").asText(),
                    categoryKey(s.path("statusCategory").asText())));
        }

        JsonNode wf = root.path("workflows").path(0);
        List<Workflow.Status> statuses = new ArrayList<>();
        for (JsonNode s : wf.path("statuses")) {
            Workflow.Status st = byRef.get(s.path("statusReference").asText());
            if (st != null) statuses.add(st);
        }
        List<Workflow.Transition> transitions = new ArrayList<>();
        for (JsonNode t : wf.path("transitions")) {
            List<String> from = new ArrayList<>();
            for (JsonNode l : t.path("links")) {
                String fref = l.path("fromStatusReference").asText("");
                if (!fref.isBlank()) from.add(fref);
            }
            transitions.add(new Workflow.Transition(
                    t.path("name").asText(), t.path("type").asText(),
                    from, t.path("toStatusReference").asText()));
        }
        return new Workflow(workflowId, wf.path("name").asText(), statuses, transitions);
    }

    /** Map the workflow API's status-category name to Jira's category key. */
    private static String categoryKey(String apiCategory) {
        return switch (apiCategory) {
            case "IN_PROGRESS" -> "indeterminate";
            case "DONE" -> "done";
            default -> "new";   // TODO and anything unexpected
        };
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

    /** Set the reporter (blank account id clears it). */
    public void setReporter(String key, String accountId) {
        ObjectNode fields = mapper.createObjectNode();
        if (notBlank(accountId)) fields.putObject("reporter").put("accountId", accountId.trim());
        else fields.putNull("reporter");
        editFields(key, fields);
    }

    /** Set the Release Manager (single-user custom field; blank clears it). */
    public void setReleaseManager(String key, String accountId) {
        setSingleUserField(key, Issue.RELEASE_MANAGER_FIELD, accountId);
    }

    /** Set the Release Authorized By user (single-user custom field; blank clears it). */
    public void setReleaseAuthorizedBy(String key, String accountId) {
        setSingleUserField(key, Issue.RELEASE_AUTHORIZED_BY_FIELD, accountId);
    }

    /** Set the Specification Approver (single-user custom field; blank clears it). */
    public void setSpecApprover(String key, String accountId) {
        setSingleUserField(key, Issue.SPEC_APPROVER_FIELD, accountId);
    }

    /** Set a single-user-picker custom field by accountId; a blank value clears it. */
    private void setSingleUserField(String key, String fieldId, String accountId) {
        ObjectNode fields = mapper.createObjectNode();
        if (notBlank(accountId)) fields.putObject(fieldId).put("accountId", accountId.trim());
        else fields.putNull(fieldId);
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

    /** Clear every Dev Tester (empty the multi-user field). */
    public void clearDevTesters(String key) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putArray(Issue.DEV_TESTER_FIELD);
        editFields(key, fields);
    }

    /** Add a Specification Author (multi-user field) via an incremental update operation. */
    public void addSpecAuthor(String key, String accountId) {
        updateUserList(key, Issue.SPEC_AUTHOR_FIELD, accountId, true);
    }

    /** Remove a Specification Author (multi-user field). */
    public void removeSpecAuthor(String key, String accountId) {
        updateUserList(key, Issue.SPEC_AUTHOR_FIELD, accountId, false);
    }

    /** Clear every Specification Author (empty the multi-user field). */
    public void clearSpecAuthors(String key) {
        ObjectNode fields = mapper.createObjectNode();
        fields.putArray(Issue.SPEC_AUTHOR_FIELD);
        editFields(key, fields);
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

    // ---- Attachments -------------------------------------------------------

    /** An attachment's bytes and content type, fetched with Kong's Jira credentials. */
    public record Attachment(byte[] bytes, String contentType) {}

    /**
     * Fetch an attachment by numeric id, following Jira's redirect to the media
     * CDN. {@code full=false} returns the small thumbnail; {@code true} the
     * full-size file. The browser can't authenticate to Jira, so Kong proxies.
     */
    public Attachment fetchAttachment(String attachmentId, boolean full) {
        String kind = full ? "content" : "thumbnail";
        String url = baseUrl + "/rest/api/3/attachment/" + kind + "/" + enc(attachmentId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authHeader)
                .GET().build();
        try {
            HttpResponse<byte[]> resp = httpRedirect.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int sc = resp.statusCode();
            if (sc < 200 || sc >= 300) {
                throw new JiraException(sc, "GET " + url + " -> HTTP " + sc);
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
            return new Attachment(resp.body(), ct);
        } catch (JiraException e) {
            throw e;
        } catch (Exception e) {
            throw new JiraException(0, "Attachment fetch failed: " + url + " (" + e.getMessage() + ")");
        }
    }

    /**
     * The authenticated user's avatar bytes, proxied so the browser (which has no
     * Jira credentials) can show it. Cached — the avatar rarely changes. Jira
     * Cloud avatar URLs live on external CDNs (gravatar / atl-paas public), so we
     * only present the API token when the URL is actually on the Jira host,
     * never leaking it to a third party.
     */
    public Attachment currentUserAvatar() {
        return cached("meta:myself:avatar", META_TTL_MS, () -> {
            try {
                JsonNode u = get(baseUrl + "/rest/api/3/myself");
                JsonNode urls = u.path("avatarUrls");
                String avatarUrl = urls.path("48x48").asText("");
                if (avatarUrl.isEmpty()) avatarUrl = urls.path("32x32").asText("");
                if (avatarUrl.isEmpty()) throw new JiraException(404, "No avatar URL in myself");
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(avatarUrl))
                        .timeout(Duration.ofSeconds(30)).GET();
                if (avatarUrl.startsWith(baseUrl)) b.header("Authorization", authHeader);
                HttpResponse<byte[]> resp = httpRedirect.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                int sc = resp.statusCode();
                if (sc < 200 || sc >= 300) throw new JiraException(sc, "avatar GET -> HTTP " + sc);
                String ct = resp.headers().firstValue("Content-Type").orElse("image/png");
                return new Attachment(resp.body(), ct);
            } catch (JiraException e) {
                throw e;
            } catch (Exception e) {
                throw new JiraException(0, "Avatar fetch failed (" + e.getMessage() + ")");
            }
        });
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
    /**
     * The token the comment box inserts when you pick someone from the @-mention
     * autocomplete: {@code @[Display Name](accountId)}. Turned into a proper ADF
     * mention node here, which is what makes Jira send the "you were mentioned"
     * notification (Jira does the emailing — Kong just supplies the right ADF).
     */
    private static final Pattern MENTION_TOKEN =
            Pattern.compile("@\\[([^\\]]+)\\]\\(([^)]+)\\)");

    /** Split a line into mention nodes and plain runs, then bold-format the runs. */
    private void appendInline(ArrayNode target, String text) {
        Matcher m = MENTION_TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) appendText(target, text.substring(last, m.start()));
            ObjectNode attrs = target.addObject().put("type", "mention").putObject("attrs");
            attrs.put("id", m.group(2));
            attrs.put("text", "@" + m.group(1));
            last = m.end();
        }
        if (last < text.length()) appendText(target, text.substring(last));
    }

    /** Emit a plain run as text nodes, honouring {@code **bold**}. */
    private void appendText(ArrayNode target, String text) {
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
