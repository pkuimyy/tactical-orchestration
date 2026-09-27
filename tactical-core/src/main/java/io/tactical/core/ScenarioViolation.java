package io.tactical.core;

/** Safe, actionable domain validation message for editor clients. */
public final class ScenarioViolation extends IllegalArgumentException {
  public ScenarioViolation(String message) {
    super(message);
  }
}
