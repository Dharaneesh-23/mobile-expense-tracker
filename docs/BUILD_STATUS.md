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
- Alembic initial migration, separate dev/qa schema/role bootstrap SQL, fail-closed environment validation, and a Render QA Blueprint.

## Implementation decisions

The first runnable build uses typed JSON financial records inside Room and PostgreSQL rather than the draft's separate relational entity tables. Stable UUIDs, update versions, per-user sync revisions, and deletion tombstones make the same representation usable offline and through sync. Financial payloads on Android are encrypted; indexed metadata contains only IDs, record type, timestamps, and sync state. Review messages remain on-device and are not synced.

The API currently exposes sync as the write path used by Android. The separately named CRUD endpoints and normalized account/category/group tables in the original API/schema documents remain future work; they are not silently treated as implemented. Dates in SMS without a time are stored at IST midnight with `date_inferred=true`.

## Verified setup

- The actual Supabase project has migrated dev/qa schemas with separate runtime roles. Both roles passed checks that they can access their own migrated tables and have no USAGE privilege on the other schema.
- Live PostgreSQL smoke tests passed for both dev and QA authentication, sync, idempotency, user isolation, and PDF generation; synthetic accounts and records were removed afterward.
- Android parser unit tests and backend API tests passed. Device instrumentation on the Samsung SM-E625F passed encrypted-storage, capture deduplication, split handling, and Android-to-local-API connection checks. The dev APK was installed and its overview screen visually checked.
- Local credentials are kept in ignored backend `.env`, `.env.dev`, `.env.qa`, and `.env.migration` files. `.env` uses the restricted dev role; administrator credentials must not be placed in Render or Android.

## Remaining validation and features

- Complete device-to-cloud multi-device synchronization and recovery testing after Render is deployed. Only dev/qa are permitted; no production schema exists.
- Render deployment is not yet active. A GitHub integration link alone does not deploy FastAPI or run migrations.
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
