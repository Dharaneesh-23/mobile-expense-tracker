# Expense Tracker App — Server Deployment Guide

Render hosts FastAPI as a free web service; Supabase hosts managed PostgreSQL on its free tier. Render handles HTTPS and process management. Authentication stays in FastAPI using passlib + python-jose JWTs; Supabase Auth is not used. No Oracle VM, SSH deployment, nginx, Certbot, or systemd is required.

## 1. Environments and free-tier limits

Keep Git branches exactly `dev`, `QA`, and `prod`, promoting reviewed changes in that order. A service auto-deploys only from its configured branch; merging into `prod` is the production deployment gate.

| Environment | Backend | Database |
| --- | --- | --- |
| dev | Local FastAPI initially | `dev` schema in Supabase project `uespfuwurianxwopguwa` |
| QA | Render free web service tracking `QA` | `qa` schema in the same Supabase project |
| prod | Deferred until development/testing complete | No production schema provisioned |

Use separate database roles, JWT secrets, and Firebase configuration. The development decision is one Supabase project with two schemas, sharing the project's 500 MB Free database quota and availability. Render provides 750 free instance hours per workspace per month, shared by services. Do not assume multiple services can run continuously for free. Production is deferred. Do not create paid resources or add billing upgrades to bypass limits.

## 2. Create the Supabase project

1. Sign in to Supabase and create a project in a Free organization. Choose a region near the backend and save a strong database password securely.
2. Run `backend/sql/bootstrap_schemas.sql` to create isolated dev/qa schemas and roles. Follow `docs/SUPABASE_DEV_QA.md`; keep the two schemas out of the exposed Data API schemas. No production data is introduced during testing.
3. In the project's Connect dialog, copy the actual connection details. For an IPv4 backend, use the shared pooler's **Session mode**, port `5432`. The host and username differ from the direct endpoint; do not construct them by guessing. Direct database connections on Free use IPv6.
4. Configure TLS for the PostgreSQL driver, including certificate verification where supported. URL-encode special characters in a password if using a URL.
5. Use a restricted application database role for runtime access and separate migration credentials. Enforce per-user access in FastAPI. Do not expose financial tables through Supabase's Data API: keep them in an unexposed schema, or restrict grants and enable appropriate RLS. This remains necessary even though Android uses only FastAPI.

Supabase Auth, Storage, and Edge Functions are not required for this architecture. Android receives only the FastAPI base URL and application tokens; database credentials and Supabase privileged keys never ship in the APK.

## 3. Prepare the backend repository

The monorepo stores Python code under `backend/`, containing `app/main.py`, `requirements.txt` and a tested dependency lock, Alembic configuration/migrations, and a sanitized `.env.example`. Current implementation and remaining work are tracked in `docs/BUILD_STATUS.md`.

Add a lightweight `/health` route that returns no credentials or financial data. Read configuration through environment settings. Keep a small SQLAlchemy connection pool suitable for the free database and recycle stale connections. Generated PDFs and uploaded statements must use bounded temporary storage or streaming and be discarded after processing.

## 4. Create the Render web service

1. Sign in to Render and connect GitHub, granting access only to `Dharaneesh-23/mobile-expense-tracker`.
2. Select **New → Web Service**, Python runtime, and the **Free** instance type. Create QA first.
3. Select branch `QA` and root directory `backend` for the proposed monorepo layout. Choose a nearby available region.
4. Set the build command to `pip install -r requirements.txt`.
5. Set the start command to `uvicorn app.main:app --host 0.0.0.0 --port $PORT`.
6. Select and pin a supported Python version compatible with the dependency lock when scaffolding the backend. Set the health-check path to `/health`.
7. Configure environment variables before deploying. Enable auto-deploy **On Commit** for the selected branch.

Render provides an `https://...onrender.com` address and managed TLS; a purchased domain is not required. An optional `deploy/render.yaml` Blueprint can describe the service later; select its path explicitly when creating the Blueprint. Store secret references only, never real secrets, in YAML.

## 5. Environment variables and secrets

| Variable | Purpose |
| --- | --- |
| `APP_ENV` | `dev`, `QA`, or `prod` |
| `DATABASE_URL` | Runtime connection URL for this environment's restricted database role |
| `MIGRATION_DATABASE_URL` | Separate privileged connection for controlled migrations, only where required |
| `JWT_SECRET_KEY` | Strong, unique random signing secret per environment |
| `JWT_ALGORITHM` | Application-supported signing algorithm, initially `HS256` |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | Initially `15` |
| `REFRESH_TOKEN_EXPIRE_DAYS` | Initially `30` |
| `FIREBASE_CREDENTIALS_PATH` | Render secret-file path for FCM service credentials, when push is implemented |

These names are the configuration contract to implement in `app/core/config.py`. Render supplies `PORT`. Use Render's environment settings and secret files for hosted secrets and ignored local `.env` files for development. Do not commit secrets to `.env.example`, `render.yaml`, logs, or GitHub. A GitHub PAT is not an application environment variable; authorize Render through its GitHub integration. FCM configuration can wait until push is implemented.

## 6. Migrations

Commit reviewed Alembic migrations alongside code. Run migrations against QA first, then separately against production after a backup and review. Use the migration role; runtime connections should not have schema-owner privileges.

For initial setup and subsequent releases, run `alembic upgrade head` from a trusted local backend environment with the correct migration connection configured. Confirm `APP_ENV` and the destination project before running it. Use direct IPv6 connectivity for migration/backup tools when available; use the documented session-pooler connection if the local network requires IPv4.

Do not rely on Render Free providing a pre-deploy command or interactive shell. Do not put migrations into every Uvicorn startup or the build command: restarts and overlapping deployments can otherwise race or change the database before code is approved. Keep schema changes backward-compatible with the running release.

## 7. Auto-deployment and verification

Render builds and deploys when commits reach its selected branch. QA tracks `QA`; production tracks `prod`. No SSH key or GitHub Actions deploy job is needed. Optional GitHub Actions tests are independent of deployment.

After each deployment, check Render build/runtime logs, `/health`, database connectivity, authentication, per-user isolation, and an Android sync round-trip. Review migrations before promoting `QA` to `prod`. Render code rollback does not undo database migrations; database recovery requires a separate plan.

## 8. Sleep, pausing, and temporary files

Render Free sleeps after 15 minutes without inbound traffic. The first request can take about one minute to wake it. Android should retain local data, show sync status, retry with backoff, and avoid duplicate writes. Server budget alerts are delayed while requests are queued; local notifications continue offline. Do not rely on an in-process scheduler running continuously on Free.

Supabase Free projects may pause after a week of inactivity; resume the project from the dashboard before testing if needed. Monitor database size, egress, and Render shared instance hours/build quotas. No always-on guarantee is part of this plan.

Render's local filesystem is ephemeral and is lost across restarts, redeploys, and sleep. Do not keep durable reports, uploads, SQLite databases, or backups there. Stream reports to Android and retain durable transaction records only in Supabase PostgreSQL.

## 9. Manual backups and restore checks

Supabase Free has no automatic database backups. Export regularly and before production migrations using the Supabase CLI `db dump` workflow or compatible PostgreSQL `pg_dump` tools from a trusted local machine. Back up application schema, data, and required roles/grants, not just migration scripts. Keep database passwords out of command arguments and logs; use a protected password file or secure credential prompt.

Store encrypted exports off-site, outside Git and outside Render's filesystem, with a documented retention policy. The repository's `backups/` path is ignored but is not itself an off-site backup. Test restoration into an isolated disposable local database and verify record counts and ownership/access controls before relying on the backup. Storage objects, if introduced later, require a separate backup process.

## 10. Official references

- [Render FastAPI deployment](https://render.com/docs/deploy-fastapi)
- [Render deployment configuration](https://render.com/docs/deploys)
- [Render Free limits](https://render.com/docs/free)
- [Supabase pricing and free limits](https://supabase.com/pricing)
- [Supabase PostgreSQL connections](https://supabase.com/docs/guides/database/connecting-to-postgres)
- [Supabase backups](https://supabase.com/docs/guides/platform/backups)
