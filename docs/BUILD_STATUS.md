# Personal-use development build

## Implemented

- Native Java Android app with separate dev/qa/prod application IDs and optional local signing configuration.
- Offline dashboard, transaction add/edit/delete, search and bank/source/direction/category/date/amount filters.
- Monthly category budgets and permission-aware local budget notifications, including manual-entry checks.
- Encrypted local merchant/category rules learned from transaction category corrections.
- Manual split obligations and settlement editing; GPay split-request parsing with incomplete group-title flags.
- HDFC and Union Bank credit/debit SMS parsers, source references, account suffixes, IST dates, conservative deduplication, SMS backfill, and an encrypted review queue.
- Explicit capture consent, SMS/notification-access setup, manual message import, normalized CSV/JSON transaction import, and best-effort completed-payment Takeout HTML import.
- Device-generated filtered PDF exports through Android's document picker.
- Room persistence with AES-GCM encrypted financial payloads and Android Keystore keys; backups/transfers disabled.
- FastAPI registration/login, rotating/revocable refresh sessions, rate limiting, per-user sync revisions/tombstones, transaction list filters, and server PDF generation.
- Alembic migrations through `0002`, separate dev/qa schema/role bootstrap SQL, fail-closed environment validation, and a Render QA Blueprint.
- Relational transactions, budgets, bank accounts, categories, split groups/members/shares, and per-device sync logs, updated atomically alongside the retained journal.
- QA/prod in-app update clients with separate encrypted update-service login, environment/version checks, SHA-256 verification, signing-certificate checks, and Android installation confirmation.
- Authenticated private GitHub release metadata/APK proxy endpoints and a channel-specific GitHub Actions publishing workflow. Online activation still needs its signing secret, read-only download token, and Render service.

## Implementation decisions

Android keeps encrypted typed payloads in Room. FastAPI now projects sync writes into the normalized PostgreSQL tables in the same transaction as the retained `records` journal. Transaction listing and server PDF reports read canonical relational rows. Client UUIDs, update versions, per-user revisions, and tombstones remain compatible with the installed APK. Namespaced physical IDs prevent one user's client UUID from colliding with another user's row; `sync_record_id` links the entities to their unchanged client IDs. Review messages remain on-device and are not synced.

The API exposes sync as Android's write path. The separately named CRUD routes in the original API design remain future work; the normalized account/category/group tables are now used by that sync path. Dates in SMS without a time are stored at IST midnight with `date_inferred=true`.

## Verified setup

- The actual Supabase project has migrated dev/qa schemas with separate runtime roles. Both roles passed checks that they can access their own migrated tables and have no USAGE privilege on the other schema.
- Live PostgreSQL smoke tests passed for both dev and QA authentication, sync, idempotency, user isolation, and PDF generation; synthetic accounts and records were removed afterward.
- Android parser unit tests and backend API tests passed. Device instrumentation on the Samsung SM-E625F passed encrypted-storage, capture deduplication, split handling, and Android-to-local-API connection checks. The dev APK was installed and its overview screen visually checked.
- Verification: 7 parser tests per dev/QA variant, 14 backend tests (including fresh/repeat migrations and backfill rollback), 6 original device instrumentation tests, and an additional real phone → FastAPI → Supabase test passed. Both APK lint/signature checks passed for the unchanged Android build. Lint retains non-blocking dependency/localization warnings.
- Both live schemas contain all 13 expected tables and migration `0002`. Transaction/budget/split relationship smoke tests passed in dev and QA. Existing cloud financial journal counts were zero at backfill time; the retained journal was not deleted or rewritten. Synthetic test accounts and related rows were cleaned up.
- Local credentials are kept in ignored backend `.env`, `.env.dev`, `.env.qa`, and `.env.migration` files. `.env` uses the restricted dev role; administrator credentials must not be placed in Render or Android.
- QA and prod previews version code 100 are installed side by side using the existing debug signing key. Each variant passed 12 unit tests (7 parsers + 5 update-policy checks) and lint. Backend coverage now has 19 passing tests, including private update authentication, channel checks, proxy streaming, and redirect handling. The workflow passed actionlint.

## Remaining validation and features

- Complete multi-device synchronization, recovery, and HTTPS Render testing after deployment. The phone-to-Supabase path through a local FastAPI service has passed. Only dev/qa are permitted; no production schema exists.
- Render deployment is not yet active. A GitHub integration link alone does not deploy FastAPI or run migrations.
- Private updates away from the PC require the online update backend, a personal update login, the read-only repository credential, a configured signing secret, and published channel releases. See `PRIVATE_APP_UPDATES.md`. Prod data sync stays offline while only dev/qa database schemas are provisioned.
- Firebase server/device registration and FCM push delivery are not configured. Current budget alerts are on-device only.
- Google Takeout split JSON requires a confirmed ownership/payment mapping; the importer explicitly rejects it rather than guessing debts. PDF/password-protected bank statement import and bank-specific CSV mappings are not implemented; use the documented normalized CSV for now.
- Automatic split-settlement matching, syncing category-learning rules across devices, custom/weekly budgets, device restore/multi-device cloud testing, and extended real-world capture testing remain pending.
- SMS/notification parsers are based on the supplied examples. GPay screenshot text is visibly truncated; the app cannot reconstruct missing group names and flags them for editing.
- No production-readiness or complete end-to-end claim is made by a successful APK build. Test on synthetic transactions first and compare with your actual statements before relying on totals.

## Import CSV format

```csv
date,payee,amount,direction,reference,bank
2026-10-30,Sample merchant,25.00,debit,111111111111,Union Bank of India
```

Amounts use INR with at most two decimal places. Dates are ISO `YYYY-MM-DD` or ISO instants. Optional columns: `reference`, `bank`. Exact reimports use stable IDs; review cross-source overlaps before relying on imported totals.

## Signing and cloud setup

See `docs/SIGNING.md` and `docs/SUPABASE_DEV_QA.md`. Do not send private keys or passwords in chat. Debug builds are usable without custom signing keys. A change of signing key requires reinstalling the application and can delete its local data.
