package com.fnba.jiramanager.jira;

import java.time.Instant;

/** One status transition from an issue's changelog: from → to, who, and when. */
public record TransitionLog(
        String key,
        String summary,
        String from,
        String to,
        String author,
        Instant at,
        String when) {}
