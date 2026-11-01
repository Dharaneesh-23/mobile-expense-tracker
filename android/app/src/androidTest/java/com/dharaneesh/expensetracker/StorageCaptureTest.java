package com.dharaneesh.expensetracker;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dharaneesh.expensetracker.data.*;
import com.dharaneesh.expensetracker.data.Record;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StorageCaptureTest {
  private Repository repo;
  private android.content.SharedPreferences preferences;
  private String originalRules;

  @Before
  public void setup() {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    preferences = c.getSharedPreferences("settings", 0);
    originalRules = preferences.getString("category_rules", null);
    repo = new Repository(c, Room.inMemoryDatabaseBuilder(c, AppDatabase.class).build());
  }

  @After
  public void close() {
    repo.db.close();
    if (originalRules == null) preferences.edit().remove("category_rules").commit();
    else preferences.edit().putString("category_rules", originalRules).commit();
  }

  @Test
  public void financialPayloadIsEncryptedAndDeleteIsTracked() throws Exception {
    repo.save(
        new JSONObject()
            .put("id", "test-record")
            .put("kind", "transaction")
            .put("payee", "PRIVATE MERCHANT")
            .put("amount", "25.00"));
    Record stored = repo.db.records().get("test-record");
    assertFalse(stored.encrypted.contains("PRIVATE MERCHANT"));
    assertFalse(stored.encrypted.contains("25.00"));
    assertEquals("PRIVATE MERCHANT", repo.unpack(stored).getString("payee"));
    repo.delete("test-record");
    assertTrue(repo.db.records().get("test-record").deleted);
    assertTrue(repo.list("transaction").isEmpty());
  }

  @Test
  public void repeatedSmsAndSharedReferenceAreDeduplicated() {
    String sms =
        "Sent Rs.30.00\nFrom HDFC Bank A/C *4321\nTo SAMPLE PERSON\nOn 24/10/26\nRef 111111111111";
    assertTrue(repo.capture("HDFC", sms, "sms", 1792800000000L, "sms:one"));
    assertFalse(repo.capture("HDFC", sms, "sms", 1792800000000L, "sms:one"));
    assertFalse(repo.capture("HDFC", sms, "sms", 1792800000000L, "sms:another"));
    assertEquals(1, repo.list("transaction").size());
  }

  @Test
  public void equalAmountsWithDifferentReferencesStaySeparate() {
    String first =
        "Sent Rs.30.00\nFrom HDFC Bank A/C *4321\nTo SAMPLE PERSON\nOn 24/10/26\nRef 111111111111";
    assertTrue(repo.capture("HDFC", first, "sms", 1792800000000L, "sms:one"));
    assertTrue(
        repo.capture(
            "HDFC",
            first.replace("111111111111", "222222222222"),
            "sms",
            1792800000000L,
            "sms:two"));
    assertEquals(2, repo.list("transaction").size());
  }

  @Test
  public void splitRequestNeverIncreasesSpent() {
    assertTrue(
        repo.capture(
            "New split request in 'Trip…",
            "Pay Sample Person ₹70.83 for 'breakfast'",
            "gpay",
            1792800000000L,
            "split:one"));
    assertEquals(1, repo.list("split").size());
    assertTrue(repo.list("transaction").isEmpty());
    assertTrue(repo.list("split").get(0).optBoolean("group_incomplete"));
  }

  @Test public void categoryCorrectionIsRememberedForFutureCaptures() throws Exception {
    JSONObject item = new JSONObject().put("id", "correction-test").put("kind", "transaction")
        .put("amount", "10.00").put("direction", "debit").put("payee", "Rule Test Merchant")
        .put("occurred_at", 1792800000000L).put("category", "Other");
    repo.save(item);
    item.put("category", "Travel");
    repo.save(item);
    assertTrue(repo.capture("HDFC", "Sent Rs.30.00\nFrom HDFC Bank A/C *4321\nTo Rule Test Merchant\nOn 24/10/26\nRef 555555555555", "sms", 1792800000000L, "rule-event"));
    for (JSONObject saved : repo.list("transaction")) assertEquals("Travel", saved.optString("category"));
    assertFalse(preferences.getString("category_rules", "").contains("Rule Test Merchant"));
  }
}
