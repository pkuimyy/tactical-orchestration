package io.tactical.core;

/** Shared wire/data version; incompatible changes require a new major version. */
public final class ContractVersion {
  public static final int CURRENT = 1;

  private ContractVersion() {}

  public static void requireSupported(int version) {
    if (version != CURRENT) throw new IllegalArgumentException("Unsupported schemaVersion");
  }
}
