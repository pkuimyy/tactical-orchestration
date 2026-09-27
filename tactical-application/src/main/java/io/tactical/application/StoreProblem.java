package io.tactical.application;

public final class StoreProblem extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    CONFLICT,
    LIMIT
  }

  private final Kind kind;

  public StoreProblem(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
