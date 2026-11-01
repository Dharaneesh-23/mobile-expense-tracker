package com.dharaneesh.expensetracker.sync;

import android.content.*;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.dharaneesh.expensetracker.BuildConfig;
import com.dharaneesh.expensetracker.ExpenseApp;
import com.dharaneesh.expensetracker.data.*;
import com.dharaneesh.expensetracker.data.Record;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

public class SyncWorker extends Worker {
  private static final Object SYNC_LOCK = new Object();
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .followSslRedirects(false)
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(90, TimeUnit.SECONDS)
          .build();

  public SyncWorker(@NonNull Context c, @NonNull WorkerParameters p) {
    super(c, p);
  }

  public static JSONObject request(String base, String path, String token, JSONObject payload)
      throws Exception {
    android.net.Uri uri = android.net.Uri.parse(base);
    boolean localDebug =
        BuildConfig.DEBUG
            && BuildConfig.FLAVOR.equals("dev")
            && uri.getScheme() != null
            && uri.getScheme().equals("http")
            && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
    if (!"https".equals(uri.getScheme()) && !localDebug)
      throw new IOException("Use an HTTPS API URL");
    Request.Builder req = new Request.Builder().url(base.replaceAll("/+$", "") + path);
    if (!token.isEmpty()) req.header("Authorization", "Bearer " + token);
    if (payload == null) req.get();
    else req.post(RequestBody.create(payload.toString(), MediaType.get("application/json")));
    try (Response response = HTTP.newCall(req.build()).execute()) {
      if (!response.isSuccessful()) throw new IOException("API returned " + response.code());
      if (response.body() == null) throw new IOException("Empty API response");
      return new JSONObject(response.body().string());
    }
  }

  @NonNull
  @Override
  public Result doWork() {
    synchronized (SYNC_LOCK) {
      Result result = Result.success();
      for (int batch = 0; batch < 10; batch++) {
        result = performSync();
        if (!result.equals(Result.success())
            || ExpenseApp.repo(getApplicationContext()).db.records().dirty().isEmpty())
          return result;
      }
      return Result.retry();
    }
  }

  private Result performSync() {
    Context c = getApplicationContext();
    SharedPreferences prefs = c.getSharedPreferences("settings", 0);
    String base = prefs.getString("api_url", "");
    String encrypted = prefs.getString("access", "");
    if (base.isEmpty() || encrypted.isEmpty()) return Result.success();
    Repository repo = ExpenseApp.repo(c);
    try {
      JSONObject health = request(base, "/health", "", null);
      if (!health.optString("environment").equals(BuildConfig.FLAVOR))
        throw new IOException("API environment does not match this app");
      JSONObject refresh =
          request(
              base,
              "/api/v1/auth/refresh",
              "",
              new JSONObject()
                  .put("refresh_token", repo.vault.decrypt(prefs.getString("refresh", ""))));
      String access = refresh.getString("access_token");
      prefs
          .edit()
          .putString("access", repo.vault.encrypt(access))
          .putString("refresh", repo.vault.encrypt(refresh.getString("refresh_token")))
          .apply();
      String device = prefs.getString("device_id", UUID.randomUUID().toString());
      prefs.edit().putString("device_id", device).apply();
      JSONArray changes = new JSONArray();
      List<Record> sent = repo.db.records().dirty();
      for (Record r : sent) {
        if (r.kind.equals("review")) continue;
        changes.put(
            new JSONObject()
                .put("id", r.id)
                .put("kind", r.kind)
                .put("updated_at", r.updatedAt)
                .put("deleted", r.deleted)
                .put("data", repo.unpack(r)));
      }
      JSONObject result =
          request(
              base,
              "/api/v1/sync",
              access,
              new JSONObject()
                  .put("device_id", device)
                  .put("cursor", prefs.getLong("cursor", 0))
                  .put("changes", changes));
      synchronized (repo) {
        for (Record r : sent)
          if (!r.kind.equals("review")) repo.db.records().markSynced(r.id, r.updatedAt);
        JSONArray rows = result.getJSONArray("server_changes");
        for (int i = 0; i < rows.length(); i++) {
          JSONObject row = rows.getJSONObject(i);
          Record old = repo.db.records().get(row.getString("id"));
          if (old != null && old.dirty)
            continue; // Never overwrite a local edit made during the HTTP request.
          Record r = new Record();
          r.id = row.getString("id");
          r.kind = row.getString("kind");
          r.updatedAt = row.getLong("updated_at");
          r.deleted = row.optBoolean("deleted");
          r.dirty = false;
          r.encrypted = repo.vault.encrypt(row.getJSONObject("data").toString());
          repo.db.records().put(r);
        }
      }
      prefs
          .edit()
          .putLong("cursor", result.getLong("cursor"))
          .putString("sync_status", "Last synced successfully")
          .apply();
      return Result.success();
    } catch (Exception e) {
      prefs.edit().putString("sync_status", "Sync pending — " + e.getMessage()).apply();
      return Result.retry();
    }
  }
}
