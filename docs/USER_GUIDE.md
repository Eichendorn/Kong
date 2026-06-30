# Jira Manager — User Guide

How to use each screen. For routes, config, and internals see [REFERENCE.md](REFERENCE.md).

## Navigation
The top bar (on every screen) has:
- **Brand** (Jira Manager) — home.
- **Board links** — one per configured board (e.g. *MIN - Encompass Work*).
- **Maintenance ▾** — a dropdown; currently → **Recent Transitions**.
- **Search box** — run ad-hoc JQL.
- **⚙ Settings**.

---

## Board (list view)
`/board/{slug}` — a table of every issue matching the board's JQL, most-advanced
status at the top (ties broken by most-recently-updated).

Columns: **Key · Summary · Type · Status · Days In · Assignee · Dev Tester · Chk**.
- **Days In** — whole days since the issue's status *category* last changed.
- **Chk** — ✓ (green) when the Developer Checklists are complete, ✗ (amber) otherwise.

Controls:
- **Show Backlog / Show Done** — backlog-queue and resolved rows are hidden by default; these toggle them on.
- **⊞ Kanban** link, **New Task** button (right-justified).

Click a Key to open its full **[Issue detail](#issue-detail)** page.

**Search** (`/search`) reuses the same table with whatever JQL you enter (e.g.
`assignee = currentUser() AND statusCategory != Done`).

---

## Kanban
`/kanban/{slug}` — **active items only** (anything not in a backlog-queue status and
not resolved). Switch between this and the list with the **LIST / KANBAN** toggle.

### Columns
Columns are curated groupings of statuses (not one-per-status), shown left→right in
workflow order and **always visible even when empty**:

| Column | Statuses it contains |
|---|---|
| **On Deck** | Encompass On Deck, Spec Review |
| **Implement** | Implement, Ready to Test |
| **Track** | Track |
| **Validate** | Revisions Pending, Ready to Release, Ready to Demo, Testing *(in that order)* |
| **Release** | Releasing |
| **Verify** | User Verification, Verified |

Each column header shows **`cards / WIP-limit`**. If a column is over its limit it's
outlined in **red** and the count turns red. WIP limits are set in **[Settings](#settings)**.

Within a multi-status column, cards are grouped by their real status, separated by a
**pale-yellow divider labeled with the status name**, and ordered **oldest-in-status first**.

### Cards
- **KEY** (never wraps) with two icons beside it: a **copy-link** and an **open-in-Jira** icon (see [Link icons](#link-icons)).
- **Status pill** (top-right, truncated if long) — shown when the card's status differs from the column name — plus the **Chk** ✓/✗ badge.
- **Summary**.
- **Tester** — the Dev Tester name, shown only for *Ready to Test*, *Testing*, and *Revisions Pending* (highlighted pale yellow).
- Footer: **type · assignee · `in-status / in-column / on-board` days**.
  - **in status** — days in the exact current status.
  - **in column** — days since the status category changed.
  - **on board** — days since the task first entered *Encompass On Deck*.
- The **assignee** is colored the same green as the KEY.

### Interacting with cards
- **Single click** — selects the card (subtle **orange** 1px outline). Click it again to deselect; click another to move the selection.
- **Double click** — opens the issue's **detail modal** (and selects the card).
- A **blue** outline means you arrived at the board from that card's detail page (via its *Kanban* link); it clears as soon as you click any card.

### Detail modal
A double-click loads the full issue detail into a modal. It's **fully interactive** —
inline edits, transitions, and comments all work just like the detail page.

Floating controls stay on screen as you scroll:
- **Full Details ↗** (top-left) — open the full detail page in a new browser tab.
- **↓ Comments** (top-left) — jump to the comments.
- **↑ Top** (bottom-left) — scroll back to the top.
- **✕** (top-right) — close (also closes on backdrop click or `Esc`).

---

## Issue detail
`/issue/{key}` — the full page: a left **sidebar** (List / Kanban nav + a mini-list of
the board's issues), the **detail pane**, and a **Claude** panel.

### Header
`KEY` + copy-link + open-in-Jira icons, the **status** pill, and (for Done/Canceled)
the **resolution**.

### Editable fields
Click a value to edit it in place; the pane re-renders on save.

| Field | Editor |
|---|---|
| **Type** | dropdown of the project's issue types |
| **Assignee** | type-ahead (search assignable users) |
| **Reporter** | type-ahead |
| **Priority** | dropdown |
| **Dev Tester** | multi-user — current testers shown as removable chips, plus a type-ahead to add |
| **Release Authorized By** | read-only |
| **Specification Details** | textarea with a **Formatted / Markdown** toggle (`**bold**`, `- bullets`) |
| **Description** | textarea |
| **Reason for Tracking** | textarea (only when status is *Track*) |
| **Story Points** | number |

### Status transitions
The transition picklist defaults to a blank "— Select transition —"; **Apply** stays
disabled until you choose one. Transitioning into **Done** or **Canceled** prompts you
to pick a **resolution** first ("Done" is excluded from the Canceled options). The
picklist is hidden once an issue is Done.

### Comments & work
Add comments (markup-aware: `**bold**`, `- bullets`) and log work from the detail pane.

### Claude panel
Run a Claude Code skill against the issue (e.g. `/developer-checklists-setup`); it
shells out to the `claude` CLI server-side and streams the run's output. A run also
auto-fires when an issue transitions to *In Progress*.

---

## New Task
The **New Task** button (board) → `/create`.

- **Project** and **Issue Type** — sourced from Jira's create metadata. Issue types are
  restricted to **Encompass, Encompass Bug, Refactor, Encompass Investigation**.
- **Reporter** — type-ahead, defaulting to you.
- **Description**.
- **Specification Details** — pre-filled with a standard template (bold headings +
  bullet placeholders), with a **Formatted / Markdown** preview toggle.
- **Compliance/Regulatory Attribute** — Yes / No / Unsure.

On save it creates the issue and jumps to its detail page.

---

## Settings
`/settings` (⚙ in the nav) → **Kanban WIP Limits**: one row per Kanban column. Set the
maximum cards per column; a column over its limit is outlined red on the board. Limits
are persisted to `settings.local.json` and survive restarts.

---

## Maintenance → Recent Transitions
`/maintenance/transitions` — a newest-first log of status changes mined from the issue
changelog: **When · Key · Summary · Transition (from → to) · By**. Scans the most
recently-updated issues; the Key links to the issue's detail page.

---

## Link icons
Next to every **KEY** (on cards and the detail header):
- **Copy link** (chain icon):
  - **Left-click** — copies the **Jira** URL (`…/browse/KEY`) to the clipboard; flashes green.
  - **Right-click** — copies this **app's** detail URL (`…/issue/KEY`); flashes orange.
- **Open in Jira** (external-link icon) — opens the task in the real Jira app in a new browser tab.
