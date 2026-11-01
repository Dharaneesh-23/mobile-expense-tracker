# Mobile Expense Tracker

Personal-use native Android app (Java), local-first encrypted Room storage, and a FastAPI/PostgreSQL backend. The numbered documents contain the product specifications; [build status](docs/BUILD_STATUS.md) distinguishes implemented behavior from remaining work.

Start with [development setup](docs/DEVELOPMENT_SETUP.md) and [Git and environment workflow](docs/GIT_ENVIRONMENTS.md).

## Repository layout

- Numbered Markdown documents: original requirements and design references.
- `docs/`: current setup instructions and environment decisions.
- `scripts/`: project-scoped Windows development helpers.
- `android/`: Android Studio project, Java package `com.dharaneesh.expensetracker`.
- `backend/`: FastAPI service, migrations, schema setup SQL, and backend tests.
- `deploy/render.yaml`: optional Render QA service Blueprint.

## Run locally

Open `android/` in Android Studio or run `./scripts/Build-Android.ps1 -Environment dev -Test`. Build QA with `-Environment qa`. APKs are generated under `android/app/build/outputs/apk/`.

For the backend, create a virtual environment in `backend/.venv`, install `backend/requirements.lock.txt`, and configure the ignored `backend/.env` from its example. From `backend/`, run `python -m alembic upgrade head` then `python -m uvicorn app.main:app`. Use a random JWT secret and environment-specific database credentials. Run `python -m pytest -q` for API tests.

For USB-connected local development, run `adb reverse tcp:8000 tcp:8000` and set the dev app's API URL to `http://127.0.0.1:8000`. Only dev debug permits localhost HTTP; QA/prod require HTTPS. The app checks that the backend environment matches its flavor.

See [Supabase dev/QA setup](docs/SUPABASE_DEV_QA.md) for two schemas in one project and [personal APK signing](docs/SIGNING.md) for private keystore setup. Production database access stays disabled during testing.

QA and prod app previews can coexist on your phone. See [private in-app updates](docs/PRIVATE_APP_UPDATES.md) for the authenticated update backend and GitHub publishing workflow. Initial builds use the existing debug key, as requested; preserve that key for future updates. Prod financial data remains offline during the dev/QA test phase.

## Branches

`dev` → `QA` → `prod`. Daily development starts on `dev`; promote reviewed and tested changes through pull requests. Exact branch casing matters.

Hosting is planned on Render's free web service with Supabase managed PostgreSQL. Render auto-deploys from each service's configured environment branch; no SSH deployment workflow is required. There are no active deployments yet.
