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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

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

    public JiraClient(Config cfg) {
        this.baseUrl = cfg.jiraBaseUrl().replaceAll("/+$", "");
        String creds = cfg.jiraEmail() + ":" + cfg.jiraToken();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Reads -------------------------------------------------------------

    /** Run a JQL search and return up to {@code maxResults} issues, paginating as needed. */
    public List<Issue> search(String jql, int maxResults) {
        String fields = "summary,status,issuetype,assignee,reporter,priority,updated,created,description,"
                + STORY_POINTS_FIELD + "," + Issue.DEV_CHECKLISTS_FIELD + "," + Issue.SMART_CHECKLIST_FIELD
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
                    + "&expand=" + enc("changelog")
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
        JsonNode root = get(baseUrl + "/rest/api/3/resolution");
        List<Resolution> out = new ArrayList<>();
        for (JsonNode r : root) {
            out.add(new Resolution(r.path("id").asText(), r.path("name").asText()));
        }
        return out;
    }

    // ---- Writes ------------------------------------------------------------

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
    }

    /** Update simple fields. Use {@link #STORY_POINTS_FIELD} for points. */
    public void editFields(String key, ObjectNode fields) {
        ObjectNode body = mapper.createObjectNode();
        body.set("fields", fields);
        put(baseUrl + "/rest/api/3/issue/" + enc(key), body);
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

    /** Wrap plain text into a minimal Atlassian Document Format doc. */
    private ObjectNode adf(String text) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");
        ObjectNode para = content.addObject();
        para.put("type", "paragraph");
        ArrayNode paraContent = para.putArray("content");
        ObjectNode textNode = paraContent.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        return doc;
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
