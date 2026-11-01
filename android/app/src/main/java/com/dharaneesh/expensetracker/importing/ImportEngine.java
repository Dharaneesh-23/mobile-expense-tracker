package com.dharaneesh.expensetracker.importing;

import com.dharaneesh.expensetracker.data.Repository;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public final class ImportEngine {
  private static String id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
  }

  public static int importText(Repository repo, String text) throws Exception {
    String s = text.trim();
    if (s.startsWith("\uFEFF")) s = s.substring(1);
    if (s.startsWith("{") || s.startsWith("[")) return json(repo, s);
    if (s.toLowerCase(Locale.ROOT).contains("outer-cell")) return html(repo, s);
    if (s.startsWith("%PDF"))
      throw new IllegalArgumentException(
          "PDF statements are not supported on-device yet. Export a CSV instead.");
    return csv(repo, s);
  }

  private static int json(Repository repo, String text) throws Exception {
    JSONObject root =
        text.startsWith("{")
            ? new JSONObject(text)
            : new JSONObject().put("transactions", new JSONArray(text));
    if (root.has("Group_expenses"))
      throw new IllegalArgumentException(
          "Takeout split imports need the account owner's name to determine who owes whom. Use the"
              + " manual split screen until this importer is configured.");
    JSONArray rows = root.optJSONArray("transactions");
    if (rows == null) throw new IllegalArgumentException("Expected a transactions array");
    List<JSONObject> valid = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      JSONObject data =
          normalized(
              r.getString("date"),
              r.getString("payee"),
              r.getString("amount"),
              r.getString("direction"),
              r.optString("reference"),
              r.optString("bank"));
      valid.add(data);
    }
    for (JSONObject r : valid) repo.save(r);
    return valid.size();
  }

  private static JSONObject normalized(
      String date, String payee, String amount, String direction, String reference, String bank)
      throws Exception {
    BigDecimal n =
        new BigDecimal(amount.replace(",", "").replace("₹", "").trim())
            .setScale(2, RoundingMode.UNNECESSARY);
    if (n.signum() <= 0
        || !Arrays.asList("debit", "credit").contains(direction)
        || payee.trim().isEmpty()) throw new IllegalArgumentException("Invalid transaction row");
    long at;
    try {
      at = Instant.parse(date).toEpochMilli();
    } catch (DateTimeParseException e) {
      at = LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();
    }
    String key =
        reference.isEmpty()
            ? date + "|" + payee + "|" + n + "|" + direction
            : reference + "|" + direction;
    return new JSONObject()
        .put("id", id("import|" + key))
        .put("kind", "transaction")
        .put("amount", n.toPlainString())
        .put("direction", direction)
        .put("payee", payee)
        .put("reference", reference)
        .put("bank", bank)
        .put("category", Repository.category(payee))
        .put("source", "import")
        .put("occurred_at", at)
        .put("date_inferred", true);
  }

  private static List<List<String>> parseCsv(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quote = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        if (quote && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else quote = !quote;
      } else if (c == ',' && !quote) {
        row.add(cell.toString());
        cell.setLength(0);
      } else if (c == '\n' && !quote) {
        row.add(cell.toString().trim());
        rows.add(row);
        row = new ArrayList<>();
        cell.setLength(0);
      } else if (c != '\r' || quote) cell.append(c);
    }
    if (quote) throw new IllegalArgumentException("Unclosed CSV quote");
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString().trim());
      rows.add(row);
    }
    return rows;
  }

  private static int csv(Repository repo, String text) throws Exception {
    List<List<String>> rows = parseCsv(text);
    if (rows.isEmpty()) return 0;
    List<String> header = new ArrayList<>();
    for (String h : rows.get(0)) header.add(h.trim().toLowerCase(Locale.ROOT));
    for (String required : new String[] {"date", "payee", "amount", "direction"})
      if (!header.contains(required))
        throw new IllegalArgumentException(
            "CSV header must include date,payee,amount,direction; optional reference,bank. Dates:"
                + " YYYY-MM-DD.");
    List<JSONObject> valid = new ArrayList<>();
    for (int i = 1; i < rows.size(); i++) {
      List<String> row = rows.get(i);
      if (row.size() == 1 && row.get(0).trim().isEmpty()) continue;
      if (row.size() != header.size())
        throw new IllegalArgumentException("CSV column mismatch on row " + (i + 1));
      valid.add(
          normalized(
              row.get(header.indexOf("date")),
              row.get(header.indexOf("payee")),
              row.get(header.indexOf("amount")),
              row.get(header.indexOf("direction")).toLowerCase(Locale.ROOT),
              header.contains("reference") ? row.get(header.indexOf("reference")) : "",
              header.contains("bank") ? row.get(header.indexOf("bank")) : ""));
    }
    for (JSONObject data : valid) repo.save(data);
    return valid.size();
  }

  private static int html(Repository repo, String html) {
    Matcher blocks =
        Pattern.compile(
                "(?is)<div[^>]*class=\"outer-cell[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"outer-cell|$)")
            .matcher(html);
    int count = 0;
    while (blocks.find()) {
      String text =
          android.text.Html.fromHtml(blocks.group(1), android.text.Html.FROM_HTML_MODE_LEGACY)
              .toString();
      if (text.trim().startsWith("Ads") || !text.matches("(?is).*\\bCompleted\\b.*")) continue;
      Matcher date =
          Pattern.compile("([A-Z][a-z]{2} \\d{1,2}, \\d{4}, \\d{1,2}:\\d{2}:\\d{2} [AP]M) IST")
              .matcher(text);
      if (!date.find()) continue;
      try {
        long at =
            LocalDateTime.parse(
                    date.group(1), DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm:ss a", Locale.US))
                .atZone(ZoneId.of("Asia/Kolkata"))
                .toInstant()
                .toEpochMilli();
        if (repo.capture("", text, "gpay", at, "takeout:" + id(text))) count++;
      } catch (DateTimeParseException ignored) {
      }
    }
    return count;
  }
}
