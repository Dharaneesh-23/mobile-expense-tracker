# Git and environment workflow

## Local repository

One monorepo contains the Android app and backend. Long-lived branches are exactly `dev`, `QA`, and `prod`; `dev` is the working branch. All three begin at the same setup commit. New commits move only the active branch until explicitly promoted.

| Branch / future GitHub Environment | Purpose | Planned Android application ID | Planned database |
| --- | --- | --- | --- |
| `dev` | Local development | `com.dharaneesh.expensetracker.dev` | `expense_tracker_dev` |
| `QA` | Verification with test data | `com.dharaneesh.expensetracker.qa` | `expense_tracker_qa` |
| `prod` | Approved releases | `com.dharaneesh.expensetracker` | `expense_tracker_prod` |

These runtime settings are a contract for the future scaffolding, not implemented build variants or provisioned databases. Use independent database roles, JWT secrets, Firebase configuration, and API endpoints per environment. Separate application IDs will allow all three apps to coexist on one phone. Keep signing keys and financial exports out of Git; put local exports in ignored `private-data/`.

## Create the remote on GitHub

1. Sign in to GitHub and create a repository named `mobile_expense_tracker`. Private is suitable for this personal project.
2. Leave initialization options unchecked: no README, license, or .gitignore; these already exist locally.
3. Copy the HTTPS repository URL. In PowerShell at `D:\mobile_expense_tracker`, replace `YOUR_USERNAME` below with your actual account:

```powershell
git remote add origin https://github.com/YOUR_USERNAME/mobile_expense_tracker.git
git push -u origin dev
git push -u origin QA
git push -u origin prod
```

Git Credential Manager is installed. Complete browser sign-in if prompted; do not paste tokens into chat or commit them. If `origin` already exists, inspect `git remote -v` before changing it.

4. Set the GitHub repository default branch to `dev`.
5. Under repository Settings → Environments, create `dev`, `QA`, and `prod` if supported by your account/repository plan. These are distinct from Git branches. Restrict deployments to the matching branch. Add production reviewers where supported; do not buy a plan for this project.
6. Configure branch rules where supported: require pull requests and passing checks for `QA` and `prod`, block force pushes and deletion. Add required checks only after CI exists. If the private repository plan does not support enforcement, follow the same review process manually.

## Daily work and promotion

```powershell
git switch dev
git pull --ff-only
git switch -c feature/capture-layer
# Make changes, review git diff, commit explicit files, and push the feature branch.
git push -u origin feature/capture-layer
```

Open a pull request from the feature branch to `dev`. After verification, open `dev` → `QA`; after device testing, open `QA` → `prod`. Use merge commits for promotions to preserve ancestry across long-lived branches. Tag approved production commits with release versions. Bring any urgent production fixes back through `QA` and `dev`.

Deployments must eventually select the GitHub Environment matching their branch and use its scoped secrets. Production requires deliberate approval. No original example that deploys from `main` should be enabled unchanged.

## Infrastructure prerequisites (later)

Provision independent environment databases/users and service configuration before enabling deployment. A shared VM can host separate services/ports and databases, but has shared availability and resource limits. Keep PostgreSQL private. Verify provider terms, current free allowances, and capacity at provisioning time. A domain, HTTPS, backup restore test, and secure deployment credentials are prerequisites for production. Local Android development does not need a cloud account.
