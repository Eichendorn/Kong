package com.fnba.jiramanager.jira;

import java.util.List;

/** A project the current user may create issues in, with its allowed issue types. */
public record CreateProject(String key, String name, List<CreateIssueType> issueTypes) {

    /** A creatable (non-subtask) issue type: its id and display name. */
    public record CreateIssueType(String id, String name) {}
}
