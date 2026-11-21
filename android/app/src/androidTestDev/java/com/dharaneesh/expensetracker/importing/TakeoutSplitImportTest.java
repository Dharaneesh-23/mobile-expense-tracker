package com.dharaneesh.expensetracker.importing;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dharaneesh.expensetracker.data.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TakeoutSplitImportTest {
  private Repository repo;

  @Before
  public void setup() {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    repo = new Repository(c, Room.inMemoryDatabaseBuilder(c, AppDatabase.class).build());
  }

  @After
  public void close() {
    repo.db.close();
  }

  private JSONObject item(String name, String amount, String state) throws Exception {
    return new JSONObject().put("payer", name).put("amount", amount).put("state", state);
  }

  private JSONObject expense(String creator, String state, JSONObject... shares) throws Exception {
    return new JSONObject()
        .put("creator", creator)
        .put("group_name", "Sample trip")
        .put("title", "Dinner")
        .put("creation_time", "2026-10-20T13:30:00Z")
        .put("total_amount", "₹100.00")
        .put("state", state)
        .put("items", new JSONArray(Arrays.asList(shares)));
  }

  private String file(JSONObject... expenses) throws Exception {
    return new JSONObject()
        .put("Group_expenses", new JSONArray(Arrays.asList(expenses)))
        .toString();
  }

  private Set<String> ids(TakeoutSplitImport.Plan p) {
    Set<String> ids = new HashSet<>();
    for (JSONObject r : p.splits) ids.add(r.optString("id"));
    return ids;
  }

  @Test
  public void creatorImportsOthersSharesButNotTheirOwn() throws Exception {
    TakeoutSplitImport.Plan p =
        TakeoutSplitImport.preview(
            file(
                expense(
                    "Owner",
                    "ONGOING",
                    item("Owner", "₹50.00", "UNPAID"),
                    item("Friend", "₹50.00", "UNPAID"))),
            "Owner");
    assertEquals(1, p.splits.size());
    JSONObject s = p.splits.get(0);
    assertEquals("Friend", s.getString("payee"));
    assertEquals("they_owe_you", s.getString("direction"));
    assertEquals("pending", s.getString("status"));
    assertEquals("50.00", s.getString("amount"));
  }

  @Test
  public void participantImportsOnlyTheirOwnShareToCreator() throws Exception {
    TakeoutSplitImport.Plan p =
        TakeoutSplitImport.preview(
            file(
                expense(
                    "Friend",
                    "ONGOING",
                    item("Other", "₹10.00", "UNPAID"),
                    item("Owner", "₹25.50", "UNPAID"))),
            "Owner");
    assertEquals(1, p.splits.size());
    assertEquals("Friend", p.splits.get(0).getString("payee"));
    assertEquals("you_owe_them", p.splits.get(0).getString("direction"));
  }

  @Test
  public void ambiguousFailedAndClosedUnpaidAreSkippedWhilePaidHistoryRemains() throws Exception {
    String json =
        file(
            expense(
                "Owner",
                "CLOSED",
                item("Blank", "₹10", "FAILED"),
                item("", "₹20", "UNPAID"),
                item("Closed", "₹20", "UNPAID"),
                item("Paid", "₹20", "PAID_RECEIVED"),
                item("Manual", "₹20", "MARK_AS_PAID"),
                item("Same", "₹1", "UNPAID"),
                item("Same", "₹2", "UNPAID")));
    TakeoutSplitImport.Plan p = TakeoutSplitImport.preview(json, "Owner");
    assertEquals(2, p.splits.size());
    for (JSONObject s : p.splits) assertEquals("settled", s.getString("status"));
    assertTrue(p.skipped.containsKey("Blank participant"));
    assertTrue(p.skipped.containsKey("Closed unpaid request - review manually"));
    assertTrue(p.skipped.containsKey("Ambiguous repeated participant"));
  }

  @Test
  public void repeatImportPreservesCorrectionsAndDeletedSharesAndNeverCreatesTransactions()
      throws Exception {
    String json =
        file(expense("Owner", "ONGOING", item("A", "₹20", "UNPAID"), item("B", "₹30", "UNPAID")));
    TakeoutSplitImport.Plan p = TakeoutSplitImport.preview(json, "Owner");
    assertEquals(2, TakeoutSplitImport.apply(repo, p, ids(p)));
    JSONObject changed = repo.list("split").get(0);
    changed.put("note", "Manual correction").put("status", "settled");
    repo.save(changed);
    String deleted =
        p.splits.get(0).getString("id").equals(changed.getString("id"))
            ? p.splits.get(1).getString("id")
            : p.splits.get(0).getString("id");
    repo.delete(deleted);
    assertEquals(0, TakeoutSplitImport.apply(repo, p, ids(p)));
    assertEquals(1, repo.list("split").size());
    assertEquals("Manual correction", repo.list("split").get(0).getString("note"));
    assertTrue(repo.db.records().get(deleted).deleted);
    assertTrue(repo.list("transaction").isEmpty());
  }

  @Test
  public void stableIdentityIgnoresItemOrderingAndStatusChanges() throws Exception {
    String a =
        file(expense("Owner", "ONGOING", item("A", "₹20", "UNPAID"), item("B", "₹30", "UNPAID")));
    String b =
        file(
            expense(
                "Owner",
                "COMPLETED",
                item("B", "₹30", "PAID_RECEIVED"),
                item("A", "₹20", "MARK_AS_PAID")));
    assertEquals(
        ids(TakeoutSplitImport.preview(a, "owner")), ids(TakeoutSplitImport.preview(b, " OWNER ")));
    assertThrows(
        IllegalArgumentException.class, () -> TakeoutSplitImport.preview(a, "Not in export"));
  }

  @Test
  public void failedWriteRollsBackTheWholeImport() throws Exception {
    TakeoutSplitImport.Plan p =
        TakeoutSplitImport.preview(
            file(
                expense(
                    "Owner", "ONGOING", item("A", "₹20", "UNPAID"), item("B", "₹30", "UNPAID"))),
            "Owner");
    String second = p.splits.get(1).getString("id");
    repo.db
        .getOpenHelper()
        .getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER fail_import BEFORE INSERT ON records WHEN NEW.id = '"
                + second
                + "' BEGIN SELECT RAISE(ABORT, 'synthetic rollback'); END");
    assertThrows(Exception.class, () -> TakeoutSplitImport.apply(repo, p, ids(p)));
    assertTrue(repo.list("split").isEmpty());
  }
}
