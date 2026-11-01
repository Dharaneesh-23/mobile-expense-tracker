package com.dharaneesh.expensetracker.parser;

import java.math.BigDecimal;

public class ParsedTransaction {
  public BigDecimal amount;
  public String direction,
      payee = "Unknown",
      bank = "",
      accountLast4 = "",
      reference = "",
      source = "",
      note = "",
      group = "";
  public long occurredAt;
  public boolean split, dateInferred, groupIncomplete;
}
