package com.fnba.kong.jira;

import java.time.Instant;

/**
 * Changelog-derived timing for a Kanban card: when it entered its current status,
 * and when it first landed on the board (entered the board-entry status). Either
 * may be null when the changelog doesn't record it.
 */
public record Timing(Instant statusSince, Instant boardSince) {}
