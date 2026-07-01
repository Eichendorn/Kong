# Kong

A lightweight, server-side web app for viewing and managing Jira tasks on
`fnba.atlassian.net` — a faster, purpose-built alternative to the Jira UI for the
Encompass team's day-to-day work (boards, a curated Kanban, quick edits, task
creation, and a transition log).

The backend does all the work; the browser only ever receives rendered HTML plus a
single vendored library (HTMX). **The Jira API token stays server-side and is never
sent to the browser.**

> Runs locally only — see [Build & run](#build--run). Git-tracked but **no remote**.

## Documentation
- **[User Guide](docs/USER_GUIDE.md)** — every screen and interaction (Board, Kanban, Issue detail, Create, Settings, Maintenance).
- **[Reference](docs/REFERENCE.md)** — HTTP routes, configuration, Jira custom-field IDs, caching/performance.

## Stack
- **Java 21**, built with **Maven**
- **Javalin 6** (embedded Jetty) — HTTP server
- **Thymeleaf** — server-side HTML templates
- **HTMX** (`resources/public/htmx.min.js`) — the only hand-written client library; powers in-place updates
- **java.net.http.HttpClient** — Jira Cloud REST v3 (HTTP Basic auth, server-side)

## Features at a glance
- **Boards** — a sortable table per configured board (MIN), plus ad-hoc JQL search. Backlog and Done rows hidden by default.
- **Kanban** — active items only, in curated workflow columns with per-column **WIP limits**, status sub-groups, and three day-in-state stats per card. Cards open a detail **modal**.
- **Issue detail** — inline-edit Type / Assignee / Reporter / Priority / Dev Tester, edit Specification Details (with a formatted preview), Description, Story Points; status transitions (with a resolution prompt for Done/Canceled); comments; work logs.
- **New Task** — guided creation with a primed Specification Details template and a Compliance field.
- **Settings** — edit the Kanban WIP limits (persisted).
- **Maintenance → Recent Transitions** — a newest-first log of status changes across the board.
- **Claude integration** — run a Claude Code skill against an issue (shells out to the `claude` CLI), with an auto-run hook when an issue moves to *In Progress*.
- **Copy / open links** — copy the Jira (or app) link from any KEY; open the task in the real Jira app in a new tab.

## Configuration
Copy the example and add your token:

```sh
cp config.local.properties.example config.local.properties
# edit config.local.properties → jira.token=...
```

Every setting can also come from an environment variable (env wins over the file):
`JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_TOKEN`, `JIRA_BOARDS`, `CLAUDE_BIN`, `PORT`.

Get an API token at <https://id.atlassian.com/manage-profile/security/api-tokens>.
Full key reference: [docs/REFERENCE.md](docs/REFERENCE.md#configuration).

`config.local.properties` (the token) and `settings.local.json` (WIP limits) are
gitignored and never committed.

## Build & run
The toolchain lives in `/workspace/.tools` (JDK 21 + Maven 3.9.9). Export it, then build:

```sh
export JAVA_HOME=/workspace/.tools/jdk-21.0.11+10
export PATH="$JAVA_HOME/bin:/workspace/.tools/apache-maven-3.9.9/bin:$PATH"

mvn -q package                                   # → target/kong.jar
setsid java -jar target/kong.jar >server.log 2>&1 </dev/null & disown
```

Then open <http://localhost:7070>.

The app runs inside the Docker container, which has no port mapping; to reach it from
the Windows host browser, run the one-time socat proxy and browse to the same port —
see [docs/REFERENCE.md](docs/REFERENCE.md#running--operations).

## Project layout
```
src/main/java/com/fnba/kong/
  App.java                  bootstrap: config → services → routes
  config/   Config, Settings, BoardDef          configuration + persisted settings
  jira/     JiraClient                          Jira REST v3 wrapper (server-side auth, caching)
            Issue, Comment, Transition, TransitionLog, Timing,
            Resolution, Priority, CreateProject, JiraUser    view-models
  claude/   ClaudeService, ClaudeRun            shells out to the claude CLI
  web/      Routes                              all HTTP routes
src/main/resources/
  templates/   board, kanban, issue, create, settings, recent_transitions, fragments/*
  public/      htmx.min.js, app.css, detail-edit.js, favicon.svg
```

See [docs/REFERENCE.md](docs/REFERENCE.md) for the route table and architecture notes.
