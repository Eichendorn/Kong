package com.fnba.jiramanager.jira;

/** A Jira user reduced to what the UI needs: account id and display name. */
public record JiraUser(String accountId, String displayName) {}
