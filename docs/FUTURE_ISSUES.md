# Future Issues

Known issues and improvements deferred for later. Add new items at the top of the
relevant section with a date and enough context to pick it up cold.

## Open

### Board exceeds the 500-result cap (`MAX_RESULTS`) — 2026-06-30
The MIN board genuinely returns more than 500 matching issues, so the board and
Kanban silently dropped the least-recently-updated matches until the truncation
banner was added (commit `ad37b7e`). The banner/log now make this visible, but the
underlying cap still hides issues.

- **Where:** `Routes.MAX_RESULTS = 500`; `JiraClient.search` / `doSearch`.
- **Why it matters:** the cap is applied *before* status-ranking
  (`sortByStatus`), so in principle an old-but-still-active issue could be
  truncated away. In practice the board JQL pulls all MIN issues of the tracked
  types regardless of status (`ORDER BY updated`), so the hidden ones are almost
  certainly old *resolved* tickets — but "almost certainly" is exactly what the
  banner warns against assuming.
- **Options:**
  1. Raise `MAX_RESULTS` (e.g. 1000) so the full board loads — costs more
     pagination and latency per board load.
  2. Narrow the default board JQL — e.g. exclude Done/Canceled or stale resolved
     work — so the active board fits under the cap.
  3. Both: keep a sane cap but make the default JQL exclude resolved issues.
- **Recommendation:** option 2/3 — the board is meant to show active work, so
  filtering out resolved issues is the natural fix and keeps loads fast.
