package com.fnba.jiramanager.web;

import com.fnba.jiramanager.claude.ClaudeService;
import com.fnba.jiramanager.config.BoardDef;
import com.fnba.jiramanager.config.Config;
import com.fnba.jiramanager.jira.Issue;
import com.fnba.jiramanager.jira.JiraClient;
import com.fnba.jiramanager.jira.Resolution;
import com.fnba.jiramanager.jira.Transition;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All HTTP routes. Navigation routes render full pages; action routes (POSTs)
 * perform a Jira write and return an HTMX fragment so the page updates in place.
 */
public class Routes {

    private static final int MAX_RESULTS = 500;

    private final Config cfg;
    private final JiraClient jira;
    private final ClaudeService claude;

    public Routes(Config cfg, JiraClient jira, ClaudeService claude) {
        this.cfg = cfg;
        this.jira = jira;
        this.claude = claude;
    }

    public void register(Javalin app) {
        app.get("/", ctx -> {
            List<BoardDef> boards = cfg.boards();
            ctx.redirect(boards.isEmpty() ? "/search" : "/board/" + boards.get(0).slug());
        });

        app.get("/board/{slug}", this::board);
        app.get("/search", this::search);
        app.get("/issue/{key}", this::issue);
        app.get("/issue/{key}/detail", this::detailFragment);

        app.post("/issue/{key}/transition", this::doTransition);
        app.post("/issue/{key}/description", this::doDescription);
        app.post("/issue/{key}/spec", this::doSpec);
        app.post("/issue/{key}/tracking", this::doTracking);
        app.post("/issue/{key}/storypoints", this::doStoryPoints);
        app.post("/issue/{key}/comment", this::doComment);
        app.post("/issue/{key}/worklog", this::doWorklog);
        app.post("/issue/{key}/claude", this::doClaude);
        app.get("/issue/{key}/claude/runs", this::claudeRuns);

        app.exception(JiraClient.JiraException.class, (e, ctx) ->
                ctx.status(502).html("<div class='error'>Jira error: "
                        + escape(e.getMessage()) + "</div>"));
        app.exception(IllegalStateException.class, (e, ctx) ->
                ctx.status(500).html("<div class='error'>" + escape(e.getMessage()) + "</div>"));
    }

    // ---- Navigation --------------------------------------------------------

    private void board(Context ctx) {
        String slug = ctx.pathParam("slug");
        BoardDef board = cfg.boards().stream()
                .filter(b -> b.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown board: " + slug));
        renderList(ctx, board.label(), board.jql(), slug);
    }

    private void search(Context ctx) {
        String jql = ctx.queryParamAsClass("jql", String.class)
                .getOrDefault("ORDER BY updated DESC");
        renderList(ctx, "Search", jql, null);
    }

    private void renderList(Context ctx, String title, String jql, String activeSlug) {
        Map<String, Object> model = baseModel(activeSlug);
        model.put("title", title);
        model.put("jql", jql);
        List<Issue> issues = jiraReady() ? jira.search(jql, MAX_RESULTS) : List.<Issue>of();
        model.put("issues", sortByStatus(issues));
        ctx.render("board.html", model);
    }

    /**
     * Most-advanced workflow status at the top, least at the bottom. Within the
     * same status, fall back to most-recently-updated first.
     */
    private static List<Issue> sortByStatus(List<Issue> issues) {
        return issues.stream()
                .sorted(Comparator.comparingInt(Issue::statusRank).reversed()
                        .thenComparing(Comparator.comparing(Issue::updated).reversed()))
                .toList();
    }

    private void issue(Context ctx) {
        String key = ctx.pathParam("key");
        BoardDef board = resolveBoard(ctx, key);
        Map<String, Object> model = baseModel(board == null ? "" : board.slug());
        model.put("title", key);
        model.put("issue", jira.getIssue(key));
        model.put("transitions", jira.transitions(key));
        model.put("comments", jira.comments(key));
        model.put("runs", claude.forIssue(key));
        // Compact list for the left sidebar (key + summary only — no changelog fetch).
        List<Issue> sidebar = (board != null && jiraReady())
                ? sortByStatus(jira.searchBrief(board.jql(), MAX_RESULTS))
                : List.<Issue>of();
        model.put("sidebarIssues", sidebar);
        ctx.render("issue.html", model);
    }

    /**
     * Which board's list backs the sidebar for this issue: the {@code board}
     * query param if valid, else the board whose JQL targets the issue's
     * project, else the first configured board.
     */
    private BoardDef resolveBoard(Context ctx, String key) {
        List<BoardDef> boards = cfg.boards();
        String slug = ctx.queryParam("board");
        if (slug != null && !slug.isBlank()) {
            for (BoardDef b : boards) if (b.slug().equals(slug)) return b;
        }
        int dash = key.indexOf('-');
        if (dash > 0) {
            String proj = key.substring(0, dash);
            for (BoardDef b : boards) if (b.jql().contains(proj)) return b;
        }
        return boards.isEmpty() ? null : boards.get(0);
    }

    // ---- Actions (return fragments) ---------------------------------------

    private void doTransition(Context ctx) {
        String key = ctx.pathParam("key");
        String transitionId = ctx.formParam("transitionId");
        String resolution = ctx.formParam("resolution");

        List<Transition> transitions = jira.transitions(key);
        Transition target = transitions.stream()
                .filter(t -> t.id().equals(transitionId))
                .findFirst().orElse(null);
        String toStatus = target == null ? null : target.toStatus();

        // Moving to Done requires a resolution. If one hasn't been chosen yet,
        // re-render the detail pane with a resolution picklist instead of
        // performing the transition; the Confirm button re-posts with it set.
        boolean needsResolution = "Done".equalsIgnoreCase(toStatus)
                && (resolution == null || resolution.isBlank());
        if (needsResolution) {
            List<Resolution> options = jira.resolutionOptions(key, transitionId);
            if (!options.isEmpty()) {
                Map<String, Object> model = detailModel(key);
                model.put("resolutionPrompt", Boolean.TRUE);
                model.put("pendingTransitionId", transitionId);
                model.put("pendingTransitionLabel", target.name() + " → " + target.toStatus());
                model.put("resolutions", options);
                ctx.render("fragments/issue_detail.html", model);
                return;
            }
            // No selectable resolutions — transition without one.
        }

        jira.transition(key, transitionId, resolution);
        claude.onTransition(key, toStatus);
        renderDetailFragment(ctx, key);
    }

    private void doDescription(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setDescription(key, ctx.formParam("text"));
        renderDetailFragment(ctx, key);
    }

    private void doSpec(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setSpecDetail(key, ctx.formParam("text"));
        renderDetailFragment(ctx, key);
    }

    private void doTracking(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setReasonForTracking(key, ctx.formParam("text"));
        renderDetailFragment(ctx, key);
    }

    private void doStoryPoints(Context ctx) {
        String key = ctx.pathParam("key");
        String raw = ctx.formParam("points");
        Double points = (raw == null || raw.isBlank()) ? null : Double.parseDouble(raw.trim());
        jira.setStoryPoints(key, points);
        renderDetailFragment(ctx, key);
    }

    private void doComment(Context ctx) {
        String key = ctx.pathParam("key");
        String text = ctx.formParam("text");
        if (text != null && !text.isBlank()) jira.addComment(key, text.trim());
        renderDetailFragment(ctx, key);
    }

    private void doWorklog(Context ctx) {
        String key = ctx.pathParam("key");
        String timeSpent = ctx.formParam("timeSpent");
        String comment = ctx.formParam("comment");
        if (timeSpent != null && !timeSpent.isBlank()) {
            jira.addWorklog(key, timeSpent.trim(), comment);
        }
        renderDetailFragment(ctx, key);
    }

    private void doClaude(Context ctx) {
        String key = ctx.pathParam("key");
        String command = ctx.formParam("command");
        if (command != null && !command.isBlank()) claude.runSkill(key, command);
        renderRunsFragment(ctx, key);
    }

    private void claudeRuns(Context ctx) {
        renderRunsFragment(ctx, ctx.pathParam("key"));
    }

    // ---- Helpers -----------------------------------------------------------

    /** GET the detail fragment on its own (used to dismiss the resolution prompt). */
    private void detailFragment(Context ctx) {
        renderDetailFragment(ctx, ctx.pathParam("key"));
    }

    private void renderDetailFragment(Context ctx, String key) {
        ctx.render("fragments/issue_detail.html", detailModel(key));
    }

    /** Base model for the issue detail fragment. */
    private Map<String, Object> detailModel(String key) {
        Map<String, Object> model = new HashMap<>();
        model.put("issue", jira.getIssue(key));
        model.put("transitions", jira.transitions(key));
        model.put("comments", jira.comments(key));
        return model;
    }

    private void renderRunsFragment(Context ctx, String key) {
        Map<String, Object> model = new HashMap<>();
        model.put("issue", jira.getIssue(key));
        model.put("runs", claude.forIssue(key));
        ctx.render("fragments/claude_runs.html", model);
    }

    private Map<String, Object> baseModel(String activeSlug) {
        Map<String, Object> model = new HashMap<>();
        model.put("boards", cfg.boards());
        model.put("activeSlug", activeSlug == null ? "" : activeSlug);
        model.put("jiraReady", jiraReady());
        return model;
    }

    private boolean jiraReady() {
        return jira != null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
