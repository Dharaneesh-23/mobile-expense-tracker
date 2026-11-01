# Git and environment workflow

## Local repository

One monorepo contains the Android app and backend. Long-lived branches are exactly `dev`, `QA`, and `prod`; `dev` is the working branch. All three begin at the same setup commit. New commits move only the active branch until explicitly promoted.

| Branch / future GitHub Environment | Purpose | Planned Android application ID | Planned database |
| --- | --- | --- | --- |
| `dev` | Local development | `com.dharaneesh.expensetracker.dev` | Supabase `dev` schema |
| `QA` | Verification with test data | `com.dharaneesh.expensetracker.qa` | Supabase `qa` schema |
| `prod` | Future personal releases | `com.dharaneesh.expensetracker` | Deferred |

These runtime settings are a contract for the future scaffolding, not implemented build variants or provisioned databases. Use independent database roles, JWT secrets, Firebase configuration, and API endpoints per environment. Separate application IDs will allow all three apps to coexist on one phone. Keep signing keys and financial exports out of Git; put local exports in ignored `private-data/`.

## Create the remote on GitHub

The local `origin` is now configured as `https://github.com/Dharaneesh-23/mobile-expense-tracker.git`. Do not run `remote add` again for this checkout. Authentication and initial remote inspection/push are pending.

Run the authentication helper in your own PowerShell terminal:

```powershell
Set-Location D:\mobile_expense_tracker
.\scripts\Connect-GitHub.ps1
# Or use hidden PAT input:
.\scripts\Connect-GitHub.ps1 -UsePat
```

Choose one authentication method. Browser login avoids managing a PAT. The PAT option reads hidden input and sends it to GitHub CLI through standard input, never a command-line argument or repository file. GitHub CLI manages credential storage. Do not put the PAT in `.env`, the repository URL, chat, or a shell command containing its literal value.

For a fine-grained PAT, select only this repository and an expiration. Contents read/write permits code operations; Workflows read/write is needed when updating Actions workflows. Pull requests read/write permits PR management. Repository settings/environments may additionally require Administration and Environments write permissions; grant those only for setup if needed. See [GitHub's permission reference](https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens).

After login, tell the agent authentication is ready. It can inspect the remote and push `dev`, `QA`, and `prod`, then configure supported GitHub settings. If the remote already has commits, preserve them and reconcile history before pushing. Do not force-push over them.

The repository has already been created. Once authenticated, inspect its branches before running the initial pushes:

```powershell
git ls-remote origin
git push -u origin dev
git push -u origin QA
git push -u origin prod
```

Git Credential Manager is installed. Complete browser sign-in if prompted; do not paste tokens into chat or commit them. If `origin` already exists, inspect `git remote -v` before changing it.

1. Set the GitHub repository default branch to `dev`.
2. Under repository Settings → Environments, create `dev`, `QA`, and `prod` if supported by your account/repository plan. These are distinct from Git branches. Restrict deployments to the matching branch. Add production reviewers where supported; do not buy a plan for this project.
3. Configure branch rules where supported: require pull requests and passing checks for `QA` and `prod`, block force pushes and deletion. Add required checks only after CI exists. If the private repository plan does not support enforcement, follow the same review process manually.

## Daily work and promotion

```powershell
git switch dev
git pull --ff-only
git switch -c feature/capture-layer
# Make changes, review git diff, commit explicit files, and push the feature branch.
git push -u origin feature/capture-layer
```

Open a pull request from the feature branch to `dev`. After verification, open `dev` → `QA`; after device testing, open `QA` → `prod`. Use merge commits for promotions to preserve ancestry across long-lived branches. Tag approved production commits with release versions. Bring any urgent production fixes back through `QA` and `dev`.

Render services track their matching branch and store separate secrets in Render. Merging reviewed changes into `prod` is the production approval gate. GitHub Environments are optional for any future Actions jobs; Render auto-deployment does not depend on them. No SSH deployment workflow is needed.

## Infrastructure prerequisites (later)

Use one Supabase project with isolated dev/qa schemas and runtime roles until testing is complete; production provisioning is deferred. See `docs/SUPABASE_DEV_QA.md`. Render's 750 free instance hours are shared across the workspace; provision hosted services as needed within quotas. Render supplies an HTTPS onrender.com endpoint, so a purchased domain is optional. Verify database access controls, per-environment secrets, backup restoration, cold-start handling, and current quotas before production. This is a personal-use APK; store publishing is not required.
