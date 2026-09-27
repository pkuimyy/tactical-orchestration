package io.tactical.application;

import io.tactical.core.ContractVersion;

/** Validates versioned envelope metadata without creating or executing game state. */
public final class ContractValidation {
  public Receipt validate(int schemaVersion, Kind kind, String id) {
    ContractVersion.requireSupported(schemaVersion);
    if (kind == null || id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
      throw new IllegalArgumentException("Invalid envelope metadata");
    }
    return new Receipt(schemaVersion, kind, id, true);
  }

  public enum Kind {
    SCENARIO,
    COMMAND,
    EVENT
  }

  public record Receipt(int schemaVersion, Kind kind, String id, boolean valid) {}
}
