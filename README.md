# Jira Manager

A small server-side web app to view and manage Jira tasks on `fnba.atlassian.net`.
Java backend, no JavaScript written by hand — partial page updates come from a single
vendored library (HTMX). The Jira API token stays server-side and is never sent to the browser.

## Stack
- **Javalin 6** (embedded Jetty) — HTTP server
- **Thymeleaf** — server-side HTML templates
- **HTMX** (`src/main/resources/public/htmx.min.js`) — the only client-side library
- **java.net.http.HttpClient** — Jira Cloud REST v3 calls
- **JDK 21**, built with Maven

## Features
- Browse boards (MIN, AOC/DC) and run ad-hoc JQL searches
- Issue detail: status transitions, edit Story Points, add comments, log work
- **Claude integration**: run a Claude Code skill against an issue (shells out to the
  `claude` CLI), plus an auto-run hook when an issue moves to *In Progress*

## Configuration
Copy the example and fill in your token:

```
cp config.local.properties.example config.local.properties
# edit config.local.properties -> jira.token=...
```

Any setting can also come from an environment variable (these win over the file):
`JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_TOKEN`, `JIRA_BOARDS`, `CLAUDE_BIN`, `PORT`.

Get an API token at https://id.atlassian.com/manage-profile/security/api-tokens

## Build & run
The toolchain lives in `/workspace/.tools` (JDK 21 + Maven 3.9.9); `~/.bashrc` puts both on `PATH`.

```
mvn -q package        # builds target/jira-manager.jar
java -jar target/jira-manager.jar
# or, for dev:
mvn -q exec:java
```

Then open http://localhost:7070

## Layout
```
src/main/java/com/fnba/jiramanager/
  App.java                 bootstrap: config -> services -> routes
  config/Config.java       env + config.local.properties loader
  config/BoardDef.java     board nav definition (slug,label,jql)
  jira/JiraClient.java     Jira REST v3 wrapper (server-side auth)
  jira/Issue.java          flattened issue view-model
  jira/Transition.java     workflow transition view-model
  claude/ClaudeService.java shells out to the claude CLI
  claude/ClaudeRun.java    one CLI invocation's status/output
  web/Routes.java          all HTTP routes
src/main/resources/
  templates/               Thymeleaf (board, issue, fragments/*)
  public/                  htmx.min.js, app.css
```
