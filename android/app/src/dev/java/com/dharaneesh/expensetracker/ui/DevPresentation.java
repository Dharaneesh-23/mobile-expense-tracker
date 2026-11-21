package com.dharaneesh.expensetracker.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** INR grouping without relying on platform locale variants. */
public final class DevPresentation {
  public static String money(BigDecimal number) {
    String value = number.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
    String[] parts = value.split("\\.");
    String whole = parts[0];
    if (whole.length() > 3) {
      String tail = whole.substring(whole.length() - 3);
      String head = whole.substring(0, whole.length() - 3);
      StringBuilder grouped = new StringBuilder();
      for (int i = 0; i < head.length(); i++) {
        if (i > 0 && (head.length() - i) % 2 == 0) grouped.append(',');
        grouped.append(head.charAt(i));
      }
      whole = grouped + "," + tail;
    }
    return (number.signum() < 0 ? "−" : "") + "₹" + whole + "." + parts[1];
  }

  public static BigDecimal positive(String input) {
    BigDecimal n = new BigDecimal(input.trim().replace(",", ""));
    n = n.setScale(2, RoundingMode.UNNECESSARY);
    if (n.signum() <= 0 || n.compareTo(new BigDecimal("9999999999.99")) > 0)
      throw new IllegalArgumentException("Enter an amount from ₹0.01 to ₹9,99,99,99,999.99");
    return n;
  }

  private DevPresentation() {}
}
