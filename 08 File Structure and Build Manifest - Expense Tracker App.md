# Expense Tracker App — File Structure & Build Manifest

The direct file-by-file build instructions for Codex. Every file maps to a feature/plan from `07_feature_implementation_plans.md`.

## 1. Android project

```
app/src/main/java/com/dharaneesh/expensetracker/
├─ capture/
│  ├─ NotificationCaptureService.java   # NotificationListenerService, captures payment-app notifications
│  └─ SmsReceiver.java                  # BroadcastReceiver, captures bank SMS
├─ parser/
│  ├─ ParserEngine.java                 # dispatches raw text to the right per-source parser
│  ├─ ParsedTransaction.java            # structured result of a parse
│  ├─ DedupEngine.java                  # merges same-event captures from two sources
│  └─ parsers/
│     ├─ GPayParser.java                # GPay notification format
│     ├─ GPaySplitParser.java           # GPay's distinct split-bill notification format
│     └─ BankSmsParser.java             # bank SMS alert formats
├─ categorize/
│  ├─ CategorizerEngine.java            # runs payee through the merchant lookup
│  └─ MerchantCategoryMap.java          # merchant name → category seed data
├─ importing/
│  ├─ TakeoutImportParser.java          # Group_expenses.json + My Activity History.html
│  ├─ SmsBackfillImporter.java          # reads existing content://sms history
│  └─ BankStatementImporter.java        # optional PDF/CSV statement, password-unlock handling
├─ data/local/
│  ├─ AppDatabase.java                  # Room database
│  ├─ TransactionEntity.java / TransactionDao.java
│  ├─ CategoryEntity.java / CategoryDao.java
│  ├─ BudgetEntity.java / BudgetDao.java
│  ├─ BankAccountEntity.java / BankAccountDao.java
│  ├─ SplitGroupEntity.java / SplitMemberEntity.java / SplitShareEntity.java (+ Daos)
│  └─ SyncLogEntity.java / SyncLogDao.java
├─ data/remote/
│  ├─ ApiService.java                   # Retrofit interface, matches 04_api_design.md
│  ├─ AuthInterceptor.java              # attaches Bearer token, handles 401 refresh
│  └─ dto/                              # request/response DTOs, one per endpoint group
├─ data/repository/
│  ├─ TransactionRepository.java
│  ├─ BudgetRepository.java
│  ├─ SplitRepository.java
│  └─ AuthRepository.java
├─ sync/
│  └─ SyncWorker.java                   # WorkManager, periodic + on-reconnect sync
├─ ui/
│  ├─ onboarding/  OnboardingActivity.java, PermissionsFragment.java, ImportFragment.java
│  ├─ auth/        LoginActivity.java, SignupActivity.java
│  ├─ dashboard/   DashboardFragment.java
│  ├─ transactions/TransactionListFragment.java, TransactionFilterBar.java, TransactionDetailActivity.java, ManualEntryActivity.java
│  ├─ budgets/     BudgetListFragment.java, BudgetEditActivity.java
│  ├─ splits/      SplitListFragment.java, SplitDetailActivity.java, CreateSplitActivity.java
│  ├─ reports/     ReportsFragment.java
│  └─ settings/    SettingsFragment.java
└─ util/
   ├─ CurrencyUtils.java                 # ₹-string parsing (used by parsers and importers alike)
   └─ DateUtils.java                     # ISO 8601 UTC + "IST"-label parsing

AndroidManifest.xml      # declares the NotificationListenerService, SMS permissions/receiver
res/layout/*.xml         # one layout per screen listed under ui/
res/values/strings.xml, colors.xml, themes.xml
build.gradle (project + app), settings.gradle
```

## 2. Backend project (FastAPI)

```
app/
├─ main.py                     # app entrypoint, includes the v1 router
├─ core/
│  ├─ config.py                # env/settings (DB URL, JWT secret, FCM credentials)
│  └─ security.py              # JWT issue/verify, bcrypt password hashing
├─ models/                     # SQLAlchemy, one file per table in 03_database_schema.md
│  ├─ user.py, bank_account.py, category.py, transaction.py
│  └─ budget.py, split_group.py, split_member.py, split_share.py, sync_log.py
├─ schemas/                    # Pydantic request/response models, mirrors models/
├─ api/v1/
│  ├─ router.py                # aggregates every endpoint router
│  └─ endpoints/
│     ├─ auth.py, bank_accounts.py, categories.py, transactions.py
│     └─ budgets.py, splits.py, reports.py, sync.py
├─ services/
│  ├─ categorizer.py           # server-side mirror of the merchant-lookup categorizer
│  ├─ dedup.py                 # server-side dedup for sync/import paths
│  ├─ pdf_report.py            # ReportLab report generation
│  ├─ takeout_import.py        # Group_expenses.json + My Activity History.html parsing
│  ├─ statement_import.py      # bank statement PDF/CSV parsing (pdfplumber/pikepdf)
│  └─ push_notifications.py    # Firebase Cloud Messaging calls for budget alerts
└─ db/
   ├─ session.py
   └─ base.py

alembic/                       # DB migrations, env.py configured against core/config.py
requirements.txt
.env.example                   # documents required env vars, real .env never committed
deploy/
├─ nginx.conf
└─ expense-backend.service       # systemd unit, matches 06_server_deployment_guide.md
.github/workflows/deploy.yml    # SSH deploy on push to main
```

## 3. Build sequence for Codex

Follow the order from `07_feature_implementation_plans.md` §"Suggested build order": capture layer → parser & dedup → local Room storage & transaction list UI → categorizer → auth + FastAPI + sync → historical import → budgets & alerts → splits → PDF reports. Each stage should be independently runnable/testable before the next begins — the local-first design means the Android app is usable well before the backend exists.
