# Expense Tracker App — System Architecture

One Android app (Java) talks to one FastAPI backend running on a single self-managed Oracle Cloud VM, which also hosts the Postgres database — no separate managed services.

## 1. Components

| Component | Responsibility | Lives in |
| --- | --- | --- |
| Capture layer | `NotificationListenerService` + SMS `BroadcastReceiver`; catches raw payment notifications and bank SMS as they arrive | Android app |
| Parser & dedup | Per-source regex parsers normalize raw text into structured transactions; merges duplicates reported by more than one source | Android app |
| Categorizer | Merchant-name lookup assigns a category; falls back to "Uncategorized" for user correction | Android app (local-first), mirrored server-side |
| Local cache | Room database; the single source of truth the UI reads from, works fully offline | Android app |
| Sync worker | WorkManager job; pushes local changes to the backend and pulls remote changes on a schedule and on reconnect | Android app |
| API layer | Auth, CRUD for transactions/categories/budgets/splits, PDF report generation, sync endpoint | FastAPI, on the VM |
| Database | All persisted data: users, transactions, categories, budgets, split groups/members/shares | PostgreSQL, same VM |
| Reverse proxy / SSL | Routes HTTPS traffic to FastAPI, terminates Let's Encrypt certificates | nginx, same VM |
| Process supervisor | Keeps FastAPI running, restarts on crash or reboot | systemd, same VM |

## 2. Data flow, end to end

1. A payment app posts a notification, or the carrier delivers a bank SMS
2. The capture layer intercepts it the instant it arrives
3. The parser extracts amount, payee, timestamp, and source; the dedup step checks whether this transaction was already captured from the other source (e.g. GPay notification and bank SMS for the same payment) and merges if so
4. The categorizer tags it (merchant lookup), writes it to the local Room cache — the transaction is visible in the UI immediately, with no network dependency
5. The sync worker pushes the new/changed record to the FastAPI API on its next run (immediately if online, queued if not)
6. FastAPI validates, writes to PostgreSQL, and returns any server-side changes (e.g. from another device) for the app to merge back into Room
7. Budget checks run server-side on each new transaction; if a category limit is crossed, FastAPI triggers a Firebase Cloud Messaging push to the device

## 3. Historical import flow (onboarding only, one-time)

- **GPay data**: user uploads their Google Takeout export ("Group expenses" + "Google transactions"); a one-time import routine parses it and writes historical transactions/splits directly to the local cache, then syncs up like any other data
- **Bank data (automatic)**: on first grant of the SMS permission, the app reads existing SMS history already on the device — no extra step
- **Bank data (optional, more complete)**: user uploads a bank statement (PDF/CSV); parsed locally or server-side and merged the same way, with dedup against anything SMS already produced

## 4. Deployment topology

Single Oracle Cloud Always Free VM: `nginx` (port 443, SSL via Certbot) → `systemd`-managed FastAPI process (Uvicorn) → PostgreSQL (local socket or localhost connection, not exposed externally). GitHub Actions builds and deploys over SSH on every push to `main`.

## 5. Why local-first

The app must stay useful — viewing past transactions, adding manual entries — even when the VM is briefly unreachable (restart, deploy, network blip). Room is the UI's source of truth; the backend is for sync, cross-device access, server-side budget logic, and PDF generation, not a hard dependency for basic use.
