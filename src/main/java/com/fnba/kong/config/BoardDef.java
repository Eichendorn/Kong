package com.fnba.kong.config;

/**
 * A board/view shown in the nav: a stable {@code slug} for URLs, a human
 * {@code label}, and the {@code jql} used to fetch its issues.
 */
public record BoardDef(String slug, String label, String jql) {}
