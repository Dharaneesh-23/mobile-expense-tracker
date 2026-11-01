package com.dharaneesh.expensetracker.data;

import android.content.Context;
import androidx.room.Room;
import com.dharaneesh.expensetracker.parser.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.json.*;

public final class Repository {
  public final AppDatabase db;
  public final Vault vault;
  private final Context context;

  public Repository(Context c) {
    this(c, Room.databaseBuilder(c, AppDatabase.class, "expense-v1.db").build());
  }

  public Repository(Context c, AppDatabase database) {
    context = c;
    vault = new Vault();
    db = database;
  }

  public JSONObject unpack(Record row) {
    try {
      return new JSONObject(vault.decrypt(row.encrypted)).put("id", row.id).put("kind", row.kind);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<JSONObject> list(String kind) {
    List<JSONObject> out = new ArrayList<>();
    for (Record r : db.records().all())
      if (kind.isEmpty() || r.kind.equals(kind)) out.add(unpack(r));
    return out;
  }

  public synchronized void save(JSONObject data) {
    try {
      String id = data.optString("id", UUID.randomUUID().toString());
      Record old = db.records().get(id);
      Record r = new Record();
      r.id = id;
      r.kind = data.getString("kind");
      r.updatedAt = Math.max(System.currentTimeMillis(), old == null ? 0 : old.updatedAt + 1);
      r.encrypted = vault.encrypt(data.toString());
      db.records().put(r);
      if (r.kind.equals("transaction") && data.optString("direction").equals("debit"))
        checkBudget(data.optString("category"));
      if (r.kind.equals("budget")) checkBudget(data.optString("category"));
      if (r.kind.equals("transaction") && old != null) {
        JSONObject previous = unpack(old);
        if (!previous.optString("category").equals(data.optString("category"))) {
          android.content.SharedPreferences prefs = context.getSharedPreferences("settings", 0);
          String encryptedRules = prefs.getString("category_rules", "");
          JSONObject rules =
              encryptedRules.isEmpty()
                  ? new JSONObject()
                  : new JSONObject(vault.decrypt(encryptedRules));
          rules.put(
              data.optString("payee").trim().toLowerCase(Locale.ROOT), data.optString("category"));
          prefs.edit().putString("category_rules", vault.encrypt(rules.toString())).apply();
        }
      }
    } catch (JSONException ex) {
      throw new IllegalArgumentException("Invalid record", ex);
    }
  }

  public synchronized void delete(String id) {
    Record r = db.records().get(id);
    if (r == null) return;
    r.deleted = true;
    r.dirty = true;
    r.updatedAt = Math.max(System.currentTimeMillis(), r.updatedAt + 1);
    db.records().put(r);
  }

  public static String category(String payee) {
    String s = payee.toLowerCase(Locale.ROOT);
    if (s.matches(".*(swiggy|zomato|restaurant|cafe|breakfast|dinner|lunch).*")) return "Food";
    if (s.matches(".*(uber|ola|cab|metro|rail).*")) return "Travel";
    if (s.matches(".*(electric|airtel|jio|broadband|rent).*")) return "Bills";
    return "Uncategorized";
  }

  public synchronized boolean capture(
      String title, String body, String source, long timestamp, String eventId) {
    ParsedTransaction p = ParserEngine.parse(title, body, source, timestamp);
    try {
      if (p == null) {
        if (source.equals("sms") && !body.matches("(?is).*(HDFC Bank|Union Bank of India).*"))
          return false;
        if (body.matches("(?is).*\\b(otp|password|verification code)\\b.*")) return false;
        for (JSONObject item : list("review"))
          if (item.optString("event_id").equals(eventId)) return false;
        save(
            new JSONObject()
                .put("kind", "review")
                .put("title", title)
                .put("body", body)
                .put("source", source)
                .put("event_id", eventId)
                .put("occurred_at", timestamp));
        return false;
      }
      String kind = p.split ? "split" : "transaction";
      for (JSONObject item : list(kind)) {
        if (!eventId.isEmpty() && item.optString("event_id").equals(eventId)) return false;
        if (!p.reference.isEmpty()
            && item.optString("reference").equals(p.reference)
            && item.optString("direction").equals(p.direction)) return false;
        // Amount/time alone are insufficient: require a matching named counterparty and opposite
        // capture sources.
        if (!p.split
            && p.reference.isEmpty()
            && !p.dateInferred
            && !item.optBoolean("date_inferred")
            && !p.payee.equals("Unknown")
            && item.optString("payee").equalsIgnoreCase(p.payee)
            && item.optString("direction").equals(p.direction)
            && !item.optString("source").equals(source)
            && Math.abs(item.optLong("occurred_at") - p.occurredAt) < 120000
            && new BigDecimal(item.optString("amount", "0")).compareTo(p.amount) == 0) return false;
      }
      JSONObject data =
          new JSONObject()
              .put("kind", kind)
              .put("amount", p.amount.toPlainString())
              .put("direction", p.direction)
              .put("payee", p.payee)
              .put("bank", p.bank)
              .put("account_last4", p.accountLast4)
              .put("reference", p.reference)
              .put("source", source)
              .put("occurred_at", p.occurredAt)
              .put("date_inferred", p.dateInferred)
              .put("category", learnedCategory(p.payee, p.note))
              .put("note", p.note)
              .put("group", p.group)
              .put("group_incomplete", p.groupIncomplete)
              .put("status", "pending")
              .put("event_id", eventId);
      save(data);
      return true;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private String learnedCategory(String payee, String note) throws JSONException {
    String rules = context.getSharedPreferences("settings", 0).getString("category_rules", "");
    if (!rules.isEmpty()) {
      JSONObject map = new JSONObject(vault.decrypt(rules));
      String key = payee.trim().toLowerCase(Locale.ROOT);
      if (map.has(key)) return map.getString(key);
    }
    return category(payee + " " + note);
  }

  private void checkBudget(String category) {
    BigDecimal spent = BigDecimal.ZERO;
    YearMonth month = YearMonth.now(ZoneId.of("Asia/Kolkata"));
    for (JSONObject t : list("transaction"))
      if (t.optString("direction").equals("debit")
          && t.optString("category").equals(category)
          && YearMonth.from(
                  Instant.ofEpochMilli(t.optLong("occurred_at")).atZone(ZoneId.of("Asia/Kolkata")))
              .equals(month)) spent = spent.add(new BigDecimal(t.optString("amount", "0")));
    for (JSONObject b : list("budget")) {
      if (!b.optString("category").equals(category)) continue;
      String key = "budget-alert:" + category.hashCode() + ":" + month;
      android.content.SharedPreferences prefs = context.getSharedPreferences("settings", 0);
      boolean reached = spent.compareTo(new BigDecimal(b.optString("amount", "0"))) >= 0;
      if (reached && !prefs.getBoolean(key, false))
        com.dharaneesh.expensetracker.ExpenseApp.budgetAlert(context, category);
      prefs.edit().putBoolean(key, reached).apply();
    }
  }
}
