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

  private static final String ACTION =
      "debited|credited|sent|paid|received|spent|withdrawn|deposited|refunded";
  private static final String CURRENCY = "(?:\\b(?:Rs|INR)\\.?\\s*:?\\s*|₹\\s*)";
  private static final Pattern MONEY =
      Pattern.compile(
          CURRENCY + "([0-9][0-9,]*(?:\\s*\\.\\s*[0-9]{1,2})?)(?![0-9,]|\\s*\\.\\s*[0-9])",
          Pattern.CASE_INSENSITIVE);

  private static String normalize(String text) {
    return java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFKC)
        .replace('\u00a0', ' ')
        .replace('\u202f', ' ')
        .replace("\u200b", "");
  }

  public static boolean isSupportedBankMessage(String title, String body) {
    return (normalize(title) + " " + normalize(body))
        .matches(
            "(?is).*\\b(?:HDFC(?:\\s*Bank|BK|BANK)?|Union\\s*Bank(?:\\s+of\\s+India)?|UNIONBK|UNIONB)\\b.*");
  }

  private static boolean excluded(String text) {
    return text.matches("(?is).*\\b(failed|declined|reversed|pending|unsuccessful)\\b.*")
        || text.matches(
            "(?is).*\\b(?:OTP|verification code|one[- ]time password|password)\\b[^.\\n"
                + "]{0,80}\\b\\d{4,8}\\b.*")
        || text.matches(
            "(?is).*\\b(?:OTP|one[- ]time password|verification code)\\s*(?:is|:|for[^.\\n"
                + "]{0,60}?is)?\\s*\\d{4,8}\\b.*")
        || text.matches("(?is).*\\b\\d{4,8}\\s+(?:is your\\s+)?(?:OTP|verification code)\\b.*");
  }

  /** Review only likely financial events, never every message mentioning a bank. */
  public static boolean shouldReview(String title, String body, String source) {
    String text = normalize(title) + "\n" + normalize(body);
    if (excluded(text)) return false;
    if ("gpay".equals(source)
        && normalize(title).toLowerCase(Locale.ROOT).contains("split request")) return true;
    if (!"gpay".equals(source) && !isSupportedBankMessage(title, body)) return false;
    if (text.matches(
        "(?is).*\\b(?:offer|pre[- ]approved|apply now|eligible|reward points|payment due|amount"
            + " due|due date|minimum due|scheduled|will be|will get|will receive|please"
            + " pay|request|requested|reminder)\\b.*")) return false;
    return text.matches("(?is).*\\b(?:" + ACTION + "|transaction|txn)\\b.*")
        && text.matches("(?is).*(?:" + CURRENCY + "|\\bamount\\b|\\bamt\\b).*");
  }

  private static String directionOf(String action) {
    return action.matches("(?i)credited|received|deposited|refunded") ? "credit" : "debit";
  }

  /** Bind a currency amount to the completion verb, excluding balances and credit limits. */
  private static boolean readAmount(ParsedTransaction p, String text) {
    Matcher amounts = MONEY.matcher(text);
    BigDecimal chosen = null;
    String chosenDirection = "";
    while (amounts.find()) {
      String raw = amounts.group(1).replaceAll("\\s", "");
      if (!raw.matches(
          "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]{1,2})?"))
        continue;
      BigDecimal value;
      try {
        value = new BigDecimal(raw.replace(",", ""));
      } catch (NumberFormatException bad) {
        continue;
      }
      if (value.signum() <= 0 || value.compareTo(new BigDecimal("999999999999.99")) > 0) continue;
      String before = text.substring(Math.max(0, amounts.start() - 90), amounts.start());
      if (before.matches(
          "(?is).*\\b(?:balance|bal|limit|due|outstanding|reward|cashback"
              + " offer)\\s*(?:is|of|:|-)?\\s*$")) continue;
      String after = text.substring(amounts.end(), Math.min(text.length(), amounts.end() + 90));
      String action =
          find(
              "\\b("
                  + ACTION
                  + ")\\b\\s*(?:(?:by|for|of|with|an?|amount|amt|sum|INR|Rs)[:. ]*\\s*)*$",
              before);
      if (action.isEmpty())
        action =
            find(
                "^\\s*(?:/-)?\\s*(?:(?:has been|have been|was|is|successfully)\\s+)*("
                    + ACTION
                    + ")\\b",
                after);
      if (action.isEmpty()) continue;
      String dir = directionOf(action);
      if (chosen != null && (chosen.compareTo(value) != 0 || !chosenDirection.equals(dir)))
        return false;
      chosen = value;
      chosenDirection = dir;
    }
    if (chosen == null) return false;
    p.amount = chosen;
    p.direction = chosenDirection;
    return true;
  }

  public static ParsedTransaction parse(String title, String body, String source, long receivedAt) {
    title = normalize(title);
    body = normalize(body);
    String text = (title + "\n" + body).trim();
    if (excluded(text)) return null;
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
    boolean bankMessage = isSupportedBankMessage(title, body);
    if (!shouldReview(title, body, source) || !readAmount(p, text)) return null;
    p.bank =
        text.matches("(?is).*\\bHDFC(?:\\s*Bank|BK|BANK)?\\b.*")
            ? "HDFC Bank"
            : bankMessage ? "Union Bank of India" : "";
    p.accountLast4 =
        find(
            "(?:A/?c|account)(?:\\s*(?:no|number)\\.?)?\\s*[:#]?\\s*(?:[*x]+\\s*)?(\\d{4})(?!\\d)",
            text);
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
