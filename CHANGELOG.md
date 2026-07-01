# Changelog

All notable changes to **Jira Manager** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
