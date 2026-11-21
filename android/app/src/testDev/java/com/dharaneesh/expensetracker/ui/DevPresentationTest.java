package com.dharaneesh.expensetracker.ui;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import org.junit.Test;

public class DevPresentationTest {
  @Test
  public void indianGroupingHandlesCroresPaiseAndNegativeValues() {
    assertEquals("₹1,25,000.50", DevPresentation.money(new BigDecimal("125000.50")));
    assertEquals("₹12,34,56,789.00", DevPresentation.money(new BigDecimal("123456789")));
    assertEquals("−₹1,000.01", DevPresentation.money(new BigDecimal("-1000.01")));
    assertEquals("₹0.00", DevPresentation.money(BigDecimal.ZERO));
  }

  @Test
  public void inputAcceptsPaiseAndIndianGrouping() {
    assertEquals(new BigDecimal("125000.50"), DevPresentation.positive("1,25,000.50"));
    assertEquals(new BigDecimal("0.01"), DevPresentation.positive("0.01"));
  }

  @Test
  public void rejectsZeroNegativeOverprecisionAndOutOfRange() {
    for (String input : new String[] {"0", "-1", "1.001", "10000000000.00", "", "NaN"}) {
      try {
        DevPresentation.positive(input);
        fail("Invalid amount accepted");
      } catch (IllegalArgumentException | ArithmeticException expected) {
      }
    }
  }
}
