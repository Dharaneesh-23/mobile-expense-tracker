# QA and prod apps with private in-app updates

Both app IDs can coexist on the phone: `com.dharaneesh.expensetracker.qa` (Expense • QA) and `com.dharaneesh.expensetracker` (Expense Tracker). They use separate app storage. The initial personal-use previews are version code 100 and use the existing debug signing key, as requested. Keep that exact key for future updates; changing keys requires uninstalling and can erase local data.

## Live service

The Free QA update backend is **https://expense-tracker-qa.onrender.com** (Singapore, deployed 2026-11-02). Its restricted database role, private download token, and signing workflow secret are configured. QA update delivery passed an online test using a disposable account; the downloaded APK matched its hash, size, and installed signing certificate. Create your personal login using the local helper below, then enter the URL and login in each app. Android still requires installation approval on the phone.

The workflow now restores the key at an explicit Gradle path and refuses publication unless the APK certificate matches `android/signing-certificate.sha256`. An earlier APK with a different certificate remains a draft and is excluded from update discovery.

## On the phone

Use **App updates** on the overview screen, or Settings → App updates. Configure the HTTPS update-service URL and sign in with your personal update account. Update credentials are encrypted with Android Keystore and are separate from financial sync credentials.

QA and prod can both use the QA backend **only for update delivery**. Prod financial sync remains disabled while the database has only dev/qa schemas. The prod app continues to work offline. The updater requests its own channel, checks the package, version, SHA-256 and installed signing certificate, then asks Android to install the APK. It never silently installs packages, downgrades, or clears app data.

On the first update, Android may ask to allow installation from this app. Enable that for the app if you want this update mechanism, return to the app, and tap **Install downloaded update**. Android displays the final installation confirmation.

## Private distribution architecture

GitHub Actions builds APKs and publishes immutable releases in the **private** repository. Tags are `android-qa-vVERSION` and `android-prod-vVERSION`. Each release contains an APK and a SHA-256 manifest. QA releases are marked prerelease. The backend reads these assets using a repository-scoped **Contents read-only** token and streams them to signed-in users. Neither GitHub PATs nor signing private keys are embedded in the APK.

The QA backend exposes authenticated endpoints `/api/v1/app-updates/qa`, `/api/v1/app-updates/prod`, and their `/download?version_code=...` paths. APK bytes are proxied through the backend; GitHub credentials are never forwarded to redirected asset hosts or Android. The shared backend does not create a production financial database.

## GitHub workflow setup

1. Run `scripts/Configure-UpdateSigning.ps1` locally. It puts the existing debug keystore into the encrypted repository secret `ANDROID_DEBUG_KEYSTORE_BASE64`, without printing it. The local GitHub token needs Secrets write permission.
2. The workflow `.github/workflows/android-update.yml` runs on QA/prod branch pushes affecting Android code and can be started manually through Actions. Manual runs always build the selected channel's matching branch. QA must contain the tested app code; prod must contain its deliberately promoted baseline.
3. Builds use the stored key, run tests/lint, verify the APK, and publish a complete release only after uploading all assets to a draft. Version codes are `100000 + run_number * 100 + run_attempt`; do not reset the workflow's history or reuse a published version code.
4. Daily changes go through dev → QA for testing. Promote an accepted baseline to prod deliberately. A QA update never automatically replaces the prod app.

## Backend setup

1. Put a separate read-only token in `UPDATES_GITHUB_TOKEN` in ignored `backend/.env.qa`. Set `UPDATES_GITHUB_REPOSITORY=Dharaneesh-23/mobile-expense-tracker`. Keep the more powerful local workflow token off Render.
2. Run `backend/scripts/prepare_render_update_env.py` to generate ignored `private-data/render-qa.env`. It contains only the restricted QA runtime database URL, QA JWT secret, private download token, and non-secret configuration. No administrator connection is exported.
3. Import this file into the Free Render QA web service only after reviewing the destination and intended secrets. Configure `backend` as root, install `requirements.lock.txt`, and start Uvicorn on `$PORT`. See `RENDER_QA_SETUP.md`.
4. Create your own QA/update login by running `backend/.venv/Scripts/python.exe backend/scripts/create_update_account.py` in your own terminal. It prompts for an email and hidden password and creates the account directly through the restricted local QA connection. Public registration can stay disabled on Render.
5. Use those credentials and Render's public HTTPS base URL in each app's private-update settings. The update checker authenticates against that service, independently of normal app data sync.

## Two-day QA check

Keep prod as the baseline and use QA for the coming two days. Verify capture permissions, supported bank SMS, GPay split requests, duplicate handling, offline manual entries, category corrections, budgets, PDF export, and installing a newer QA APK without losing entries. Check that a QA release is never offered to prod. Record unparsed messages in the encrypted review queue; do not post bank messages or statements in GitHub issues or commits.

Updates away from the PC require a running HTTPS backend, a configured read-only repository token, a personal login, and a published release. Installing the two APKs alone does not make the online updater operational.

References: [Android app signing](https://developer.android.com/studio/publish/app-signing), [GitHub private artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts), [Render setup](https://render.com/docs/infrastructure-as-code).
