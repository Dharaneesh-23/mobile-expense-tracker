# Expense Tracker App — Requirements

## 1. Overview

A personal expense-tracking Android app that automatically captures UPI and bank transactions from the apps and SMS the user already uses, then gives filtering, auto-categorization, budgeting, and reporting on top of that data.

## 2. Platform

- Native Android, built in Java
- No iOS support in v1 (Android notification/SMS access has no iOS equivalent due to sandboxing)

## 3. Data capture

- **Primary**: Android `NotificationListenerService`, parsing transaction notifications posted by payment apps
- **Fallback/backstop**: SMS `BroadcastReceiver`, parsing bank transaction alert texts
- Deduplication logic where both sources report the same transaction (e.g. a UPI app notification and a bank SMS for the same payment)
- **v1 scope**: GPay notifications + bank SMS only
- **v2+ (deferred)**: PhonePe, Paytm, Slice, SuperMoney

## 4. Core features

- Automatic transaction capture (amount, merchant/payee, timestamp, source app/bank, credit or debit)
- Manual transaction entry (for cash or anything auto-capture misses)
- Editing/correcting a wrongly captured or parsed transaction
- Auto-categorization via merchant-name lookup, with manual correction always available; corrections should be able to improve future categorization
- Budget limits per category, with alerts when nearing/exceeding a limit
- Filters: by bank, by source app, credit vs debit, by payee, by date range, by amount range
- Search by merchant name or note
- Shared-expense / split tracking: mark a captured transaction as a group expense, record who else was involved and their share, and track what's settled vs outstanding; a dedicated Splits screen lists every pending split by group name, person, and amount owed
- Downloadable reports in PDF format, for a month or a custom date range

## 5. Data storage & sync

- Cloud-based storage for multi-device access
- Local-first Room database cache on-device for offline use
- Background sync between local cache and cloud backend via WorkManager

## 6. Hard constraints

- **Zero paid services or resources anywhere in the stack** — every tool, library, and hosting choice must be genuinely free
- Every resource choice must be researched and compared against competing alternatives before being locked in, with the trade-offs made explicit
- The app will be built entirely by an AI coding agent (Codex), so downstream planning documents need explicit file/folder scaffolding, not just prose descriptions

## 7. Non-functional requirements

- Security/privacy: encryption at rest and in transit, proper authentication (not custom-rolled crypto), per-user data isolation in the cloud database
- Play Store policy compliance: notification access and SMS read permissions require a declared, approved use-case justification at release time
- Reliability: the app must remain useful (view past transactions, add manual entries) even when the backend is temporarily unreachable

## 8. Out of scope for v1

- iOS support
- Non-GPay/non-SMS transaction sources (Paytm, PhonePe, Slice, SuperMoney) — planned for a later version

## 9. Locked technical decisions

| Decision | Choice |
| --- | --- |
| Mobile platform | Native Android, Java |
| Backend framework | FastAPI (Python) |
| Hosting | Render (free web service) |
| Database | Supabase (managed PostgreSQL, free tier) |
| Auth | Self-rolled JWT (passlib + python-jose) |
| PDF reports | ReportLab |
| Push alerts | Firebase Cloud Messaging |
| CI/CD | GitHub → Render auto-deploy on push to the configured environment branch |
