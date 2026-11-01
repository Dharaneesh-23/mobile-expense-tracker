package com.dharaneesh.expensetracker.update;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.dharaneesh.expensetracker.*;
import com.dharaneesh.expensetracker.data.Vault;
import com.dharaneesh.expensetracker.sync.SyncWorker;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import org.json.*;

/** Private, authenticated, channel-specific APK updates with explicit Android approval. */
public final class AppUpdater {
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .connectTimeout(30, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .build();
  private final Activity activity;
  private final SharedPreferences prefs;
  private final Vault vault;

  public AppUpdater(Activity activity) {
    this.activity = activity;
    prefs = activity.getSharedPreferences("settings", 0);
    vault = ExpenseApp.repo(activity).vault;
  }

  public void open() {
    if (base().isEmpty()) configure();
    else check();
  }

  public void addSettings(LinearLayout host) {
    if (!BuildConfig.FLAVOR.equals("qa") && !BuildConfig.FLAVOR.equals("prod")) return;
    TextView heading = new TextView(activity);
    heading.setText("App updates · " + BuildConfig.FLAVOR.toUpperCase(Locale.ROOT));
    heading.setTextSize(22);
    host.addView(heading);
    TextView detail = new TextView(activity);
    detail.setText(
        "Installed "
            + BuildConfig.VERSION_NAME
            + " ("
            + BuildConfig.VERSION_CODE
            + "). Private downloads; Android asks before installing.");
    host.addView(detail);
    addButton(host, "Configure private update service", this::configure);
    addButton(host, "Check for app updates", this::check);
    if (new File(activity.getCacheDir(), "updates/update.apk").exists())
      addButton(host, "Install downloaded update", this::install);
  }

  private void addButton(LinearLayout host, String label, Runnable action) {
    Button button = new Button(activity);
    button.setText(label);
    button.setAllCaps(false);
    host.addView(button);
    button.setOnClickListener(v -> action.run());
  }

  private void fail(Exception exception) {
    activity.runOnUiThread(
        () ->
            new AlertDialog.Builder(activity)
                .setTitle("App update")
                .setMessage(exception.getMessage())
                .setPositiveButton("OK", null)
                .show());
  }

  private String base() {
    return prefs.getString("update_base_url", "");
  }

  private String normalize(String input) {
    String value = input.trim().replaceAll("/+$", "");
    Uri uri = Uri.parse(value);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath() == null || uri.getPath().isEmpty()))
      throw new IllegalArgumentException("Enter only the HTTPS base URL of your update backend");
    return value;
  }

  private EditText field(LinearLayout form, String hint, String value) {
    EditText input = new EditText(activity);
    input.setHint(hint);
    input.setSingleLine(true);
    input.setText(value);
    form.addView(input);
    return input;
  }

  private void configure() {
    LinearLayout form = new LinearLayout(activity);
    form.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
    form.setPadding(pad, 0, pad, 0);
    EditText url = field(form, "HTTPS update service URL", base()),
        email = field(form, "Update account email", ""),
        password = field(form, "Update account password", "");
    password.setInputType(129);
    CheckBox create = new CheckBox(activity);
    create.setText("Create my update account (if enabled on server)");
    form.addView(create);
    TextView info = new TextView(activity);
    info.setText(
        "Both apps may use the QA update service. This sign-in is separate from financial sync;"
            + " prod data stays offline.");
    form.addView(info);
    AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setTitle("Private update service")
            .setView(form)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    w -> {
                      final String endpoint;
                      try {
                        endpoint = normalize(url.getText().toString());
                      } catch (Exception e) {
                        url.setError(e.getMessage());
                        return;
                      }
                      String username = email.getText().toString().trim(),
                          secret = password.getText().toString();
                      boolean register = create.isChecked();
                      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                      IO.execute(
                          () -> {
                            try {
                              JSONObject health = SyncWorker.request(endpoint, "/health", "", null);
                              if (!Arrays.asList("dev", "qa")
                                  .contains(health.optString("environment")))
                                throw new IOException(
                                    "Use the configured development or QA update service");
                              JSONObject account =
                                  SyncWorker.request(
                                      endpoint,
                                      "/api/v1/auth/" + (register ? "register" : "login"),
                                      "",
                                      new JSONObject()
                                          .put("email", username)
                                          .put("password", secret));
                              prefs
                                  .edit()
                                  .putString("update_base_url", endpoint)
                                  .putString(
                                      "update_access",
                                      vault.encrypt(account.getString("access_token")))
                                  .putString(
                                      "update_refresh",
                                      vault.encrypt(account.getString("refresh_token")))
                                  .commit();
                              activity.runOnUiThread(
                                  () -> {
                                    password.setText("");
                                    dialog.dismiss();
                                    Toast.makeText(
                                            activity,
                                            "Private update service connected",
                                            Toast.LENGTH_LONG)
                                        .show();
                                  });
                            } catch (Exception e) {
                              activity.runOnUiThread(
                                  () ->
                                      dialog
                                          .getButton(AlertDialog.BUTTON_POSITIVE)
                                          .setEnabled(true));
                              fail(e);
                            }
                          });
                    }));
    dialog.show();
  }

  private String access() throws Exception {
    if (base().isEmpty() || prefs.getString("update_refresh", "").isEmpty())
      throw new IOException("Configure the private update service first");
    JSONObject refreshed =
        SyncWorker.request(
            base(),
            "/api/v1/auth/refresh",
            "",
            new JSONObject()
                .put("refresh_token", vault.decrypt(prefs.getString("update_refresh", ""))));
    String access = refreshed.getString("access_token");
    prefs
        .edit()
        .putString("update_access", vault.encrypt(access))
        .putString("update_refresh", vault.encrypt(refreshed.getString("refresh_token")))
        .commit();
    return access;
  }

  private void check() {
    Toast.makeText(
            activity, "Checking private " + BuildConfig.FLAVOR + " updates…", Toast.LENGTH_SHORT)
        .show();
    IO.execute(
        () -> {
          try {
            String token = access();
            JSONObject manifest =
                SyncWorker.request(
                    base(), "/api/v1/app-updates/" + BuildConfig.FLAVOR, token, null);
            if (manifest.getLong("version_code") <= BuildConfig.VERSION_CODE) {
              activity.runOnUiThread(
                  () ->
                      Toast.makeText(
                              activity, "You have the latest published version", Toast.LENGTH_LONG)
                          .show());
              return;
            }
            validateManifest(manifest);
            activity.runOnUiThread(
                () ->
                    new AlertDialog.Builder(activity)
                        .setTitle("Update to " + manifest.optString("version_name"))
                        .setMessage(
                            manifest.optString("notes")
                                + "\n\nDownload: "
                                + String.format(
                                    Locale.US, "%.1f MB", manifest.optLong("size") / 1_000_000.0))
                        .setPositiveButton("Download", (d, w) -> download(manifest))
                        .setNegativeButton("Later", null)
                        .show());
          } catch (Exception e) {
            fail(e);
          }
        });
  }

  private void validateManifest(JSONObject manifest) throws Exception {
    UpdatePolicy.validate(
        manifest.getString("channel"),
        manifest.getString("package_name"),
        manifest.getLong("version_code"),
        manifest.getLong("size"),
        manifest.getString("sha256"),
        manifest.optInt("min_sdk", 26),
        BuildConfig.FLAVOR,
        activity.getPackageName(),
        BuildConfig.VERSION_CODE,
        Build.VERSION.SDK_INT);
    String expected =
        "/api/v1/app-updates/"
            + BuildConfig.FLAVOR
            + "/download?version_code="
            + manifest.getLong("version_code");
    if (!expected.equals(manifest.getString("download_path")))
      throw new IOException("Invalid private download endpoint");
  }

  private void download(JSONObject manifest) {
    ProgressDialog progress = new ProgressDialog(activity);
    progress.setTitle("Downloading verified app update");
    progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    progress.setMax(100);
    progress.setCancelable(true);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    progress.setOnCancelListener(d -> cancelled.set(true));
    progress.show();
    IO.execute(
        () -> {
          File partial = null;
          try {
            validateManifest(manifest);
            String token = access();
            File directory = new File(activity.getCacheDir(), "updates");
            if (!directory.exists() && !directory.mkdirs())
              throw new IOException("Cannot create update cache");
            partial = new File(directory, "update.part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            Request request =
                new Request.Builder()
                    .url(base() + manifest.getString("download_path"))
                    .header("Authorization", "Bearer " + token)
                    .build();
            try (Response response = HTTP.newCall(request).execute()) {
              if (!response.isSuccessful() || response.body() == null)
                throw new IOException(
                    "Private download returned " + response.code() + "; check for updates again");
              try (InputStream input = response.body().byteStream();
                  OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = input.read(buffer)) != -1) {
                  if (cancelled.get()) throw new IOException("Download cancelled");
                  total += length;
                  if (total > manifest.getLong("size"))
                    throw new IOException("APK exceeded the expected size");
                  output.write(buffer, 0, length);
                  digest.update(buffer, 0, length);
                  int percent = (int) (total * 100 / manifest.getLong("size"));
                  activity.runOnUiThread(() -> progress.setProgress(percent));
                }
              }
            }
            if (total != manifest.getLong("size")
                || !hex(digest.digest()).equals(manifest.getString("sha256")))
              throw new IOException("APK verification failed; nothing was installed");
            verifyArchive(activity, partial, manifest.getLong("version_code"));
            File ready = new File(directory, "update.apk");
            if (ready.exists() && !ready.delete())
              throw new IOException("Cannot replace the previous update");
            if (!partial.renameTo(ready)) throw new IOException("Cannot prepare update");
            prefs
                .edit()
                .putLong("downloaded_update_code", manifest.getLong("version_code"))
                .commit();
            activity.runOnUiThread(
                () -> {
                  progress.dismiss();
                  install();
                });
          } catch (Exception e) {
            if (partial != null) partial.delete();
            activity.runOnUiThread(progress::dismiss);
            if (!cancelled.get()) fail(e);
          }
        });
  }

  private void install() {
    try {
      File apk = new File(activity.getCacheDir(), "updates/update.apk");
      if (!apk.exists()) throw new IOException("Download an update first");
      verifyArchive(activity, apk, prefs.getLong("downloaded_update_code", 0));
      if (!activity.getPackageManager().canRequestPackageInstalls()) {
        new AlertDialog.Builder(activity)
            .setTitle("Allow app updates")
            .setMessage(
                "Enable ‘Allow from this source’ for this app. Return here and tap Install"
                    + " downloaded update. Android will ask you to confirm the installation.")
            .setPositiveButton(
                "Open settings",
                (d, w) ->
                    activity.startActivity(
                        new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()))))
            .setNegativeButton("Later", null)
            .show();
        return;
      }
      Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
      Intent installer =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      activity.startActivity(installer);
    } catch (Exception e) {
      fail(e);
    }
  }

  public static void verifyArchive(android.content.Context context, File apk, long expectedCode)
      throws Exception {
    PackageManager pm = context.getPackageManager();
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageInfo next = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags),
        current = pm.getPackageInfo(context.getPackageName(), flags);
    if (next == null || !next.packageName.equals(context.getPackageName()))
      throw new IOException("APK package does not match this app");
    long nextCode = Build.VERSION.SDK_INT >= 28 ? next.getLongVersionCode() : next.versionCode;
    long currentCode =
        Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
    if (nextCode != expectedCode || nextCode <= currentCode)
      throw new IOException("APK version does not match a newer update");
    if (next.applicationInfo != null && next.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
      throw new IOException("APK requires a newer Android version");
    if (!certificates(next).equals(certificates(current)))
      throw new IOException("APK signing key does not match the installed app");
  }

  private static Set<String> certificates(PackageInfo info) throws Exception {
    android.content.pm.Signature[] signatures =
        Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
            ? info.signingInfo.getApkContentsSigners()
            : info.signatures;
    if (signatures == null || signatures.length == 0)
      throw new IOException("APK signing certificate is missing");
    Set<String> result = new HashSet<>();
    for (android.content.pm.Signature signature : signatures)
      result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
    return result;
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }
}
