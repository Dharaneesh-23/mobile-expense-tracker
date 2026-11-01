# Render QA deployment handoff

The Supabase QA schema is ready, migration `0002` is applied, and live API tests passed. The Android app's sync protocol is unchanged. A hosted Render service is not yet provisioned.

1. Promote the tested `dev` branch into `QA` before deploying. The original QA baseline has no backend code; do not create a service from that baseline.
2. Sign into Render, connect GitHub access to `Dharaneesh-23/mobile-expense-tracker`, and choose **New → Blueprint**.
3. Select this repository, branch **QA**, and Blueprint Path **deploy/render.yaml**.
4. Review the proposed single Python web service `expense-tracker-qa` and confirm **Free**. Do not add a Render database; Supabase is already provisioned.
5. Supply `DATABASE_URL` from your ignored `backend/.env.qa` file. This must use `expense_qa_app.PROJECT_REF`, not `postgres` and not the dev role. Never upload `.env.migration` or administrator credentials.
6. The Blueprint sets `APP_ENV=qa`, `DB_SCHEMA=qa`, generates a separate JWT secret, uses root directory `backend`, installs `requirements.lock.txt`, and starts `uvicorn app.main:app --host 0.0.0.0 --port $PORT`. Auto-deploy tracks pushes to QA.
7. Deploy, wait for the health check, then visit the service's `/health` URL. Expect `status=ok`, `environment=qa`, and `storage=relational`.
8. To create your one personal QA account, temporarily set `ALLOW_REGISTRATION=true` in Render and wait for deployment, then use the QA APK's Settings → Configure API / sign in → Create account. Set it back to false afterward (the Blueprint's default is false).
9. Enter the service's HTTPS base URL in the QA APK. Grant capture permissions manually only after reviewing the consent screen. Add a synthetic transaction, sync, and verify its presence in `qa.transactions` and `qa.records` before importing actual messages.

Render Free sleeps after idle periods; allow the first request time to wake the backend. Android queues failed sync for retry. Do not put schema migrations into startup or build commands; migrations are already applied and future ones run from the trusted local migration process.

For future backend releases, run `backend/scripts/migrate_environments.py` from the trusted local machine, dry-run `backend/scripts/backfill_relational.py`, review its result, and use `--apply` if needed. These operations use ignored local credentials and never require storing the administrator connection on Render.

Once the service is live, share only its public `https://...onrender.com` URL so its health and Android HTTPS sync can be checked. Do not send passwords, JWT secrets, or database connection URLs in chat.

Official references: [Render Blueprints](https://render.com/docs/infrastructure-as-code), [Blueprint fields](https://render.com/docs/blueprint-spec), [Free service behavior](https://render.com/docs/free).
