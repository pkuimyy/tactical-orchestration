package io.tactical.server;

public record ApiError(int schemaVersion, String code, String message) {
  static ApiError of(String code, String message) {
    return new ApiError(1, code, message);
  }
}
