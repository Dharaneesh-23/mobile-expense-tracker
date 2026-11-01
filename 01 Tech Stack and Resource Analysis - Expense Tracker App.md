# Expense Tracker App — Tech Stack & Resource Analysis

Every resource below was compared against its real competitors before being locked in. The hard constraint across all of them: genuinely free, no card-gated trial, no paid tier required for this app to function.

## 1. Backend hosting

| Option | Free tier reality | Verdict |
| --- | --- | --- |
| Oracle Cloud "Always Free" | Card required for account verification; self-managed VM infrastructure | Rejected — card requirement prevents setup |
| **Render** | Free web service, GitHub auto-deploy, managed HTTPS; 750 free instance hours/month shared across the workspace; sleeps after 15 minutes idle, with roughly one-minute cold starts | **Chosen** — avoids VM administration; accept sleep/cold starts and free-tier quotas |
| Railway | Free trial credit only now; requires card for continued use | Rejected — not genuinely free anymore |
| Fly.io | No longer offers a free tier for new signups | Rejected |
| Google Cloud Run | Usage-based service with a free quota; scales to zero | Not chosen — more billing and deployment setup than the selected Render path |

## 2. Database

| Option | Free tier | Verdict |
| --- | --- | --- |
| PostgreSQL, self-installed on the Oracle VM | Database software is free, but requires the VM and ongoing administration | Rejected — Oracle signup is blocked by the card requirement |
| **Supabase** | 500 MB managed Postgres per free project; two active free projects; inactivity pausing after one week; no automatic backups on Free | **Chosen** — managed PostgreSQL without running a database server; use only the database, retaining FastAPI JWT auth |
| Neon | Managed PostgreSQL with a free plan and scale-to-zero | Alternative — not selected; Supabase is the chosen managed database |

## 3. Authentication

| Option | Trade-off | Verdict |
| --- | --- | --- |
| **Self-rolled JWT (FastAPI + passlib + python-jose)** | Full control, no third-party dependency, more code to get right (password hashing, refresh tokens, rate limiting) | **Chosen** — consistent with the full-control hosting decision; user has prior auth-module build experience |
| Firebase Auth | Free up to 50k MAU, handles edge cases out of the box | Rejected — reintroduces a third-party dependency the full-control choice was meant to avoid |
| Supabase Auth | Bundled with Supabase DB | Not selected — Supabase is used for PostgreSQL only; retain self-rolled JWT |

## 4. PDF report generation

| Option | Trade-off | Verdict |
| --- | --- | --- |
| **ReportLab** | Free, open-source, precise low-level control over tabular layouts (ideal for transaction tables) | **Chosen** |
| WeasyPrint | Free, but renders from HTML/CSS — heavier dependency footprint for a simple tabular report | Rejected — more weight than this use case needs |

## 5. Push notifications (budget alerts)

**Firebase Cloud Messaging** — free with no meaningful cap for a personal app. No practical self-hosted equivalent exists for Android push delivery, so this is used without a real competing alternative.

## 6. Historical data import

| Source | Mechanism | Verdict |
| --- | --- | --- |
| GPay splits & activity | Google Takeout export ("Group expenses" category), structured file, parsed once at onboarding | **Chosen** — confirmed available via Takeout, free, structured |
| Bank transactions (primary) | Device SMS history, read via the same `READ_SMS` permission already needed for live capture — no extra step | **Chosen** — zero extra effort, uses a permission already being requested |
| Bank transactions (optional, more complete) | User-uploaded bank statement (PDF/CSV from net banking), parsed with free libraries (pdfplumber/PyPDF2 for text PDFs, pikepdf for password-protected ones) | **Chosen as optional** — more authoritative than SMS, handles password-protected statements |
| Other UPI apps (PhonePe, Paytm, Slice, SuperMoney) | No Takeout-equivalent; each app's own statement export, format unknown | Deferred to v2 — investigate per-app when built |

## 7. On-device OCR (for any screenshot-based fallback import)

**Google ML Kit Text Recognition** — free, runs fully on-device, no cloud API cost, no per-call billing. Chosen over any cloud OCR API (Azure/Google Cloud Vision) specifically because those have usage-based costs that would violate the no-paid-resources constraint at any real scale.

## 8. Android app stack (no paid alternative exists for any of these — all are free, standard Android SDK/Jetpack components)

- `NotificationListenerService` — notification capture
- `BroadcastReceiver` — SMS capture
- Room — local on-device database
- Retrofit — HTTP client for the FastAPI backend
- WorkManager — reliable background sync, survives app kill/reboot

## 9. CI/CD & version control

**GitHub (free) → Render auto-deploy on push.** Each service tracks its configured environment branch (`dev`, `QA`, or `prod`). No SSH deployment workflow or GitHub Actions deployment job is required; Actions can be added separately for tests. Promote reviewed changes through `dev` → `QA` → `prod`.

## 10. Summary table

| Layer | Choice |
| --- | --- |
| Mobile | Native Android, Java |
| Backend | FastAPI (Python) |
| Hosting | Render (free web service) |
| Database | Supabase (managed PostgreSQL, free tier) |
| Auth | Self-rolled JWT |
| PDF | ReportLab |
| Push | Firebase Cloud Messaging |
| Historical import | Google Takeout (GPay), SMS history (bank), optional statement upload (bank) |
| OCR fallback | Google ML Kit (on-device) |
| CI/CD | GitHub → Render auto-deploy on push |

Current service limits and setup details: [Render Free](https://render.com/docs/free), [Supabase pricing](https://supabase.com/pricing), and [Supabase backups](https://supabase.com/docs/guides/platform/backups). Free hosting does not guarantee continuous availability.
