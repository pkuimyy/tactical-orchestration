package io.tactical.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ApiBoundary extends OncePerRequestFilter {
  private final SessionToken token;
  private final int limit;

  ApiBoundary(SessionToken token, @Value("${tactical.max-request-bytes}") int limit) {
    if (limit < 1 || limit > 1048576) throw new IllegalArgumentException("Invalid request limit");
    this.token = token;
    this.limit = limit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    boolean health = request.getMethod().equals("GET") && request.getRequestURI().equals("/health");
    var headers = Collections.list(request.getHeaders("Authorization"));
    if (!health && (headers.size() != 1 || !token.matches(headers.getFirst()))) {
      response.setHeader("WWW-Authenticate", "Bearer");
      reject(response, 401, "UNAUTHORIZED", "A valid session Bearer token is required");
      return;
    }
    if (request.getHeader("Content-Encoding") != null) {
      reject(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Content-Encoding is not supported");
      return;
    }
    if (request.getContentLengthLong() > limit) {
      reject(response, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds limit");
      return;
    }
    byte[] body = request.getInputStream().readNBytes(limit + 1);
    if (body.length > limit) {
      reject(response, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds limit");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public int read(byte[] b, int off, int len) {
                return input.read(b, off, len);
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }
        },
        response);
  }

  private static void reject(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response
        .getWriter()
        .write("{\"schemaVersion\":1,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
