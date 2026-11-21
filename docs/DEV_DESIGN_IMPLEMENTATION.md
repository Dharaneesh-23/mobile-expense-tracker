> Dev manual-scan restoration (2026-11-21): Scan SMS History is restored under Import History with 30/90/180-day and all-available ranges. It asks for READ_SMS only, explains inbox access, and requires a separate scan confirmation. Permission grant alone does not start a scan. Automatic SMS/GPay capture remains removed: no RECEIVE_SMS, SmsReceiver or NotificationCaptureService in the dev APK. The pipeline checks this distinction before publication. READ_SMS can still trigger the Play Protect installation restriction. No phone data was accessed or APK installed for this change.

> Current dev behavior (2026-11-21): automatic SMS/GPay capture and SMS inbox scans have been removed from the dev manifest and UI. Earlier sections below record the prior implementation. Manual entry, pasted messages, supported file imports, and existing records remain. Dev APKs are published by `.github/workflows/android-dev.yml` as GitHub prereleases using the existing signing identity. The pipeline validates the packaged APK has no sensitive capture declarations before publication. No phone installation or app-data inspection is part of this change. QA/prod capture declarations are unchanged.

# Expense Tracker - dev design implementation

## Scope

The approved visual reference is archived in `design/stitch-reference/`. The native implementation is isolated to the Android `dev` flavor. QA/prod retain their current launcher and UI. No cloud schema, API, hosting configuration, capture parser, or publishing workflow is changed by this work.

Dev package: `com.dharaneesh.expensetracker.dev`.

Initial design build: version code `101`, version name `0.4.0-dev-design`. The follow-up dev build `102` (`0.4.1-dev-takeout`) adds explicit Google Pay group-export owner selection and preview; see `TAKEOUT_SPLIT_IMPORT.md`.

## Native UI

- Cream/sand cards, dark primary buttons, green/red financial values, and bundled Plus Jakarta Sans typography. The font is from Google Fonts with its SIL Open Font License in dev assets.
- Four anchored main tabs: Home, Transactions, Budgets, Shared. Child pages use Back navigation, preserve parent selections, and omit the main tabs.
- Real monthly totals, selected month, budget balance, seven-day spending chart, recent activity, capture state and sync status. No Stitch example financial data is inserted.
- Search/filter page with direction, source, bank, category, date and amount ranges; chronological ledger; transaction detail and separate validated editor with paise and date/time.
- Budget overview, category detail and editor; split overview/filtering, detail/editor, linked split creation and separate full-settlement confirmation. Settlement does not create a second expense or initiate payment.
- Settings directory, opt-in permissions, import history, local review inbox, optional dev cloud connection, privacy, About and dev-distribution explanation.
- On-device PDF export uses the existing report generator and Android document picker. Existing normalized CSV/JSON/Takeout HTML and message import paths are retained. SMS history supports 30/90/180-day/all-available ranges with result counts.
- Form drafts use Keystore-backed encryption on activity recreation. EditText plaintext automatic view-state persistence is disabled. Dirty forms prompt before discard. Newer Android gesture Back uses OnBackInvokedDispatcher with an older-version fallback.

## Existing behavior retained

MainActivity's reusable helpers are now protected so the dev activity can reuse the established repository, imports, reports and capture behavior. Its QA/prod behavior is otherwise unchanged. The dev manifest selects DevMainActivity; the existing dev installation is updated with `adb install -r` so its application ID, signing identity and data are retained.

The app still requires user consent plus Android permissions for automatic capture. It does not turn permissions on automatically. Bank parsing remains limited to supported HDFC/Union formats and GPay notifications. Raw review messages stay local. Category corrections retain the existing merchant-learning behavior.

## Limits

- This is a native interpretation of the approved visual direction, not a pixel-identical WebView. The web prototype, demo records, illustrative versions and success toasts are not embedded.
- No receipt OCR, arbitrary statement PDF/ZIP import, partial settlement, payment initiation, automatic settlement matching or new bank integration is introduced.
- Review offers raw-message reveal, manual correction paths, dismissal and incomplete split correction. It does not fabricate a duplicate-review backlog or claim bank reconciliation.
- Dev update delivery remains Android Studio/USB. QA/prod publishing and their updater are unchanged; dev never downloads an APK from another channel.
- Dev cloud login must use a dev backend. The Render QA service intentionally fails the dev environment check.
- The Play Protect block on browser installation remains a separate issue. No device protection was disabled for this installation.

## Verification

- Dev unit suite: 15 tests (7 parser, 5 update policy, 3 INR amount/format checks).
- Dev assemble and lint pass; existing SDK/dependency/deprecation warnings remain non-blocking.
- Physical-phone UI instrumentation uses a separate in-memory Room repository, preserves draft preferences, and does not write synthetic entries to the installed financial database. It checks tab/child navigation, filters, amount validation, paise saving and settings navigation.
- Five existing encrypted-storage/capture tests passed on the phone using in-memory storage.
- The dev APK is installed as an update, with visual inspection on the connected Samsung phone. QA and prod are not reinstalled.

Build with the installed Android tools and the socket workaround used by `scripts/Build-Android.ps1`. For the versioned build, pass quoted Gradle arguments `-PappVersionCode=101` and `-PappVersionName=0.4.0-dev-design`.


## Dev 103: device feedback fixes (2026-11-03)

- Long SMS history imports stream only supported HDFC/Union messages, reuse a bounded-lifetime repository cache instead of repeatedly decrypting the full ledger, show progress and always release the busy state. Malformed messages are counted separately. Each completed save remains durable if scanning stops.
- Back navigation renders immediately instead of waiting behind the import I/O job. Busy actions explain that work is running rather than silently doing nothing.
- Selecting Other in transaction or budget forms reveals a required category name (1–60 characters). Saved custom categories become selectable from existing records without a schema migration.
- Transactions and review lists render 50 entries at a time with explicit Show More controls; totals still use the entire matching ledger. Buttons allow multiline labels and compact header controls use smaller text and spacing.
- Private device backups were taken before updating. A newer pre-install snapshot retained a budget edit made since the first snapshot. The in-place version 103 installation exactly matched that latest financial-record snapshot, including encrypted payloads, timestamps and tombstones. No database reset, uninstall or automatic real-SMS reimport was used.
- No retained Java crash confirmed the reported 90-day failure. The scan and rendering bottlenecks were addressed; the real 90-day import must be retried by the user because importing it can add records.

Final verification for dev 103: 15 unit tests, lint and assemble passed. On SM-S741B, 6 encrypted-storage/capture tests (including 300 distinct SMS messages scanned twice), 6 Takeout parser tests, and 3 isolated UI tests passed. The UI harness was corrected to reset its route between tests. Financial records matched the latest pre-install snapshot after installation and again after instrumentation. The temporary test package was removed and the real Home screen reopened and visually inspected. QA/prod installations were not updated.
