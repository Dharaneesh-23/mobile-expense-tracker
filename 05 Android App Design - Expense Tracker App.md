# Expense Tracker App — Android App Design

Native Android, Java. Local-first: Room is the UI's source of truth; the backend is for sync, cross-device access, and PDF generation.

## 1. Screens

| Screen | Purpose |
| --- | --- |
| Onboarding / permissions | Explains and requests Notification Access + SMS Read, then offers the three historical import options |
| Login / signup | Auth against FastAPI |
| Dashboard | Recent transactions, this-month summary, category breakdown |
| Transaction list | Full list with the filter bar (bank, app, credit/debit, payee, date range, amount range) pinned at top |
| Transaction detail | Single transaction: source, category (editable), amount, timestamp, note |
| Categories & budgets | Budget limits per category, progress bars, manage categories |
| Splits | List of pending/settled splits by group name, person, amount, direction; totals at top |
| Reports | Pick a date range → generate PDF → download/share |
| Settings | Connected banks/apps, permission status, data export, account |

## 2. Onboarding import flow

Three backfill sources, all optional, run once at setup:

1. **GPay historical data** — user uploads their Google Takeout export (`Group_expenses.json` + the `My Activity History` HTML file); parsed on-device or server-side per §3 below
2. **Bank SMS history** — automatic the moment SMS Read permission is granted; no upload needed, reads what's already on the device
3. **Bank statement upload (optional)** — user uploads a PDF/CSV statement from net banking; parsed with pdfplumber/PyPDF2 (password-unlock prompt if the PDF is protected); offered as the more-complete alternative/supplement to SMS history

All three write into the same `transactions`/`split_*` tables as live capture, going through the same dedup step, so overlapping data from multiple sources merges rather than duplicating.

## 3. GPay Takeout parser (confirmed against the real export format)

**`Group_expenses.json` → split\_groups / split\_members / split\_shares**

- Read the `Group_expenses` array; for each expense, strip `₹` and commas from `total_amount` and every item's `amount` before parsing as a decimal
- `group_name` is not normalized in the source (emoji, inconsistent casing) — match/merge groups case-insensitively and trimmed, but display the original casing
- Each `items[]` entry maps to a `split_member` (by name) and a `split_share`; `payer` is the share-holder, not necessarily who paid
- Item `state` mapping: `UNPAID` → share status `pending`; `PAID_RECEIVED` and `MARK_AS_PAID` → `settled`; `FAILED` → skipped (not a real obligation)
- Direction (`they_owe_you` vs `you_owe_them`) is derived by comparing each item's `payer` against the account owner's own name
- Known data quirks to handle defensively: at least one item has an empty `payer` (skip with a flag for manual review, don't crash the import); rounding remainders across an uneven split (e.g. ₹231.67/₹231.67/₹231.66) are expected, not an error

**`My Activity History` (HTML) → transactions**

- This file is HTML (Material Design Lite), not JSON, and the sample confirmed it can arrive truncated — treat import coverage as best-effort, not guaranteed-complete
- Parse each `<div class="outer-cell mdl-cell mdl-cell--12-col mdl-shadow--2dp">` block; skip any block whose header-cell title is "Ads"
- From the first content cell, regex out the action type (`Sent`/`Paid`/`Received`), amount, and payee name when present (present for some `Paid` entries, absent for `Sent`)
- Date/time is plain text after a `<br>` in that same cell, format `"Oct 18, 2026, 7:07:01 PM IST"` — needs a custom parser (12-hour clock, no zero-padded day, `IST` label mapped to `+05:30`), unlike the JSON file's clean ISO 8601 UTC timestamps
- Transaction ID and status come from the two lines under "Details:" in the caption cell
- **Only import entries with status `Completed`** — `Pending` and `Failed` entries aren't finalized facts; a pending one will be captured properly once it completes via live capture, and a failed one never moved money
- Map `Sent`/`Paid` → debit, `Received` → credit; `source_app = gpay`, `source_type = import_takeout`; store the original block text in `raw_text` for re-parsing if the parser improves later

## 4. Local Room schema

Mirrors the backend schema (see `03_database_schema.md`) table for table — `TransactionEntity`, `CategoryEntity`, `BudgetEntity`, `SplitGroupEntity`, `SplitMemberEntity`, `SplitShareEntity`, `BankAccountEntity` — so sync is a straightforward diff rather than a translation between two different shapes.

## 5. Sync protocol

WorkManager job runs on a schedule and on reconnect: calls `POST /sync` with any local rows changed since the last successful sync (tracked per-device, matching the `sync_log` table), receives back anything newer on the server, and merges both ways into Room. Conflicts (the same row changed on two devices) resolve last-write-wins by `updated_at`, which is acceptable for a single-user personal finance app.
