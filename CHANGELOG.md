# Changelog

All notable changes to **Kong** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.1] — 2026-07-20

### Changed
- **Investigate column heading is now yellow.** On the Kanban board the
  **Investigate** column's status pill renders in yellow (amber background, light
  yellow text) to flag it at a glance, consistent with how "Ready to Release" is
  flagged red. Other columns are unchanged.

## [1.2.0] — 2026-07-20

### Added
- **One-click Windows installer.** Kong can now be packaged as a self-contained
  `.msi` (via `jpackage`) that bundles its own Java runtime — end users install
  nothing else, double-click, and get a Start-Menu shortcut. Build with
  `packaging\package.ps1`; a GitHub Actions workflow (`.github/workflows/release.yml`)
  builds and attaches the installer to a GitHub Release on every `v*` tag, and
  `packaging\publish-to-share.ps1` mirrors it to an internal file share. Full
  end-user and maintainer instructions in `docs/INSTALL.md`.
- **First-run setup screen.** On first launch (no saved credentials) Kong opens a
  **Welcome to Kong** page asking for the Jira URL, email, and API token, verifies
  them against Jira before saving, and never touches a config file by hand. A
  before-filter funnels any page to `/setup` until setup is complete; `/setup`
  stays reachable afterward to switch accounts.
- **Auto-open browser on launch.** The installed app (which has no window of its
  own) opens the default browser to Kong on startup; relaunching while it's
  already running just reopens the tab. Suppress with `KONG_NO_BROWSER=1`.

### Changed
- **Per-user data directory.** Config and settings now live in a writable per-user
  location (`%APPDATA%\Kong` on Windows) so an installed copy under Program Files
  works, while a source/dev checkout that already has `config.local.properties` in
  the working directory keeps using it. Override with `KONG_HOME`.

## [1.1.22] — 2026-07-20

### Changed
- **Kanban cards abbreviate the `Encompass Investigation` type to `Investigation`.**
  The card's type badge now reads just **Investigation** for those tasks (styling
  unchanged); every other issue type still shows its full name. Kanban view only.

## [1.1.21] — 2026-07-17

### Changed
- **Top-bar view-toggle buttons now follow one pattern: shown on every screen
  except the one they link to.** The **Kanban** button now appears everywhere but
  the Kanban screen; **WIP List** and **Specify Done** follow the same rule. As a
  result WIP List also shows on the issue, Settings, History, Maintenance,
  Create, and Search screens (previously only on Kanban and Specify Done). On
  screens without a board context, all three target the first configured board.
- **Removed the redundant Kanban link** from the top of the issue-detail
  sidebar — the top-bar Kanban button now covers it.

## [1.1.20] — 2026-07-17

### Fixed
- **Thin dark line under the brand mark.** The top bar's 1px `border-bottom` ran
  directly beneath the app icon, reading as a black underline on it. The brand
  mark now extends 1px lower to cover the border under itself; the bar's divider
  is unchanged everywhere else and the bar height stays the same.

## [1.1.19] — 2026-07-17

### Added
- **Go-to-task search in the top bar.** A key box in the top bar (on every
  screen) jumps straight to a task's detail screen. The key is normalised
  (trimmed, upper-cased, spaces stripped), so `min-679` works. An unknown or
  invalid key lands on a clean full-screen **Task Not Found** response (HTTP 404)
  showing the key you searched, instead of a raw Jira error.

## [1.1.18] — 2026-07-17

### Added
- **@-mention users in comments.** Typing `@` followed by a name in the comment
  box opens an autocomplete of Jira users (instance-wide, keyboard-navigable with
  ↑/↓/Enter/Esc or click). Picking someone posts a proper Atlassian mention, so
  Jira sends that person its standard "you were mentioned" notification — just
  like the Jira editor. Plain `@name` typed without picking stays literal text.

### Fixed
- **Comment box layout.** The mention wrapper is now a full-width flex row, so the
  comment textarea spans the box again (it had collapsed on both the issue screen
  and the Kanban modal).
- **@-mention autocomplete not registering on the Kanban page.** `detail-edit.js`
  is loaded in `<head>` there, so `document.body` was still null when it ran; the
  swap-focus handler now binds to `document` instead, so the script no longer
  throws at load and the mention module registers.

## [1.1.17] — 2026-07-16

### Changed
- **Specify Done button is now shown on every screen except the Specify Done
  screen itself.** Previously it only appeared on the Kanban and WIP List
  screens; it now follows a single app-wide convention, so it's also available
  from the issue detail, Search, Settings, History, Maintenance, and Create
  screens. On screens without a board context, the button targets the first
  configured board.

## [1.1.16] — 2026-07-16

### Removed
- **Claude integration.** The *Claude* panel on the issue detail screen is gone,
  along with its whole back end: the skill-runner service that shelled out to the
  `claude` CLI, the run-history polling fragment, the `POST /issue/{key}/claude`
  and `GET /issue/{key}/claude/runs` routes, and the `claude.bin` config setting.
  This also removes the transition hook that auto-ran
  `/developer-checklists-setup` when an issue moved to *In Progress* — that
  automation no longer fires from Kong.

## [1.1.15] — 2026-07-15

### Added
- **Flag a Kanban card by click-and-hold.** Pressing and holding a card (~450ms)
  toggles a subtle yellow wash on it, so you can mark cards while you work; hold
  again to clear. Marks are background-only, so they layer under the existing
  selection (orange outline) and post-edit spotlight (blue border). They persist
  per browser (localStorage, keyed by issue key), so they survive the board's own
  reload after you edit a card. Dragging or pressing on a card's link buttons
  doesn't trigger it, and a completed hold won't also select the card.
- **Colour-coded issue types on Kanban cards.** In the card foot, **Encompass
  Bug** is red, **Refactor** is blue, and **Encompass Investigation** is brown;
  other types stay the default muted grey.

### Changed
- **Cleaner multi-status columns.** A column no longer repeats its own entry
  status. In **On Deck**, the *Encompass On Deck* cards drop their redundant
  status badge and yellow separator and sit flush under the header; in
  **Implement**, the *Implement* separator is likewise gone. Genuinely distinct
  sub-statuses (Spec Review, Ready to Test, …) keep their separators, so the
  columns read consistently with the single-status ones.

## [1.1.14] — 2026-07-14

### Added
- **Workflow Diagram** (Maintenance → Workflow Diagram). Renders the MIN
  workflow as a flowchart, fetched live from Jira: statuses coloured by category
  (To Do / In Progress / Done), the create step off a Start node, and the four
  global transitions (Cancel Task, CLOSE, Send to Backlog, TRACK) off a dashed
  "Any status" node. Diagrams are drawn with Mermaid, vendored locally so no
  external network calls are made. The target workflow is configurable via
  `jira.workflowId` / `JIRA_WORKFLOW_ID`.
- **Zoom control** on the Workflow Diagram — `−` / `+` steps and a "Fit" reset,
  with a live percentage readout. The diagram fits the full window width by
  default and scrolls vertically as it grows.

### Fixed
- **Detail field columns use the full pane width.** The top-right transition
  dropdown had reserved 300px down the entire detail head, squeezing every field
  row below it; that reservation now applies only to the title lines, so the
  two-column layout (and long rows like Labels + its Edit-in-Jira link) get the
  space that was previously wasted.

## [1.1.13] — 2026-07-14

### Added
- **Specification Approver detail field** — a single-user, inline-editable
  field shown under Specification Author (type-ahead to set, Clear to empty).
- **Labels detail field** — shows the issue's labels with an "Edit in Jira ↗"
  link (labels are edited in Jira).

### Fixed
- **Detail field columns now use the full pane width.** The top-right
  transition dropdown had reserved 300px down the entire detail head, needlessly
  squeezing every field row below it. That reservation now applies only to the
  title lines, so the two-column field layout stretches into the previously
  wasted space and long rows (e.g. Labels + its link) fit on one line.

## [1.1.12] — 2026-07-13

### Added
- **Specification Author is now an editable detail field.** It appears in the
  issue detail/modal and edits like Dev Tester — a multi-user picker with
  removable chips, a type-ahead to add people, and "Clear all".

### Changed
- **Detail fields are laid out in two columns.** Left: Dev Checklists,
  Assignee, Reporter, Specification Author, Dev Tester. Right: Type, Priority,
  Release Authorized By, Release Manager. The columns collapse to one when the
  pane is narrow.

## [1.1.11] — 2026-07-13

### Changed
- **Reason for Tracking is subtly flagged red on the issue detail.** On
  `Track`-status issues, the Reason for Tracking field now shows a faint red
  tint on its value box and a red title, so it stands out from the other
  fields without being loud.

## [1.1.10] — 2026-07-10

### Fixed
- **Square brackets in rich-text fields no longer look like errors.** Jira's
  wiki renderer treats `[text]` as a link and, when it can't resolve one (e.g.
  field codes like `[CX.VLINDICATOR]`), wraps it in `<span class="error">` —
  which collided with Kong's own red `.error` banner style and made the
  bracketed text look highlighted. Kong now unwraps those spans so brackets
  render as plain text.
- **Low-contrast colored text in rich-text fields is now readable.** Jira's text
  colors and highlights are chosen for a white page, so a dark color (its blue
  `#0747a6`, dark red, …) could sink into Kong's dark panel. Kong now measures
  each inline color's WCAG contrast against the background it sits on and, when
  it's too faint, shifts the color's lightness — keeping its hue — until it's
  legible. Works for any color, and also darkens the default light text when it
  lands on a light highlight.

## [1.1.9] — 2026-07-10

### Added
- **The logged-in Jira user now shows at the far right of the top bar** — their
  avatar and display name, on every page. Kong reads the account from Jira's
  `/myself` endpoint (the identity of the configured API token) and caches it.
  The avatar is proxied through Kong (`/me/avatar`) because the browser has no
  Jira credentials; the API token is only ever sent to the Jira host itself,
  never to the external avatar CDNs. If the avatar can't be loaded the name
  stands on its own.

## [1.1.8] — 2026-07-09

### Added
- **The Kanban board refreshes itself after you edit a card.** When you change a
  card in the detail modal — a field edit, a workflow transition, a comment —
  and then close it, the board reloads so it reflects the change instead of
  showing stale data. The card you were on stays spotlighted, and the active
  board/person filter is preserved. Closing a card you didn't change does
  nothing (no needless reload). The WIP List already reloads on its own, since
  it opens cards as full pages.
- **Kanban and WIP List toggles on the Recent Transitions screen.** The
  transitions log now carries the same top-bar view toggles as the boards, so
  you can jump straight back to the Kanban board or the WIP List instead of
  routing through the Maintenance menu.

### Changed
- **Kanban is now the app's default screen.** Opening Kong (and clicking the
  "Kong" title in the top bar) lands on the Kanban board rather than the WIP
  List.

## [1.1.7] — 2026-07-09

### Added
- **King Kong brand mark in the top bar.** A gorilla image (recolored from a
  source painting to the app's orange theme, grass kept green) sits as a square
  in the top-left corner of the top bar, flush to the top, bottom, and left
  edges, just left of the "Kong" wordmark.

### Changed
- **New favicon.** The browser-tab icon is now a stylized gorilla *head* —
  greyscale gorilla on an orange background, cropped from the same source and
  reduced to just orange + greyscale. Replaces the orange "K" glyph across all
  pages (64×64 PNG).

## [1.1.6] — 2026-07-08

### Added
- **Filter the Kanban board by person.** A new top-bar picklist lists the people
  who appear on the board as **Assignee**, **Dev Tester**, or **Release
  Manager**. Picking a name narrows the board to the cards where that person
  holds one of those roles; **All people** clears it. The picklist gets an
  active-filter highlight while a name is selected.
- **Role matching is status-aware — it follows what the card actually shows.**
  A person only matches a card through a field the card renders in its current
  status: Assignee always (it's in every card foot), Dev Tester only in
  Implement / Ready to Test / Testing / Revisions Pending, and Release Manager
  only in Ready to Release. So a tester drops off a card once it reaches Ready to
  Demo, and a release manager doesn't surface until Ready to Release. The
  picklist honors the same rule, so it never offers a name that would match
  nothing.

### Added
- **Clear user fields from the detail screen.** The inline editors for
  **Assignee**, **Reporter**, **Dev Tester**, **Release Manager**, and **Release
  Authorized By** now carry a clear control so a field can be emptied, not just
  reassigned. Assignee shows **Unassign** (the field reverts to Unassigned);
  the single-user fields show **Clear**; Dev Tester (multi-user) shows **Clear
  all** when it has entries.

## [1.1.4] — 2026-07-07

### Added
- **Rich text on the detail screen.** Description, Specification Details, and
  comments now render with Jira's own formatting — headings, lists, tables,
  colors, links, and inline images — instead of flattened plain text. Kong asks
  Jira for the rendered HTML (`expand=renderedFields` / `renderedBody`),
  sanitizes it, and streams attachment images through a new `/attachment/{id}`
  proxy (the browser can't authenticate to Jira; Kong does, following Jira's
  redirect to the media CDN). Kong's own comment box still posts plain text; a
  **Comment in Jira ↗** link jumps to the task (scrolled to the comments
  section) when a comment needs formatting or images.

### Changed
- **Description & Specification Details are now read-only in Kong**, with an
  **Edit in Jira ↗** link on each. Editing them here previously flattened all
  formatting and images on save; authoring rich content stays in Jira for now.
  (A full in-Kong rich editor is a separate, larger effort, deferred.)

## [1.1.3] — 2026-07-06

### Added
- **Specify Done view.** A new top-bar toggle opens a list of the board's
  "Specify Done" items (same project/issue-type filter, status pinned to
  Specify Done). It uses the WIP List columns but drops **Assignee**, adds a
  **Priority** column, and adds **Specification Author** and **Specification
  Approver** after Reporter.

### Changed
- **Renamed the "LIST" toggle to "WIP List"** to distinguish it from the new
  Specify Done view.
- **Board switcher is now a dropdown.** The row of board buttons (which, with a
  single board, duplicated the WIP List toggle) is now an always-visible
  pick-list — one entry today, ready to scale as boards are added.

## [1.1.2] — 2026-07-02

### Fixed
- **Kanban card hover.** Hovering a card no longer blends it into the column —
  the highlight was the same shade as the column background. It now goes a shade
  darker than the card's resting background, reading as the card recessing on
  hover and keeping the depth order (screen › column › card) intact.

## [1.1.1] — 2026-07-01

### Changed
- **Lighter theme.** The app background is lighter, giving the Kanban a clear
  depth order — screen background (lightest) › columns › cards (darkest).
- **Always-visible scrollbars** at normal width (no longer thin/transparent),
  with a subtle muted-grey thumb.

## [1.1.0] — 2026-07-01

The "Kong" rebrand.

### Changed
- **Renamed the application to "Kong"** (was "Jira Manager") across the top-bar
  brand and page titles.
- **Top-bar app name is now set in Metal Mania** (SIL OFL, self-hosted from
  `/public/fonts`). Page headings keep the default UI font.
- **New favicon** — a Metal Mania orange "K" (the glyph baked in as a vector
  path) on the dark rounded square, replacing the lime-green "JM".
- **Renamed the technical identity to Kong**: Java package
  `com.fnba.jiramanager` → `com.fnba.kong`, Maven artifact/jar `jira-manager` →
  `kong` (build now produces `target/kong.jar`), and the version resource
  `jira-manager.properties` → `kong.properties`. The git repository directory
  and remote are unchanged.

## [1.0.0] — 2026-07-01

First tagged release. A local web app (Java + Javalin + Thymeleaf + HTMX) for
working the Encompass boards on `fnba.atlassian.net` — a faster, opinionated
view over the Jira REST API with a built-in Claude integration.

### Added
- **Board list ("Work In Progress").** A sortable table of a board's active
  items (backlog and resolved statuses excluded from the JQL), ordered by
  workflow status. Columns: Key, Summary, Type, Status, Days In, Reporter,
  Assignee, Dev Tester, and a checklists-complete badge. Full-width layout with
  the list scrolling inside its own region (sticky header) so the page itself
  doesn't scroll. Unassigned assignees render in red.
- **Kanban view ("Work In Progress - Kanban").** Status-grouped columns with
  per-column WIP limits (editable in Settings), a status-category colour scheme,
  and a card detail modal. The **Verify** column has no WIP limit and groups its
  cards by Reporter; On Deck cards show the Specification Approver, Implement
  cards the Tester, Ready to Demo the Demo date, Ready to Release the Release
  Manager. LIST/KANBAN toggles live in the top bar.
- **Issue detail.** Inline-editable Type, Assignee, Reporter, Priority, Dev
  Tester, Release Manager, and Release Authorized By (user fields use a
  type-ahead); editable Specification Details, Reason for Tracking, and
  Description; workflow transitions with Done/Canceled resolution prompts;
  comments and worklog; markup-aware ADF rendering.
- **Create task** from a board, with project-scoped Reporter type-ahead.
- **Claude integration.** Run an allow-listed set of skills
  (`/developer-checklists-setup`, `/create-task-gameplan`, `/create-release-plan`,
  `/create-developer-notes`, `/create-developer-reminders`,
  `/replace-smart-checklist`) against an issue as `claude -p` subprocesses, with
  a run log and a per-run timeout watchdog.
- **Maintenance → Recent Transitions**, a newest-first status-change log.
- **Settings** screen for the Kanban WIP limits.
- **Performance.** An in-memory TTL cache (bounded, with eviction) plus
  concurrent issue reads on virtual threads bring a board load from ~9s to ~2s.
- **Version badge + revision history.** The top bar shows the app version
  (`vX.Y.Z`, baked in from the pom at build time via a filtered
  `jira-manager.properties`); clicking it opens a **Revision history** screen at
  `/history` that renders this changelog (bundled into the jar, converted to
  HTML with commonmark).
