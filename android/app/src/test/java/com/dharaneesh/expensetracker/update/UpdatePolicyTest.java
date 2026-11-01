package com.dharaneesh.expensetracker.update;

import static org.junit.Assert.*;

import org.junit.Test;

public class UpdatePolicyTest {
  private final String hash = "a".repeat(64);

  private void validate(String channel, String pkg, long code, long size, String sha, int sdk) {
    UpdatePolicy.validate(
        channel, pkg, code, size, sha, sdk, "qa", "com.dharaneesh.expensetracker.qa", 100, 33);
  }

  @Test
  public void newerMatchingReleaseAccepted() {
    validate("qa", "com.dharaneesh.expensetracker.qa", 101, 1000, hash, 26);
  }

  @Test
  public void otherEnvironmentRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("prod", "com.dharaneesh.expensetracker", 101, 1000, hash, 26));
  }

  @Test
  public void downgradeAndSameVersionRejected() {
    for (int code : new int[] {99, 100})
      assertThrows(
          IllegalArgumentException.class,
          () -> validate("qa", "com.dharaneesh.expensetracker.qa", code, 1000, hash, 26));
  }

  @Test
  public void invalidChecksumAndSizeRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("qa", "com.dharaneesh.expensetracker.qa", 101, 0, hash, 26));
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("qa", "com.dharaneesh.expensetracker.qa", 101, 1000, "invalid", 26));
  }

  @Test
  public void unsupportedAndroidRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("qa", "com.dharaneesh.expensetracker.qa", 101, 1000, hash, 34));
  }
}
