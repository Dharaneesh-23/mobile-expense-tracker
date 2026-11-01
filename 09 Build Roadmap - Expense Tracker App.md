# Expense Tracker App — Build Roadmap

Nine phases, each independently testable before the next begins. No calendar dates — paced by completion, not a deadline.

## Phase 0 — Environment setup

- Provision the Oracle Cloud VM, run through `06_server_deployment_guide.md` end to end with a "Hello World" FastAPI route
- Create the Android project skeleton with the package structure from `08_file_structure_and_build_manifest.md`
- GitHub repo + Actions deploy pipeline working against the empty FastAPI app
- **Ready when**: a change pushed to `main` auto-deploys and is reachable over HTTPS

## Phase 1 — Capture, parser, dedup (on-device only)

- `NotificationCaptureService`, `SmsReceiver`, `GPayParser`, `BankSmsParser`, `DedupEngine`
- No backend or UI dependency yet — log parsed results to confirm accuracy
- **Ready when**: a real GPay payment and a real bank SMS both produce one correct, deduplicated parsed transaction

## Phase 2 — Local storage & core UI

- Room database, `TransactionDao`, transaction list screen with the full filter bar, transaction detail, manual entry
- **Ready when**: the app is genuinely usable standalone — view, filter, and manually add transactions, fully offline

## Phase 3 — Categorizer

- Seeded categories, merchant lookup, auto-tag on capture, manual correction in the detail screen
- **Ready when**: most real transactions land in a sensible category automatically

## Phase 4 — Auth, backend, sync

- FastAPI auth endpoints, `transactions`/`categories` CRUD endpoints, `SyncWorker`
- **Ready when**: data created on the device shows up after a fresh install + login on a second test device

## Phase 5 — Historical import

- Takeout import (`Group_expenses.json` + `My Activity History.html`), SMS backfill, optional bank statement upload
- **Ready when**: a real Takeout export and real SMS history both import cleanly with correct dedup against anything already captured live

## Phase 6 — Budgets & alerts

- Budget CRUD, local + server-side limit checks, Firebase Cloud Messaging push
- **Ready when**: crossing a budget limit triggers a push notification within the session it happened

## Phase 7 — Splits

- Manual split flow, Splits screen, GPay split auto-detection, settlement auto-matching
- **Ready when**: the Splits screen correctly shows who owes what by group, for both manually created and GPay-auto-detected splits

## Phase 8 — PDF reports

- `POST /reports/pdf`, Reports screen with date-range picker and share sheet
- **Ready when**: a generated report's numbers match the filtered transaction list for the same range

## Phase 9 — Polish & release readiness

- App icon, branding, onboarding copy
- Google Play's Notification Access / SMS permission declaration form, filled out with the app's actual use case
- End-to-end test pass across all phases together
- **Ready when**: a clean install, through onboarding, import, and a week of live use, produces no missed or duplicated transactions

This roadmap, together with the eight documents before it, is the complete build package — ready to hand to Codex phase by phase.
