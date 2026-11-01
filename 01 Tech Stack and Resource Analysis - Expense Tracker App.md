# Expense Tracker App — Tech Stack & Resource Analysis

Every resource below was compared against its real competitors before being locked in. The hard constraint across all of them: genuinely free, no card-gated trial, no paid tier required for this app to function.

## 1. Backend hosting

| Option | Free tier reality | Verdict |
| --- | --- | --- |
| **Oracle Cloud "Always Free"** | Always-on ARM VM (4 OCPU, 24GB RAM, 200GB block storage), free forever, no sleep. Card required for identity verification only, never charged within Always Free limits. Known risk: Oracle has reclaimed instances it detects as idle/underutilized | **Chosen** — full control, genuinely always-on, no vendor BaaS dependency |
| Render | 750 hrs/month, no card, auto-deploy from GitHub, but sleeps after \~15 min idle (30-60s cold start) | Rejected — cold starts conflict with the always-on goal of Option B |
| Railway | Free trial credit only now; requires card for continued use | Rejected — not genuinely free anymore |
| Fly.io | No longer offers a free tier for new signups | Rejected |
| Google Cloud Run | Generous free quota (2M requests/month), scales to zero | Viable alternative, more Docker/gcloud setup than Oracle VM, still not always-on |

## 2. Database

| Option | Free tier | Verdict |
| --- | --- | --- |
| **PostgreSQL, self-installed on the Oracle VM** | Free, capped only by the VM's own 200GB block storage | **Chosen** — consistent with full-control hosting choice, no separate account/service to manage |
| Supabase | 500MB Postgres + bundled Auth/Storage, no card | Rejected as primary — would reintroduce a managed BaaS dependency after choosing full control |
| Neon | 0.5GB, true scale-to-zero, no card | Rejected as primary for the same reason; worth reconsidering only if the VM's Postgres ever becomes a bottleneck |

## 3. Authentication

| Option | Trade-off | Verdict |
| --- | --- | --- |
| **Self-rolled JWT (FastAPI + passlib + python-jose)** | Full control, no third-party dependency, more code to get right (password hashing, refresh tokens, rate limiting) | **Chosen** — consistent with the full-control hosting decision; user has prior auth-module build experience |
| Firebase Auth | Free up to 50k MAU, handles edge cases out of the box | Rejected — reintroduces a third-party dependency the full-control choice was meant to avoid |
| Supabase Auth | Bundled with Supabase DB | Rejected along with Supabase as a whole |

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

**GitHub (free) + GitHub Actions (free minutes, generous for a solo repo) → SSH deploy to the Oracle VM.** No competing option offers a meaningfully better free tier for a single-developer project.

## 10. Summary table

| Layer | Choice |
| --- | --- |
| Mobile | Native Android, Java |
| Backend | FastAPI (Python) |
| Hosting | Oracle Cloud Always Free VM |
| Database | PostgreSQL (self-installed on the VM) |
| Auth | Self-rolled JWT |
| PDF | ReportLab |
| Push | Firebase Cloud Messaging |
| Historical import | Google Takeout (GPay), SMS history (bank), optional statement upload (bank) |
| OCR fallback | Google ML Kit (on-device) |
| CI/CD | GitHub Actions |
