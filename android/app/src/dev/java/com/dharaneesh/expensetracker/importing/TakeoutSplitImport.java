package com.dharaneesh.expensetracker.importing;

import com.dharaneesh.expensetracker.data.Record;
import com.dharaneesh.expensetracker.data.Repository;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** Explicit owner mapping for Google Pay's Group_expenses export; never creates transactions. */
public final class TakeoutSplitImport {
  public static final class Plan {
    public final List<JSONObject> splits = new ArrayList<>();
    public final Map<String, Integer> skipped = new LinkedHashMap<>();
    public final String owner;
    public int expenseCount, existingCount;

    Plan(String owner) {
      this.owner = owner;
    }

    void skip(String reason) {
      skipped.put(reason, skipped.getOrDefault(reason, 0) + 1);
    }
  }

  public static String key(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFKC)
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static String id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static JSONArray expenses(String json) throws Exception {
    if (json.length() > 5_000_000) throw new IllegalArgumentException("Import limit is 5 MB");
    JSONObject root = new JSONObject(json.startsWith("\uFEFF") ? json.substring(1) : json);
    JSONArray rows = root.optJSONArray("Group_expenses");
    if (rows == null || rows.length() > 10000)
      throw new IllegalArgumentException(
          "Expected a Group_expenses array with at most 10,000 expenses");
    return rows;
  }

  public static List<String> participants(String json) throws Exception {
    Map<String, String> names = new TreeMap<>();
    JSONArray rows = expenses(json);
    for (int n = 0; n < rows.length(); n++) {
      JSONObject r = rows.optJSONObject(n);
      if (r == null) continue;
      String creator = r.optString("creator", "").trim();
      if (!creator.isEmpty()) names.putIfAbsent(key(creator), creator);
      JSONArray items = r.optJSONArray("items");
      if (items == null) continue;
      for (int j = 0; j < items.length(); j++) {
        JSONObject item = items.optJSONObject(j);
        if (item == null) continue;
        String name = item.optString("payer", "").trim();
        if (!name.isEmpty()) names.putIfAbsent(key(name), name);
      }
    }
    return new ArrayList<>(names.values());
  }

  private static BigDecimal amount(String value) {
    BigDecimal n =
        new BigDecimal(value.replace("₹", "").replace(",", "").trim())
            .setScale(2, RoundingMode.UNNECESSARY);
    if (n.signum() <= 0 || n.compareTo(new BigDecimal("9999999999.99")) > 0)
      throw new IllegalArgumentException("Invalid share amount");
    return n;
  }

  public static Plan preview(String json, String owner) throws Exception {
    if (key(owner).isEmpty()
        || !participants(json).stream().anyMatch(n -> key(n).equals(key(owner))))
      throw new IllegalArgumentException("Select your exact participant name from this export");
    Plan plan = new Plan(owner);
    JSONArray rows = expenses(json);
    plan.expenseCount = rows.length();
    Set<String> seen = new HashSet<>();
    for (int n = 0; n < rows.length(); n++) {
      JSONObject r = rows.optJSONObject(n);
      if (r == null) {
        plan.skip("Invalid expense");
        continue;
      }
      try {
        String creator = r.getString("creator").trim(),
            group = r.getString("group_name").trim(),
            title = r.getString("title").trim(),
            expenseState = r.getString("state");
        long at = Instant.parse(r.getString("creation_time")).toEpochMilli();
        if (creator.isEmpty() || group.isEmpty() || at < 0 || at > 32503680000000L)
          throw new IllegalArgumentException();
        amount(r.getString("total_amount"));
        if (!Arrays.asList("ONGOING", "COMPLETED", "CLOSED").contains(expenseState)) {
          plan.skip("Unknown expense state");
          continue;
        }
        JSONArray items = r.getJSONArray("items");
        boolean ownerCreated = key(creator).equals(key(owner));
        // Duplicate participant names within one expense cannot be mapped unambiguously.
        Map<String, Integer> occurrences = new HashMap<>();
        for (int j = 0; j < items.length(); j++) {
          JSONObject item = items.optJSONObject(j);
          if (item != null) {
            String name = key(item.optString("payer", ""));
            occurrences.put(name, occurrences.getOrDefault(name, 0) + 1);
          }
        }
        for (int j = 0; j < items.length(); j++) {
          JSONObject item = items.optJSONObject(j);
          if (item == null) {
            plan.skip("Invalid share");
            continue;
          }
          String payer = item.optString("payer", "").trim(), state = item.optString("state", "");
          if (payer.isEmpty()) {
            plan.skip("Blank participant");
            continue;
          }
          if (occurrences.get(key(payer)) > 1) {
            plan.skip("Ambiguous repeated participant");
            continue;
          }
          if (ownerCreated && key(payer).equals(key(owner))) {
            plan.skip("Your own share of your request");
            continue;
          }
          if (!ownerCreated && !key(payer).equals(key(owner))) {
            plan.skip("Another participant's share");
            continue;
          }
          if (state.equals("FAILED")) {
            plan.skip("Failed share");
            continue;
          }
          boolean settled = state.equals("PAID_RECEIVED") || state.equals("MARK_AS_PAID");
          if (!settled && !state.equals("UNPAID")) {
            plan.skip("Unknown share state");
            continue;
          }
          if (expenseState.equals("CLOSED") && !settled) {
            plan.skip("Closed unpaid request - review manually");
            continue;
          }
          if (expenseState.equals("COMPLETED") && !settled) {
            plan.skip("Completed request with unpaid share - review manually");
            continue;
          }
          try {
            String counterparty = ownerCreated ? payer : creator;
            String origin =
                id(
                    "gpay-expense|"
                        + r.getString("creation_time")
                        + "|"
                        + key(creator)
                        + "|"
                        + key(group)
                        + "|"
                        + key(title));
            String recordId = id("gpay-share|" + key(owner) + "|" + origin + "|" + key(payer));
            if (!seen.add(recordId)) {
              plan.skip("Repeated source share");
              continue;
            }
            plan.splits.add(
                new JSONObject()
                    .put("id", recordId)
                    .put("kind", "split")
                    .put("group", group)
                    .put("payee", counterparty)
                    .put("amount", amount(item.getString("amount")).toPlainString())
                    .put("direction", ownerCreated ? "they_owe_you" : "you_owe_them")
                    .put("status", settled ? "settled" : "pending")
                    .put("note", title)
                    .put("occurred_at", at)
                    .put("group_incomplete", false)
                    .put("source", "import")
                    .put("import_format", "gpay_group_expenses")
                    .put("import_owner", key(owner))
                    .put("import_origin", origin)
                    .put("takeout_expense_state", expenseState)
                    .put("takeout_share_state", state));
          } catch (Exception invalid) {
            plan.skip("Invalid share amount");
          }
        }
      } catch (Exception invalid) {
        plan.skip("Invalid expense fields or date");
      }
    }
    return plan;
  }

  /** Retain duplicates, local corrections, settled status, and deletion tombstones unchanged. */
  public static int apply(Repository repo, Plan plan, Set<String> selected) throws Exception {
    final int[] added = {0};
    synchronized (repo) {
      repo.db.runInTransaction(
          () -> {
            for (JSONObject row : plan.splits) {
              String recordId = row.optString("id");
              if (!selected.contains(recordId)) continue;
              Record existing = repo.db.records().get(recordId);
              if (existing != null) continue;
              repo.save(row);
              added[0]++;
            }
          });
    }
    return added[0];
  }

  private TakeoutSplitImport() {}
}
