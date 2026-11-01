# Expense Tracker App — Feature Implementation Plans

Concrete, ordered steps per feature. File names referenced here match `08_file_structure_and_build_manifest.md` (next doc).

## 1. Capture layer

1. Implement `NotificationCaptureService extends NotificationListenerService`; declare it in `AndroidManifest.xml` with the `BIND_NOTIFICATION_LISTENER_SERVICE` permission
2. Filter incoming notifications to the target package names (GPay first; others added in v2)
3. Implement `SmsReceiver extends BroadcastReceiver`, registered for `SMS_RECEIVED`, filtered to known bank sender IDs
4. Both write raw text + timestamp + source into a lightweight "raw capture" queue table, picked up by the parser — keep this step dumb and fast, since notification listener callbacks must return quickly
5. Build the onboarding permission screen that requests Notification Access (via `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, since it can't be requested as a normal runtime permission) and `READ_SMS`/`RECEIVE_SMS`

## 2. Parser & dedup

1. Build `ParserEngine` as a registry of per-source parsers (`GPayParser`, `BankSmsParser`, one class per bank sender ID pattern)
2. Each parser takes raw text → returns a structured `ParsedTransaction` (amount, direction, payee, timestamp) or null if it doesn't match
3. Dedup step: before inserting, check for an existing transaction within a small time window (e.g. ±5 min) with the same amount and direction; if found, treat as the other-source confirmation of the same event, merge rather than duplicate
4. Log unparsed/unmatched raw captures to a review queue so parser gaps are visible instead of silently dropped

## 3. Categorizer

1. Ship a seeded `categories` table with common defaults (Food, Travel, Bills, Shopping, Entertainment, Other)
2. Build a merchant-name → category lookup table, starting with common merchants (Swiggy/Zomato → Food, Uber/Ola → Travel, etc.), extendable over time
3. On capture, run the payee name through the lookup; no match → `category_id = null` ("Uncategorized"), surfaced for the user to correct
4. Every manual correction is logged — this correction log is the foundation for a future ML classifier, not needed for v1

## 4. Historical import

1. **Takeout import**: file picker for `Group_expenses.json` + `My Activity History.html` → parse per `05_android_app_design.md` §3 → write into `transactions`/`split_*` tables → run through the same dedup as live capture
2. **SMS backfill**: on first `READ_SMS` grant, query `content://sms` for existing history, run every message through `BankSmsParser`
3. **Bank statement upload (optional)**: file picker (PDF/CSV) → if PDF is password-protected, prompt for the password → extract table rows with pdfplumber (text-based PDFs) → map `Date | Narration | Debit | Credit | Balance` columns (format varies slightly per bank, so build one generic parser plus per-bank column-mapping overrides)
4. All three import paths run after onboarding permissions are granted, and are explicitly optional/skippable

## 5. Transactions, filters, manual entry

1. `TransactionDao` with query methods covering every filter combination from the requirements doc (bank, source app, direction, payee, date range, amount range)
2. Manual entry form → writes directly to Room with `source_type = manual`
3. Transaction detail screen allows editing category, note, and correcting mis-parsed fields

## 6. Budgets & alerts

1. `BudgetEntity` per category with limit + period; a background check runs on every new transaction write
2. When a category's running total for its period crosses the limit, trigger a local notification immediately (works offline) and sync the budget-alert event to the backend
3. Server-side (FastAPI) runs the same check on every synced/imported transaction and calls Firebase Cloud Messaging, covering transactions that arrived via sync rather than local capture

## 7. Splits

1. Manual flow: "Split this" action on any transaction → create/select group → add members → enter shares → writes to `split_groups`/`split_members`/`split_shares`
2. GPay split auto-detection: extend the notification parser with a `GPaySplitParser` recognizing GPay's distinct split-bill notification pattern (format to be confirmed against real notifications during build) → auto-creates group/members/shares
3. Settlement auto-matching: when a new credit transaction's payee name + amount closely match a pending `split_share`, auto-mark settled if the match is exact, otherwise flag for one-tap confirmation
4. Takeout import populates historical splits per `05_android_app_design.md` §3
5. Splits screen: list grouped by `group_name`, filterable by pending/settled/group, totals at top

## 8. Reports (PDF)

1. FastAPI endpoint `POST /reports/pdf` takes a date range, queries transactions for that user/range, and builds a table-based PDF with ReportLab (date, payee, category, amount, direction)
2. Summary section at the top: total spent, total received, net, top categories
3. Android side: date-range picker → calls the endpoint → streams the PDF → saves locally and offers a share sheet

## 9. Auth

1. FastAPI: register/login issue a JWT access token (\~15 min) + refresh token (\~30 days); passwords hashed with bcrypt via passlib, tokens signed with python-jose
2. Android: tokens stored in `EncryptedSharedPreferences`; Retrofit interceptor attaches the access token as a Bearer header and auto-refreshes on a 401

## 10. Sync

1. `SyncWorker` (WorkManager, periodic + triggered on reconnect) collects local rows changed since the last successful sync
2. Calls `POST /sync`; merges returned server changes back into Room; updates the local `sync_log` timestamp only after a fully successful round-trip
3. Conflict rule: last-write-wins by `updated_at`, acceptable for a single-user app

## Suggested build order

Capture layer → parser & dedup (local, testable without a backend) → local Room storage & transaction list UI → categorizer → auth + FastAPI + sync → historical import (all three sources) → budgets & alerts → splits → PDF reports. This gets something testable on your own phone early, before the full backend exists.
