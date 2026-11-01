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
}
