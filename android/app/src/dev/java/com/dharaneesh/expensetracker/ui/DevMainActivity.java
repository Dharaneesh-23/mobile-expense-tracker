package com.dharaneesh.expensetracker.ui;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.work.*;
import com.dharaneesh.expensetracker.*;
import com.dharaneesh.expensetracker.R;
import com.dharaneesh.expensetracker.importing.TakeoutSplitImport;
import com.dharaneesh.expensetracker.sync.SyncWorker;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

/** Dev-only native implementation of the approved Stitch visual reference. */
public class DevMainActivity extends MainActivity {
  private static final int CREAM = 0xFFFCF8F1,
      DARK = 0xFF302923,
      MUTED = 0xFF817469,
      LINE = 0xFFE9DFD3,
      CARD = 0xFFFFFCF7,
      SAND = 0xFFF3EADF,
      TEAL = 0xFF24745F,
      RED = 0xFFB34E42;
  private static final String[] ROOTS = {"Home", "Transactions", "Budgets", "Shared"};
  private String route = "Home",
      selectedId = "",
      budgetCategory = "",
      splitFilter = "Pending",
      importResult = "";
  private YearMonth month = YearMonth.now(IST);
  private final Deque<String> history = new ArrayDeque<>();
  private final Map<String, Integer> scrollPositions = new HashMap<>();
  private final Map<String, EditText> inputs = new LinkedHashMap<>();
  private final Map<String, Spinner> pickers = new LinkedHashMap<>();
  private ScrollView viewport;
  private Typeface designFont;
  private String takeoutJson = "";
  private TakeoutSplitImport.Plan takeoutPlan;
  private final Set<String> takeoutSelected = new HashSet<>();
  private int takeoutPage = 0, splitDisplayLimit = 50;
  private boolean takeoutConfirmed = false;
  private final Set<String> takeoutOverlaps = new HashSet<>();
  private final Set<String> existingTakeoutIds = new HashSet<>();
  private boolean formOpen = false, dirty = false;
  private int transactionDisplayLimit = 50, reviewDisplayLimit = 50;
  private String busyStatus = "";
  private TextView importProgress;
  private volatile boolean busy = false;
  private int loadGeneration = 0;
  private final DateTimeFormatter monthFormat =
      DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);

  @Override
  public void onCreate(Bundle state) {
    designFont = getResources().getFont(R.font.plus_jakarta_sans);
    if (state != null) {
      month = YearMonth.parse(state.getString("design_month", month.toString()));
      route = state.getString("design_route", "Home");
      selectedId = state.getString("design_selected", "");
      ArrayList<String> savedHistory = state.getStringArrayList("design_history");
      if (savedHistory != null) history.addAll(savedHistory);
    }
    super.onCreate(state);
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("design_month", month.toString());
    out.putString("design_route", route);
    out.putString("design_selected", selectedId);
    out.putStringArrayList("design_history", new ArrayList<>(history));
    if (formOpen) {
      try {
        JSONObject draft = new JSONObject();
        for (Map.Entry<String, EditText> entry : inputs.entrySet())
          draft.put(entry.getKey(), entry.getValue().getText().toString());
        for (Map.Entry<String, Spinner> entry : pickers.entrySet())
          draft.put("picker:" + entry.getKey(), entry.getValue().getSelectedItem().toString());
        getSharedPreferences("settings", 0)
            .edit()
            .putString("dev_form_draft", repo.vault.encrypt(draft.toString()))
            .putString("dev_draft_route", route)
            .apply();
      } catch (Exception ignored) {
        /* Never persist a plaintext form draft. */
      }
    }
  }

  @Override
  protected void reload() {
    if (formOpen) return; // Permission dialogs and activity resume must not erase a draft.
    int generation = ++loadGeneration;
    ExpenseApp.IO.execute(
        () -> {
          try {
            List<JSONObject> loaded = repo.list("");
            runOnUiThread(
                () -> {
                  if (generation != loadGeneration || formOpen || isFinishing() || isDestroyed())
                    return;
                  records = loaded;
                  render();
                });
          } catch (Exception e) {
            error(e);
          }
        });
  }

  @Override
  protected TextView text(LinearLayout host, String value, int size) {
    TextView t = super.text(host, value, size);
    t.setTypeface(designFont);
    t.setTextColor(DARK);
    t.setFontFeatureSettings("tnum");
    t.setPadding(0, dp(4), 0, dp(4));
    return t;
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), LINE);
    return d;
  }

  @Override
  protected LinearLayout box(LinearLayout host) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(14), dp(16), dp(14));
    card.setBackground(shape(CARD, 20));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(7), 0, dp(7));
    host.addView(card, lp);
    return card;
  }

  @Override
  protected Button button(LinearLayout host, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTypeface(Typeface.create(designFont, Typeface.BOLD));
    b.setTextColor(Color.WHITE);
    b.setBackground(shape(DARK, 14));
    b.setMinHeight(dp(50));
    b.setMinimumWidth(0);
    b.setMaxLines(3);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(6), 0, dp(6));
    host.addView(b, lp);
    b.setOnClickListener(
        v -> {
          if (!busy) action.run();
          else toast("An import or connection is still running. Please wait.");
        });
    return b;
  }

  private Button secondary(LinearLayout host, String label, Runnable action) {
    Button b = button(host, label, action);
    b.setTextColor(DARK);
    b.setBackground(shape(SAND, 14));
    return b;
  }

  @Override
  protected EditText field(LinearLayout host, String hint, String value) {
    TextView label = text(host, hint, 12);
    label.setTextColor(MUTED);
    EditText e = new EditText(this);
    e.setText(value);
    e.setHint(hint);
    e.setSingleLine(true);
    e.setSaveEnabled(false); // Financial drafts use Vault, never plaintext view-state bundles.
    e.setTextSize(16);
    e.setTextColor(DARK);
    e.setTypeface(designFont);
    e.setHintTextColor(MUTED);
    e.setPadding(dp(13), dp(12), dp(13), dp(12));
    e.setBackground(shape(Color.WHITE, 12));
    e.setMinHeight(dp(50));
    e.setContentDescription(hint);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(2), 0, dp(10));
    host.addView(e, lp);
    label.setLabelFor(View.generateViewId());
    e.setId(label.getLabelFor());
    return e;
  }

  private EditText input(LinearLayout host, String key, String label, String value) {
    String restored = value;
    if (getSharedPreferences("settings", 0).getString("dev_draft_route", "").equals(route)) {
      try {
        restored =
            new JSONObject(
                    repo.vault.decrypt(
                        getSharedPreferences("settings", 0).getString("dev_form_draft", "")))
                .optString(key, value);
      } catch (Exception ignored) {
      }
    }
    EditText e = field(host, label, restored);
    inputs.put(key, e);
    e.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            dirty = true;
          }

          public void afterTextChanged(Editable x) {}
        });
    return e;
  }

  private Spinner picker(LinearLayout host, String label, String[] choices, String chosen) {
    text(host, label, 12).setTextColor(MUTED);
    Spinner s = new Spinner(this);
    s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
    String restored = chosen;
    if (getSharedPreferences("settings", 0).getString("dev_draft_route", "").equals(route)) {
      try {
        restored =
            new JSONObject(
                    repo.vault.decrypt(
                        getSharedPreferences("settings", 0).getString("dev_form_draft", "")))
                .optString("picker:" + label, chosen);
      } catch (Exception ignored) {
      }
    }
    s.setSelection(Math.max(0, Arrays.asList(choices).indexOf(restored)));
    pickers.put(label, s);
    s.setBackground(shape(Color.WHITE, 12));
    s.setPadding(dp(10), dp(6), dp(10), dp(6));
    s.setMinimumHeight(dp(50));
    s.setContentDescription(label);
    host.addView(s);
    s.setTag(s.getSelectedItemPosition());
    s.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> p) {}

          public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            if (formOpen && pos != (Integer) s.getTag()) dirty = true;
          }
        });
    return s;
  }

  private final class CategoryInput {
    final Spinner category;
    final EditText custom;

    CategoryInput(LinearLayout host, String chosen) {
      LinkedHashSet<String> names = new LinkedHashSet<>(Arrays.asList(categories));
      for (JSONObject row : records)
        if (!row.optString("category").trim().isEmpty()) names.add(row.optString("category"));
      category = picker(host, "Category", names.toArray(new String[0]), chosen);
      LinearLayout other = new LinearLayout(DevMainActivity.this);
      other.setOrientation(LinearLayout.VERTICAL);
      host.addView(other);
      custom = input(other, "other-category", "Name of other category", "");
      other.setVisibility(
          category.getSelectedItem().toString().equals("Other") ? View.VISIBLE : View.GONE);
      android.widget.AdapterView.OnItemSelectedListener previous =
          category.getOnItemSelectedListener();
      category.setOnItemSelectedListener(
          new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}

            public void onItemSelected(
                android.widget.AdapterView<?> parent, View view, int position, long id) {
              if (previous != null) previous.onItemSelected(parent, view, position, id);
              other.setVisibility(
                  category.getSelectedItem().toString().equals("Other") ? View.VISIBLE : View.GONE);
            }
          });
    }

    String name() {
      String selected = category.getSelectedItem().toString();
      if (!selected.equals("Other")) return selected;
      String name = custom.getText().toString().trim().replaceAll("\\s+", " ");
      if (name.isEmpty() || name.length() > 60 || name.equalsIgnoreCase("Other")) {
        custom.setError("Enter a specific category name (1–60 characters)");
        throw new IllegalArgumentException("Name your other category before saving");
      }
      return name;
    }
  }

  private String val(String key) {
    return inputs.get(key).getText().toString().trim();
  }

  private boolean root() {
    return Arrays.asList(ROOTS).contains(route);
  }

  private void clearDraft() {
    getSharedPreferences("settings", 0)
        .edit()
        .remove("dev_form_draft")
        .remove("dev_draft_route")
        .apply();
  }

  private void remember() {
    if (viewport != null) scrollPositions.put(route, viewport.getScrollY());
  }

  private void go(String next) {
    remember();
    history.push(route + "\t" + selectedId);
    formOpen = false;
    dirty = false;
    route = next;
    render();
  }

  private void switchTab(String next) {
    remember();
    history.clear();
    selectedId = "";
    formOpen = false;
    dirty = false;
    route = next;
    render();
  }

  private void back() {
    if (dirty) {
      new AlertDialog.Builder(this)
          .setTitle("Discard unsaved changes?")
          .setMessage("Your edits have not been saved.")
          .setPositiveButton(
              "Discard",
              (d, w) -> {
                dirty = false;
                clearDraft();
                back();
              })
          .setNegativeButton("Keep editing", null)
          .show();
      return;
    }
    formOpen = false;
    clearDraft();
    if (history.isEmpty()) {
      if (!route.equals("Home")) {
        route = "Home";
        render();
      } else finish();
    } else {
      String[] previous = history.pop().split("\t", -1);
      route = previous[0];
      selectedId = previous.length > 1 ? previous[1] : "";
      render(); // Navigation must never wait behind a long import on the I/O queue.
    }
  }

  @Override
  @android.annotation.SuppressLint(
      "GestureBackNavigation") // API 26-32 fallback; 33+ registered above.
  public void onBackPressed() {
    back();
  }

  private JSONObject selected() {
    for (JSONObject o : records) if (o.optString("id").equals(selectedId)) return o;
    return null;
  }

  private void open(JSONObject o, String next) {
    remember();
    history.push(route + "\t" + selectedId);
    selectedId = o.optString("id");
    formOpen = false;
    dirty = false;
    route = next;
    render();
  }

  private void caption(LinearLayout host, String value) {
    text(host, value, 12).setTextColor(MUTED);
  }

  private void heading(String title) {
    text(content, title, 20).setTypeface(designFont, Typeface.BOLD);
  }

  private void message(String title, String detail) {
    LinearLayout card = box(content);
    text(card, title, 18).setTypeface(designFont, Typeface.BOLD);
    caption(card, detail);
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }

  @Override
  protected String money(String value) {
    try {
      return DevPresentation.money(new BigDecimal(value));
    } catch (Exception e) {
      return "₹0.00";
    }
  }

  private String money(BigDecimal n) {
    return DevPresentation.money(n);
  }

  private List<JSONObject> monthTransactions() {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject o : kind("transaction"))
      if (YearMonth.from(Instant.ofEpochMilli(o.optLong("occurred_at")).atZone(IST)).equals(month))
        result.add(o);
    return result;
  }

  private BigDecimal sum(List<JSONObject> rows, String dir) {
    BigDecimal n = BigDecimal.ZERO;
    for (JSONObject o : rows)
      if (dir.isEmpty() || o.optString("direction").equals(dir)) n = n.add(amount(o));
    return n;
  }

  private BigDecimal spent(String cat) {
    BigDecimal n = BigDecimal.ZERO;
    for (JSONObject o : monthTransactions())
      if (o.optString("category").equals(cat) && o.optString("direction").equals("debit"))
        n = n.add(amount(o));
    return n;
  }

  private void monthSelector() {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    content.addView(row);
    small(
        row,
        "‹",
        () -> {
          month = month.minusMonths(1);
          render();
        });
    TextView title = text(row, month.format(monthFormat), 18);
    title.setGravity(Gravity.CENTER);
    title.setTypeface(designFont, Typeface.BOLD);
    title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    small(
        row,
        "›",
        () -> {
          month = month.plusMonths(1);
          render();
        });
  }

  private void small(LinearLayout host, String label, Runnable action) {
    TextView t = text(host, label, label.length() > 2 ? 13 : 18);
    t.setGravity(Gravity.CENTER);
    t.setMinWidth(dp(48));
    t.setMinHeight(dp(48));
    t.setPadding(dp(10), dp(6), dp(10), dp(6));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(dp(4), 0, dp(4), 0);
    t.setLayoutParams(lp);
    t.setContentDescription(label);
    t.setBackground(shape(SAND, 14));
    t.setOnClickListener(v -> action.run());
  }

  @Override
  protected void render() {
    inputs.clear();
    pickers.clear();
    dirty = false;
    formOpen = false;
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setBackgroundColor(CREAM);
    setContentView(page);
    getWindow().setStatusBarColor(CREAM);
    getWindow().setNavigationBarColor(CREAM);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(20), dp(7), dp(20), dp(7));
    page.addView(header);
    if (!root()) small(header, "←", this::back);
    LinearLayout titles = new LinearLayout(this);
    titles.setOrientation(LinearLayout.VERTICAL);
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    caption(titles, "EXPENSE TRACKER  ·  DEV");
    text(titles, route, 24).setTypeface(designFont, Typeface.BOLD);
    if (root()) {
      small(header, "Review", () -> go("Review Inbox"));
      small(header, "⚙", () -> go("Settings"));
    }
    viewport = new ScrollView(this);
    viewport.setFillViewport(true);
    viewport.setClipToPadding(false);
    page.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(4), dp(20), dp(24));
    viewport.addView(content);
    switch (route) {
      case "Home":
        overview();
        break;
      case "Transactions":
        transactions();
        break;
      case "Budgets":
        budgets();
        break;
      case "Shared":
        splits();
        break;
      case "Transaction Detail":
        transactionDetail();
        break;
      case "Add Transaction":
        transactionForm(null);
        break;
      case "Edit Transaction":
        transactionForm(selected());
        break;
      case "Filters":
        filterScreen();
        break;
      case "Budget Detail":
        budgetDetail();
        break;
      case "Add Budget":
        budgetForm(null);
        break;
      case "Edit Budget":
        budgetForm(selected());
        break;
      case "Split Detail":
        splitDetail();
        break;
      case "Create Split":
        splitForm(null);
        break;
      case "Edit Split":
        splitForm(selected());
        break;
      case "Record Settlement":
        settlement();
        break;
      case "Settings":
        settings();
        break;
      case "Capture & Permissions":
        permissions();
        break;
      case "Import History":
        imports();
        break;
      case "Takeout Owner":
        takeoutOwner();
        break;
      case "Takeout Preview":
        takeoutPreview();
        break;
      case "Cloud Sync":
        cloud();
        break;
      case "Review Inbox":
        review();
        break;
      case "Reports":
        reports();
        break;
      case "Privacy & Storage":
        privacy();
        break;
      case "App Updates":
        updates();
        break;
      case "About":
        about();
        break;
      case "Welcome":
        welcome();
        break;
      default:
        message("Screen unavailable", "Use Back to return.");
    }
    if (root()) {
      LinearLayout tabs = new LinearLayout(this);
      tabs.setPadding(dp(8), dp(5), dp(8), dp(5));
      tabs.setBackground(shape(CREAM, 0));
      page.addView(tabs);
      String[] icons = {"⌂", "≡", "◔", "♧"};
      for (int i = 0; i < ROOTS.length; i++) {
        final String name = ROOTS[i];
        TextView t = text(tabs, icons[i] + "\n" + name, 12);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(58));
        t.setContentDescription(name + " tab");
        t.setSelected(route.equals(name));
        if (route.equals(name)) {
          t.setBackground(shape(SAND, 18));
          t.setTypeface(designFont, Typeface.BOLD);
        }
        tabs.removeView(t);
        tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        t.setOnClickListener(v -> switchTab(name));
      }
    }
    int y = scrollPositions.getOrDefault(route, 0);
    viewport.post(() -> viewport.scrollTo(0, y));
  }

  private void metric(LinearLayout row, String name, BigDecimal number, int color) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(8), dp(10), dp(8), dp(10));
    c.setBackground(shape(SAND, 15));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
    lp.setMargins(dp(2), 0, dp(2), 0);
    row.addView(c, lp);
    caption(c, name);
    TextView value = text(c, money(number), 15);
    value.setTextColor(color);
    value.setTypeface(designFont, Typeface.BOLD);
  }

  private void progress(LinearLayout card, BigDecimal value, BigDecimal limit) {
    ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    p.setMax(100);
    p.setProgress(
        limit.signum() == 0
            ? 0
            : Math.min(
                100,
                value
                    .multiply(new BigDecimal(100))
                    .divide(limit, 0, RoundingMode.DOWN)
                    .intValue()));
    p.setProgressTintList(ColorStateList.valueOf(value.compareTo(limit) > 0 ? RED : DARK));
    p.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
    card.addView(p, new LinearLayout.LayoutParams(-1, dp(8)));
  }

  @Override
  protected void overview() {
    monthSelector();
    caption(content, "Manual entries & file imports");
    caption(
        content,
        getSharedPreferences("settings", 0)
            .getString("sync_status", "Cloud sync off · Local storage"));
    List<JSONObject> tx = monthTransactions();
    BigDecimal debit = sum(tx, "debit"), credit = sum(tx, "credit");
    LinearLayout metrics = new LinearLayout(this);
    content.addView(metrics);
    metric(metrics, "EXPENSES", debit, RED);
    metric(metrics, "INCOME", credit, TEAL);
    metric(metrics, "NET FLOW", credit.subtract(debit), DARK);
    LinearLayout remaining = box(content);
    caption(remaining, "REMAINING MONTHLY BUDGET");
    BigDecimal limit = BigDecimal.ZERO, used = BigDecimal.ZERO;
    for (JSONObject b : kind("budget")) {
      limit = limit.add(amount(b));
      used = used.add(spent(b.optString("category")));
    }
    text(remaining, money(limit.subtract(used)), 32).setTypeface(designFont, Typeface.BOLD);
    progress(remaining, used, limit);
    caption(remaining, "Spent " + money(used) + " of " + money(limit));
    caption(remaining, "Based on configured budgets, not your bank balance.");
    LinearLayout pace = box(content);
    text(pace, "Weekly spending pace", 17).setTypeface(designFont, Typeface.BOLD);
    pace.addView(new PaceView(), new LinearLayout.LayoutParams(-1, dp(110)));
    caption(pace, "Recorded expenses over the last 7 days. No estimated transactions.");
    int reviews = kind("review").size();
    for (JSONObject s : kind("split")) if (s.optBoolean("group_incomplete")) reviews++;
    final int count = reviews;
    secondary(content, count + " items need review  →", () -> go("Review Inbox"));
    button(
        content,
        "+ Add Transaction",
        () -> {
          selectedId = "";
          clearDraft();
          go("Add Transaction");
        });
    secondary(content, "Reports & PDF", () -> go("Reports"));
    heading("Recent transactions");
    List<JSONObject> all = kind("transaction");
    all.sort((a, b) -> Long.compare(b.optLong("occurred_at"), a.optLong("occurred_at")));
    if (all.isEmpty())
      message(
          "Your expenses start here",
          "Add a transaction, paste a bank message, or import a supported file.");
    for (int i = 0; i < Math.min(5, all.size()); i++) transactionCard(all.get(i));
  }

  private final class PaceView extends View {
    PaceView() {
      super(DevMainActivity.this);
      setContentDescription("Last seven days of recorded expenses");
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      BigDecimal[] days = new BigDecimal[7];
      Arrays.fill(days, BigDecimal.ZERO);
      LocalDate today = LocalDate.now(IST);
      for (JSONObject t : kind("transaction")) {
        long offset =
            java.time.temporal.ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(t.optLong("occurred_at")).atZone(IST).toLocalDate(), today);
        if (offset >= 0 && offset < 7 && t.optString("direction").equals("debit"))
          days[6 - (int) offset] = days[6 - (int) offset].add(amount(t));
      }
      float peak = 1;
      for (BigDecimal n : days) peak = Math.max(peak, n.floatValue());
      float slot = getWidth() / 7f;
      for (int i = 0; i < 7; i++) {
        p.setColor(i == 6 ? DARK : 0xFFC5B6A5);
        float h = Math.max(dp(3), (getHeight() - dp(28)) * days[i].floatValue() / peak);
        c.drawRoundRect(
            i * slot + dp(8),
            getHeight() - dp(25) - h,
            (i + 1) * slot - dp(8),
            getHeight() - dp(25),
            dp(4),
            dp(4),
            p);
        p.setColor(MUTED);
        p.setTextSize(dp(11));
        c.drawText(
            today.minusDays(6 - i).getDayOfWeek().toString().substring(0, 1),
            i * slot + slot / 2 - dp(4),
            getHeight() - dp(6),
            p);
      }
    }
  }

  @Override
  protected void transactionCard(JSONObject t) {
    LinearLayout card = box(content);
    text(card, t.optString("payee", "Unknown"), 17).setTypeface(designFont, Typeface.BOLD);
    TextView value =
        text(
            card,
            (t.optString("direction").equals("credit") ? "+" : "−") + money(t.optString("amount")),
            22);
    value.setTextColor(t.optString("direction").equals("credit") ? TEAL : RED);
    caption(
        card, t.optString("category", "Uncategorized") + " · " + t.optString("source", "manual"));
    caption(card, date(t.optLong("occurred_at")) + " IST");
    if (!t.optString("bank").isEmpty())
      caption(
          card,
          t.optString("bank")
              + (t.optString("account_last4").isEmpty()
                  ? ""
                  : " ••••" + t.optString("account_last4")));
    card.setContentDescription("Open transaction: " + t.optString("payee"));
    card.setOnClickListener(v -> open(t, "Transaction Detail"));
  }

  @Override
  protected void transactions() {
    LinearLayout search = box(content);
    EditText q = field(search, "Search merchant or note", query);
    secondary(
        search,
        "Search",
        () -> {
          query = q.getText().toString().trim();
          render();
        });
    LinearLayout chips = new LinearLayout(this);
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    hs.addView(chips);
    content.addView(hs);
    for (String label : new String[] {"All", "Debit", "Credit", "Bank SMS", "GPay", "Cash"}) {
      Button b =
          secondary(
              chips,
              label,
              () -> {
                direction = "All";
                source = "All";
                if (label.equals("Debit")) direction = "debit";
                if (label.equals("Credit")) direction = "credit";
                if (label.equals("Bank SMS")) source = "sms";
                if (label.equals("GPay")) source = "gpay";
                if (label.equals("Cash")) {
                  source = "manual";
                  bank = "Cash";
                } else bank = "";
                render();
              });
      b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(48)));
    }
    secondary(content, "Filters & date range", () -> go("Filters"));
    button(
        content,
        "+ Add Transaction",
        () -> {
          selectedId = "";
          clearDraft();
          go("Add Transaction");
        });
    List<JSONObject> rows = filtered();
    LinearLayout total = box(content);
    caption(total, rows.size() + " matching transactions");
    text(total, "Out " + money(sum(rows, "debit")) + "  ·  In " + money(sum(rows, "credit")), 15);
    caption(total, "Net " + money(sum(rows, "credit").subtract(sum(rows, "debit"))));
    if (rows.isEmpty()) message("No matching transactions", "Change your filters or add an entry.");
    String day = "";
    int displayed = 0;
    for (JSONObject t : rows) {
      if (displayed++ >= transactionDisplayLimit) break;
      String d =
          Instant.ofEpochMilli(t.optLong("occurred_at"))
              .atZone(IST)
              .toLocalDate()
              .format(DateTimeFormatter.ofPattern("dd MMM uuuu"));
      if (!d.equals(day)) {
        caption(content, d.toUpperCase(Locale.ROOT));
        day = d;
      }
      transactionCard(t);
    }
    if (rows.size() > transactionDisplayLimit)
      secondary(
          content,
          "Show More Transactions",
          () -> {
            transactionDisplayLimit += 50;
            render();
          });
  }

  private void filterScreen() {
    formOpen = true;
    LinearLayout form = box(content);
    input(form, "q", "Merchant or note", query);
    input(form, "bank", "Bank / account name contains", bank);
    input(form, "category", "Category (blank = all)", category);
    Spinner d = picker(form, "Direction", new String[] {"All", "debit", "credit"}, direction);
    Spinner s =
        picker(form, "Source", new String[] {"All", "sms", "gpay", "manual", "import"}, source);
    EditText start = input(form, "from", "From date (YYYY-MM-DD)", from),
        end = input(form, "to", "To date (YYYY-MM-DD)", to);
    datePicker(start);
    datePicker(end);
    input(form, "min", "Minimum amount (optional)", min).setInputType(8194);
    input(form, "max", "Maximum amount (optional)", max).setInputType(8194);
    button(
        content,
        "Apply Filters",
        () -> {
          try {
            if (!val("from").isEmpty()) LocalDate.parse(val("from"));
            if (!val("to").isEmpty()) LocalDate.parse(val("to"));
            if (!val("from").isEmpty()
                && !val("to").isEmpty()
                && LocalDate.parse(val("from")).isAfter(LocalDate.parse(val("to"))))
              throw new IllegalArgumentException("Start date must precede end date");
            BigDecimal lo = val("min").isEmpty() ? BigDecimal.ZERO : new BigDecimal(val("min"));
            BigDecimal hi =
                val("max").isEmpty() ? new BigDecimal("9999999999.99") : new BigDecimal(val("max"));
            if (lo.signum() < 0 || hi.compareTo(lo) < 0)
              throw new IllegalArgumentException("Check the amount range");
            query = val("q");
            bank = val("bank");
            category = val("category");
            from = val("from");
            to = val("to");
            min = val("min");
            max = val("max");
            direction = d.getSelectedItem().toString();
            source = s.getSelectedItem().toString();
            dirty = false;
            formOpen = false;
            clearDraft();
            back();
          } catch (Exception e) {
            end.setError("Check dates and amount range");
            toast("Use valid dates, with minimum no greater than maximum");
          }
        });
    secondary(
        content,
        "Reset Filters",
        () -> {
          query = bank = category = from = to = min = max = "";
          direction = source = "All";
          clearDraft();
          render();
        });
    secondary(content, "Cancel", this::back);
  }

  private void datePicker(EditText e) {
    e.setInputType(
        android.text.InputType.TYPE_CLASS_DATETIME
            | android.text.InputType.TYPE_DATETIME_VARIATION_DATE);
    e.setOnClickListener(
        v -> {
          LocalDate date;
          try {
            date = LocalDate.parse(e.getText());
          } catch (Exception x) {
            date = LocalDate.now(IST);
          }
          new DatePickerDialog(
                  this,
                  (p, y, m, d) -> e.setText(LocalDate.of(y, m + 1, d).toString()),
                  date.getYear(),
                  date.getMonthValue() - 1,
                  date.getDayOfMonth())
              .show();
        });
  }

  private void transactionDetail() {
    JSONObject t = selected();
    if (t == null) {
      message("Transaction unavailable", "It may have been removed. Return to your ledger.");
      return;
    }
    LinearLayout hero = box(content);
    caption(
        hero, t.optString("direction").equals("debit") ? "DEBIT · MONEY OUT" : "CREDIT · MONEY IN");
    text(hero, money(t.optString("amount")), 36).setTypeface(designFont, Typeface.BOLD);
    text(hero, t.optString("payee"), 21);
    caption(
        hero,
        date(t.optLong("occurred_at"))
            + " IST"
            + (t.optBoolean("date_inferred") ? " · Time estimated" : ""));
    info("Category", t.optString("category"));
    info("Source", t.optString("source"));
    info(
        "Account",
        t.optString("bank", "Cash")
            + " "
            + (t.optString("account_last4").isEmpty()
                ? ""
                : "••••" + t.optString("account_last4")));
    info("Reference", t.optString("reference", "Not available"));
    if (!t.optString("reference").isEmpty())
      secondary(
          content,
          "Copy Reference",
          () -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(
                    ClipData.newPlainText("Transaction reference", t.optString("reference")));
            toast("Reference copied");
          });
    info("Note", t.optString("note", ""));
    button(
        content,
        "Edit Transaction",
        () -> {
          clearDraft();
          go("Edit Transaction");
        });
    secondary(
        content,
        "Create Linked Split",
        () -> {
          clearDraft();
          go("Create Split");
        });
    secondary(content, "Delete Transaction", () -> confirmDelete(t));
  }

  private void info(String label, String value) {
    LinearLayout row = box(content);
    caption(row, label);
    text(row, value.isEmpty() ? "—" : value, 16);
  }

  private void transactionForm(JSONObject old) {
    formOpen = true;
    caption(content, "Works offline · Saves to encrypted local storage");
    LinearLayout form = box(content);
    EditText value =
        input(form, "amount", "Amount (₹)", old == null ? "" : old.optString("amount"));
    value.setInputType(8194);
    Spinner dir =
        picker(
            form,
            "Direction",
            new String[] {"debit", "credit"},
            old == null ? "debit" : old.optString("direction"));
    input(form, "payee", "Merchant / Payee", old == null ? "" : old.optString("payee"));
    CategoryInput cat =
        new CategoryInput(form, old == null ? "Uncategorized" : old.optString("category"));
    input(
        form,
        "bank",
        "Bank / payment method (Cash for cash)",
        old == null ? "Cash" : old.optString("bank"));
    input(
            form,
            "last",
            "Account last four (optional)",
            old == null ? "" : old.optString("account_last4"))
        .setInputType(2);
    ZonedDateTime at =
        Instant.ofEpochMilli(old == null ? System.currentTimeMillis() : old.optLong("occurred_at"))
            .atZone(IST);
    EditText day = input(form, "day", "Date (YYYY-MM-DD)", at.toLocalDate().toString());
    datePicker(day);
    EditText time =
        input(
            form,
            "time",
            "Time (HH:mm) · IST",
            at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
    time.setInputType(4);
    time.setOnClickListener(
        v -> {
          LocalTime tm;
          try {
            tm = LocalTime.parse(time.getText());
          } catch (Exception e) {
            tm = LocalTime.now(IST);
          }
          new TimePickerDialog(
                  this,
                  (p, h, m) -> time.setText(String.format(Locale.ROOT, "%02d:%02d", h, m)),
                  tm.getHour(),
                  tm.getMinute(),
                  true)
              .show();
        });
    input(form, "note", "Note (optional)", old == null ? "" : old.optString("note"));
    if (old != null)
      caption(
          form,
          "Category corrections improve future merchant categorization. Source and reference are"
              + " retained.");
    button(
        content,
        "Save Transaction",
        () -> {
          try {
            BigDecimal n = DevPresentation.positive(val("amount"));
            if (val("payee").isEmpty())
              throw new IllegalArgumentException("Enter a merchant or person");
            if (!val("last").isEmpty() && !val("last").matches("\\d{4}"))
              throw new IllegalArgumentException("Use four account digits");
            LocalDate date = LocalDate.parse(val("day"));
            LocalTime tm = LocalTime.parse(val("time"));
            long when = date.atTime(tm).atZone(IST).toInstant().toEpochMilli();
            if (when < 0) throw new IllegalArgumentException("Use a date after 1970");
            JSONObject data =
                old == null
                    ? new JSONObject().put("source", "manual")
                    : new JSONObject(old.toString());
            data.put("kind", "transaction")
                .put("amount", n.toPlainString())
                .put("direction", dir.getSelectedItem())
                .put("payee", val("payee"))
                .put("category", cat.name())
                .put("bank", val("bank"))
                .put("account_last4", val("last"))
                .put("occurred_at", when)
                .put(
                    "date_inferred",
                    old != null
                        && old.optBoolean("date_inferred")
                        && when == old.optLong("occurred_at"))
                .put("note", val("note"));
            persist(data, "Transactions");
          } catch (Exception e) {
            value.setError("Check amount, merchant, account, date and time");
            toast(e.getMessage() == null ? "Check the form" : e.getMessage());
          }
        });
    secondary(content, "Cancel", this::back);
  }

  private void persist(JSONObject data, String destination) {
    if (busy) return;
    busy = true;
    ExpenseApp.IO.execute(
        () -> {
          try {
            repo.save(data);
            runOnUiThread(
                () -> {
                  busy = false;
                  formOpen = false;
                  dirty = false;
                  clearDraft();
                  history.clear();
                  route = destination;
                  toast("Saved locally");
                  reload();
                });
          } catch (Exception e) {
            busy = false;
            error(e);
          }
        });
  }

  private void confirmDelete(JSONObject o) {
    new AlertDialog.Builder(this)
        .setTitle("Delete this record?")
        .setMessage("It will be removed locally. Deletion will sync if cloud sync is connected.")
        .setPositiveButton(
            "Delete",
            (d, w) -> {
              formOpen = false;
              dirty = false;
              history.clear();
              route =
                  o.optString("kind").equals("transaction")
                      ? "Transactions"
                      : o.optString("kind").equals("budget") ? "Budgets" : "Shared";
              task(() -> repo.delete(o.optString("id")));
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  @Override
  protected void budgets() {
    monthSelector();
    BigDecimal limit = BigDecimal.ZERO, used = BigDecimal.ZERO;
    for (JSONObject b : kind("budget")) {
      limit = limit.add(amount(b));
      used = used.add(spent(b.optString("category")));
    }
    LinearLayout total = box(content);
    caption(total, "REMAINING MONTHLY BUDGET");
    text(total, money(limit.subtract(used)), 32).setTypeface(designFont, Typeface.BOLD);
    progress(total, used, limit);
    caption(total, "Spent " + money(used) + " · Allocated " + money(limit));
    caption(total, "Alerts when the current month's budget limit is reached.");
    button(
        content,
        "+ Add Category Budget",
        () -> {
          selectedId = "";
          clearDraft();
          go("Add Budget");
        });
    if (kind("budget").isEmpty())
      message("Give your spending a plan", "Create your first monthly category limit.");
    for (JSONObject b : kind("budget")) {
      LinearLayout c = box(content);
      BigDecimal spent = spent(b.optString("category"));
      text(c, b.optString("category"), 18).setTypeface(designFont, Typeface.BOLD);
      progress(c, spent, amount(b));
      text(c, money(spent) + " / " + money(b.optString("amount")), 16);
      BigDecimal remaining = amount(b).subtract(spent);
      TextView status =
          text(
              c,
              remaining.signum() < 0
                  ? "Exceeded by " + money(remaining.abs())
                  : money(remaining) + " remaining",
              13);
      status.setTextColor(remaining.signum() < 0 ? RED : TEAL);
      c.setOnClickListener(
          v -> {
            budgetCategory = b.optString("category");
            open(b, "Budget Detail");
          });
    }
  }

  private void budgetDetail() {
    JSONObject b = selected();
    if (b == null) {
      message("Budget unavailable", "Return to Budgets.");
      return;
    }
    info(
        b.optString("category"),
        money(spent(b.optString("category"))) + " spent of " + money(b.optString("amount")));
    button(
        content,
        "Edit Budget",
        () -> {
          clearDraft();
          go("Edit Budget");
        });
    secondary(content, "Delete Budget", () -> confirmDelete(b));
    heading("Transactions · " + month.format(monthFormat));
    for (JSONObject t : monthTransactions())
      if (t.optString("category").equals(b.optString("category"))
          && t.optString("direction").equals("debit")) transactionCard(t);
  }

  private void budgetForm(JSONObject old) {
    formOpen = true;
    LinearLayout form = box(content);
    CategoryInput cat =
        new CategoryInput(form, old == null ? "Uncategorized" : old.optString("category"));
    EditText n =
        input(form, "limit", "Monthly limit (₹)", old == null ? "" : old.optString("amount"));
    n.setInputType(8194);
    caption(
        form,
        "Monthly category limit. Existing limits for this category are updated, not duplicated.");
    button(
        content,
        "Save Budget",
        () -> {
          try {
            JSONObject b = old == null ? new JSONObject() : new JSONObject(old.toString());
            String name = cat.name();
            for (JSONObject other : kind("budget"))
              if (other.optString("category").equals(name)) {
                if (old != null && !old.optString("id").equals(other.optString("id")))
                  throw new IllegalArgumentException("That category already has a budget");
                b.put("id", other.optString("id"));
              }
            b.put("kind", "budget")
                .put("category", name)
                .put("amount", DevPresentation.positive(val("limit")).toPlainString())
                .put("period", "monthly");
            persist(b, "Budgets");
          } catch (Exception e) {
            n.setError(e.getMessage());
          }
        });
    secondary(content, "Cancel", this::back);
  }

  @Override
  protected void splits() {
    BigDecimal owed = BigDecimal.ZERO, owe = BigDecimal.ZERO;
    for (JSONObject s : kind("split"))
      if (!s.optString("status").equals("settled")) {
        if (s.optString("direction").equals("they_owe_you")) owed = owed.add(amount(s));
        else owe = owe.add(amount(s));
      }
    LinearLayout top = box(content);
    caption(top, "PENDING OBLIGATIONS");
    text(top, "You are owed  " + money(owed), 23).setTextColor(TEAL);
    text(top, "You owe  " + money(owe), 23).setTextColor(RED);
    caption(
        top,
        "Net position " + money(owed.subtract(owe)) + " · Requests are not completed payments.");
    button(
        content,
        "+ Add Split",
        () -> {
          selectedId = "";
          clearDraft();
          go("Create Split");
        });
    LinearLayout chips = new LinearLayout(this);
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.addView(chips);
    content.addView(hs);
    for (String name : new String[] {"Pending", "All", "You owe", "You are owed", "Settled"}) {
      Button b =
          secondary(
              chips,
              name,
              () -> {
                splitFilter = name;
                splitDisplayLimit = 50;
                render();
              });
      b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(48)));
    }
    int n = 0;
    for (JSONObject s : kind("split")) {
      boolean settled = s.optString("status").equals("settled");
      if (splitFilter.equals("Pending") && settled) continue;
      if (splitFilter.equals("Settled") && !settled) continue;
      if (splitFilter.equals("You owe")
          && (settled || !s.optString("direction").equals("you_owe_them"))) continue;
      if (splitFilter.equals("You are owed")
          && (settled || !s.optString("direction").equals("they_owe_you"))) continue;
      n++;
      if (n > splitDisplayLimit) continue;
      LinearLayout c = box(content);
      text(c, s.optString("group") + " · " + s.optString("payee"), 18)
          .setTypeface(designFont, Typeface.BOLD);
      text(c, money(s.optString("amount")), 24);
      caption(
          c,
          (s.optString("direction").equals("you_owe_them") ? "You owe them" : "They owe you")
              + " · "
              + s.optString("status", "pending"));
      if (s.optBoolean("group_incomplete"))
        text(c, "Captured request needs review", 13).setTextColor(RED);
      c.setOnClickListener(v -> open(s, "Split Detail"));
    }
    if (n == 0) message("No matching splits", "Add an obligation or review GPay requests.");
    if (n > splitDisplayLimit) {
      caption(content, "Showing " + splitDisplayLimit + " of " + n + " matching splits");
      secondary(
          content,
          "Show 50 More",
          () -> {
            splitDisplayLimit += 50;
            render();
          });
    }
  }

  private void splitDetail() {
    JSONObject s = selected();
    if (s == null) {
      message("Split unavailable", "Return to Shared.");
      return;
    }
    info(
        s.optString("direction").equals("you_owe_them") ? "YOU OWE" : "THEY OWE YOU",
        money(s.optString("amount")));
    info("Group / Person", s.optString("group") + " / " + s.optString("payee"));
    info("Status", s.optString("status", "pending") + " · Obligation, not a completed payment");
    info("Source", s.optString("source", "manual"));
    info("Description", s.optString("note"));
    if (s.optBoolean("group_incomplete"))
      message(
          "Review group name",
          "The original GPay notification was truncated. Edit to confirm its details.");
    if (!s.optString("linked_transaction_id").isEmpty())
      info("Linked transaction", s.optString("linked_transaction_id"));
    button(
        content,
        "Edit Split",
        () -> {
          clearDraft();
          go("Edit Split");
        });
    if (!s.optString("status").equals("settled"))
      secondary(
          content,
          "Record Settlement",
          () -> {
            clearDraft();
            go("Record Settlement");
          });
    else if (s.has("settled_at")) info("Settled", date(s.optLong("settled_at")) + " IST");
    secondary(content, "Delete Split", () -> confirmDelete(s));
  }

  private void splitForm(JSONObject old) {
    formOpen = true;
    JSONObject linked = old == null ? selected() : null;
    if (linked != null && !linked.optString("kind").equals("transaction")) linked = null;
    final JSONObject payment = linked;
    LinearLayout form = box(content);
    input(form, "group", "Group", old == null ? "Personal" : old.optString("group"));
    input(form, "person", "Person", old == null ? "" : old.optString("payee"));
    Spinner dir =
        picker(
            form,
            "Direction",
            new String[] {"they_owe_you", "you_owe_them"},
            old == null ? "they_owe_you" : old.optString("direction"));
    EditText a =
        input(
            form,
            "share",
            "Share amount (₹)",
            old != null
                ? old.optString("amount")
                : payment == null ? "" : payment.optString("amount"));
    a.setInputType(8194);
    input(form, "description", "Description (optional)", old == null ? "" : old.optString("note"));
    if (payment != null)
      caption(
          form,
          "Linked to "
              + payment.optString("payee")
              + ". This creates an obligation, not another expense.");
    button(
        content,
        "Save Split",
        () -> {
          try {
            if (val("group").isEmpty() || val("person").isEmpty())
              throw new IllegalArgumentException("Enter group and person");
            JSONObject s = old == null ? new JSONObject() : new JSONObject(old.toString());
            s.put("kind", "split")
                .put("group", val("group"))
                .put("payee", val("person"))
                .put("direction", dir.getSelectedItem())
                .put("amount", DevPresentation.positive(val("share")).toPlainString())
                .put("note", val("description"))
                .put("status", old == null ? "pending" : old.optString("status", "pending"))
                .put("group_incomplete", false)
                .put(
                    "occurred_at",
                    old == null ? System.currentTimeMillis() : old.optLong("occurred_at"));
            if (payment != null) s.put("linked_transaction_id", payment.optString("id"));
            persist(s, "Shared");
          } catch (Exception e) {
            a.setError(e.getMessage());
          }
        });
    secondary(content, "Cancel", this::back);
  }

  private void settlement() {
    JSONObject s = selected();
    if (s == null || s.optString("status").equals("settled")) {
      message("No pending obligation selected", "Open a pending split from Shared.");
      return;
    }
    formOpen = true;
    message(
        "Record a full settlement",
        s.optString("group")
            + " · "
            + s.optString("payee")
            + " · "
            + money(s.optString("amount"))
            + ". This does not initiate payment or add an expense.");
    LinearLayout form = box(content);
    EditText day =
        input(form, "settled", "Settlement date (YYYY-MM-DD)", LocalDate.now(IST).toString());
    datePicker(day);
    Spinner method =
        picker(
            form, "Payment method", new String[] {"UPI", "Cash", "Bank transfer", "Other"}, "UPI");
    List<JSONObject> tx = kind("transaction");
    List<String> labels = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    labels.add("No linked payment");
    ids.add("");
    for (JSONObject t : tx)
      if (t.optString("direction")
              .equals(s.optString("direction").equals("you_owe_them") ? "debit" : "credit")
          && amount(t).compareTo(amount(s)) == 0) {
        labels.add(
            t.optString("payee")
                + " · "
                + money(t.optString("amount"))
                + " · "
                + date(t.optLong("occurred_at")));
        ids.add(t.optString("id"));
      }
    Spinner link =
        picker(
            form,
            "Existing completed transaction (optional)",
            labels.toArray(new String[0]),
            labels.get(0));
    button(
        content,
        "Confirm Full Settlement",
        () -> {
          try {
            long at = LocalDate.parse(val("settled")).atStartOfDay(IST).toInstant().toEpochMilli();
            if (at < 0) throw new IllegalArgumentException();
            JSONObject data =
                new JSONObject(s.toString())
                    .put("status", "settled")
                    .put("settled_at", at)
                    .put("settlement_method", method.getSelectedItem())
                    .put("settlement_transaction_id", ids.get(link.getSelectedItemPosition()));
            new AlertDialog.Builder(this)
                .setTitle("Confirm settlement?")
                .setMessage(
                    "Mark the full "
                        + money(s.optString("amount"))
                        + " as settled? No new financial transaction will be created.")
                .setPositiveButton("Confirm", (d, w) -> persist(data, "Shared"))
                .setNegativeButton("Cancel", null)
                .show();
          } catch (Exception e) {
            day.setError("Choose a valid settlement date");
          }
        });
    secondary(content, "Cancel", this::back);
  }

  @Override
  protected void settings() {
    caption(content, "Your data, your choice");
    settingRow("Notifications", "Optional budget alerts", "Capture & Permissions");
    settingRow("Review Inbox", kind("review").size() + " local messages", "Review Inbox");
    settingRow(
        "Import History",
        "Supported CSV/JSON, Takeout files, and pasted messages",
        "Import History");
    settingRow("Reports & PDF", "Generate a report on this device", "Reports");
    settingRow(
        "Cloud Sync",
        "Optional · "
            + getSharedPreferences("settings", 0).getString("sync_status", "Not connected"),
        "Cloud Sync");
    settingRow("App Updates", "Download dev builds from GitHub Releases", "App Updates");
    settingRow(
        "Privacy & Storage", "Encrypted local payloads and optional sync", "Privacy & Storage");
    settingRow("About", "Expense Tracker · " + BuildConfig.VERSION_NAME, "About");
  }

  private void settingRow(String title, String detail, String next) {
    LinearLayout c = box(content);
    text(c, title + "  ›", 17).setTypeface(designFont, Typeface.BOLD);
    caption(c, detail);
    c.setOnClickListener(v -> go(next));
    c.setContentDescription("Open " + title);
  }

  private void welcome() {
    message(
        "Your expenses, in one place",
        "Add transactions manually, paste selected bank messages, or import supported files. This"
            + " dev version does not read your SMS inbox or other apps' notifications.");
    button(content, "Import a File", () -> go("Import History"));
    secondary(content, "Go to Home", () -> switchTab("Home"));
  }

  private void permissions() {
    message(
        "Budget notifications",
        "Optional alerts when a monthly category budget is reached. SMS and notification capture"
            + " are unavailable in this dev version.");
    secondary(
        content,
        "Allow Budget Notifications",
        () -> {
          if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 12);
          else
            toast(
                "Budget notifications are available; review Android notification settings if"
                    + " disabled.");
        });
    caption(content, "Budget alerts trigger when the current monthly category limit is reached.");
    secondary(
        content,
        "Open Android App Settings",
        () ->
            startActivity(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
    button(content, "Continue to Import History", () -> go("Import History"));
    secondary(content, "Go to Home", () -> switchTab("Home"));
  }

  private void imports() {
    message(
        "Initialize or extend your ledger",
        "Imports are optional. Existing transactions are retained. Review cross-source overlaps"
            + " before relying on totals.");
    importProgress = null;
    if (busy) {
      LinearLayout progress = box(content);
      importProgress = text(progress, busyStatus, 16);
      progress.addView(new ProgressBar(this));
    }
    if (!importResult.isEmpty()) info("Last import", importResult);
    LinearLayout files = box(content);
    text(files, "Import a file", 19).setTypeface(designFont, Typeface.BOLD);
    caption(
        files,
        "Normalized CSV/JSON: date, payee, amount, direction; optional reference and bank."
            + " Supported Takeout payment-history HTML is best-effort.");
    caption(
        files,
        "Group expenses JSON opens a participant-selection and split preview before importing."
            + " PDF statements, ZIP archives and arbitrary bank CSV are not supported.");
    button(
        files,
        "Choose Transaction / Group Expenses File",
        () -> {
          Intent i =
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .setType("*/*")
                  .addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(i, 102);
        });
    secondary(content, "Paste a Bank / GPay Message", this::parseMessage);
    secondary(content, "Open Review Inbox", () -> go("Review Inbox"));
    secondary(content, "Start Fresh / Continue", () -> switchTab("Home"));
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    if (request != 102) {
      super.onActivityResult(request, result, data);
      return;
    }
    if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
    Uri file = data.getData();
    busy = true;
    ExpenseApp.IO.execute(
        () -> {
          try (java.io.InputStream in = getContentResolver().openInputStream(file)) {
            if (in == null) throw new java.io.IOException("Cannot open file");
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
              buffer.write(chunk, 0, read);
              if (buffer.size() > 5_000_000)
                throw new IllegalArgumentException("Import limit is 5 MB");
            }
            String json =
                new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (json.startsWith("\uFEFF")) json = json.substring(1);
            if (json.startsWith("{") && new JSONObject(json).has("Group_expenses")) {
              if (TakeoutSplitImport.participants(json).isEmpty())
                throw new IllegalArgumentException("No named participants found in this export");
              final String source = json;
              runOnUiThread(
                  () -> {
                    busy = false;
                    takeoutJson = source;
                    takeoutPlan = null;
                    takeoutSelected.clear();
                    takeoutOverlaps.clear();
                    takeoutConfirmed = false;
                    go("Takeout Owner");
                  });
            } else {
              int added =
                  com.dharaneesh.expensetracker.importing.ImportEngine.importText(repo, json);
              runOnUiThread(
                  () -> {
                    busy = false;
                    importResult = "Imported " + added + " transaction records";
                    toast(importResult);
                    reload();
                  });
            }
          } catch (Exception e) {
            busy = false;
            error(e);
          }
        });
  }

  private void takeoutOwner() {
    if (takeoutJson.isEmpty()) {
      message(
          "Select the file again",
          "The raw export is kept in memory only and is discarded if Android recreates this import"
              + " session.");
      secondary(content, "Choose Import File", () -> go("Import History"));
      return;
    }
    message(
        "Which participant is you?",
        "Select your exact name from this export. Nothing is imported before preview and"
            + " confirmation. Do not select another person's name.");
    LinearLayout card = box(content);
    caption(card, "Your participant name · Type to search or tap to browse");
    AutoCompleteTextView owner = new AutoCompleteTextView(this);
    owner.setTextSize(16);
    owner.setTypeface(designFont);
    owner.setTextColor(DARK);
    owner.setBackground(shape(Color.WHITE, 12));
    owner.setPadding(dp(12), dp(12), dp(12), dp(12));
    owner.setMinHeight(dp(52));
    owner.setThreshold(1);
    owner.setHint("Choose your name");
    owner.setSaveEnabled(false);
    owner.setContentDescription("Your Takeout participant name");
    card.addView(owner);
    try {
      List<String> names = TakeoutSplitImport.participants(takeoutJson);
      owner.setAdapter(
          new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, names));
      owner.setOnClickListener(v -> owner.showDropDown());
      caption(
          card,
          names.size()
              + " participant names found. Select the name used by your Google Pay account.");
    } catch (Exception e) {
      error(e);
      return;
    }
    caption(
        content,
        "Mapping rule: when you created the request, other participants' shares are owed to you."
            + " When someone else created it, only your own share is owed to that creator. Review"
            + " the directions before confirming; this export is not a bank statement.");
    button(
        content,
        "Preview Group Expenses",
        () -> {
          final String choice = owner.getText().toString().trim();
          if (choice.isEmpty()) {
            owner.setError("Choose your participant name");
            return;
          }
          busy = true;
          toast("Preparing local preview…");
          ExpenseApp.IO.execute(
              () -> {
                try {
                  for (JSONObject old : repo.list("split"))
                    if (old.optString("import_format").equals("gpay_group_expenses")
                        && !old.optString("import_owner").equals(TakeoutSplitImport.key(choice)))
                      throw new IllegalArgumentException(
                          "Existing group imports use a different owner. Select the same"
                              + " participant identity; do not mix accounts.");
                  TakeoutSplitImport.Plan plan = TakeoutSplitImport.preview(takeoutJson, choice);
                  Set<String> selected = new HashSet<>(),
                      overlaps = new HashSet<>(),
                      existingIds = new HashSet<>();
                  List<JSONObject> existing = repo.list("split");
                  for (JSONObject row : plan.splits) {
                    String id = row.optString("id");
                    if (repo.db.records().get(id) != null) {
                      plan.existingCount++;
                      existingIds.add(id);
                      continue;
                    }
                    boolean overlap = false;
                    for (JSONObject old : existing)
                      if (!old.optString("import_format").equals("gpay_group_expenses")
                          && TakeoutSplitImport.key(old.optString("payee"))
                              .equals(TakeoutSplitImport.key(row.optString("payee")))
                          && old.optString("direction").equals(row.optString("direction"))
                          && amount(old).compareTo(amount(row)) == 0
                          && (TakeoutSplitImport.key(old.optString("group"))
                                  .equals(TakeoutSplitImport.key(row.optString("group")))
                              || (!old.optString("note").isEmpty()
                                  && TakeoutSplitImport.key(old.optString("note"))
                                      .equals(TakeoutSplitImport.key(row.optString("note")))))) {
                        overlap = true;
                        break;
                      }
                    if (overlap) overlaps.add(id);
                    else selected.add(id);
                  }
                  runOnUiThread(
                      () -> {
                        busy = false;
                        takeoutPlan = plan;
                        existingTakeoutIds.clear();
                        existingTakeoutIds.addAll(existingIds);
                        takeoutSelected.clear();
                        takeoutSelected.addAll(selected);
                        takeoutOverlaps.clear();
                        takeoutOverlaps.addAll(overlaps);
                        takeoutPage = 0;
                        takeoutConfirmed = false;
                        go("Takeout Preview");
                      });
                } catch (Exception e) {
                  busy = false;
                  error(e);
                }
              });
        });
    secondary(
        content,
        "Cancel Import",
        () -> {
          takeoutJson = "";
          takeoutPlan = null;
          back();
        });
  }

  private String takeoutSummary() {
    BigDecimal owe = BigDecimal.ZERO, owed = BigDecimal.ZERO;
    int settled = 0;
    for (JSONObject row : takeoutPlan.splits)
      if (takeoutSelected.contains(row.optString("id"))) {
        if (row.optString("status").equals("settled")) settled++;
        else if (row.optString("direction").equals("you_owe_them")) owe = owe.add(amount(row));
        else owed = owed.add(amount(row));
      }
    return takeoutSelected.size()
        + " selected shares\nPending: you owe "
        + money(owe)
        + " · You are owed "
        + money(owed)
        + "\n"
        + settled
        + " selected settled shares (history only)";
  }

  private void takeoutPreview() {
    if (takeoutPlan == null) {
      message("Preview expired", "Select the export again to continue safely.");
      return;
    }
    message(
        "Review before importing",
        "Owner: "
            + takeoutPlan.owner
            + "\n"
            + takeoutPlan.expenseCount
            + " expenses read. This creates split obligations and settled history only; it never"
            + " creates expenses or income.");
    LinearLayout card = box(content);
    TextView summary = text(card, takeoutSummary(), 16);
    caption(
        card,
        takeoutPlan.existingCount
            + " existing source shares will stay unchanged (including edits, settlements and"
            + " deletions). "
            + takeoutOverlaps.size()
            + " possible notification/manual overlaps are unselected by default.");
    caption(
        card,
        "Closed unpaid or inconsistent completed requests are skipped for manual review. Blank"
            + " names, failed shares, and shares unrelated to you are skipped.");
    for (Map.Entry<String, Integer> skip : takeoutPlan.skipped.entrySet())
      caption(card, skip.getValue() + " · " + skip.getKey());
    List<JSONObject> candidates = new ArrayList<>();
    for (JSONObject row : takeoutPlan.splits) {
      if (!existingTakeoutIds.contains(row.optString("id"))) candidates.add(row);
    }
    // Existing IDs are populated below asynchronously before this screen is shown.
    int start = Math.min(takeoutPage * 25, candidates.size()),
        end = Math.min(start + 25, candidates.size());
    caption(
        content,
        "Preview "
            + (candidates.isEmpty() ? 0 : start + 1)
            + "–"
            + end
            + " of "
            + candidates.size()
            + " new shares. Use pages to review all items.");
    for (int n = start; n < end; n++) {
      JSONObject row = candidates.get(n);
      String id = row.optString("id");
      LinearLayout c = box(content);
      CheckBox select = new CheckBox(this);
      select.setTypeface(designFont);
      select.setText(row.optString("group") + " · " + row.optString("payee"));
      select.setChecked(takeoutSelected.contains(id));
      select.setMinHeight(dp(48));
      c.addView(select);
      text(
          c,
          (row.optString("direction").equals("you_owe_them") ? "You owe " : "They owe you ")
              + money(row.optString("amount")),
          18);
      caption(c, row.optString("status") + " · " + date(row.optLong("occurred_at")) + " IST");
      caption(c, row.optString("note"));
      if (takeoutOverlaps.contains(id))
        text(c, "Possible existing split — review before selecting", 13).setTextColor(RED);
      select.setOnCheckedChangeListener(
          (v, checked) -> {
            if (checked) takeoutSelected.add(id);
            else takeoutSelected.remove(id);
            summary.setText(takeoutSummary());
          });
    }
    if (start > 0)
      secondary(
          content,
          "Previous 25",
          () -> {
            takeoutPage--;
            scrollPositions.put(route, 0);
            render();
          });
    if (end < candidates.size())
      secondary(
          content,
          "Next 25",
          () -> {
            takeoutPage++;
            scrollPositions.put(route, 0);
            render();
          });
    CheckBox confirm = new CheckBox(this);
    confirm.setTypeface(designFont);
    confirm.setText("I checked my identity and the creator-based directions above");
    confirm.setChecked(takeoutConfirmed);
    confirm.setMinHeight(dp(52));
    content.addView(confirm);
    confirm.setOnCheckedChangeListener((v, on) -> takeoutConfirmed = on);
    button(
        content,
        "Import Selected Splits",
        () -> {
          if (!takeoutConfirmed) {
            toast("Confirm your identity and directions first");
            return;
          }
          if (takeoutSelected.isEmpty()) {
            toast("No new shares selected");
            return;
          }
          new AlertDialog.Builder(this)
              .setTitle("Import " + takeoutSelected.size() + " split shares?")
              .setMessage(
                  "Existing records are kept. No expense/income transactions will be created."
                      + " Parsed split records can sync if you have enabled dev cloud sync.")
              .setPositiveButton(
                  "Import",
                  (d, w) -> {
                    if (busy) return;
                    busy = true;
                    final Set<String> chosen = new HashSet<>(takeoutSelected);
                    final TakeoutSplitImport.Plan plan = takeoutPlan;
                    ExpenseApp.IO.execute(
                        () -> {
                          try {
                            int added = TakeoutSplitImport.apply(repo, plan, chosen);
                            runOnUiThread(
                                () -> {
                                  busy = false;
                                  importResult =
                                      "Added "
                                          + added
                                          + " split shares; existing shares unchanged. No financial"
                                          + " transactions created.";
                                  takeoutJson = "";
                                  takeoutPlan = null;
                                  takeoutSelected.clear();
                                  takeoutOverlaps.clear();
                                  existingTakeoutIds.clear();
                                  history.clear();
                                  route = "Shared";
                                  splitFilter = "Pending";
                                  toast(importResult);
                                  reload();
                                });
                          } catch (Exception e) {
                            busy = false;
                            error(e);
                          }
                        });
                  })
              .setNegativeButton("Cancel", null)
              .show();
        });
    secondary(
        content,
        "Cancel Import",
        () -> {
          takeoutJson = "";
          takeoutPlan = null;
          takeoutSelected.clear();
          history.clear();
          route = "Import History";
          render();
        });
  }

  private void review() {
    message(
        "Local review inbox",
        "Raw messages stay on this device and are excluded from financial sync. Incomplete split"
            + " names can be corrected below.");
    int displayed = 0;
    for (JSONObject r : kind("review")) {
      if (displayed++ >= reviewDisplayLimit) continue;
      LinearLayout c = box(content);
      text(c, r.optString("title", "Unparsed message"), 17);
      caption(c, r.optString("source") + " · " + date(r.optLong("occurred_at")));
      secondary(
          c,
          "Reveal Raw Message",
          () ->
              new AlertDialog.Builder(this)
                  .setTitle("Local message content")
                  .setMessage(r.optString("body"))
                  .setPositiveButton("Close", null)
                  .show());
      secondary(
          c,
          "Create Corrected Transaction",
          () -> {
            selectedId = "";
            clearDraft();
            go("Add Transaction");
          });
      secondary(
          c,
          "Create Corrected Split",
          () -> {
            selectedId = "";
            clearDraft();
            go("Create Split");
          });
      secondary(
          c,
          "Dismiss Message",
          () ->
              new AlertDialog.Builder(this)
                  .setTitle("Dismiss this local review item?")
                  .setPositiveButton(
                      "Dismiss", (d, w) -> task(() -> repo.delete(r.optString("id"))))
                  .setNegativeButton("Cancel", null)
                  .show());
    }
    int incomplete = 0;
    for (JSONObject s : kind("split"))
      if (s.optBoolean("group_incomplete")) {
        incomplete++;
        if (displayed++ >= reviewDisplayLimit) continue;
        LinearLayout c = box(content);
        text(c, "Incomplete GPay request", 18);
        caption(c, s.optString("payee") + " · " + money(s.optString("amount")));
        button(c, "Review Split Details", () -> open(s, "Split Detail"));
      }
    if (displayed > reviewDisplayLimit)
      secondary(
          content,
          "Show More Review Items",
          () -> {
            reviewDisplayLimit += 50;
            render();
          });
    if (kind("review").isEmpty() && incomplete == 0)
      message(
          "Nothing to review",
          "New unparsed supported messages or incomplete GPay requests will appear here. Duplicate"
              + " detection runs during capture; uncertain records are not declared reconciled.");
  }

  @Override
  protected void reports() {
    message(
        "Reports & PDF",
        "Your report is generated on this device. No cloud login is required. Exports contain"
            + " financial information outside the app's encrypted storage.");
    secondary(
        content,
        "This Month",
        () -> {
          from = month.atDay(1).toString();
          to = month.atEndOfMonth().toString();
          render();
        });
    secondary(
        content,
        "Last Month",
        () -> {
          YearMonth previous = month.minusMonths(1);
          from = previous.atDay(1).toString();
          to = previous.atEndOfMonth().toString();
          render();
        });
    secondary(content, "Custom Dates & Report Filters", () -> go("Filters"));
    List<JSONObject> rows = filtered();
    LinearLayout c = box(content);
    caption(
        c,
        "REPORT PREVIEW · "
            + (from.isEmpty() ? "All dates" : from)
            + " to "
            + (to.isEmpty() ? "present" : to));
    text(c, rows.size() + " recorded transactions", 20);
    infoReport(c, "Income", sum(rows, "credit"));
    infoReport(c, "Expenses", sum(rows, "debit"));
    infoReport(c, "Net recorded cash flow", sum(rows, "credit").subtract(sum(rows, "debit")));
    if (rows.isEmpty()) caption(c, "No matching data. Adjust filters before exporting.");
    button(
        content,
        "Save PDF Report",
        () -> {
          if (rows.isEmpty()) {
            toast("Choose a range containing transactions");
            return;
          }
          Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf");
          i.putExtra(Intent.EXTRA_TITLE, "Expense-Tracker-" + LocalDate.now(IST) + ".pdf");
          startActivityForResult(i, 101);
        });
  }

  private void infoReport(LinearLayout host, String name, BigDecimal value) {
    text(host, name + "  " + money(value), 16);
  }

  private void privacy() {
    message(
        "Your financial data",
        "Financial payloads are encrypted locally using Android Keystore-backed AES-GCM. Record"
            + " identifiers, type, timestamps, and sync flags are metadata, not whole-database"
            + " encryption.");
    info(
        "Manual input and imports",
        "This dev version has no SMS inbox permission, SMS receiver, or notification listener."
            + " Existing records remain available. You choose the messages and files to import.");
    info(
        "Optional cloud sync",
        "Parsed transactions, budgets, and split records can sync to the configured authenticated"
            + " service. Raw local review messages are excluded.");
    info(
        "Exports and backups",
        "Android app backups/transfers are disabled. Exported reports are outside the app's"
            + " encrypted storage; choose where to save/share them carefully.");
    info(
        "App updates", "Android requires installation approval. Keep Google Play Protect enabled.");
  }

  private void updates() {
    info(
        "Development build",
        BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" + getPackageName());
    message(
        "Dev distribution",
        "Download the dev APK from this repository's GitHub Releases and approve installation in"
            + " Android. QA and prod use separate channels.");
    secondary(content, "About Expense Tracker", () -> go("About"));
  }

  private void about() {
    info(
        "Expense Tracker",
        "Personal-use preview · DEV\nVersion "
            + BuildConfig.VERSION_NAME
            + " ("
            + BuildConfig.VERSION_CODE
            + ")\nPackage "
            + getPackageName());
    info(
        "Sources",
        "Manual transactions, pasted supported HDFC/Union bank messages, and supported CSV/JSON or"
            + " Google Pay Takeout files. Automatic capture is unavailable in this dev version.");
    info(
        "Design",
        "Native Android implementation based on the approved Stitch reference. No web prototype or"
            + " sample financial data is embedded in the app.");
  }

  private void cloud() {
    SharedPreferences prefs = getSharedPreferences("settings", 0);
    message(
        "Optional cloud sync",
        prefs.getString("sync_status", "Not connected · Works offline")
            + ". Connect only a dev backend to this dev installation; the QA backend is a different"
            + " environment.");
    LinearLayout form = box(content);
    EditText base =
        field(form, "Dev API base URL", prefs.getString("api_url", "http://127.0.0.1:8000"));
    EditText email = field(form, "Email", "");
    email.setInputType(33);
    EditText password = field(form, "Password", "");
    password.setInputType(129);
    caption(
        form,
        "HTTPS is required except dev localhost. Your account must already exist; public"
            + " registration may be disabled.");
    button(
        content,
        "Sign In",
        () -> {
          String url = base.getText().toString().trim(),
              user = email.getText().toString().trim(),
              pass = password.getText().toString();
          if (user.isEmpty() || pass.isEmpty()) {
            password.setError("Enter email and password");
            return;
          }
          if (busy) return;
          busy = true;
          toast("Connecting…");
          ExpenseApp.IO.execute(
              () -> {
                try {
                  JSONObject health = SyncWorker.request(url, "/health", "", null);
                  if (!health.optString("environment").equals("dev"))
                    throw new IllegalArgumentException(
                        "This dev app requires a dev backend, not QA/prod");
                  JSONObject result =
                      SyncWorker.request(
                          url,
                          "/api/v1/auth/login",
                          "",
                          new JSONObject().put("email", user).put("password", pass));
                  String previous = prefs.getString("cloud_user", "");
                  if (!previous.isEmpty() && !previous.equals(result.getString("user_id")))
                    throw new IllegalArgumentException(
                        "This installation is linked to another account");
                  prefs
                      .edit()
                      .putString("api_url", url)
                      .putString("cloud_user", result.getString("user_id"))
                      .putString("access", repo.vault.encrypt(result.getString("access_token")))
                      .putString("refresh", repo.vault.encrypt(result.getString("refresh_token")))
                      .putString("sync_status", "Connected · Ready to sync")
                      .apply();
                  runOnUiThread(
                      () -> {
                        busy = false;
                        reload();
                      });
                } catch (Exception e) {
                  busy = false;
                  error(e);
                }
              });
        });
    secondary(
        content,
        "Sync Now",
        () -> {
          if (prefs.getString("refresh", "").isEmpty()) {
            toast("Sign in first");
            return;
          }
          WorkManager.getInstance(this)
              .enqueueUniqueWork(
                  "sync-now",
                  ExistingWorkPolicy.KEEP,
                  new OneTimeWorkRequest.Builder(SyncWorker.class).build());
          toast("Sync queued; local data remains available");
        });
    secondary(
        content,
        "Sign Out (Keep Local Data)",
        () -> {
          String encrypted = prefs.getString("refresh", ""),
              baseUrl = prefs.getString("api_url", "");
          prefs
              .edit()
              .remove("access")
              .remove("refresh")
              .putString("sync_status", "Signed out · Local data retained")
              .apply();
          ExpenseApp.IO.execute(
              () -> {
                try {
                  if (!encrypted.isEmpty())
                    SyncWorker.request(
                        baseUrl,
                        "/api/v1/auth/logout",
                        "",
                        new JSONObject().put("refresh_token", repo.vault.decrypt(encrypted)));
                } catch (Exception ignored) {
                }
              });
          render();
        });
  }
}
