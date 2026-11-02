# Render QA deployment handoff

The Free QA web service is deployed at **https://expense-tracker-qa.onrender.com**. Its health endpoint reports `environment=qa` and `storage=relational`. It uses the restricted QA database role, the QA JWT secret, and a separate repository read-only token for private APK delivery. The dev/qa schemas remain separate; there is no production financial schema.

## Current service (verified 2026-11-02)

- Source: `Dharaneesh-23/mobile-expense-tracker`, branch `QA`, root `backend`.
- Compute: Free, Singapore. Build: `pip install -r requirements.lock.txt`.
- Start: `uvicorn app.main:app --host 0.0.0.0 --port $PORT`; health check: `/health`; auto-deploy enabled.
- Public registration stays disabled. Create a personal login locally using `backend/.venv/Scripts/python.exe backend/scripts/create_update_account.py`; it prompts for the password without displaying it. No temporary public registration is needed.
- In each app's **App updates** settings, use the public base URL above and that login. Prod uses this service for update delivery only; its financial sync remains offline.
- Online QA authentication, private metadata, and the full APK stream passed with a disposable account. File size and SHA-256 matched, and the downloaded APK's certificate matched the installed apps. The disposable account and its sessions were removed.

## Recreating the service if needed

1. Promote the tested `dev` branch into `QA` before deploying. The original QA baseline has no backend code; do not create a service from that baseline.
2. Sign into Render, connect GitHub access to `Dharaneesh-23/mobile-expense-tracker`, and choose **New → Blueprint**.
3. Select this repository, branch **QA**, and Blueprint Path **deploy/render.yaml**.
4. Review the proposed single Python web service `expense-tracker-qa` and confirm **Free**. Do not add a Render database; Supabase is already provisioned.
5. Supply `DATABASE_URL` from your ignored `backend/.env.qa` file. This must use `expense_qa_app.PROJECT_REF`, not `postgres` and not the dev role. Never upload `.env.migration` or administrator credentials.
6. The Blueprint sets `APP_ENV=qa`, `DB_SCHEMA=qa`, generates a separate JWT secret, uses root directory `backend`, installs `requirements.lock.txt`, and starts `uvicorn app.main:app --host 0.0.0.0 --port $PORT`. Auto-deploy tracks pushes to QA.
7. Deploy, wait for the health check, then visit the service's `/health` URL. Expect `status=ok`, `environment=qa`, and `storage=relational`.
8. Keep `ALLOW_REGISTRATION=false`. Create your personal account using the local helper described above.
9. Enter the service's HTTPS base URL in the QA APK. Grant capture permissions manually only after reviewing the consent screen. Add a synthetic transaction, sync, and verify its presence in `qa.transactions` and `qa.records` before importing actual messages.

Render Free sleeps after idle periods; allow the first request time to wake the backend. Android queues failed sync for retry. Do not put schema migrations into startup or build commands; migrations are already applied and future ones run from the trusted local migration process.

For future backend releases, run `backend/scripts/migrate_environments.py` from the trusted local machine, dry-run `backend/scripts/backfill_relational.py`, review its result, and use `--apply` if needed. These operations use ignored local credentials and never require storing the administrator connection on Render.

Run `backend/.venv/Scripts/python.exe backend/scripts/check_online_updates.py --url https://expense-tracker-qa.onrender.com` to verify both published APK channels using a disposable QA account with exact-ID cleanup. Do not send passwords, JWT secrets, or database connection URLs in chat.

Official references: [Render Blueprints](https://render.com/docs/infrastructure-as-code), [Blueprint fields](https://render.com/docs/blueprint-spec), [Free service behavior](https://render.com/docs/free).
