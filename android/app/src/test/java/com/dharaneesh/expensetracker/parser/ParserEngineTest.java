package com.dharaneesh.expensetracker.parser;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class ParserEngineTest {
  private ParsedTransaction sms(String text) {
    return ParserEngine.parse("BANK", text, "sms", 1700000000000L);
  }

  @Test
  public void unionDebit() {
    ParsedTransaction p =
        sms(
            "Union Bank of India A/c *1234 Debited Rs:25.00 on 30-10-2026 23:16:56 by Mob Bk ref no"
                + " 111111111111, Fvg: SAMPLE PAYEE Avl Bal Rs:126.51. Not you?Call 18002333");
    assertNotNull(p);
    assertEquals("25.00", p.amount.toPlainString());
    assertEquals("debit", p.direction);
    assertEquals("1234", p.accountLast4);
    assertEquals("SAMPLE PAYEE", p.payee);
    assertEquals("111111111111", p.reference);
    assertEquals(
        LocalDateTime.of(2026, 10, 30, 23, 16, 56)
            .atZone(ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli(),
        p.occurredAt);
  }

  @Test
  public void hdfcDebit() {
    ParsedTransaction p =
        sms(
            "Sent Rs.30.00\n"
                + "From HDFC Bank A/C *4321\n"
                + "To SAMPLE PERSON\n"
                + "On 24/10/26\n"
                + "Ref 222222222222\n"
                + "Not You?");
    assertNotNull(p);
    assertEquals("30.00", p.amount.toPlainString());
    assertEquals("SAMPLE PERSON", p.payee);
    assertEquals("4321", p.accountLast4);
    assertTrue(p.dateInferred);
  }

  @Test
  public void unionCredit() {
    ParsedTransaction p =
        sms(
            "A/c *1234 Credited for Rs:43.88 on 19-10-2026 17:44:09 by Mob Bk ref no 333333333333"
                + " Avl Bal Rs:952.51.Never Share OTP/PIN/CVV-Union Bank of India");
    assertNotNull(p);
    assertEquals("43.88", p.amount.toPlainString());
    assertEquals("credit", p.direction);
  }

  @Test
  public void hdfcCredit() {
    ParsedTransaction p =
        sms(
            "Credit Alert!\n"
                + "Rs.20.00 credited to HDFC Bank A/c XX4321 on 24-10-26 from VPA *user@oksbi (UPI"
                + " 444444444444)");
    assertNotNull(p);
    assertEquals("credit", p.direction);
    assertEquals("*user@oksbi", p.payee);
    assertEquals("444444444444", p.reference);
  }

  @Test
  public void splitIsObligation() {
    ParsedTransaction p =
        ParserEngine.parse(
            "New split request in ‘Trip…",
            "Pay Sample Person ₹70.83 for ‘breakfast’",
            "gpay",
            1700000000000L);
    assertNotNull(p);
    assertTrue(p.split);
    assertTrue(p.groupIncomplete);
    assertEquals("you_owe_them", p.direction);
    assertEquals("70.83", p.amount.toPlainString());
  }

  @Test
  public void unrelatedAndFailures() {
    assertNull(sms("Your verification OTP is 123456"));
    assertNull(sms("HDFC Bank paid Rs.25.00 failed"));
    assertNull(sms("Balance Rs.123.00 HDFC Bank"));
  }

  @Test
  public void paymentRequestNotExpense() {
    assertNull(ParserEngine.parse("Payment request", "Pay ₹20.00 to Sample", "gpay", 0));
  }

  @Test
  public void currencySpacingGroupingAndMinorUnits() {
    String[][] cases = {
      {"Rs. 1,234.50", "1234.50"},
      {"INR: 1,00,000.25", "100000.25"},
      {"₹\u00a025.5", "25.5"},
      {"Rs:25 /-", "25"},
      {"INR 250. 00", "250.00"},
      {"Rs.0.01", "0.01"},
      {"INR\u202f1,000", "1000"}
    };
    for (String[] c : cases) {
      ParsedTransaction p =
          sms("HDFC Bank A/c XX4321 has been debited by " + c[0] + " on 24-10-26 Ref 111111111111");
      assertNotNull(c[0], p);
      assertEquals(c[1], p.amount.toPlainString());
    }
  }

  @Test
  public void transactionAmountWinsOverBalanceAndLimit() {
    for (String body :
        new String[] {
          "HDFC Bank Available balance INR 5,000.00. INR 250.00 has been debited from A/c XX4321.",
          "Union Bank of India A/c *1234 credited with Rs.43.88. Avl Bal Rs.952.51.",
          "HDFC Bank Credit limit Rs.50,000.00. Rs.250.00 spent using card XX4321."
        }) {
      ParsedTransaction p = sms(body);
      assertNotNull(body, p);
      assertEquals(body.contains("43.88") ? "43.88" : "250.00", p.amount.toPlainString());
    }
  }

  @Test
  public void supportedSenderAliasesAndCompletionVerbs() {
    ParsedTransaction p =
        ParserEngine.parse(
            "VM-HDFCBK-S", "INR 75.00 withdrawn from account XX4321 on 24-10-26", "sms", 0);
    assertNotNull(p);
    assertEquals("HDFC Bank", p.bank);
    assertEquals("debit", p.direction);
    assertEquals("4321", p.accountLast4);
    p = ParserEngine.parse("AD-UNIONB-S", "Rs.15.50 refunded to A/c *1234", "sms", 0);
    assertNotNull(p);
    assertEquals("Union Bank of India", p.bank);
    assertEquals("credit", p.direction);
  }

  @Test
  public void nonTransactionsDoNotEnterReview() {
    for (String body :
        new String[] {
          "HDFC Bank balance Rs.500.00",
          "HDFC Bank OTP for transaction 123456 amount Rs100",
          "HDFC Bank transaction OTP is 123456 for Rs.100",
          "123456 is your OTP for HDFC Bank transaction Rs.100",
          "HDFC Bank payment of Rs.500 failed",
          "HDFC Bank payment Rs.500 pending",
          "HDFC Bank pre-approved loan Rs.50000 apply now",
          "HDFC Bank pay minimum due Rs.500 by due date",
          "HDFC Bank Rs.500 will be debited tomorrow",
          "HDFC Bank payment request Rs.500",
          "Union Bank of India credited cashback offer Rs.50 apply now"
        }) {
      assertNull(body, sms(body));
      assertFalse(body, ParserEngine.shouldReview("BANK", body, "sms"));
    }
  }

  @Test
  public void ambiguousAndMalformedAmountsStayForReview() {
    for (String body :
        new String[] {
          "HDFC Bank Rs.100 debited and Rs.25 credited to A/c XX4321",
          "HDFC Bank debited Rs.1,2,3.00 from A/c XX4321",
          "HDFC Bank debited INR 25.999 from A/c XX4321",
          "Union Bank of India transaction amount unavailable for A/c *1234"
        }) {
      assertNull(body, sms(body));
      assertTrue(body, ParserEngine.shouldReview("BANK", body, "sms"));
    }
  }

  @Test
  public void oldSecurityFooterDoesNotSuppressCompletedTransaction() {
    ParsedTransaction p =
        sms("Union Bank of India A/c *1234 credited for Rs.25.00. Never share OTP/PIN/CVV");
    assertNotNull(p);
    assertEquals("25.00", p.amount.toPlainString());
    assertNull(ParserEngine.parse(null, null, "sms", 0));
  }
}
