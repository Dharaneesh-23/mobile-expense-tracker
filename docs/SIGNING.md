# Personal APK signing

This app is for direct installation on your own Android phone. Play Console, App Bundles, store declarations, and publishing accounts are not required.

1. Open `D:\mobile_expense_tracker\android` as a project in Android Studio. Let Gradle sync finish.
2. Choose Build → Generate Signed App Bundle or APK → APK → Create new.
3. Save the JKS outside this repository, for example under your user profile's `expense-keys` directory. Use a strong password, RSA 2048 or stronger, and a long validity period. Keep a secure backup of the file and passwords.
4. One key can sign all three application IDs for personal use, or create separate dev/qa/prod files with aliases `expense-dev`, `expense-qa`, `expense-prod`.
5. Copy `android/signing.properties.example` to ignored `android/signing.properties`. Enter absolute keystore paths with forward slashes, aliases, and passwords locally. Do not send the keystores or passwords in chat. The example's values are placeholders.
6. Build from the repository root: `./scripts/Build-Android.ps1 -Environment qa -Release`. Choose `dev`, `qa`, or `prod` as needed. Production cloud connections remain disabled during the dev/qa testing phase.

Without these keys, debug builds use Android's standard debug keystore and work on your phone. Changing the signing key of an already installed application requires uninstalling that application first, which deletes its local data. Export/sync data first. Future APK updates must keep the same application ID and signing key. The environment IDs are `com.dharaneesh.expensetracker.dev`, `.qa`, and `com.dharaneesh.expensetracker`.

The local signing properties file is ignored by Git, but contains passwords in plaintext on your machine; restrict its filesystem access and keep it out of cloud-synced folders. Delete it when you no longer need automatic signing. Android Studio's signing wizard can instead supply credentials interactively.

Reference: https://developer.android.com/studio/publish/app-signing
