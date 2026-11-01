# One Supabase project, two isolated schemas

Project: `uespfuwurianxwopguwa`. Use only `dev` and `qa` schemas while building and testing this personal app. No production schema is provisioned. Both schemas share the project's storage quota and availability; isolation is by schema privileges plus separate runtime login roles, not separate servers.

Initial setup has now been applied and verified. `backend/.env` uses the restricted dev connection, `.env.dev` and `.env.qa` hold the two local runtime configurations, and `.env.migration` preserves the original administrator connection separately. All four files are ignored. Do not rerun the one-time bootstrap or overwrite these with administrator credentials. The steps below document the setup for reference.

## Setup in Supabase

1. Open SQL Editor and execute `backend/sql/bootstrap_schemas.sql` as the administrator. Review the script first; it creates schemas and roles without passwords and grants no access to `anon` or `authenticated`.
2. Set different strong passwords on `expense_dev_app` and `expense_qa_app` using your private SQL Editor session or a trusted database administration tool. Do not save password-bearing SQL in the repository or send passwords to chat.
3. Keep `dev` and `qa` out of the exposed schemas in Supabase's Data API settings. Supabase Auth and client Data APIs are not used by Android.
4. In Connect → Session pooler, copy the actual hostname. Custom-role usernames are `expense_dev_app.uespfuwurianxwopguwa` and `expense_qa_app.uespfuwurianxwopguwa`.

## Local configuration

Copy `backend/.env.example` to ignored `backend/.env`. Set the session-pooler URL, using URL-encoded passwords and TLS. Use matching environment/schema values:

```dotenv
APP_ENV=dev
DB_SCHEMA=dev
DATABASE_URL=postgresql+psycopg://expense_dev_app.uespfuwurianxwopguwa:ENCODED_PASSWORD@ACTUAL_POOLER_HOST:5432/postgres?sslmode=require
JWT_SECRET_KEY=YOUR_RANDOM_SECRET_AT_LEAST_32_CHARACTERS
ALLOW_REGISTRATION=true
```

For QA use `qa` for both variables, the QA role, and a different JWT secret. You can save separate ignored `.env.dev` and `.env.qa` files and copy the selected file to `.env`. Do not mix their credentials. Production is rejected by configuration validation until explicitly enabled later.

Install the locked backend dependencies, then run migrations **once per environment** from the `backend` directory. Set `MIGRATION_DATABASE_URL` only in the migration terminal to your administrator connection, using the Connect dialog's actual host and username (`postgres.PROJECT_REF` for the pooler):

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
.\.venv\Scripts\python.exe -m alembic upgrade head
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Remove the administrator connection variable before starting the service. Keep runtime roles restricted to their own schema. Render QA uses only `expense_qa_app`, `APP_ENV=qa`, `DB_SCHEMA=qa`; do not store migration administrator credentials in the web service.

After the one personal account is registered, set `ALLOW_REGISTRATION=false`. Never put Supabase database credentials, service-role keys, or JWT signing secrets into Android build configuration.

## Verify permissions

Using each runtime role, verify that reads work on its own `users` and `records` tables and fail with permission denied on the other schema. Run the backend's API tests separately; SQLite-based unit tests do not prove PostgreSQL role isolation. A live check requires your local `.env` configuration and completed migrations.

This document supersedes earlier suggestions to create separate QA/prod Supabase projects during development. Git branches remain `dev`, `QA`, `prod`; Android flavors remain `dev`, `qa`, `prod`.
