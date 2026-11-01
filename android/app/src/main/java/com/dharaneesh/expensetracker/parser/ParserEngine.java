package com.dharaneesh.expensetracker.parser;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.*;

/** Deterministic parsers: requests and failed payments never become completed expenses. */
public final class ParserEngine {
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private static String find(String pattern, String text) {
    Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
    return m.find() ? m.group(1).trim() : "";
  }

  public static ParsedTransaction parse(String title, String body, String source, long receivedAt) {
    String text = (title + "\n" + body).trim();
    if (text.matches("(?is).*\\b(failed|declined|reversed|pending)\\b.*")
        || text.matches("(?is).*\\bOTP\\s*(?:is|:)?\\s*\\d{4,8}\\b.*")) return null;
    ParsedTransaction p = new ParsedTransaction();
    p.source = source;
    p.occurredAt = receivedAt;
    p.dateInferred = true;
    if (source.equals("gpay") && title.toLowerCase(Locale.ROOT).contains("split request")) {
      Matcher m =
          Pattern.compile("(?is)Pay\\s+(.+?)\\s+₹\\s*([\\d,]+\\.\\d{2})\\s+for\\s+['‘](.+?)['’]")
              .matcher(body);
      if (!m.find()) return null;
      p.split = true;
      p.payee = m.group(1).trim();
      p.amount = new BigDecimal(m.group(2).replace(",", ""));
      p.note = m.group(3);
      p.direction = "you_owe_them";
      p.group = find("in\\s+['‘](.+?)(?:['’]|$)", title);
      p.groupIncomplete = title.endsWith("...") || title.endsWith("…") || p.group.isEmpty();
      if (p.group.isEmpty()) p.group = "GPay group (review name)";
      return p.amount.signum() > 0 ? p : null;
    }
    boolean bankMessage = text.matches("(?is).*(HDFC Bank|Union Bank of India).*");
    if (!source.equals("gpay") && !bankMessage) return null;
    String amount = find("(?:Rs[.:]*|INR|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)", text);
    if (amount.isEmpty()) return null;
    try {
      p.amount = new BigDecimal(amount.replace(",", ""));
    } catch (NumberFormatException ex) {
      return null;
    }
    if (p.amount.signum() <= 0) return null;
    if (text.matches("(?is).*\\b(debited|sent|paid)\\b.*")) p.direction = "debit";
    else if (text.matches("(?is).*\\b(credited|received)\\b.*")) p.direction = "credit";
    else return null;
    if (source.equals("gpay") && text.matches("(?is).*\\b(request|requested|reminder)\\b.*"))
      return null;
    p.bank =
        text.toLowerCase(Locale.ROOT).contains("hdfc")
            ? "HDFC Bank"
            : bankMessage ? "Union Bank of India" : "";
    p.accountLast4 = find("A/?c\\s*(?:\\*|X)+(\\d{4})", text);
    p.reference = find("(?:ref(?:\\s+no)?|UPI)\\s*[:#]?\\s*(\\d{10,})", text);
    String payee = find("Fvg:\\s*(.+?)\\s+Avl", text);
    if (payee.isEmpty()) payee = find("from VPA\\s+([^\\s(]+)", text);
    if (payee.isEmpty() && p.direction.equals("debit"))
      payee = find("\\bTo\\s+(.+?)(?:\\r?\\n|\\s+On\\s)", text);
    if (payee.isEmpty() && source.equals("gpay"))
      payee = find("(?:to|from)\\s+(.+?)(?:\\r?\\n|$)", text);
    if (!payee.isEmpty()) p.payee = payee;
    String date =
        find("\\bon\\s+(\\d{2}[-/]\\d{2}[-/]\\d{2,4}(?:\\s+\\d{2}:\\d{2}:\\d{2})?)", text);
    if (!date.isEmpty()) {
      String normalized = date.replace('/', '-');
      for (String format : new String[] {"dd-MM-uuuu HH:mm:ss", "dd-MM-uuuu", "dd-MM-uu"}) {
        try {
          DateTimeFormatter f =
              DateTimeFormatter.ofPattern(format)
                  .withResolverStyle(java.time.format.ResolverStyle.STRICT);
          p.occurredAt =
              (format.contains("HH")
                      ? LocalDateTime.parse(normalized, f).atZone(IST)
                      : LocalDate.parse(normalized, f).atStartOfDay(IST))
                  .toInstant()
                  .toEpochMilli();
          p.dateInferred = !format.contains("HH");
          break;
        } catch (DateTimeParseException ignored) {
        }
      }
    }
    return p;
  }
}
