package com.dharaneesh.expensetracker;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.dharaneesh.expensetracker.sync.SyncWorker;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in smoke test: run with -PdeviceApiTest=true and adb reverse tcp:8000 tcp:8000. */
@RunWith(AndroidJUnit4.class)
public class ApiConnectionTest {
  @Test
  public void deviceCanRegisterAndSyncWithLocalApi() throws Exception {
    org.junit.Assume.assumeTrue(
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("deviceApiTest", "false")
            .equals("true"));
    String base = "http://127.0.0.1:8000";
    assertEquals("dev", SyncWorker.request(base, "/health", "", null).getString("environment"));
    JSONObject account =
        SyncWorker.request(
            base,
            "/api/v1/auth/register",
            "",
            new JSONObject()
                .put("email", "device-" + UUID.randomUUID() + "@example.com")
                .put("password", "device-smoke-test-password"));
    String id = UUID.randomUUID().toString();
    JSONObject record =
        new JSONObject()
            .put("id", id)
            .put("kind", "transaction")
            .put("updated_at", System.currentTimeMillis())
            .put("deleted", false)
            .put(
                "data",
                new JSONObject()
                    .put("amount", "12.34")
                    .put("direction", "debit")
                    .put("payee", "Device smoke test")
                    .put("occurred_at", System.currentTimeMillis()));
    JSONObject result =
        SyncWorker.request(
            base,
            "/api/v1/sync",
            account.getString("access_token"),
            new JSONObject()
                .put("device_id", "instrumentation")
                .put("cursor", 0)
                .put("changes", new JSONArray().put(record)));
    assertEquals(1, result.getLong("cursor"));
    assertEquals(id, result.getJSONArray("server_changes").getJSONObject(0).getString("id"));
  }
}
