package com.fnba.jiramanager.claude;

import com.fnba.jiramanager.config.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /**
     * Launch a skill against an issue. {@code command} is the prompt handed to
     * {@code claude -p}, e.g. {@code "/developer-checklists-setup"} or free text.
     * The issue key is appended so the skill knows its target.
     */
    public ClaudeRun runSkill(String issueKey, String command) {
        String id = "run-" + seq.incrementAndGet();
        String prompt = command.strip() + " " + issueKey;
        ClaudeRun run = new ClaudeRun(id, issueKey, prompt, now());
        runs.put(id, run);
        pool.submit(() -> execute(run, prompt));
        return run;
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
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    claudeBin, "-p", prompt,
                    "--permission-mode", "acceptEdits")
                    .directory(new File("/workspace"))
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectErrorStream(true);
            Process proc = pb.start();
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
            run.status = code == 0 ? ClaudeRun.Status.SUCCESS : ClaudeRun.Status.FAILED;
        } catch (Exception e) {
            run.output = run.output + "\n[launcher error] " + e.getMessage();
            run.status = ClaudeRun.Status.FAILED;
        }
    }
}
