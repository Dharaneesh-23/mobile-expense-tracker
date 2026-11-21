> Dev parser improvement (2026-11-21): Added supported-bank sender aliases, transaction keyword filtering and amount-to-completion-verb matching. Normalizes currency spacing/Unicode and validates grouped/decimal amounts. Balance/credit-limit figures are excluded; conflicting transaction amounts stay in Review. OTPs, promotions, due reminders and failed/pending events no longer create new review entries. Existing review/financial records are not changed or removed. Scanner reports new review counts. Tests use synthetic data only; no phone access or installation.

> Dev manual-scan restoration (2026-11-21): Scan SMS History is restored under Import History with 30/90/180-day and all-available ranges. It asks for READ_SMS only, explains inbox access, and requires a separate scan confirmation. Permission grant alone does not start a scan. Automatic SMS/GPay capture remains removed: no RECEIVE_SMS, SmsReceiver or NotificationCaptureService in the dev APK. The pipeline checks this distinction before publication. READ_SMS can still trigger the Play Protect installation restriction. No phone data was accessed or APK installed for this change.

## Dev capture removal — 2026-11-21

Dev now excludes READ_SMS, RECEIVE_SMS, SmsReceiver and NotificationCaptureService through its manifest overlay. Capture consent, notification-listener setup and inbox scanning controls were removed from dev. Existing SMS/GPay source filters remain for previously stored records. Manual paste, file/Takeout imports, budgets, reports and optional sync remain available.

The dev pipeline builds/tests/lints dev, restores the existing signing key from ANDROID_DEBUG_KEYSTORE_BASE64, verifies package/signature and absence of capture declarations, then publishes a versioned APK and checksum metadata as a GitHub prerelease. Its push trigger is limited to dev; QA/prod workflows are unchanged. The user installs from the release link. No device data was read, and no APK was installed on a phone for this change.

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
- Authenticated private GitHub release metadata/APK proxy endpoints and a channel-specific GitHub Actions publishing workflow, with the signing secret and read-only token configured. The Free Render QA service is live.

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
- Render QA is live at `https://expense-tracker-qa.onrender.com`. Online QA authentication and private APK metadata/download passed; size, SHA-256, and the installed signing certificate matched. A disposable test account was removed afterward. A personal login must still be entered in each app's update settings, followed by the Android installation confirmation.
- Private updates away from the PC require the online update backend, a personal update login, the read-only repository credential, a configured signing secret, and published channel releases. See `PRIVATE_APP_UPDATES.md`. Prod data sync stays offline while only dev/qa database schemas are provisioned.
- Firebase server/device registration and FCM push delivery are not configured. Current budget alerts are on-device only.
- Dev version 102 adds Google Takeout group-expense JSON import with explicit owner mapping, paged preview, user confirmation, settled/pending separation, stable duplicate IDs and atomic writes. The creator-as-repayment-requester assumption is disclosed in preview. QA/prod retain the earlier unsupported-format guard. See `TAKEOUT_SPLIT_IMPORT.md`. PDF/password-protected bank statement import and bank-specific CSV mappings remain unimplemented.
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

### Dev 103 device feedback update (2026-11-03)

Installed 0.4.2-dev-fixes in place on the connected SM-S741B. Optimized supported SMS history scans, added progress and malformed-message handling, immediate Back navigation, custom Other category names, multiline button labels and paginated ledger/review rendering. Build/lint, 15 unit tests and 15 isolated device tests passed. Latest backed-up financial records were byte-for-byte unchanged after installation and tests. Real 90-day SMS import was deliberately left for user-triggered testing; retained logs did not establish a specific Java crash cause.
