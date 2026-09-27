package io.tactical.server;

import io.tactical.application.StoreProblem;
import io.tactical.core.ScenarioViolation;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiExceptionHandler {
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handle(Exception exception) {
    if (exception instanceof StoreProblem problem) {
      int status =
          switch (problem.kind()) {
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case LIMIT -> 429;
          };
      return ResponseEntity.status(status)
          .body(ApiError.of(problem.kind().name(), problem.getMessage()));
    }
    if (exception instanceof ScenarioViolation violation)
      return ResponseEntity.badRequest()
          .body(ApiError.of("INVALID_SCENARIO", violation.getMessage()));
    if (exception instanceof MethodArgumentNotValidException validation) {
      String message =
          validation.getBindingResult().getFieldErrors().stream()
              .map(e -> e.getField() + ": " + e.getDefaultMessage())
              .findFirst()
              .orElse("请求字段不合法");
      return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", message));
    }
    if (exception instanceof HttpMessageNotReadableException) {
      Throwable cause = exception;
      while (cause != null) {
        if (cause instanceof ScenarioViolation violation)
          return ResponseEntity.badRequest()
              .body(ApiError.of("INVALID_SCENARIO", violation.getMessage()));
        cause = cause.getCause();
      }
    }
    int status =
        exception instanceof ErrorResponse error
            ? error.getStatusCode().value()
            : exception instanceof IllegalArgumentException
                    || exception instanceof HttpMessageNotReadableException
                ? 400
                : 500;
    String code =
        switch (status) {
          case 400 -> "INVALID_REQUEST";
          case 404 -> "NOT_FOUND";
          case 405 -> "METHOD_NOT_ALLOWED";
          case 415 -> "UNSUPPORTED_MEDIA_TYPE";
          default -> "INTERNAL_ERROR";
        };
    String message =
        status == 500
            ? "Internal server error"
            : "Request rejected; check method, path and versioned payload";
    return ResponseEntity.status(status).body(ApiError.of(code, message));
  }
}
