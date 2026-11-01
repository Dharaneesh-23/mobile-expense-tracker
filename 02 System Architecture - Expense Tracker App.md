# Expense Tracker App — System Architecture

One Android app (Java) talks to a FastAPI backend hosted as a Render free web service. FastAPI connects over TLS to managed PostgreSQL on Supabase's free tier. Authentication remains self-rolled JWT in FastAPI; Supabase Auth is not used.

## 1. Components

| Component | Responsibility | Lives in |
| --- | --- | --- |
| Capture layer | `NotificationListenerService` + SMS `BroadcastReceiver`; catches raw payment notifications and bank SMS as they arrive | Android app |
| Parser & dedup | Per-source regex parsers normalize raw text into structured transactions; merges duplicates reported by more than one source | Android app |
| Categorizer | Merchant-name lookup assigns a category; falls back to "Uncategorized" for user correction | Android app (local-first), mirrored server-side |
| Local cache | Room database; the single source of truth the UI reads from, works fully offline | Android app |
| Sync worker | WorkManager job; pushes local changes to the backend and pulls remote changes on a schedule and on reconnect | Android app |
| API layer | Auth, CRUD for transactions/categories/budgets/splits, PDF report generation, sync endpoint | FastAPI, Render free web service |
| Database | All persisted data: users, transactions, categories, budgets, split groups/members/shares | Supabase managed PostgreSQL, free tier |
| Reverse proxy / TLS | Public HTTPS endpoint and managed certificates | Render-managed ingress |
| Process supervisor | Starts the application and manages its process lifecycle; free services sleep when idle | Render platform |

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

Android → Render-managed HTTPS → FastAPI/Uvicorn → TLS connection to Supabase PostgreSQL. Uvicorn binds to `0.0.0.0` and Render's `$PORT`. No self-managed nginx, Certbot, systemd, or SSH deployment is needed.

Render auto-deploys on pushes to the branch configured for each service. Keep the Git branches `dev`, `QA`, and `prod` and promote changes in that order. Database credentials, JWT secrets, and Firebase credentials are scoped to each environment and remain server-side. Android never connects directly with database credentials.

During personal-use development, one Supabase project contains separate `dev` and `qa` schemas with independent runtime roles and JWT secrets. Production schema access is disabled until development and testing are complete. Render's free instance hours are shared within a workspace; run dev locally and provision hosted QA only as needed within quotas. Supabase is used only as managed PostgreSQL. See `docs/SUPABASE_DEV_QA.md` for provisioning and verification.

Render sleeps after 15 minutes idle and may take about a minute to wake. Its filesystem is ephemeral: stream reports and discard temporary uploads, storing durable application data in PostgreSQL. Supabase Free may pause after inactivity and requires manual off-site backups.

## 5. Why local-first

The app must stay useful — viewing past transactions, adding manual entries — while Render is sleeping, waking, deploying, or temporarily unreachable, or Supabase is paused. Room is the UI's source of truth; queue sync for retry with backoff. Server-side alerts and reports depend on backend availability; local budget alerts work offline.
