package com.dharaneesh.expensetracker.update;

/** Pure validation shared by the updater and its unit tests. */
public final class UpdatePolicy {
  public static final long MAX_BYTES = 100_000_000L;

  public static void validate(
      String channel,
      String packageName,
      long code,
      long size,
      String sha256,
      int minSdk,
      String expectedChannel,
      String expectedPackage,
      long installedCode,
      int deviceSdk) {
    if (!channel.equals(expectedChannel) || !packageName.equals(expectedPackage))
      throw new IllegalArgumentException("This update belongs to another app environment");
    if (code <= installedCode || code > 2100000000L)
      throw new IllegalArgumentException("There is no newer version to install");
    if (size <= 0 || size > MAX_BYTES) throw new IllegalArgumentException("Update size is invalid");
    if (!sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Update checksum is invalid");
    if (minSdk > deviceSdk)
      throw new IllegalArgumentException("This update needs a newer Android version");
  }

  private UpdatePolicy() {}
}
