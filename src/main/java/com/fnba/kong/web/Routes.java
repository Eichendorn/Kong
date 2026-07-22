package com.fnba.kong.web;

import com.fnba.kong.config.BoardDef;
import com.fnba.kong.config.Config;
import com.fnba.kong.config.Settings;
import com.fnba.kong.jira.CreateProject;
import com.fnba.kong.jira.Comment;
import com.fnba.kong.jira.Issue;
import com.fnba.kong.jira.JiraClient;
import com.fnba.kong.jira.JiraUser;
import com.fnba.kong.jira.Resolution;
import com.fnba.kong.jira.Timing;
import com.fnba.kong.jira.Transition;
import com.fnba.kong.jira.TransitionLog;
import com.fnba.kong.jira.Workflow;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All HTTP routes. Navigation routes render full pages; action routes (POSTs)
 * perform a Jira write and return an HTMX fragment so the page updates in place.
 */
public class Routes {

    private static final int MAX_RESULTS = 500;

    /** Runs the independent Jira reads of a page concurrently (blocking I/O → virtual threads). */
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    // cfg/jira are swapped out by the first-run setup screen once credentials are
    // saved, so they're not final; settings never changes.
    private volatile Config cfg;
    private volatile JiraClient jira;
    private final Settings settings;

    public Routes(Config cfg, JiraClient jira, Settings settings) {
        this.cfg = cfg;
        this.jira = jira;
        this.settings = settings;
    }

    /** True once Jira credentials are present (setup complete). */
    private boolean configured() {
        return cfg.hasJiraCredentials();
    }

    /** Re-read the config file and rebuild the Jira client (after setup saves). */
    private synchronized void reloadConfigAndJira() {
        this.cfg = Config.load();
        this.jira = cfg.hasJiraCredentials() ? new JiraClient(cfg) : null;
    }

    public void register(Javalin app) {
        // First-run gate: until Jira credentials are saved, funnel every page to
        // the setup screen. Static assets (so /setup keeps its styling) and the
        // setup routes themselves are always allowed through.
        app.before(ctx -> {
            if (configured()) return;
            String p = ctx.path();
            if (p.equals("/setup") || p.startsWith("/setup") || isStaticAsset(p)) return;
            ctx.redirect("/setup");
            ctx.skipRemainingHandlers();
        });

        app.get("/setup", this::showSetup);
        app.post("/setup", this::doSetup);

        app.get("/", ctx -> {
            List<BoardDef> boards = cfg.boards();
            ctx.redirect(boards.isEmpty() ? "/search" : "/kanban/" + boards.get(0).slug());
        });

        app.get("/board/{slug}", this::board);
        app.get("/kanban/{slug}", this::kanban);
        app.get("/specify-done/{slug}", this::specifyDone);
        app.get("/done/{slug}", this::done);
        app.get("/aoc-dc", this::aocDc);
        app.get("/attachment/{id}", this::attachment);
        app.get("/me/avatar", this::currentAvatar);
        app.get("/search", this::search);
        app.get("/goto", this::gotoIssue);
        app.get("/create", this::showCreate);
        app.post("/create", this::doCreate);
        app.get("/settings", this::showSettings);
        app.post("/settings/wip", this::saveWipLimits);
        app.get("/maintenance/transitions", this::showTransitions);
        app.get("/maintenance/workflow", this::showWorkflow);
        app.get("/history", this::history);
        app.get("/users/suggest", this::suggestUsers);
        app.get("/users/mention-suggest", this::mentionSuggest);
        app.get("/issue/{key}", this::issue);
        app.get("/issue/{key}/detail", this::detailFragment);

        app.get("/issue/{key}/edit/{field}", this::editField);
        app.get("/issue/{key}/users/suggest", this::suggestIssueUsers);
        app.post("/issue/{key}/type", this::doType);
        app.post("/issue/{key}/priority", this::doPriority);
        app.post("/issue/{key}/assignee", this::doAssignee);
        app.post("/issue/{key}/reporter", this::doReporter);
        app.post("/issue/{key}/releasemanager", this::doReleaseManager);
        app.post("/issue/{key}/releaseauthorizedby", this::doReleaseAuthorizedBy);
        app.post("/issue/{key}/specapprover", this::doSpecApprover);
        app.post("/issue/{key}/devtester/add", this::doDevTesterAdd);
        app.post("/issue/{key}/devtester/remove/{accountId}", this::doDevTesterRemove);
        app.post("/issue/{key}/devtester/clear", this::doDevTesterClear);
        app.post("/issue/{key}/specauthor/add", this::doSpecAuthorAdd);
        app.post("/issue/{key}/specauthor/remove/{accountId}", this::doSpecAuthorRemove);
        app.post("/issue/{key}/specauthor/clear", this::doSpecAuthorClear);
        app.post("/issue/{key}/transition", this::doTransition);
        app.post("/issue/{key}/description", this::doDescription);
        app.post("/issue/{key}/spec", this::doSpec);
        app.post("/issue/{key}/tracking", this::doTracking);
        app.post("/issue/{key}/storypoints", this::doStoryPoints);
        app.post("/issue/{key}/comment", this::doComment);
        app.post("/issue/{key}/worklog", this::doWorklog);

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
        renderList(ctx, "Work In Progress", board.jql(), slug);
    }

    /**
     * The Specify Done queue for a board: the same project/issue-type filter as
     * the board, but restricted to the "Specify Done" status. Rendered with its
     * own column set (Specification Author/Approver instead of Assignee).
     */
    private void specifyDone(Context ctx) {
        String slug = ctx.pathParam("slug");
        BoardDef board = cfg.boards().stream()
                .filter(b -> b.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown board: " + slug));
        Map<String, Object> model = baseModel(slug);
        model.put("title", "Specify Done");
        String jql = specifyDoneJql(board.jql());
        model.put("jql", jql);
        JiraClient.SearchResult res = jiraReady()
                ? jira.search(jql, MAX_RESULTS) : new JiraClient.SearchResult(List.of(), false);
        model.put("issues", sortByStatus(res.issues()));
        model.put("truncated", res.truncated());
        model.put("resultCap", MAX_RESULTS);
        // Kanban and WIP List default to shown (baseModel); hide only this screen's own button.
        model.put("showSpecifyDoneNav", false);
        ctx.render("specify_done.html", model);
    }

    private static final Pattern STATUS_CLAUSE = Pattern.compile(
            "(?i)\\s+AND\\s+status\\s+(?:not\\s+in|in|=|!=)\\s*(?:\\([^)]*\\)|\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Pattern ORDER_BY_CLAUSE = Pattern.compile("(?i)\\s+ORDER\\s+BY\\s+.*$");

    /**
     * Rewrite a board's JQL to select only its "Specify Done" items: drop any
     * existing status predicate and ORDER BY, then pin the status and re-sort by
     * most-recently updated. Keeps the project/issue-type filter board-agnostic.
     */
    static String specifyDoneJql(String boardJql) {
        String base = ORDER_BY_CLAUSE.matcher(boardJql).replaceAll("");
        base = STATUS_CLAUSE.matcher(base).replaceAll("").trim();
        return base + " AND status = \"Specify Done\" ORDER BY updated DESC";
    }

    /**
     * The Done screen for a board: the board's project/issue-type filter with the
     * status pinned to "Done", most-recently-completed first. The data source for
     * throughput/cycle-time stats, and a click-through to any finished card.
     */
    private void done(Context ctx) {
        String slug = ctx.pathParam("slug");
        BoardDef board = cfg.boards().stream()
                .filter(b -> b.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown board: " + slug));
        Map<String, Object> model = baseModel(slug);
        model.put("title", "Done");
        // Date range (on resolution date). A fresh visit (neither param present in
        // the URL) defaults to the current month; submitting the form with the boxes
        // cleared sends empty params, which means "all time" (no bounds). Only
        // well-formed yyyy-MM-dd values are used, so a stray param can't break the JQL.
        String fromRaw = ctx.queryParam("from");
        String toRaw = ctx.queryParam("to");
        String from, to;
        if (fromRaw == null && toRaw == null) {
            LocalDate today = LocalDate.now();
            from = today.withDayOfMonth(1).toString();
            to = today.toString();
        } else {
            from = cleanDate(fromRaw);
            to = cleanDate(toRaw);
        }
        model.put("fromDate", from);
        model.put("toDate", to);
        String jql = doneJql(board.jql(), from, to);
        model.put("jql", jql);
        JiraClient.SearchResult res = jiraReady()
                ? jira.search(jql, MAX_RESULTS) : new JiraClient.SearchResult(List.of(), false);
        model.put("issues", res.issues());   // already resolved-date DESC from JQL
        model.put("truncated", res.truncated());
        model.put("resultCap", MAX_RESULTS);
        model.put("showDoneNav", false);   // this IS the Done screen
        ctx.render("done.html", model);
    }

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Return the value only if it is a well-formed yyyy-MM-dd date, else null. */
    private static String cleanDate(String s) {
        if (s == null) return null;
        s = s.trim();
        return ISO_DATE.matcher(s).matches() ? s : null;
    }

    /**
     * Rewrite a board's JQL to select only its completed items: drop any existing
     * status predicate and ORDER BY, pin the status to "Done", constrain by the
     * resolution-date range (inclusive), and sort by resolution date
     * (most-recently-completed first). {@code from}/{@code to} are yyyy-MM-dd or null.
     */
    static String doneJql(String boardJql, String from, String to) {
        String base = ORDER_BY_CLAUSE.matcher(boardJql).replaceAll("");
        base = STATUS_CLAUSE.matcher(base).replaceAll("").trim();
        StringBuilder sb = new StringBuilder(base).append(" AND status = \"Done\"");
        if (from != null) sb.append(" AND resolved >= \"").append(from).append("\"");
        // Include the whole end day (Jira treats a bare date as 00:00).
        if (to != null) sb.append(" AND resolved <= \"").append(to).append(" 23:59\"");
        sb.append(" ORDER BY resolved DESC");
        return sb.toString();
    }

    /**
     * The two epics whose backlog the AOC/DC screen lists: AOC/DC (MIN-2197) and
     * AOC/DC-8 (MIN-2213), both in project MIN. Keys (not names) so a rename in
     * Jira doesn't break the screen.
     */
    private static final List<String> AOC_DC_EPICS = List.of("MIN-2197", "MIN-2213");

    /**
     * The AOC/DC backlog screen: every task in the Backlog under the AOC/DC and
     * AOC/DC-8 epics, ordered by epic then task key.
     */
    private void aocDc(Context ctx) {
        Map<String, Object> model = baseModel(null);
        model.put("title", "AOC/DC Backlog");
        model.put("showAocDcNav", false);   // this IS the AOC/DC screen
        String jql = aocDcJql();
        model.put("jql", jql);
        JiraClient.SearchResult res = jiraReady()
                ? jira.search(jql, MAX_RESULTS) : new JiraClient.SearchResult(List.of(), false);
        model.put("issues", sortByEpicThenKey(res.issues()));
        model.put("truncated", res.truncated());
        model.put("resultCap", MAX_RESULTS);
        ctx.render("aoc_dc.html", model);
    }

    /** JQL for the AOC/DC backlog: Backlog tasks under the two AOC/DC epics. */
    private static String aocDcJql() {
        return "parent in (" + String.join(",", AOC_DC_EPICS)
                + ") AND status = \"Backlog\" ORDER BY key";
    }

    /** The AOC/DC backlog, ordered as the screen shows it (epic, then task key). */
    private List<Issue> aocDcBacklog() {
        if (!jiraReady()) return List.of();
        return sortByEpicThenKey(jira.search(aocDcJql(), MAX_RESULTS).issues());
    }

    /**
     * Order by epic (parent key), then task key — both numerically so MIN-2199
     * sorts before MIN-2214 regardless of string length.
     */
    private static List<Issue> sortByEpicThenKey(List<Issue> issues) {
        return issues.stream()
                .sorted(Comparator.comparingInt((Issue i) -> keyNum(i.parentKey()))
                        .thenComparingInt(i -> keyNum(i.key())))
                .toList();
    }

    /** Numeric suffix of a Jira key like {@code MIN-2197} → 2197; -1 if none. */
    private static int keyNum(String key) {
        if (key == null) return -1;
        int dash = key.lastIndexOf('-');
        if (dash < 0) return -1;
        try {
            return Integer.parseInt(key.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A Kanban column: label, colour category, WIP limit, and status sub-groups. */
    public record Column(String status, String statusCategory, int wipLimit, List<StatusGroup> groups) {
        public int cardCount() {
            return groups.stream().mapToInt(g -> g.issues().size()).sum();
        }
        public boolean overWip() {
            return wipLimit > 0 && cardCount() > wipLimit;
        }
    }

    /**
     * The Kanban board columns in workflow order — the WIP-limit settings rows.
     * These are columns, not statuses: a column's count sums every status mapped
     * into it (see KANBAN_COLUMN), and its WIP limit applies to that total.
     */
    static final List<String> KANBAN_COLUMNS = List.of(
            "On Deck", "Implement", "Track", "Validate", "Release", "Verify");
    private static final int DEFAULT_WIP = 5;

    /** Columns that never carry a WIP limit (unlimited; no settings row, never flagged). */
    private static final Set<String> NO_WIP_COLUMNS = Set.of("Verify");
    /** Columns whose cards are grouped by Reporter (alphabetical) instead of by status. */
    private static final Set<String> REPORTER_GROUPED_COLUMNS = Set.of("Verify");
    /** The columns that do get an editable WIP limit, in workflow order. */
    static final List<String> WIP_LIMITED_COLUMNS = KANBAN_COLUMNS.stream()
            .filter(c -> !NO_WIP_COLUMNS.contains(c)).toList();

    /** "Days on board" counts from when a task first enters this status. */
    private static final String BOARD_ENTRY_STATUS = "Encompass On Deck";

    /** Cards sharing one status within a column, oldest-in-status first. */
    public record StatusGroup(String status, String statusCategory, List<Issue> issues) {}

    /** Oldest first: earliest entry into the current status category sinks to the top. */
    private static final Comparator<Issue> BY_AGE_OLDEST_FIRST =
            Comparator.comparing(Issue::statusSince, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Earliest workflow rank of any status belonging to a column (for ordering empty columns). */
    private static int columnRank(String label) {
        int best = Issue.rankOf(label);   // self-mapped columns (e.g. Track); -1 otherwise
        for (Map.Entry<String, String> e : KANBAN_COLUMN.entrySet()) {
            if (e.getValue().equals(label)) {
                int r = Issue.rankOf(e.getKey());
                if (r >= 0 && (best < 0 || r < best)) best = r;
            }
        }
        return best;
    }

    /** Split a column's cards into status groups (workflow order), each aged oldest-first. */
    private static List<StatusGroup> statusGroups(List<Issue> issues) {
        Map<String, List<Issue>> byStatus = new HashMap<>();
        for (Issue i : issues) byStatus.computeIfAbsent(i.status(), k -> new ArrayList<>()).add(i);
        return byStatus.values().stream()
                .sorted(Comparator.comparingInt(g ->
                        GROUP_ORDER.getOrDefault(g.get(0).status(), g.get(0).statusRank())))
                .map(g -> new StatusGroup(g.get(0).status(), g.get(0).statusCategory(),
                        g.stream().sorted(BY_AGE_OLDEST_FIRST).toList()))
                .toList();
    }

    /** Split a column's cards into groups by Reporter (alphabetical), each aged oldest-first. */
    private static List<StatusGroup> reporterGroups(List<Issue> issues) {
        Map<String, List<Issue>> byReporter = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Issue i : issues) {
            String r = (i.reporter() == null || i.reporter().isBlank()) ? "—" : i.reporter();
            byReporter.computeIfAbsent(r, k -> new ArrayList<>()).add(i);
        }
        return byReporter.entrySet().stream()
                .map(e -> new StatusGroup(e.getKey(), e.getValue().get(0).statusCategory(),
                        e.getValue().stream().sorted(BY_AGE_OLDEST_FIRST).toList()))
                .toList();
    }

    /** Statuses folded into a shared Kanban column; others get their own column. */
    private static final Map<String, String> KANBAN_COLUMN = Map.ofEntries(
            Map.entry("Encompass On Deck", "On Deck"),
            Map.entry("Spec Review", "On Deck"),
            Map.entry("Implement", "Implement"),
            Map.entry("Ready to Test", "Implement"),
            Map.entry("Testing", "Validate"),
            Map.entry("Revisions Pending", "Validate"),
            Map.entry("Ready to Release", "Validate"),
            Map.entry("Ready to Demo", "Validate"),
            Map.entry("Releasing", "Release"),
            Map.entry("User Verification", "Verify"),
            Map.entry("Verified", "Verify"));

    /** Custom within-column group order (status → ordinal); others fall back to workflow rank. */
    private static final Map<String, Integer> GROUP_ORDER = Map.of(
            "Revisions Pending", 0,
            "Ready to Release", 1,
            "Ready to Demo", 2,
            "Testing", 3);

    // People-filter gating: a person only matches a card through a field that the
    // card actually renders in its current status. These status sets mirror the
    // th:if conditions on the Kanban card (see kanban.html). The Assignee is shown
    // on every card, so it always counts.
    /** Statuses whose card shows the Dev Tester name. */
    private static final Set<String> TESTER_VISIBLE = Set.of(
            "Implement", "Ready to Test", "Testing", "Revisions Pending");
    /** Statuses whose card shows the Release Manager name. */
    private static final Set<String> RELEASE_MANAGER_VISIBLE = Set.of("Ready to Release");

    /** A Dev Tester only counts while the card displays the Tester field. */
    private static boolean testerActive(Issue i) { return TESTER_VISIBLE.contains(i.status()); }

    /** A Release Manager only counts while the card displays the Rel Mgr field. */
    private static boolean releaseManagerActive(Issue i) { return RELEASE_MANAGER_VISIBLE.contains(i.status()); }

    /** Kanban view of a board's active items (no backlog, no resolved), grouped into columns. */
    private void kanban(Context ctx) {
        String slug = ctx.pathParam("slug");
        BoardDef board = cfg.boards().stream()
                .filter(b -> b.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown board: " + slug));
        Map<String, Object> model = baseModel(slug);
        model.put("title", "Work In Progress - Kanban");
        model.put("boardSlug", slug);
        model.put("showKanbanNav", false);   // this IS the Kanban screen
        model.put("highlight", ctx.queryParam("highlight"));   // card to spotlight, if any

        // Reuses the (cached) board search. Active items only, folded into the
        // configured columns; each column ordered by its earliest workflow status,
        // cards newest-first. A column's colour comes from its lowest-rank status.
        JiraClient.SearchResult res = jiraReady()
                ? jira.search(board.jql(), MAX_RESULTS) : new JiraClient.SearchResult(List.of(), false);
        model.put("truncated", res.truncated());
        model.put("resultCap", MAX_RESULTS);
        List<Issue> active = res.issues().stream().filter(Issue::isActive).toList();
        // People filter: the distinct names in the Assignee / Dev Tester / Release
        // Manager fields across the whole board feed a top-bar picklist; picking one
        // narrows the board to cards where that person appears in any of those fields.
        model.put("people", peopleIn(active));
        String person = ctx.queryParam("person");
        model.put("selectedPerson", person == null ? "" : person);
        model.put("showPersonFilter", true);
        if (person != null && !person.isBlank()) {
            active = active.stream().filter(i -> matchesPerson(i, person)).toList();
        }
        // Changelog timing (exact status-since + board-since = Encompass On Deck
        // entry), fetched only for the active cards, overlaid onto each issue.
        Map<String, Timing> timings = jira.issueTimings(
                active.stream().map(Issue::key).toList(), BOARD_ENTRY_STATUS);
        Map<String, List<Issue>> byCol = new HashMap<>();
        Map<String, Integer> colRank = new HashMap<>();
        Map<String, String> colCat = new HashMap<>();
        for (Issue base : active) {
            Timing t = timings.get(base.key());
            Instant ss = (t != null && t.statusSince() != null) ? t.statusSince() : base.statusSince();
            Instant bs = (t != null && t.boardSince() != null) ? t.boardSince() : base.boardSince();
            Issue i = base.withTiming(ss, bs);
            String col = KANBAN_COLUMN.getOrDefault(i.status(), i.status());
            byCol.computeIfAbsent(col, k -> new ArrayList<>()).add(i);
            int rank = i.statusRank();
            if (!colRank.containsKey(col) || rank < colRank.get(col)) {
                colRank.put(col, rank);
                colCat.put(col, i.statusCategory());
            }
        }
        // Always render the defined columns, even when empty (e.g. Release).
        for (String label : KANBAN_COLUMNS) {
            if (!byCol.containsKey(label)) {
                byCol.put(label, new ArrayList<>());
                colRank.put(label, columnRank(label));
                colCat.put(label, "indeterminate");
            }
        }
        List<Column> columns = byCol.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> colRank.get(e.getKey())))
                .map(e -> new Column(e.getKey(), colCat.get(e.getKey()),
                        NO_WIP_COLUMNS.contains(e.getKey()) ? 0
                                : settings.wipLimit(e.getKey(), DEFAULT_WIP),
                        REPORTER_GROUPED_COLUMNS.contains(e.getKey())
                                ? reporterGroups(e.getValue()) : statusGroups(e.getValue())))
                .toList();
        model.put("columns", columns);
        ctx.render("kanban.html", model);
    }

    /** Distinct, case-insensitively sorted names that would match at least one card
     *  under the same status-aware rules as {@link #matchesPerson} (so the picklist
     *  never offers a name that filters to nothing). Unassigned/blank skipped. */
    private static List<String> peopleIn(List<Issue> issues) {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Issue i : issues) {
            if (notBlankName(i.assignee()) && !"Unassigned".equalsIgnoreCase(i.assignee())) {
                names.add(i.assignee());
            }
            if (notBlankName(i.releaseManager()) && releaseManagerActive(i)) {
                names.add(i.releaseManager());
            }
            if (testerActive(i)) {
                for (JiraUser u : i.devTesterUsers()) {
                    if (notBlankName(u.displayName())) names.add(u.displayName());
                }
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * True if {@code name} appears on the issue in a currently-relevant role:
     * as the Assignee (always), the Release Manager (only from Ready to Release
     * onward), or a Dev Tester (only before Ready to Demo).
     */
    private static boolean matchesPerson(Issue i, String name) {
        if (name.equalsIgnoreCase(i.assignee())) return true;
        if (releaseManagerActive(i) && name.equalsIgnoreCase(i.releaseManager())) return true;
        if (testerActive(i)) {
            for (JiraUser u : i.devTesterUsers()) {
                if (name.equalsIgnoreCase(u.displayName())) return true;
            }
        }
        return false;
    }

    private static boolean notBlankName(String s) { return s != null && !s.isBlank(); }

    /**
     * Image proxy for rich-text attachments: the rendered description/comment
     * HTML points its images here, and Kong fetches the bytes from Jira with its
     * own credentials (the browser has none). Numeric ids only — the URL Kong
     * builds is fixed, so there's no SSRF surface.
     */
    private void attachment(Context ctx) {
        String id = ctx.pathParam("id");
        if (!id.matches("\\d+")) throw new IllegalStateException("Invalid attachment id: " + id);
        if (!jiraReady()) { ctx.status(404); return; }
        boolean full = "1".equals(ctx.queryParam("full")) || "true".equalsIgnoreCase(ctx.queryParam("full"));
        JiraClient.Attachment att = jira.fetchAttachment(id, full);
        ctx.contentType(att.contentType());
        ctx.header("Cache-Control", "private, max-age=3600");
        ctx.result(att.bytes());
    }

    /** Proxy the logged-in user's Jira avatar for the top bar. */
    private void currentAvatar(Context ctx) {
        if (!jiraReady()) { ctx.status(404); return; }
        try {
            JiraClient.Attachment a = jira.currentUserAvatar();
            ctx.contentType(a.contentType());
            ctx.header("Cache-Control", "private, max-age=3600");
            ctx.result(a.bytes());
        } catch (Exception e) {
            ctx.status(404);
        }
    }

    private void search(Context ctx) {
        String jql = ctx.queryParamAsClass("jql", String.class)
                .getOrDefault("ORDER BY updated DESC");
        renderList(ctx, "Search", jql, null);
    }

    /** One editable WIP-limit row on the settings screen. */
    public record WipRow(String column, int limit) {}

    private void showSettings(Context ctx) {
        Map<String, Object> model = baseModel(null);
        model.put("title", "Settings");
        List<WipRow> rows = WIP_LIMITED_COLUMNS.stream()
                .map(c -> new WipRow(c, settings.wipLimit(c, DEFAULT_WIP)))
                .toList();
        model.put("wipRows", rows);
        ctx.render("settings.html", model);
    }

    private void saveWipLimits(Context ctx) {
        Map<String, Integer> limits = new java.util.LinkedHashMap<>();
        for (int i = 0; i < WIP_LIMITED_COLUMNS.size(); i++) {
            String raw = ctx.formParam("wip_" + i);
            if (raw != null && !raw.isBlank()) {
                try {
                    int v = Integer.parseInt(raw.trim());
                    if (v > 0) limits.put(WIP_LIMITED_COLUMNS.get(i), v);
                } catch (NumberFormatException ignore) { /* skip invalid */ }
            }
        }
        settings.setWipLimits(limits);
        ctx.redirect("/settings");
    }

    /** Maintenance → Recent Transitions: a newest-first log of status changes. */
    private void showTransitions(Context ctx) {
        List<BoardDef> boards = cfg.boards();
        String slug = boards.isEmpty() ? "" : boards.get(0).slug();
        Map<String, Object> model = baseModel(slug);
        model.put("title", "Recent Transitions");
        String jql = boards.isEmpty()
                ? "ORDER BY updated DESC" : boards.get(0).jql();
        model.put("logs", jiraReady() ? jira.recentTransitions(jql, 50) : List.<TransitionLog>of());
        ctx.render("recent_transitions.html", model);
    }

    /** Maintenance → Workflow Diagram: the MIN workflow drawn as a Mermaid flowchart. */
    private void showWorkflow(Context ctx) {
        List<BoardDef> boards = cfg.boards();
        String slug = boards.isEmpty() ? "" : boards.get(0).slug();
        Map<String, Object> model = baseModel(slug);
        model.put("title", "Workflow Diagram");
        model.put("workflowId", cfg.jiraWorkflowId());
        if (jiraReady()) {
            Workflow wf = jira.workflow(cfg.jiraWorkflowId());
            model.put("workflowName", wf.name());
            model.put("mermaid", wf.toMermaid());
        }
        ctx.render("workflow.html", model);
    }

    /** The revision-history screen: the app version + the changelog rendered to HTML. */
    private void history(Context ctx) {
        Map<String, Object> model = baseModel(null);
        model.put("title", "Revision history");
        model.put("changelogHtml", Changelog.html());
        ctx.render("history.html", model);
    }

    // ---- First-run setup ---------------------------------------------------

    /** Paths the setup gate always lets through, so /setup can load its assets. */
    private static boolean isStaticAsset(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "css", "js", "map", "png", "ico", "svg", "jpg", "jpeg",
                 "gif", "webp", "woff", "woff2", "ttf" -> true;
            default -> false;
        };
    }

    /** The first-run setup form (Jira URL / email / API token). */
    private void showSetup(Context ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("version", Config.appVersion());
        model.put("baseUrl", cfg.jiraBaseUrl());
        model.put("email", configured() ? cfg.jiraEmail() : "");
        model.put("configured", configured());
        ctx.render("setup.html", model);
    }

    /** Verify the entered credentials against Jira, then persist and reload. */
    private void doSetup(Context ctx) {
        String baseUrl = ctx.formParam("baseUrl");
        String email = ctx.formParam("email");
        String token = ctx.formParam("token");
        baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://fnba.atlassian.net" : baseUrl.trim();

        Map<String, Object> model = new HashMap<>();
        model.put("version", Config.appVersion());
        model.put("baseUrl", baseUrl);
        model.put("email", email == null ? "" : email);
        model.put("configured", configured());

        if (email == null || email.isBlank() || token == null || token.isBlank()) {
            model.put("error", "Both your Jira email and API token are required.");
            ctx.status(400).render("setup.html", model);
            return;
        }

        // Verify before writing anything: build a throwaway client and hit /myself.
        String who;
        try {
            who = new JiraClient(baseUrl, email.trim(), token.trim()).currentUser().displayName();
        } catch (JiraClient.JiraException e) {
            model.put("error", e.statusCode == 401 || e.statusCode == 403
                    ? "Jira rejected those credentials (check the email and API token)."
                    : "Couldn't reach Jira at that URL (" + escape(e.getMessage()) + ").");
            ctx.status(400).render("setup.html", model);
            return;
        } catch (Exception e) {
            model.put("error", "Couldn't verify those details: " + escape(e.getMessage()));
            ctx.status(400).render("setup.html", model);
            return;
        }

        try {
            Config.writeCredentials(baseUrl, email.trim(), token.trim());
        } catch (java.io.IOException e) {
            model.put("error", "Saved-config write failed: " + escape(e.getMessage()));
            ctx.status(500).render("setup.html", model);
            return;
        }
        reloadConfigAndJira();
        ctx.redirect("/");
    }

    /** The only issue types offered when creating a new task. */
    private static final Set<String> ALLOWED_ISSUE_TYPES = Set.of(
            "Encompass", "Encompass Bug", "Refactor", "Encompass Investigation");

    /** Target statuses that prompt for a resolution when transitioned into. */
    private static final Set<String> RESOLVING_STATUSES = Set.of("Done", "Canceled");

    /** Boilerplate the Specification Details field is primed with on the create screen. */
    private static final String SPEC_TEMPLATE = """
            **What is the problem?**

            - Problem description

            **Who is the owner?**

            - Person who sponsors the task

            **Who are the stakeholders?**

            - People or departments that will be impacted by the change

            **Proposal**

            - What is the proposed outcome of the task?

            **What does success look like, and how can we measure that?**

            - Requirement 1
            - Requirement 2
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
        JiraClient.SearchResult res = jiraReady()
                ? jira.search(jql, MAX_RESULTS) : new JiraClient.SearchResult(List.of(), false);
        model.put("issues", sortByStatus(res.issues()));
        model.put("truncated", res.truncated());
        model.put("resultCap", MAX_RESULTS);
        // This renders both the board's WIP List (with a slug) and /search (no
        // slug). On the WIP List screen, hide its own WIP List button; Kanban and
        // Specify Done default to shown (baseModel). /search isn't any of the
        // three board views, so all three buttons stay shown there.
        boolean isBoardList = activeSlug != null && !activeSlug.isBlank();
        if (isBoardList) model.put("showListNav", false);
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

    /**
     * Top-bar "go to task key" box: normalise the typed key and jump to its
     * detail screen. A blank entry just returns home; a bad key lands on the
     * Task Not Found screen via {@link #issue}.
     */
    private void gotoIssue(Context ctx) {
        String raw = ctx.queryParam("key");
        String key = raw == null ? "" : raw.trim().toUpperCase().replaceAll("\\s+", "");
        ctx.redirect(key.isEmpty() ? "/" : "/issue/" + key);
    }

    private void issue(Context ctx) {
        String key = ctx.pathParam("key");
        // Fetch the issue first so an unknown/invalid key becomes a friendly
        // Task Not Found screen rather than a raw Jira 4xx error page.
        Issue issue;
        try {
            issue = jira.getIssue(key);
        } catch (JiraClient.JiraException e) {
            if (e.statusCode == 404 || e.statusCode == 400) { renderNotFound(ctx, key); return; }
            throw e;
        }
        BoardDef board = resolveBoard(ctx, key);
        // When the user came from the AOC/DC screen (list=aoc-dc), back the sidebar
        // with the AOC/DC backlog instead of the board's WIP list.
        boolean aocDcList = "aoc-dc".equals(ctx.queryParam("list"));
        Map<String, Object> model = baseModel(board == null ? "" : board.slug());
        model.put("title", key);
        model.put("aocDcList", aocDcList);
        // Fire the remaining independent Jira reads concurrently, then join.
        var transF = async(() -> jira.transitions(key));
        var commsF = async(() -> jira.comments(key));
        var sidebarF = async(() -> aocDcList
                ? aocDcBacklog()
                : (board != null && jiraReady()
                        ? sortByStatus(jira.searchBrief(board.jql(), MAX_RESULTS))
                        : List.<Issue>of()));
        List<Comment> comments = join(commsF);
        model.put("issue", issue);
        model.put("transitions", join(transF));
        model.put("comments", comments);
        model.put("commentJiraUrl", commentJiraUrl(key, comments));
        model.put("sidebarIssues", join(sidebarF));
        ctx.render("issue.html", model);
    }

    /** Full-screen "Task Not Found" response for an unknown/invalid issue key. */
    private void renderNotFound(Context ctx, String key) {
        Map<String, Object> model = baseModel("");
        model.put("title", "Task Not Found");
        model.put("missingKey", key);
        ctx.status(404);
        ctx.render("not_found.html", model);
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

        // No transition chosen (blank default) — just re-render, never transition.
        if (transitionId == null || transitionId.isBlank()) {
            renderDetailFragment(ctx, key);
            return;
        }

        List<Transition> transitions = jira.transitions(key);
        Transition target = transitions.stream()
                .filter(t -> t.id().equals(transitionId))
                .findFirst().orElse(null);
        String toStatus = target == null ? null : target.toStatus();

        // Moving to a resolving status (Done/Canceled) requires a resolution. If
        // one hasn't been chosen yet, re-render the detail pane with a resolution
        // picklist instead of transitioning; Confirm re-posts with it set.
        boolean needsResolution = toStatus != null
                && RESOLVING_STATUSES.stream().anyMatch(s -> s.equalsIgnoreCase(toStatus))
                && (resolution == null || resolution.isBlank());
        if (needsResolution) {
            List<Resolution> options = jira.resolutionOptions(key, transitionId);
            // "Done" is not a meaningful resolution for a cancellation.
            if ("Canceled".equalsIgnoreCase(toStatus)) {
                options = options.stream()
                        .filter(o -> !"Done".equalsIgnoreCase(o.name())).toList();
            }
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
        renderDetailFragment(ctx, key);
    }

    /** Load the inline editor for one meta field (type/priority/assignee/reporter). */
    private void editField(Context ctx) {
        String key = ctx.pathParam("key");
        String field = ctx.pathParam("field");
        Issue issue = jira.getIssue(key);
        Map<String, Object> model = new HashMap<>();
        model.put("key", key);
        model.put("field", field);
        switch (field) {
            case "type" -> {
                String proj = projectKeyOf(key);
                model.put("issueTypes", proj == null ? List.of() : jira.projectIssueTypes(proj));
                model.put("current", issue.issueType());
            }
            case "priority" -> {
                model.put("priorities", jira.priorities());
                model.put("current", issue.priority());
            }
            case "devtester" -> model.put("devTesters", issue.devTesterUsers());
            case "specauthor" -> model.put("specAuthors", issue.specAuthorUsers());
            default -> { /* assignee/reporter: a type-ahead, no options to preload */ }
        }
        ctx.render("fragments/inline_edit.html", model);
    }

    /** Type-ahead user suggestions for inline assignee/reporter edits. */
    /** Autocomplete for @-mentions in comments — any active user, instance-wide. */
    private void mentionSuggest(Context ctx) {
        String q = ctx.queryParam("q");
        List<JiraUser> users = (jiraReady() && q != null && !q.isBlank())
                ? jira.searchUsers(q, 8) : List.<JiraUser>of();
        Map<String, Object> model = new HashMap<>();
        model.put("users", users);
        ctx.render("fragments/mention_suggestions.html", model);
    }

    private void suggestIssueUsers(Context ctx) {
        String key = ctx.pathParam("key");
        String field = ctx.queryParam("field");
        String q = ctx.queryParam("q");
        String proj = projectKeyOf(key);
        List<JiraUser> users = (proj != null && q != null && !q.isBlank())
                ? jira.assignableUsers(proj, q, 8) : List.<JiraUser>of();
        Map<String, Object> model = new HashMap<>();
        model.put("key", key);
        model.put("field", field);
        model.put("users", users);
        ctx.render("fragments/inline_user_suggestions.html", model);
    }

    private void doType(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setIssueType(key, ctx.formParam("issueType"));
        renderDetailFragment(ctx, key);
    }

    private void doPriority(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setPriority(key, ctx.formParam("priority"));
        renderDetailFragment(ctx, key);
    }

    private void doAssignee(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setAssignee(key, ctx.formParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doReporter(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setReporter(key, ctx.formParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doReleaseManager(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setReleaseManager(key, ctx.formParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doSpecApprover(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setSpecApprover(key, ctx.formParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doReleaseAuthorizedBy(Context ctx) {
        String key = ctx.pathParam("key");
        jira.setReleaseAuthorizedBy(key, ctx.formParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doDevTesterAdd(Context ctx) {
        String key = ctx.pathParam("key");
        String accountId = ctx.formParam("accountId");
        if (accountId != null && !accountId.isBlank()) jira.addDevTester(key, accountId);
        renderDetailFragment(ctx, key);
    }

    private void doDevTesterRemove(Context ctx) {
        String key = ctx.pathParam("key");
        jira.removeDevTester(key, ctx.pathParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doDevTesterClear(Context ctx) {
        String key = ctx.pathParam("key");
        jira.clearDevTesters(key);
        renderDetailFragment(ctx, key);
    }

    private void doSpecAuthorAdd(Context ctx) {
        String key = ctx.pathParam("key");
        String accountId = ctx.formParam("accountId");
        if (accountId != null && !accountId.isBlank()) jira.addSpecAuthor(key, accountId);
        renderDetailFragment(ctx, key);
    }

    private void doSpecAuthorRemove(Context ctx) {
        String key = ctx.pathParam("key");
        jira.removeSpecAuthor(key, ctx.pathParam("accountId"));
        renderDetailFragment(ctx, key);
    }

    private void doSpecAuthorClear(Context ctx) {
        String key = ctx.pathParam("key");
        jira.clearSpecAuthors(key);
        renderDetailFragment(ctx, key);
    }

    /** Project key prefix of an issue key (e.g. MIN-1673 → MIN), or null. */
    private static String projectKeyOf(String key) {
        int dash = key.indexOf('-');
        return dash > 0 ? key.substring(0, dash) : null;
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
        Double points = null;
        if (raw != null && !raw.isBlank()) {
            try {
                points = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                // Mapped to a clean error fragment; a raw parse would 500 unhandled.
                throw new IllegalStateException("Story points must be a number (got \""
                        + raw.trim() + "\").");
            }
            if (points < 0) {
                throw new IllegalStateException("Story points can't be negative.");
            }
        }
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

    // ---- Helpers -----------------------------------------------------------

    /** GET the detail fragment on its own (used to dismiss the resolution prompt). */
    private void detailFragment(Context ctx) {
        renderDetailFragment(ctx, ctx.pathParam("key"));
    }

    private void renderDetailFragment(Context ctx, String key) {
        ctx.render("fragments/issue_detail.html", detailModel(key));
    }

    /** Base model for the issue detail fragment — the three reads run concurrently. */
    private Map<String, Object> detailModel(String key) {
        var issueF = async(() -> jira.getIssue(key));
        var transF = async(() -> jira.transitions(key));
        var commsF = async(() -> jira.comments(key));
        List<Comment> comments = join(commsF);
        Map<String, Object> model = new HashMap<>();
        model.put("issue", join(issueF));
        model.put("transitions", join(transF));
        model.put("comments", comments);
        model.put("commentJiraUrl", commentJiraUrl(key, comments));
        model.put("jiraBaseUrl", jiraBrowseBase());
        return model;
    }

    /** Jira site base URL (no trailing slash) for building /browse/ links. */
    private String jiraBrowseBase() {
        return cfg.jiraBaseUrl().replaceAll("/+$", "");
    }

    /**
     * The "Comment in Jira" target: the task with {@code focusedCommentId} set to
     * the newest comment, so Jira scrolls to the top of the comments section
     * (this instance sorts newest first). Falls back to the bare task when there
     * are no comments to anchor to.
     */
    private String commentJiraUrl(String key, List<Comment> comments) {
        String url = jiraBrowseBase() + "/browse/" + key;
        if (comments == null || comments.isEmpty()) return url;
        // comments() returns root comments newest-first, so the first is the newest.
        String newest = comments.get(0).id();
        return (newest == null || newest.isBlank()) ? url : url + "?focusedCommentId=" + newest;
    }

    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, pool);
    }

    /** Join a future, unwrapping the cause so Javalin's exception mappers still apply. */
    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private Map<String, Object> baseModel(String activeSlug) {
        Map<String, Object> model = new HashMap<>();
        List<BoardDef> boards = cfg.boards();
        model.put("boards", boards);
        model.put("activeSlug", activeSlug == null ? "" : activeSlug);
        // Default pattern for the top-bar view-toggle buttons (Kanban, WIP List,
        // Specify Done): each one shows on every screen EXCEPT its own — the
        // screen it links to overrides its flag to false. Every button targets a
        // board, so on slug-less screens (search, settings, history, maintenance)
        // the link falls back to the first configured board via navSlug.
        String navSlug = (activeSlug != null && !activeSlug.isBlank())
                ? activeSlug
                : (boards.isEmpty() ? "" : boards.get(0).slug());
        boolean haveBoard = !navSlug.isEmpty();
        model.put("navSlug", navSlug);
        model.put("showKanbanNav", haveBoard);
        model.put("showListNav", haveBoard);
        model.put("showSpecifyDoneNav", haveBoard);
        model.put("showDoneNav", haveBoard);
        model.put("showAocDcNav", true);   // global screen, not board-scoped
        model.put("jiraReady", jiraReady());
        model.put("jiraBaseUrl", jiraBrowseBase());
        model.put("version", Config.appVersion());
        model.put("currentUserName", currentUserName());
        return model;
    }

    private boolean jiraReady() {
        return jira != null;
    }

    /** Display name of the Jira account Kong is authenticated as, for the top
     *  bar. Cached in JiraClient; defensive so a myself hiccup never breaks a page. */
    private String currentUserName() {
        if (!jiraReady()) return "";
        try {
            JiraUser me = jira.currentUser();
            return me == null ? "" : me.displayName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
