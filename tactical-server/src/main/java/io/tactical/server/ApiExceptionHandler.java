package io.tactical.server;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiExceptionHandler {
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handle(Exception exception) {
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
