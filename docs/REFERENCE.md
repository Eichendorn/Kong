# Jira Manager — Reference

Routes, configuration, custom-field IDs, architecture, and operational notes.
For day-to-day usage see [USER_GUIDE.md](USER_GUIDE.md).

## Architecture
`App.main` wires **Config → services (JiraClient, ClaudeService, Settings) → Routes**
and starts embedded Jetty (Javalin). Every request is handled server-side; responses
are Thymeleaf-rendered HTML. Action routes (POSTs) perform a Jira write and return an
HTMX fragment so the page updates in place — there is no hand-written JS beyond the
small `detail-edit.js` helper and the inline scripts in `kanban.html`.

- **`JiraClient`** — thin wrapper over Jira Cloud REST v3 via `java.net.http.HttpClient`,
  HTTP Basic auth built from the configured email + token (never sent to the browser).
- **`Settings`** — JSON-backed (`settings.local.json`) store for the Kanban WIP limits.
- **`Routes`** — all endpoints; also holds the Kanban column model (`Column`, `StatusGroup`)
  and the column/WIP/grouping configuration.
- **View-models** (`jira/*`) are immutable records built from the raw REST JSON.

### Templating gotcha
Thymeleaf + Java **records**: OGNL needs method-call syntax `${x.foo()}` (not `${x.foo}`)
because records expose components as methods, not getters. All templates use `()`.

## HTTP routes

### Pages
| Method | Path | Purpose |
|---|---|---|
| GET | `/` | redirect to the first board (or `/search`) |
| GET | `/board/{slug}` | board list view |
| GET | `/kanban/{slug}` | Kanban (active items); `?highlight=KEY` outlines a card |
| GET | `/search?jql=…` | ad-hoc JQL list |
| GET | `/create` · POST `/create` | New Task form / submit (→ redirect to new issue) |
| GET | `/settings` · POST `/settings/wip` | Settings / save WIP limits |
| GET | `/maintenance/transitions` | Recent Transitions log |
| GET | `/issue/{key}` | full issue detail page |

### Fragments & actions (return HTML fragments for HTMX)
| Method | Path | Purpose |
|---|---|---|
| GET | `/issue/{key}/detail` | the detail fragment (used by the Kanban modal & cancel) |
| GET | `/issue/{key}/edit/{field}` | inline editor for `type`/`priority`/`assignee`/`reporter`/`devtester` |
| GET | `/issue/{key}/users/suggest?field=&q=` | user type-ahead suggestions (inline edit) |
| GET | `/users/suggest` | user type-ahead suggestions (create form) |
| POST | `/issue/{key}/type` · `/priority` · `/assignee` · `/reporter` | set a meta field |
| POST | `/issue/{key}/devtester/add` · `/devtester/remove/{accountId}` | multi-user Dev Tester |
| POST | `/issue/{key}/transition` | run a workflow transition (resolution prompt for Done/Canceled) |
| POST | `/issue/{key}/description` · `/spec` · `/tracking` · `/storypoints` | edit fields |
| POST | `/issue/{key}/comment` · `/worklog` | add a comment / log work |
| POST | `/issue/{key}/claude` · GET `/issue/{key}/claude/runs` | start a Claude run / poll output |

## Configuration
Resolution order: **environment variable → `config.local.properties` → built-in default.**

| Property | Env var | Default | Notes |
|---|---|---|---|
| `jira.baseUrl` | `JIRA_BASE_URL` | `https://fnba.atlassian.net` | |
| `jira.email` | `JIRA_EMAIL` | — | required for live calls |
| `jira.token` | `JIRA_TOKEN` | — | required; API token, server-side only |
| `jira.boards` | `JIRA_BOARDS` | MIN board | `label|jql` pairs separated by `;;` |
| `claude.bin` | `CLAUDE_BIN` | `/workspace/.local/bin/claude` | path to the `claude` CLI |
| `server.port` | `PORT` | `7070` | |

**Boards** are `label|jql` pairs joined by `;;`, e.g.:
```
jira.boards=MIN - Encompass Work|project = "MIN" AND issuetype in (Encompass, "Encompass Bug", "Encompass Investigation", Refactor) ORDER BY updated DESC;;AOC/DC|project = EA AND issuetype in (...) ORDER BY updated DESC
```
> `MIN` is a reserved JQL word — it must be quoted (`project = "MIN"`).

`settings.local.json` (gitignored) holds per-column **WIP limits**, edited via `/settings`.
If absent, every column defaults to a WIP limit of 5.

### Kanban column configuration (in `Routes`)
- `KANBAN_COLUMN` — maps each status → its column label.
- `KANBAN_COLUMNS` — the ordered list of columns shown in Settings.
- `GROUP_ORDER` — custom within-column status order (overrides workflow rank; used by Validate).
- `BOARD_ENTRY_STATUS = "Encompass On Deck"` — where "days on board" starts counting.
- Allowed create issue types: `ALLOWED_ISSUE_TYPES`. Resolution-prompting statuses: `RESOLVING_STATUSES = {Done, Canceled}`.

## Jira custom-field IDs (fnba.atlassian.net)
| Field | ID |
|---|---|
| Developer Checklists | `customfield_14567` |
| Smart Checklist | `customfield_13097` |
| Story Points | `customfield_10016` |
| Specification Details | `customfield_10075` |
| Dev Tester (multi-user) | `customfield_10071` |
| Reason for Tracking | `customfield_13467` |
| Compliance/Regulatory Attribute | `customfield_11667` (Yes/No/Unsure) |
| Release Authorized By (user) | `customfield_13330` |

## Performance & caching
The board can match ~500 issues, so reads are tuned:
- **List/search results** are cached in-memory for **60s** and **invalidated on any write**
  (`editFields`, `transition`, `createIssue`, Dev Tester updates). Per-issue `getIssue` is never cached (always fresh).
- **Slow-changing metadata** (priorities, resolutions, create-meta) is cached for **10 min**.
- The board list omits `expand=changelog` (≈5× faster) and derives "Days In" from the
  cheap `statuscategorychangedate` field.
- The **Kanban** needs exact per-status timing, so it fetches the changelog **only for the
  active cards** (`statusSinceByKeys`, bounded to ~dozens of issues) and overlays it.
- The issue detail page runs its independent Jira reads **concurrently** on virtual threads.

Net: board ≈ 2s cold / ~0.01s warm; detail/kanban interactions are sub-second warm.

## ADF (rich text)
`JiraClient.adf()` converts lightweight markup to Atlassian Document Format for
Description, Specification Details, and comments: `**bold**` → strong, `- `/`* ` lines →
bullet lists, blank lines → paragraphs, single newlines → hard breaks.

## Running & operations
- **Build/run**: see the [README](../README.md#build--run). Toolchain in `/workspace/.tools`.
- **Browser access from the Windows host** (the container has no port mapping) — run the
  socat proxy once, then open `http://localhost:7070`:
  ```sh
  docker run -d --name jira-proxy --network fnba-claude-docker-dist_default \
    -p 7070:7070 alpine/socat tcp-listen:7070,fork,reuseaddr tcp-connect:claude-code:7070
  ```
  Stop: `docker rm -f jira-proxy`.
- **Restart caveat**: don't `pkill -f` with a pattern that matches your own shell command
  (e.g. `java -jar`); start detached with `setsid … >server.log 2>&1 </dev/null & disown`.
- **Git**: repository is local-only (no remote). `config.local.properties`, `settings.local.json`,
  `target/`, and `server.log` are gitignored.
