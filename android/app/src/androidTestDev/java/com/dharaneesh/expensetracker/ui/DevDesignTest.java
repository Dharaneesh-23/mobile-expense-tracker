package com.dharaneesh.expensetracker.ui;

import static org.junit.Assert.*;

import android.app.*;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DevDesignTest {
  private Instrumentation instrument;
  private DevMainActivity activity;
  private com.dharaneesh.expensetracker.data.Repository testRepo;
  private String savedDraft, savedRoute;
  private java.io.File fixture;

  @Before
  public void launch() throws Exception {
    instrument = InstrumentationRegistry.getInstrumentation();
    android.content.SharedPreferences prefs =
        instrument.getTargetContext().getSharedPreferences("settings", 0);
    savedDraft = prefs.getString("dev_form_draft", null);
    savedRoute = prefs.getString("dev_draft_route", null);
    prefs.edit().remove("dev_form_draft").remove("dev_draft_route").commit();
    Intent intent =
        instrument
            .getTargetContext()
            .getPackageManager()
            .getLaunchIntentForPackage(instrument.getTargetContext().getPackageName());
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    activity = (DevMainActivity) instrument.startActivitySync(intent);
    testRepo =
        new com.dharaneesh.expensetracker.data.Repository(
            activity,
            androidx.room.Room.inMemoryDatabaseBuilder(
                    activity, com.dharaneesh.expensetracker.data.AppDatabase.class)
                .build());
    instrument.runOnMainSync(
        () -> {
          try {
            java.lang.reflect.Field generation =
                DevMainActivity.class.getDeclaredField("loadGeneration");
            generation.setAccessible(true);
            generation.setInt(activity, generation.getInt(activity) + 1);
            java.lang.reflect.Field route = DevMainActivity.class.getDeclaredField("route");
            route.setAccessible(true);
            route.set(activity, "Home");
          } catch (Exception e) {
            throw new AssertionError(e);
          }
          activity.repo = testRepo;
          activity.records = new ArrayList<>();
          activity.render();
        });
  }

  @After
  public void cleanup() {
    if (activity != null) instrument.runOnMainSync(() -> activity.finish());
    if (testRepo != null) testRepo.db.close();
    if (fixture != null) assertTrue(fixture.delete());
    android.content.SharedPreferences.Editor e =
        instrument.getTargetContext().getSharedPreferences("settings", 0).edit();
    if (savedDraft == null) e.remove("dev_form_draft");
    else e.putString("dev_form_draft", savedDraft);
    if (savedRoute == null) e.remove("dev_draft_route");
    else e.putString("dev_draft_route", savedRoute);
    e.commit();
  }

  private View find(View root, String name, Class<?> type) {
    if (type.isInstance(root)
        && (name.contentEquals(
                root.getContentDescription() == null ? "" : root.getContentDescription())
            || root instanceof TextView && name.contentEquals(((TextView) root).getText())))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View result = find(((ViewGroup) root).getChildAt(i), name, type);
        if (result != null) return result;
      }
    return null;
  }

  private View lookup(String name, Class<?> type) {
    return find(activity.getWindow().getDecorView(), name, type);
  }

  private void waitFor(String name) throws Exception {
    for (int i = 0; i < 50; i++) {
      final boolean[] found = {false};
      instrument.runOnMainSync(() -> found[0] = lookup(name, View.class) != null);
      if (found[0]) return;
      Thread.sleep(100);
    }
    fail("Missing control: " + name);
  }

  private void click(String name) throws Exception {
    waitFor(name);
    instrument.runOnMainSync(() -> lookup(name, View.class).performClick());
    instrument.waitForIdleSync();
  }

  private void enter(String name, String value) throws Exception {
    waitFor(name);
    instrument.runOnMainSync(() -> ((EditText) lookup(name, EditText.class)).setText(value));
  }

  @Test
  public void navigationFiltersAndPaiseSaveWorkWithoutTouchingExistingRecords() throws Exception {
    click("Transactions tab");
    click("Filters & date range");
    waitFor("From date (YYYY-MM-DD)");
    click("Cancel");
    click("+ Add Transaction");
    enter("Merchant / Payee", "Synthetic merchant");
    enter("Amount (₹)", "0");
    click("Save Transaction");
    assertTrue(testRepo.list("transaction").isEmpty());
    enter("Amount (₹)", "0.01");
    click("Save Transaction");
    waitFor("Transactions tab");
    assertEquals("0.01", testRepo.list("transaction").get(0).getString("amount"));
    click("Budgets tab");
    waitFor("+ Add Category Budget");
    click("Shared tab");
    waitFor("+ Add Split");
    click("Home tab");
    click("⚙");
    waitFor("Open Notifications");
  }

  @Test
  public void otherCategoryRequiresNameAndBackNavigates() throws Exception {
    click("Transactions tab");
    click("+ Add Transaction");
    enter("Merchant / Payee", "Synthetic merchant");
    enter("Amount (₹)", "10.50");
    instrument.runOnMainSync(
        () -> {
          Spinner spinner = (Spinner) lookup("Category", Spinner.class);
          for (int i = 0; i < spinner.getCount(); i++)
            if (spinner.getItemAtPosition(i).equals("Other")) spinner.setSelection(i);
        });
    instrument.waitForIdleSync();
    click("Save Transaction");
    assertTrue(testRepo.list("transaction").isEmpty());
    enter("Name of other category", "Pet care");
    click("Save Transaction");
    waitFor("Transactions tab");
    assertEquals("Pet care", testRepo.list("transaction").get(0).getString("category"));
    click("Home tab");
    click("⚙");
    click("Open Privacy & Storage");
    click("←");
    waitFor("Open Notifications");
  }

  @Test
  public void takeoutFileRequiresOwnerAndPreviewBeforeWriting() throws Exception {
    JSONObject item =
        new JSONObject().put("payer", "Owner").put("amount", "₹25.50").put("state", "UNPAID");
    JSONObject expense =
        new JSONObject()
            .put("creator", "Friend")
            .put("group_name", "Synthetic trip")
            .put("title", "Dinner")
            .put("total_amount", "₹25.50")
            .put("creation_time", "2026-10-20T13:30:00Z")
            .put("state", "ONGOING")
            .put("items", new JSONArray().put(item));
    fixture = java.io.File.createTempFile("synthetic-takeout-", ".json", activity.getCacheDir());
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(fixture)) {
      out.write(
          new JSONObject()
              .put("Group_expenses", new JSONArray().put(expense))
              .toString()
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    Intent result = new Intent().setData(android.net.Uri.fromFile(fixture));
    instrument.runOnMainSync(() -> activity.onActivityResult(102, Activity.RESULT_OK, result));
    waitFor("Your Takeout participant name");
    assertTrue(testRepo.list("split").isEmpty());
    enter("Your Takeout participant name", "Owner");
    click("Preview Group Expenses");
    waitFor("Import Selected Splits");
    assertTrue(testRepo.list("split").isEmpty());
    click("Cancel Import");
    waitFor("Choose Transaction / Group Expenses File");
    assertTrue(testRepo.list("split").isEmpty());
  }
}
