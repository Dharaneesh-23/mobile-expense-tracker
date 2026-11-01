# Mobile Expense Tracker

Native Android (Java), local-first Room storage, and a planned FastAPI/PostgreSQL backend. The numbered documents contain the product specifications. No application code has been generated yet.

Start with [development setup](docs/DEVELOPMENT_SETUP.md) and [Git and environment workflow](docs/GIT_ENVIRONMENTS.md).

## Repository layout

- Numbered Markdown documents: original requirements and design references.
- `docs/`: current setup instructions and environment decisions.
- `scripts/`: project-scoped Windows development helpers.
- Planned `android/`: Android Studio project, Java package `com.dharaneesh.expensetracker`.
- Planned `backend/`: FastAPI service, migrations, and backend tests.

## Branches

`dev` → `QA` → `prod`. Daily development starts on `dev`; promote reviewed and tested changes through pull requests. Exact branch casing matters.

The environment workflow supersedes references to automatic deployment from `main` in the original planning documents. There are no active deployments or deployment workflows yet.
