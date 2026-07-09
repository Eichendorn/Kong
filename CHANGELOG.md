# Changelog

All notable changes to **Kong** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
