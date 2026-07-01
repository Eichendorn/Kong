package com.fnba.kong.claude;

import com.fnba.kong.config.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs Claude Code skills against a Jira issue by shelling out to the {@code claude}
 * CLI in non-interactive mode ({@code claude -p "<prompt>"}). Each invocation runs
 * on a background thread; the UI polls {@link #get(String)} / {@link #forIssue(String)}
 * via HTMX to watch progress.
 *
 * <p>The integration has two entry points:
 * <ul>
 *   <li><b>Manual trigger</b> — a button on the issue page calls {@link #runSkill}.
 *   <li><b>Transition hook</b> — {@link #onTransition} fires a configured skill
 *       automatically when an issue moves to a matching status.
 * </ul>
 */
public class ClaudeService {

    private final String claudeBin;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, ClaudeRun> runs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final long bootMillis;

    /**
     * Hard cap on a single claude run. The pool only has two threads, so a run
     * that hangs — whether blocked producing no output or simply never exiting —
     * would otherwise pin a thread forever and eventually starve the feature. A
     * watchdog force-kills any process that overruns this; the dev-checklists
     * pipeline is the longest legitimate run and finishes well inside it.
     */
    private static final long RUN_TIMEOUT_MS = 20 * 60 * 1000L;

    /** Most claude runs to retain in memory; oldest finished ones are evicted past this. */
    private static final int MAX_RUNS = 200;

    /** One daemon thread that force-kills overrunning claude processes. */
    private final ScheduledExecutorService watchdogs =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "claude-watchdog");
                t.setDaemon(true);
                return t;
            });

    /**
     * The only skills this service will launch. The {@code command} a caller
     * passes must match one of these EXACTLY (after trimming) — no free-text
     * prompts, no extra arguments. The endpoint is unauthenticated and runs
     * {@code claude} with {@code --permission-mode acceptEdits} against the whole
     * working tree, so anything outside this set is refused before it reaches the
     * CLI. Keep the transition-hook command ({@link #transitionHooks}) in here.
     */
    public static final List<String> ALLOWED_SKILLS = List.of(
            "/developer-checklists-setup",
            "/create-task-gameplan",
            "/create-release-plan",
            "/create-developer-notes",
            "/create-developer-reminders",
            "/replace-smart-checklist");

    /**
     * Status name -> skill command to auto-run when an issue transitions into it.
     * Keys are matched case-insensitively against the target status.
     */
    private final Map<String, String> transitionHooks = Map.of(
            "in progress", "/developer-checklists-setup"
    );

    public ClaudeService(Config cfg, long bootMillis) {
        this.claudeBin = cfg.claudeBin();
        this.bootMillis = bootMillis;
    }

    /** A monotonic millis source seeded at boot (Date.now() is unavailable in some contexts). */
    private long now() {
        return bootMillis + System.nanoTime() / 1_000_000L;
    }

    /** The skills the UI may offer / the service will run, in display order. */
    public List<String> allowedSkills() {
        return ALLOWED_SKILLS;
    }

    /**
     * Launch a skill against an issue. {@code command} must be one of
     * {@link #ALLOWED_SKILLS} exactly (after trimming); the issue key is appended
     * so the skill knows its target. Anything else is refused and recorded as a
     * FAILED run (so the UI shows why) rather than executed.
     */
    public ClaudeRun runSkill(String issueKey, String command) {
        String skill = command == null ? "" : command.strip();
        String id = "run-" + seq.incrementAndGet();
        if (!ALLOWED_SKILLS.contains(skill)) {
            ClaudeRun rejected = new ClaudeRun(id, issueKey,
                    skill.isEmpty() ? "(no command)" : skill, now());
            rejected.status = ClaudeRun.Status.FAILED;
            rejected.exitCode = -1;
            rejected.output = "Rejected: not an allowed skill.\nPermitted: "
                    + String.join(", ", ALLOWED_SKILLS);
            record(rejected);
            return rejected;
        }
        String prompt = skill + " " + issueKey;
        ClaudeRun run = new ClaudeRun(id, issueKey, prompt, now());
        record(run);
        pool.submit(() -> execute(run, prompt));
        return run;
    }

    /**
     * Register a run, then bound the history. Every run — including rejected
     * ones — was previously retained for the life of the process, each holding
     * its full captured output; over a long uptime that is an unbounded heap
     * leak. Keep at most {@link #MAX_RUNS}, evicting the oldest FINISHED runs
     * first and never dropping one that is still RUNNING.
     */
    private void record(ClaudeRun run) {
        runs.put(run.id, run);
        int over = runs.size() - MAX_RUNS;
        if (over <= 0) return;
        runs.values().stream()
                .filter(r -> r.status != ClaudeRun.Status.RUNNING)
                .sorted(Comparator.comparingLong(r -> r.startedAtMillis))
                .limit(over)
                .forEach(r -> runs.remove(r.id));
    }

    /** Called after a successful transition; fires a hook skill if one is configured. */
    public void onTransition(String issueKey, String toStatus) {
        if (toStatus == null) return;
        String skill = transitionHooks.get(toStatus.toLowerCase());
        if (skill != null) {
            runSkill(issueKey, skill);
        }
    }

    public ClaudeRun get(String id) {
        return runs.get(id);
    }

    /** All runs for an issue, newest first. */
    public List<ClaudeRun> forIssue(String issueKey) {
        return runs.values().stream()
                .filter(r -> r.issueKey.equals(issueKey))
                .sorted((a, b) -> Long.compare(b.startedAtMillis, a.startedAtMillis))
                .toList();
    }

    private void execute(ClaudeRun run, String prompt) {
        Process proc = null;
        ScheduledFuture<?> watchdog = null;
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    claudeBin, "-p", prompt,
                    "--permission-mode", "acceptEdits")
                    .directory(new File("/workspace"))
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectErrorStream(true);
            proc = pb.start();
            final Process p = proc;
            // Force-kill on overrun. Destroying the process closes its output
            // stream, so the reader loop below unblocks and we fall through to
            // waitFor() — covering both the silent-hang and never-exit cases.
            watchdog = watchdogs.schedule(() -> {
                if (p.isAlive()) {
                    timedOut.set(true);
                    p.destroyForcibly();
                }
            }, RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    run.output = sb.toString();
                }
            }
            int code = proc.waitFor();
            run.exitCode = code;
            if (timedOut.get()) {
                run.output = sb + "\n[timed out after " + (RUN_TIMEOUT_MS / 60_000)
                        + " min — process killed]";
                run.status = ClaudeRun.Status.FAILED;
            } else {
                run.status = code == 0 ? ClaudeRun.Status.SUCCESS : ClaudeRun.Status.FAILED;
            }
        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            run.output = run.output + "\n[launcher error] " + e.getMessage();
            run.status = ClaudeRun.Status.FAILED;
        } finally {
            if (watchdog != null) watchdog.cancel(false);
        }
    }
}
