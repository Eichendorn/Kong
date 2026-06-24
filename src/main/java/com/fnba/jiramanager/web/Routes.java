package com.fnba.jiramanager.web;

import com.fnba.jiramanager.claude.ClaudeService;
import com.fnba.jiramanager.config.BoardDef;
import com.fnba.jiramanager.config.Config;
import com.fnba.jiramanager.jira.CreateProject;
import com.fnba.jiramanager.jira.Issue;
import com.fnba.jiramanager.jira.JiraClient;
import com.fnba.jiramanager.jira.JiraUser;
import com.fnba.jiramanager.jira.Resolution;
import com.fnba.jiramanager.jira.Transition;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        app.get("/create", this::showCreate);
        app.post("/create", this::doCreate);
        app.get("/users/suggest", this::suggestUsers);
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

    /** The only issue types offered when creating a new task. */
    private static final Set<String> ALLOWED_ISSUE_TYPES = Set.of(
            "Encompass", "Encompass Bug", "Refactor", "Encompass Investigation");

    /** Boilerplate the Specification Details field is primed with on the create screen. */
    private static final String SPEC_TEMPLATE = """
            What is the problem?

            Problem description

            Who is the owner?

            Person who sponsors the task

            Who are the stakeholders?

            People or departments that will be impacted by the change

            Proposal

            What is the proposed outcome of the task?

            What does success look like, and how can we measure that?

            Requirement 1

            Requirement 2
            """;

    /** Render the task-creation screen, pre-selecting the active board's project. */
    private void showCreate(Context ctx) {
        Map<String, Object> model = baseModel(null);
        model.put("title", "Create Task");
        List<CreateProject> projects = jiraReady()
                ? jira.createMeta(configuredProjectKeys()) : List.<CreateProject>of();
        // Restrict to the allowed issue types; drop any project left with none.
        projects = projects.stream()
                .map(p -> new CreateProject(p.key(), p.name(), p.issueTypes().stream()
                        .filter(t -> ALLOWED_ISSUE_TYPES.contains(t.name())).toList()))
                .filter(p -> !p.issueTypes().isEmpty())
                .toList();
        model.put("projects", projects);
        model.put("defaultProjectKey", defaultProjectKey(ctx.queryParam("board"), projects));

        // Reporter defaults to the current user; the field itself is a type-ahead
        // backed by /users/suggest. Compliance options are a fixed set.
        JiraUser me = jiraReady() ? jira.currentUser() : null;
        model.put("currentUserAccountId", me == null ? "" : me.accountId());
        model.put("currentUserName", me == null ? "" : me.displayName());
        model.put("complianceOptions", List.of("Yes", "No", "Unsure"));
        model.put("specTemplate", SPEC_TEMPLATE);

        // Where Cancel returns to: the board the user came from, else the board list root.
        String back = ctx.queryParam("board");
        model.put("backHref", (back == null || back.isBlank()) ? "/" : "/board/" + back);
        ctx.render("create.html", model);
    }

    /** Create the issue from the submitted form and jump to its detail page. */
    private void doCreate(Context ctx) {
        String project = ctx.formParam("project");
        String issueType = ctx.formParam("issuetype");
        String summary = ctx.formParam("summary");
        String description = ctx.formParam("description");
        String reporter = ctx.formParam("reporter");
        String specDetail = ctx.formParam("specDetail");
        String compliance = ctx.formParam("compliance");
        if (project == null || project.isBlank() || issueType == null || issueType.isBlank()
                || summary == null || summary.isBlank()) {
            throw new IllegalStateException("Project, issue type, and summary are all required.");
        }
        String key = jira.createIssue(project.trim(), issueType.trim(), summary.trim(),
                description, reporter, specDetail, compliance);
        ctx.redirect("/issue/" + key);
    }

    /** Type-ahead suggestions for the Reporter field, scoped to the chosen project. */
    private void suggestUsers(Context ctx) {
        String project = ctx.queryParam("project");
        String q = ctx.queryParam("reporterName");
        List<JiraUser> users = List.of();
        if (jiraReady() && project != null && !project.isBlank() && q != null && !q.isBlank()) {
            users = jira.assignableUsers(project, q, 8);
        }
        Map<String, Object> model = new HashMap<>();
        model.put("users", users);
        ctx.render("fragments/user_suggestions.html", model);
    }

    /**
     * The project to pre-select on the create screen: the project behind the
     * given board slug if it resolves, otherwise the first available project.
     */
    private String defaultProjectKey(String boardSlug, List<CreateProject> projects) {
        if (projects.isEmpty()) return "";
        if (boardSlug != null && !boardSlug.isBlank()) {
            for (BoardDef b : cfg.boards()) {
                if (!b.slug().equals(boardSlug)) continue;
                for (String key : projectKeysIn(b.jql())) {
                    for (CreateProject p : projects) if (p.key().equals(key)) return key;
                }
            }
        }
        return projects.get(0).key();
    }

    /** Distinct project keys referenced by any configured board's JQL. */
    private List<String> configuredProjectKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (BoardDef b : cfg.boards()) keys.addAll(projectKeysIn(b.jql()));
        return List.copyOf(keys);
    }

    private static final Pattern PROJECT_KEY = Pattern.compile(
            "project\\s*(?:=|in)\\s*\\(?\\s*\"?([A-Za-z][A-Za-z0-9_]*)\"?", Pattern.CASE_INSENSITIVE);

    private static List<String> projectKeysIn(String jql) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = PROJECT_KEY.matcher(jql);
        while (m.find()) keys.add(m.group(1).toUpperCase());
        return List.copyOf(keys);
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
