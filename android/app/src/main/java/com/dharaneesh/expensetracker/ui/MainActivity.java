package com.dharaneesh.expensetracker.ui;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.work.*;
import com.dharaneesh.expensetracker.*;
import com.dharaneesh.expensetracker.data.Repository;
import com.dharaneesh.expensetracker.sync.SyncWorker;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  private final int GREEN = Color.rgb(23, 106, 87), INK = Color.rgb(27, 43, 37);
  private LinearLayout page, content;
  private String tab = "Overview";
  private Repository repo;
  private List<JSONObject> records = new ArrayList<>();
  private String query = "",
      direction = "All",
      source = "All",
      bank = "",
      category = "",
      from = "",
      to = "",
      min = "",
      max = "";
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private final String[] categories = {
    "Uncategorized", "Food", "Travel", "Bills", "Shopping", "Entertainment", "Other"
  };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    repo = ExpenseApp.repo(this);
    getWindow()
        .getDecorView()
        .setOnApplyWindowInsetsListener(
            (v, insets) -> {
              v.setPadding(
                  insets.getSystemWindowInsetLeft(),
                  insets.getSystemWindowInsetTop(),
                  insets.getSystemWindowInsetRight(),
                  insets.getSystemWindowInsetBottom());
              return insets.consumeSystemWindowInsets();
            });
    reload();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (repo != null) reload();
  }

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  private void reload() {
    ExpenseApp.IO.execute(
        () -> {
          try {
            List<JSONObject> loaded = repo.list("");
            runOnUiThread(
                () -> {
                  records = loaded;
                  render();
                });
          } catch (Exception e) {
            error(e);
          }
        });
  }

  private void error(Exception e) {
    runOnUiThread(
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Could not complete action")
                .setMessage(e.getMessage())
                .setPositiveButton("OK", null)
                .show());
  }

  private void task(Runnable job) {
    ExpenseApp.IO.execute(
        () -> {
          try {
            job.run();
            reload();
          } catch (Exception e) {
            error(e);
          }
        });
  }

  private List<JSONObject> kind(String kind) {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject o : records) if (o.optString("kind").equals(kind)) out.add(o);
    return out;
  }

  private TextView text(LinearLayout host, String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(INK);
    t.setPadding(0, dp(7), 0, dp(7));
    host.addView(t);
    return t;
  }

  private Button button(LinearLayout host, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextColor(GREEN);
    b.setAllCaps(false);
    host.addView(b);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private LinearLayout box(LinearLayout host) {
    LinearLayout b = new LinearLayout(this);
    b.setOrientation(LinearLayout.VERTICAL);
    b.setPadding(dp(16), dp(8), dp(16), dp(8));
    b.setBackgroundColor(Color.WHITE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(7), 0, dp(7));
    host.addView(b, lp);
    return b;
  }

  private EditText field(LinearLayout host, String hint, String value) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setText(value);
    e.setSingleLine();
    e.setTextSize(15);
    host.addView(e);
    return e;
  }

  private String money(String amount) {
    try {
      return "₹" + new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    } catch (Exception e) {
      return "₹0.00";
    }
  }

  private String date(long millis) {
    return Instant.ofEpochMilli(millis)
        .atZone(IST)
        .format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm"));
  }

  private BigDecimal amount(JSONObject o) {
    return new BigDecimal(o.optString("amount", "0"));
  }

  private void render() {
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setBackgroundColor(Color.rgb(245, 247, 244));
    page.setPadding(dp(18), dp(8), dp(18), 0);
    setContentView(page);
    text(page, "EXPENSE / " + BuildConfig.FLAVOR.toUpperCase(Locale.ROOT), 12);
    text(page, tab, 30).setTypeface(null, Typeface.BOLD);
    HorizontalScrollView nav = new HorizontalScrollView(this);
    LinearLayout links = new LinearLayout(this);
    nav.addView(links);
    page.addView(nav);
    for (String name :
        new String[] {"Overview", "Transactions", "Budgets", "Splits", "Reports", "Settings"}) {
      Button b =
          button(
              links,
              name,
              () -> {
                tab = name;
                render();
              });
      if (name.equals(tab)) b.setTypeface(null, Typeface.BOLD);
    }
    ScrollView scroll = new ScrollView(this);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(content);
    switch (tab) {
      case "Transactions":
        transactions();
        break;
      case "Budgets":
        budgets();
        break;
      case "Splits":
        splits();
        break;
      case "Reports":
        reports();
        break;
      case "Settings":
        settings();
        break;
      default:
        overview();
    }
  }

  private void overview() {
    BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
    YearMonth month = YearMonth.now(IST);
    for (JSONObject t : kind("transaction"))
      if (YearMonth.from(Instant.ofEpochMilli(t.optLong("occurred_at")).atZone(IST))
          .equals(month)) {
        if (t.optString("direction").equals("debit")) debit = debit.add(amount(t));
        else credit = credit.add(amount(t));
      }
    LinearLayout summary = box(content);
    text(summary, month.getMonth().toString() + " · " + month.getYear(), 13);
    text(summary, money(debit.toPlainString()), 38);
    text(summary, "Spent this month", 15);
    text(
        summary,
        "Received "
            + money(credit.toPlainString())
            + "  ·  Net "
            + money(credit.subtract(debit).toPlainString()),
        15);
    button(content, "+ Add a transaction", () -> editTransaction(null));
    if (BuildConfig.FLAVOR.equals("qa") || BuildConfig.FLAVOR.equals("prod")) {
      button(
          content,
          "App updates",
          () -> new com.dharaneesh.expensetracker.update.AppUpdater(this).open());
    }
    text(content, "Recent activity", 21);
    List<JSONObject> recent = kind("transaction");
    recent.sort((a, b) -> Long.compare(b.optLong("occurred_at"), a.optLong("occurred_at")));
    if (recent.isEmpty())
      empty(
          "Your expenses start here",
          "Add an entry or enable capture in Settings. No sample transactions are added to your"
              + " finances.");
    for (int i = 0; i < Math.min(6, recent.size()); i++) transactionCard(recent.get(i));
    if (!getSharedPreferences("settings", 0).getBoolean("capture_consent", false))
      button(
          content,
          "Set up automatic capture",
          () -> {
            tab = "Settings";
            render();
          });
  }

  private void empty(String title, String detail) {
    LinearLayout card = box(content);
    text(card, title, 20);
    text(card, detail, 15);
  }

  private List<JSONObject> filtered() {
    List<JSONObject> out = new ArrayList<>();
    for (JSONObject t : kind("transaction")) {
      if (!query.isEmpty()
          && !(t.optString("payee") + " " + t.optString("note"))
              .toLowerCase(Locale.ROOT)
              .contains(query.toLowerCase(Locale.ROOT))) continue;
      if (!direction.equals("All") && !t.optString("direction").equals(direction)) continue;
      if (!source.equals("All") && !t.optString("source").equals(source)) continue;
      if (!bank.isEmpty()
          && !t.optString("bank").toLowerCase(Locale.ROOT).contains(bank.toLowerCase(Locale.ROOT)))
        continue;
      if (!category.isEmpty() && !t.optString("category").equalsIgnoreCase(category)) continue;
      LocalDate d = Instant.ofEpochMilli(t.optLong("occurred_at")).atZone(IST).toLocalDate();
      if (!from.isEmpty() && d.isBefore(LocalDate.parse(from))) continue;
      if (!to.isEmpty() && d.isAfter(LocalDate.parse(to))) continue;
      if (!min.isEmpty() && amount(t).compareTo(new BigDecimal(min)) < 0) continue;
      if (!max.isEmpty() && amount(t).compareTo(new BigDecimal(max)) > 0) continue;
      out.add(t);
    }
    out.sort((a, b) -> Long.compare(b.optLong("occurred_at"), a.optLong("occurred_at")));
    return out;
  }

  private void transactions() {
    button(content, "+ Add transaction", () -> editTransaction(null));
    button(content, "Search & filters", this::filters);
    List<JSONObject> list = filtered();
    text(content, list.size() + " transactions", 14);
    if (list.isEmpty()) empty("No matching transactions", "Try changing filters or add an entry.");
    for (JSONObject t : list) transactionCard(t);
  }

  private void filters() {
    LinearLayout form = new LinearLayout(this);
    form.setPadding(dp(20), 0, dp(20), 0);
    form.setOrientation(LinearLayout.VERTICAL);
    EditText q = field(form, "Merchant or note", query),
        b = field(form, "Bank contains", bank),
        cat = field(form, "Category", category),
        d = field(form, "Direction: All / debit / credit", direction),
        s = field(form, "Source: All / sms / gpay / manual / import", source);
    EditText f = field(form, "From YYYY-MM-DD", from),
        t = field(form, "To YYYY-MM-DD", to),
        lo = field(form, "Minimum amount", min),
        hi = field(form, "Maximum amount", max);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Filter transactions")
            .setView(form)
            .setPositiveButton("Apply", null)
            .setNeutralButton(
                "Reset",
                (x, w) -> {
                  query = bank = category = from = to = min = max = "";
                  direction = source = "All";
                  render();
                })
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        if (!f.getText().toString().isEmpty()) {
                          LocalDate.parse(f.getText().toString());
                        }
                        if (!t.getText().toString().isEmpty()) {
                          LocalDate.parse(t.getText().toString());
                        }
                        if (!lo.getText().toString().isEmpty()) {
                          new BigDecimal(lo.getText().toString());
                        }
                        if (!hi.getText().toString().isEmpty()) {
                          new BigDecimal(hi.getText().toString());
                        }
                        query = q.getText().toString();
                        bank = b.getText().toString();
                        category = cat.getText().toString();
                        direction = d.getText().toString();
                        source = s.getText().toString();
                        from = f.getText().toString();
                        to = t.getText().toString();
                        min = lo.getText().toString();
                        max = hi.getText().toString();
                        dialog.dismiss();
                        render();
                      } catch (Exception ex) {
                        f.setError("Check dates and amounts");
                      }
                    }));
    dialog.show();
  }

  private void transactionCard(JSONObject t) {
    LinearLayout card = box(content);
    text(card, t.optString("payee", "Unknown"), 19).setTypeface(null, Typeface.BOLD);
    text(
        card,
        (t.optString("direction").equals("debit") ? "− " : "+ ") + money(t.optString("amount")),
        24);
    text(
        card,
        t.optString("category")
            + " · "
            + t.optString("source")
            + " · "
            + date(t.optLong("occurred_at")),
        13);
    if (!t.optString("bank").isEmpty())
      text(card, t.optString("bank") + " • " + t.optString("account_last4"), 13);
    if (!t.optString("note").isEmpty()) text(card, t.optString("note"), 14);
    card.setOnClickListener(v -> editTransaction(t));
  }

  private void editTransaction(JSONObject existing) {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), 0, dp(20), 0);
    EditText payee =
        field(form, "Merchant / person", existing == null ? "" : existing.optString("payee"));
    EditText value = field(form, "Amount ₹", existing == null ? "" : existing.optString("amount"));
    value.setInputType(8194);
    Spinner dir = new Spinner(this);
    dir.setAdapter(
        new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, new String[] {"debit", "credit"}));
    form.addView(dir);
    if (existing != null && existing.optString("direction").equals("credit")) dir.setSelection(1);
    Spinner cat = new Spinner(this);
    cat.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));
    form.addView(cat);
    if (existing != null)
      cat.setSelection(
          Math.max(0, Arrays.asList(categories).indexOf(existing.optString("category"))));
    EditText account = field(form, "Bank name", existing == null ? "" : existing.optString("bank")),
        last =
            field(
                form,
                "Account last four (optional)",
                existing == null ? "" : existing.optString("account_last4"));
    EditText day =
        field(
            form,
            "Date YYYY-MM-DD",
            existing == null
                ? LocalDate.now(IST).toString()
                : Instant.ofEpochMilli(existing.optLong("occurred_at"))
                    .atZone(IST)
                    .toLocalDate()
                    .toString());
    EditText note = field(form, "Note", existing == null ? "" : existing.optString("note"));
    if (existing != null) text(form, "Reference: " + existing.optString("reference", "—"), 12);
    ScrollView sc = new ScrollView(this);
    sc.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "Add transaction" : "Edit transaction")
            .setView(sc)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    if (existing != null) {
      button(
          form,
          "Split this expense",
          () -> {
            dialog.dismiss();
            editSplit(existing);
          });
      button(
          form,
          "Delete transaction",
          () ->
              new AlertDialog.Builder(this)
                  .setMessage("Delete this transaction?")
                  .setPositiveButton(
                      "Delete",
                      (d, w) -> {
                        task(() -> repo.delete(existing.optString("id")));
                        dialog.dismiss();
                      })
                  .setNegativeButton("Cancel", null)
                  .show());
    }
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        BigDecimal amt =
                            new BigDecimal(value.getText().toString())
                                .setScale(2, RoundingMode.UNNECESSARY);
                        if (amt.signum() <= 0 || amt.compareTo(new BigDecimal("9999999999.99")) > 0)
                          throw new IllegalArgumentException();
                        if (payee.getText().toString().trim().isEmpty())
                          throw new IllegalArgumentException();
                        String last4 = last.getText().toString().trim();
                        if (!last4.isEmpty() && !last4.matches("\\d{4}"))
                          throw new IllegalArgumentException();
                        LocalDate chosen = LocalDate.parse(day.getText().toString());
                        long at = chosen.atStartOfDay(IST).toInstant().toEpochMilli();
                        if (existing != null
                            && chosen.equals(
                                Instant.ofEpochMilli(existing.optLong("occurred_at"))
                                    .atZone(IST)
                                    .toLocalDate())) at = existing.optLong("occurred_at");
                        JSONObject data =
                            existing == null
                                ? new JSONObject().put("source", "manual")
                                : new JSONObject(existing.toString());
                        data.put("kind", "transaction")
                            .put("payee", payee.getText().toString().trim())
                            .put("amount", amt.toPlainString())
                            .put("direction", dir.getSelectedItem())
                            .put("category", cat.getSelectedItem())
                            .put("bank", account.getText().toString())
                            .put("account_last4", last4)
                            .put("occurred_at", at)
                            .put("note", note.getText().toString());
                        task(() -> repo.save(data));
                        dialog.dismiss();
                      } catch (Exception ex) {
                        value.setError(
                            "Use a positive amount, merchant, valid date and 4-digit account"
                                + " suffix");
                      }
                    }));
    dialog.show();
  }

  private void budgets() {
    button(content, "+ Monthly category budget", this::editBudget);
    List<JSONObject> budgets = kind("budget");
    if (budgets.isEmpty())
      empty("Give your spending a plan", "Create a monthly limit for a category.");
    for (JSONObject budget : budgets) {
      BigDecimal spent = BigDecimal.ZERO;
      for (JSONObject t : kind("transaction"))
        if (t.optString("category").equals(budget.optString("category"))
            && t.optString("direction").equals("debit")
            && YearMonth.from(Instant.ofEpochMilli(t.optLong("occurred_at")).atZone(IST))
                .equals(YearMonth.now(IST))) spent = spent.add(amount(t));
      LinearLayout card = box(content);
      text(card, budget.optString("category"), 21);
      text(card, money(spent.toPlainString()) + " of " + money(budget.optString("amount")), 19);
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setProgress(
          Math.min(
              100,
              spent
                  .multiply(new BigDecimal(100))
                  .divide(amount(budget), 0, RoundingMode.DOWN)
                  .intValue()));
      card.addView(progress);
      button(card, "Remove limit", () -> task(() -> repo.delete(budget.optString("id"))));
    }
  }

  private void editBudget() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), 0, dp(20), 0);
    Spinner cat = new Spinner(this);
    cat.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));
    form.addView(cat);
    EditText amt = field(form, "Monthly limit ₹", "");
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("Monthly budget")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        BigDecimal n =
                            new BigDecimal(amt.getText().toString())
                                .setScale(2, RoundingMode.UNNECESSARY);
                        if (n.signum() <= 0) throw new IllegalArgumentException();
                        JSONObject b =
                            new JSONObject()
                                .put("kind", "budget")
                                .put("category", cat.getSelectedItem())
                                .put("amount", n.toPlainString())
                                .put("period", "monthly");
                        for (JSONObject old : kind("budget"))
                          if (old.optString("category").equals(cat.getSelectedItem()))
                            b.put("id", old.optString("id"));
                        task(() -> repo.save(b));
                        d.dismiss();
                      } catch (Exception e) {
                        amt.setError("Enter a positive amount");
                      }
                    }));
    d.show();
  }

  private void splits() {
    button(content, "+ Add an obligation", () -> editSplit(null));
    List<JSONObject> all = kind("split");
    if (all.isEmpty())
      empty("No outstanding splits", "Add one manually or enable GPay notification capture.");
    for (JSONObject split : all) {
      LinearLayout card = box(content);
      text(card, split.optString("group", "Personal"), 20);
      text(card, split.optString("payee") + " · " + money(split.optString("amount")), 20);
      text(
          card,
          (split.optString("direction").equals("you_owe_them") ? "You owe" : "They owe you")
              + " · "
              + split.optString("status"),
          15);
      text(card, split.optString("note"), 14);
      if (split.optBoolean("group_incomplete"))
        text(card, "Group title was truncated. Rename it to confirm.", 13);
      button(card, "Edit / settle", () -> editSplit(split));
    }
  }

  private void editSplit(JSONObject original) {
    boolean editing = original != null && original.optString("kind").equals("split");
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), 0, dp(20), 0);
    EditText group = field(form, "Group", editing ? original.optString("group") : "Personal"),
        person = field(form, "Person", editing ? original.optString("payee") : ""),
        amt = field(form, "Their share ₹", original == null ? "" : original.optString("amount")),
        note = field(form, "Description", original == null ? "" : original.optString("note"));
    Spinner direction = new Spinner(this);
    direction.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"they_owe_you", "you_owe_them"}));
    form.addView(direction);
    if (editing && original.optString("direction").equals("you_owe_them"))
      direction.setSelection(1);
    CheckBox settled = new CheckBox(this);
    settled.setText("Settled");
    settled.setChecked(editing && original.optString("status").equals("settled"));
    form.addView(settled);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("Split obligation")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    w -> {
                      try {
                        BigDecimal value =
                            new BigDecimal(amt.getText().toString())
                                .setScale(2, RoundingMode.UNNECESSARY);
                        if (value.signum() <= 0
                            || person.getText().toString().trim().isEmpty()
                            || group.getText().toString().trim().isEmpty())
                          throw new IllegalArgumentException();
                        JSONObject s =
                            editing ? new JSONObject(original.toString()) : new JSONObject();
                        s.put("kind", "split")
                            .put("group", group.getText().toString())
                            .put("payee", person.getText().toString())
                            .put("amount", value.toPlainString())
                            .put("note", note.getText().toString())
                            .put("direction", direction.getSelectedItem())
                            .put("status", settled.isChecked() ? "settled" : "pending")
                            .put("group_incomplete", false)
                            .put(
                                "occurred_at",
                                editing
                                    ? original.optLong("occurred_at")
                                    : System.currentTimeMillis());
                        if (original != null && !editing)
                          s.put("linked_transaction_id", original.optString("id"));
                        task(() -> repo.save(s));
                        d.dismiss();
                      } catch (Exception e) {
                        amt.setError("Enter a positive share, group and person");
                      }
                    }));
    d.show();
  }

  private void reports() {
    text(content, "Export a private PDF", 23);
    text(
        content,
        "Uses the current transaction filters. Your report is generated on this device and saved"
            + " only where you choose.",
        15);
    button(content, "Set report filters", this::filters);
    button(
        content,
        "Save PDF report",
        () -> {
          Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
          i.setType("application/pdf");
          i.putExtra(Intent.EXTRA_TITLE, "expenses-" + LocalDate.now() + ".pdf");
          startActivityForResult(i, 101);
        });
  }

  private void settings() {
    SharedPreferences prefs = getSharedPreferences("settings", 0);
    new com.dharaneesh.expensetracker.update.AppUpdater(this).addSettings(content);
    text(content, "Your data, your choice", 23);
    text(
        content,
        "Capture reads GPay notifications and supported bank SMS only after you enable it."
            + " Financial fields are encrypted with an Android Keystore key. Device backups are"
            + " disabled.",
        15);
    Switch consent = new Switch(this);
    consent.setText("Allow automatic transaction capture");
    consent.setChecked(prefs.getBoolean("capture_consent", false));
    content.addView(consent);
    consent.setOnCheckedChangeListener(
        (b, on) -> prefs.edit().putBoolean("capture_consent", on).apply());
    button(
        content,
        "Grant notification access",
        () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
    button(
        content,
        "Grant SMS & budget notification permissions",
        () -> {
          if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(
                new String[] {
                  Manifest.permission.READ_SMS,
                  Manifest.permission.RECEIVE_SMS,
                  Manifest.permission.POST_NOTIFICATIONS
                },
                1);
          else
            requestPermissions(
                new String[] {Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, 1);
        });
    button(
        content,
        "Import existing bank SMS",
        () -> {
          if (!prefs.getBoolean("capture_consent", false)) {
            Toast.makeText(this, "Enable capture consent first", Toast.LENGTH_LONG).show();
            return;
          }
          if (checkSelfPermission(Manifest.permission.READ_SMS)
              != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant SMS permission first", Toast.LENGTH_LONG).show();
            return;
          }
          new AlertDialog.Builder(this)
              .setMessage(
                  "Read existing HDFC and Union Bank messages and import recognized transactions?")
              .setPositiveButton("Import", (d, w) -> importSms())
              .setNegativeButton("Cancel", null)
              .show();
        });
    button(content, "Parse a message manually", this::parseMessage);
    button(
        content,
        "Import Google Takeout / CSV",
        () -> {
          Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          i.setType("*/*");
          i.addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(i, 102);
        });
    text(content, "Cloud sync", 23);
    text(content, prefs.getString("sync_status", "Not connected — offline mode"), 14);
    button(content, "Configure API / sign in", this::login);
    button(
        content,
        "Sync now",
        () -> {
          WorkManager.getInstance(this)
              .enqueueUniqueWork(
                  "sync-now",
                  ExistingWorkPolicy.KEEP,
                  new OneTimeWorkRequest.Builder(SyncWorker.class).build());
          Toast.makeText(this, "Sync queued", Toast.LENGTH_SHORT).show();
        });
    button(
        content,
        "Sign out",
        () -> {
          ExpenseApp.IO.execute(
              () -> {
                try {
                  String refresh = prefs.getString("refresh", "");
                  if (!refresh.isEmpty())
                    SyncWorker.request(
                        prefs.getString("api_url", ""),
                        "/api/v1/auth/logout",
                        "",
                        new JSONObject().put("refresh_token", repo.vault.decrypt(refresh)));
                } catch (Exception ignored) {
                } finally {
                  prefs
                      .edit()
                      .remove("access")
                      .remove("refresh")
                      .putString("sync_status", "Signed out — local data retained")
                      .apply();
                  reload();
                }
              });
        });
    List<JSONObject> review = kind("review");
    text(content, "Needs review · " + review.size(), 21);
    for (JSONObject r : review) {
      LinearLayout card = box(content);
      text(card, r.optString("title"), 16);
      text(card, r.optString("body"), 14);
      button(card, "Dismiss", () -> task(() -> repo.delete(r.optString("id"))));
    }
    text(
        content,
        "Personal-use preview · " + BuildConfig.VERSION_NAME + " · " + BuildConfig.FLAVOR,
        12);
  }

  private void parseMessage() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    EditText title = field(form, "Notification title (optional)", "");
    EditText body = field(form, "Paste bank SMS / GPay text", "");
    body.setSingleLine(false);
    body.setMinLines(4);
    CheckBox gpay = new CheckBox(this);
    gpay.setText("GPay notification");
    form.addView(gpay);
    new AlertDialog.Builder(this)
        .setTitle("Parse a message")
        .setView(form)
        .setPositiveButton(
            "Import",
            (d, w) ->
                task(
                    () -> {
                      String message = body.getText().toString();
                      boolean added =
                          repo.capture(
                              title.getText().toString(),
                              message,
                              gpay.isChecked() ? "gpay" : "sms",
                              System.currentTimeMillis(),
                              "paste:"
                                  + UUID.nameUUIDFromBytes(
                                      (title.getText().toString() + message)
                                          .getBytes(StandardCharsets.UTF_8)));
                      runOnUiThread(
                          () ->
                              Toast.makeText(
                                      this,
                                      added ? "Imported" : "Already captured or needs review",
                                      Toast.LENGTH_LONG)
                                  .show());
                    }))
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void importSms() {
    task(
        () -> {
          int count = 0;
          try (Cursor c =
              getContentResolver()
                  .query(
                      Uri.parse("content://sms/inbox"),
                      new String[] {"_id", "address", "body", "date"},
                      null,
                      null,
                      "date ASC")) {
            if (c != null)
              while (c.moveToNext())
                if (repo.capture(
                    c.getString(1),
                    c.getString(2),
                    "sms",
                    c.getLong(3),
                    "sms:" + c.getString(1) + ":" + c.getLong(3))) count++;
          }
          int added = count;
          runOnUiThread(
              () ->
                  Toast.makeText(this, "Imported " + added + " messages", Toast.LENGTH_LONG)
                      .show());
        });
  }

  private void login() {
    SharedPreferences prefs = getSharedPreferences("settings", 0);
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    EditText base = field(form, "HTTPS FastAPI base URL", prefs.getString("api_url", "")),
        email = field(form, "Email", ""),
        password = field(form, "Password (12+ characters)", "");
    password.setInputType(129);
    CheckBox signup = new CheckBox(this);
    signup.setText("Create account");
    form.addView(signup);
    new AlertDialog.Builder(this)
        .setTitle("Connect your account")
        .setView(form)
        .setPositiveButton(
            "Connect",
            (d, w) ->
                ExpenseApp.IO.execute(
                    () -> {
                      try {
                        JSONObject health =
                            SyncWorker.request(base.getText().toString(), "/health", "", null);
                        if (!health.optString("environment").equals(BuildConfig.FLAVOR))
                          throw new IllegalArgumentException(
                              "API environment does not match this app");
                        JSONObject result =
                            SyncWorker.request(
                                base.getText().toString(),
                                "/api/v1/auth/" + (signup.isChecked() ? "register" : "login"),
                                "",
                                new JSONObject()
                                    .put("email", email.getText().toString())
                                    .put("password", password.getText().toString()));
                        String previous = prefs.getString("cloud_user", "");
                        String next = result.getString("user_id");
                        if (!previous.isEmpty() && !previous.equals(next))
                          throw new IllegalStateException(
                              "This installation is linked to another account. Use a separate"
                                  + " environment installation.");
                        prefs
                            .edit()
                            .putString("api_url", base.getText().toString())
                            .putString("cloud_user", next)
                            .putString(
                                "access", repo.vault.encrypt(result.getString("access_token")))
                            .putString(
                                "refresh", repo.vault.encrypt(result.getString("refresh_token")))
                            .putString("sync_status", "Connected; sync is ready")
                            .apply();
                        reload();
                      } catch (Exception e) {
                        error(e);
                      }
                    }))
        .setNegativeButton("Cancel", null)
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 101) {
      List<JSONObject> report = filtered();
      task(() -> savePdf(uri, report));
    } else if (request == 102)
      task(
          () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException("Cannot open file");
              ByteArrayOutputStream buffer = new ByteArrayOutputStream();
              byte[] chunk = new byte[8192];
              int read;
              while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 5_000_000) throw new IOException("Import limit is 5 MB");
              }
              byte[] bytes = buffer.toByteArray();
              if (bytes.length > 5_000_000) throw new IOException("Import limit is 5 MB");
              int n =
                  com.dharaneesh.expensetracker.importing.ImportEngine.importText(
                      repo, new String(bytes, StandardCharsets.UTF_8));
              runOnUiThread(
                  () ->
                      Toast.makeText(this, "Imported " + n + " records", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
              throw new IllegalArgumentException(e.getMessage(), e);
            }
          });
  }

  private void savePdf(Uri uri, List<JSONObject> rows) {
    PdfDocument pdf = new PdfDocument();
    try {
      Paint paint = new Paint();
      paint.setTextSize(11);
      int index = 0, pageNo = 1;
      BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
      do {
        PdfDocument.Page p =
            pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo++).create());
        Canvas canvas = p.getCanvas();
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("Expense report (INR) · " + LocalDate.now(IST), 32, 38, paint);
        paint.setTypeface(Typeface.DEFAULT);
        int y = 70;
        while (index < rows.size() && y < 780) {
          JSONObject t = rows.get(index++);
          String line =
              date(t.optLong("occurred_at"))
                  + " | "
                  + t.optString("direction")
                  + " "
                  + t.optString("amount")
                  + " | "
                  + t.optString("payee");
          canvas.drawText(line.length() > 88 ? line.substring(0, 88) : line, 32, y, paint);
          y += 22;
          if (t.optString("direction").equals("debit")) debit = debit.add(amount(t));
          else credit = credit.add(amount(t));
        }
        pdf.finishPage(p);
      } while (index < rows.size());
      PdfDocument.Page total =
          pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo).create());
      total
          .getCanvas()
          .drawText("Total spent INR " + debit + " / received INR " + credit, 32, 50, paint);
      pdf.finishPage(total);
      try (OutputStream out = getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new IOException("Cannot write report");
        pdf.writeTo(out);
      }
      runOnUiThread(() -> Toast.makeText(this, "PDF saved", Toast.LENGTH_LONG).show());
    } catch (Exception e) {
      throw new IllegalStateException("PDF export failed", e);
    } finally {
      pdf.close();
    }
  }
}
