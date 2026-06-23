package com.fnba.jiramanager.claude;

/**
 * A single invocation of the claude CLI against an issue. Mutable because the
 * background process updates {@code status}/{@code output} as it progresses.
 */
public class ClaudeRun {
    public enum Status { RUNNING, SUCCESS, FAILED }

    public final String id;
    public final String issueKey;
    public final String command;
    public final long startedAtMillis;
    public volatile Status status = Status.RUNNING;
    public volatile String output = "";
    public volatile Integer exitCode = null;

    public ClaudeRun(String id, String issueKey, String command, long startedAtMillis) {
        this.id = id;
        this.issueKey = issueKey;
        this.command = command;
        this.startedAtMillis = startedAtMillis;
    }

    public String statusLabel() {
        return switch (status) {
            case RUNNING -> "Running…";
            case SUCCESS -> "Done";
            case FAILED  -> "Failed";
        };
    }
}
