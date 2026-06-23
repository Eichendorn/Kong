package com.fnba.jiramanager.jira;

/** An available workflow transition for an issue: its id, label, and target status. */
public record Transition(String id, String name, String toStatus) {}
