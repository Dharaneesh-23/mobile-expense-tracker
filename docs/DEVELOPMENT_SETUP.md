# Development setup

## Verified on 2026-11-01

| Tool | Observed status |
| --- | --- |
| Git | 2.53.0.windows.1; identity already configured |
| Android Studio | Installed at `C:\Program Files\Android\Android Studio` |
| Studio Java runtime | OpenJDK 21.0.10, bundled `jbr` |
| Android SDK | `C:\Users\Dharaneesh\AppData\Local\Android\Sdk` |
| SDK platforms | `android-34`, `android-36.1` |
| SDK Build Tools | 33.0.1, 34.0.0, 35.0.0, 36.0.0, 36.1.0, 37.0.0 |
| Physical Android device | Samsung SM-E625F connected and authorized through ADB |
| Emulator | Installed; no configured AVD was reported |
| SDK command-line tools | Not present in the inspected SDK directory |
| Python | 3.10.0 available |
| Docker CLI | 29.2.1 available; Docker Desktop Linux engine was unreachable |
| GitHub CLI | 2.103.3 installed at `C:\Program Files\GitHub CLI\gh.exe`; login pending |

## Start a development terminal

```powershell
Set-Location D:\mobile_expense_tracker
. .\scripts\Enter-Dev.ps1
java -version
adb devices -l
```

The helper selects the installed Android Studio runtime and SDK only for that terminal. It does not modify machine-wide settings or execution policy. If script execution is restricted, use Android Studio's terminal or set `JAVA_HOME`, `ANDROID_HOME`, and PATH for the current terminal manually according to the helper.

## Android Studio prerequisites

1. Open Android Studio → Settings → Languages & Frameworks → Android SDK. Confirm the SDK path above.
2. In SDK Tools, install **Android SDK Command-line Tools (latest)** if needed for command-line SDK management. Review and accept licenses through SDK Manager. Keep Platform-Tools and Emulator installed.
3. Use the connected physical phone for initial development. Keep USB debugging authorized. An emulator is optional: Device Manager → Create Device → choose an installed/available image. Use a real phone later to validate bank SMS and payment notifications.
4. When creating `android/`, use Java with Views/XML, package `com.dharaneesh.expensetracker`. Configure `dev`, `qa`, and `prod` product flavors; the Git branch remains uppercase `QA`. Select a compatible Android Gradle Plugin / Gradle / JDK combination using official Android documentation at scaffolding time. Commit the Gradle wrapper including its JAR. Configure Studio's Gradle JDK consistently with that choice.
5. Put the SDK path in ignored `android/local.properties` when the Android project exists. Do not commit absolute local SDK paths. Add local-only HTTP access, if needed, to the development variant only.

No Android build has been run: this repository currently contains specifications and setup files only.

## Backend prerequisites

Python 3.10 is installed. Choose a supported Python version and lock dependencies when scaffolding the backend; use an isolated `backend/.venv`. Docker is optional for local PostgreSQL. Verify Docker Desktop/engine before using containers, or install PostgreSQL locally. Do not install FastAPI dependencies globally.

The first backend setup must include ignored local environment files, a sanitized `.env.example`, migrations, and independent environment databases. Real credentials stay out of Git and examples. Cloud, Firebase, and production signing setup can follow the local Android milestone.

## Planning gaps to resolve before the affected feature

- Sync relies on `updated_at`, but the proposed schema lacks change versions and deletion tombstones; add these before sync implementation.
- Refresh-token rotation/revocation requires persistent session state missing from the draft schema and a consistent refresh API response.
- Room does not by itself satisfy the encryption-at-rest requirement; decide the encryption/key-management approach before storing financial data.
- Recheck permission policy and hosting/free-tier assumptions with official sources before release or provisioning. The original documents are design inputs, not verified current service guarantees.

See [Git setup](GIT_ENVIRONMENTS.md) to create and connect the GitHub remote. Feature implementation starts after the setup baseline is reviewed.
