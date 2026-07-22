# Kong — Kanban Flow Statistics (design & analysis)

Status: **design agreed; implementation starting with the Done screen.**
Owner: Oberbefehlshaber (Carl Gustaf). Drafted 2026-07-22.

This document captures the plan for adding flow statistics to Kong — "time in
status" per card, aggregate averages over time intervals, and a screen listing
completed (Done) tasks. It records what already exists, what we'll build, the
one real architectural decision, and the open questions still to settle.

---

## What Kong already has

The raw material is largely present — we just don't aggregate it yet.

- **Full Jira changelog parsing** for active cards
  (`JiraClient.issueTimings(keys, boardEntryStatus)`) → exact *status-since* and
  *board-since* (first entry into **Encompass On Deck**). Cached, cleared on writes.
- **Per-card ageing** already rendered on every Kanban card: *days in status /
  column / board* (`Issue.daysInStatus/daysInColumn/daysOnBoard`).
- **`recentTransitions(jql, 50)`** + the `TransitionLog` record (from → to, who,
  when) already feed a *Maintenance → Recent Transitions* screen.
- **`specify_done` screen pattern**: a board's JQL rewritten to a single status,
  rendered with its own template — the template to clone for the Done screen.
- **Source of truth**: the Jira changelog holds the *complete* transition history
  of every issue, so time-in-status, cycle time, throughput, and averages are all
  *derivable* on demand. Nothing is persisted locally today (config + settings
  files only); everything is computed live from Jira per request.

---

## Proposed deliverables

1. **Per-card "time in status" breakdown.** On the issue detail view, a compact
   table/timeline: each status the card passed through, how long it sat there,
   entry → exit dates, with the current status's age highlighted. Extends what
   `issueTimings` already does (needs the per-status durations, not just entry).

2. **Done screen** (`/done/{slug}`) — clone the `specify_done` pattern. Lists
   completed cards with resolution date, an age/throughput metric, story points,
   assignee, reporter. Filterable by completion-date window (later). Doubles as
   the data source for throughput and cycle-time stats and lets you click into any
   finished card. **← we build this first.**

3. **Flow-metrics screen** (`/metrics/{slug}`), scoped by board + a date-window
   selector:
   - **Average time-in-status per column** — the "where do cards get stuck" view
     (the direct answer to "averages on all cards").
   - **Cycle time** (board entry → Done) and **lead time** (created → Done).
   - **Throughput** — cards Done per week/month.
   - **Aging WIP** — current cards, oldest-first (mostly already have).

---

## The one real architectural decision: how to source the data

- **Option A — Live from Jira changelog, no new storage.** Each metrics/Done page
  computes on demand from the changelog, with a short in-memory cache. Everything
  requested is computable this way. *Cons:* changelog expand is ~5× slower, so we
  bound it to a window/cap; true time-series charts (a cumulative-flow diagram,
  WIP-over-time) get expensive as history grows.
- **Option B — Local persistence / daily snapshot store** (JSON or SQLite in
  `%APPDATA%\Kong`). Enables cheap trend charts and decouples from Jira. *Cons:* a
  new moving part (scheduler, staleness, per-user data), more code.

**Recommendation:** start with **Option A** — delivers 100% of what was named with
zero infrastructure and matches Kong's stateless-against-Jira design. Add Option B
later *only* if we decide we want genuine time-series charts (CFD) that Option A
can't do cheaply.

---

## Open decisions (still to settle before the metrics screen)

1. **Cycle-time boundaries:** board entry (**Encompass On Deck**) → Done, or
   created → Done, or both? Affects every number.
2. **Statistic type:** average only, or **average + median + p85**? Flow metrics
   are heavily skewed by outliers — recommend median + p85 alongside the average.
3. **"All cards" scope for time-in-status:** cards *currently* in a status, or
   *every card that passed through it* within the window? Lean toward the latter —
   it's the real flow signal.
4. **Time windows** in the selector: rolling 7/30/90 days, calendar month,
   quarter, all-time? Any weekly/monthly bucketing for throughput?
5. **Sourcing:** confirm Option A to start (recommended), or stand up the
   persistent store now because CFD/trend charts matter.

---

## Implementation notes / gotchas discovered

- **`issueTimings` reads only the first page** (`/search/jql` paginates via an
  opaque `nextPageToken`; the method doesn't loop). Fine for the Kanban's few
  active cards, but for a Done set > ~100 it would silently miss the tail. If a
  changelog-based cycle time is added to the Done screen at scale, paginate it or
  bound the set.
- The plain `search` fetches a fixed field list (no changelog) — cheap. The Done
  screen v1 uses only fields already/newly in that list (`created`,
  `resolutiondate`) so it stays fast and needs no changelog.
- `Issue` gained `createdAt` + `resolutionDate` (Instants) for lead-time math.

### Done screen v1 (first build)

- Route `/done/{slug}`, nav button "Done" (mirrors "Specify Done").
- JQL: board filter with status pinned to **Done**, an optional resolution-date
  range (`resolved >= from`, `resolved <= to 23:59` — end-day inclusive),
  `ORDER BY resolved DESC` (most-recently-completed first), capped at `MAX_RESULTS`.
- **Date-range interface**: From/To date pickers on the screen; only well-formed
  `yyyy-MM-dd` values are honoured (`cleanDate` guard), with a Clear link.
- Columns: Key, Summary, Type, Resolution, **Resolved** (date), **Days to Done**
  (created → resolved — a decision-neutral lead-time proxy that needs no
  changelog), Assignee, Reporter. (Priority and Story Points columns were dropped.)
- **Deliberately NOT** committing to a board-entry cycle-time column yet — that
  waits on open decision #1. "Days to Done" (created → resolved) is unambiguous
  and free.
