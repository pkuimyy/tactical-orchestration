package io.tactical.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class HttpBoundaryTest {
  static ConfigurableApplicationContext context;
  static Path directory;
  static String base;
  static String token;
  static final HttpClient client = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    directory = Files.createTempDirectory("tactical-http-test-");
    SpringApplication app = new SpringApplication(TacticalServer.class);
    app.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    context =
        app.run("--server.port=0", "--tactical.token-file=" + directory.resolve("session.token"));
    base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    token = Files.readString(directory.resolve("session.token"));
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) context.close();
    if (directory != null) {
      Files.deleteIfExists(directory.resolve("session.token"));
      Files.delete(directory);
    }
  }

  HttpResponse<String> request(String path, String method, String body, String auth)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
    if (auth != null) builder.header("Authorization", auth);
    return client.send(
        builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsPublicButApiRequiresExactBearer() throws Exception {
    assertEquals(200, request("/health", "GET", "", null).statusCode());
    for (String auth : new String[] {null, "Bearer wrong", "Basic " + token}) {
      var response = request("/api/v1/system", "GET", "", auth);
      assertEquals(401, response.statusCode());
      assertTrue(response.body().contains("UNAUTHORIZED"));
      assertFalse(response.body().contains(token));
    }
    assertEquals(401, request("/api/v1/system?token=" + token, "GET", "", null).statusCode());
    var response = request("/api/v1/system", "GET", "", "Bearer " + token);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("M0"));
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
  }

  @Test
  void validatesAllEnvelopeKindsAndRejectsInvalidInputs() throws Exception {
    for (String kind : new String[] {"SCENARIO", "COMMAND", "EVENT"}) {
      assertEquals(
          200,
          request(
                  "/api/v1/contracts/validate",
                  "POST",
                  "{\"schemaVersion\":1,\"kind\":\"" + kind + "\",\"id\":\"demo-1\"}",
                  "Bearer " + token)
              .statusCode());
    }
    for (String body :
        new String[] {
          "{",
          "{}",
          "{\"schemaVersion\":2,\"kind\":\"COMMAND\",\"id\":\"x\"}",
          "{\"schemaVersion\":1,\"kind\":\"COMMAND\",\"id\":\"../bad\"}",
          "{\"schemaVersion\":1,\"kind\":\"UNKNOWN\",\"id\":\"x\"}"
        }) {
      var response = request("/api/v1/contracts/validate", "POST", body, "Bearer " + token);
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains("INVALID_REQUEST"));
    }
  }

  @Test
  void rejectsOversizedFixedAndChunkedBodies() throws Exception {
    assertEquals(
        413,
        request("/api/v1/contracts/validate", "POST", "x".repeat(65537), "Bearer " + token)
            .statusCode());
    var request =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/contracts/validate"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(new byte[65537])))
            .build();
    assertEquals(413, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void documentsApiAndNormalizesRoutingErrors() throws Exception {
    var spec = request("/api/v1/openapi", "GET", "", "Bearer " + token);
    assertEquals(200, spec.statusCode());
    assertTrue(spec.body().contains("3.1.0"));
    assertEquals(404, request("/api/v1/missing", "GET", "", "Bearer " + token).statusCode());
    assertEquals(405, request("/api/v1/system", "POST", "", "Bearer " + token).statusCode());
  }

  @Test
  void openApiIsGeneratedFromRoutesAndValidatedDtos() throws Exception {
    assertEquals(401, request("/api/v1/openapi", "GET", "", null).statusCode());
    var response = request("/api/v1/openapi", "GET", "", "Bearer " + token);
    var document = new tools.jackson.databind.ObjectMapper().readTree(response.body());
    var paths = document.path("paths");
    assertTrue(paths.has("/health"));
    assertTrue(paths.has("/api/v1/system"));
    var operation = paths.path("/api/v1/contracts/validate").path("post");
    assertTrue(operation.path("requestBody").path("required").asBoolean());
    var schemas = document.path("components").path("schemas");
    var envelope = schemas.path("Envelope");
    assertEquals(
        "[A-Za-z0-9_-]{1,64}", envelope.path("properties").path("id").path("pattern").asText());
    assertEquals(1, envelope.path("properties").path("schemaVersion").path("minimum").asInt());
    assertTrue(envelope.path("required").toString().contains("kind"));
    assertTrue(
        envelope.path("properties").path("kind").path("enum").toString().contains("COMMAND"));
    assertTrue(schemas.path("Receipt").path("properties").has("valid"));
    assertTrue(schemas.path("SystemInfo").path("properties").has("rulesVersion"));
    assertTrue(schemas.path("ApiError").path("properties").has("code"));
    assertEquals(
        "bearer",
        document
            .path("components")
            .path("securitySchemes")
            .path("sessionBearer")
            .path("scheme")
            .asText());
    assertTrue(document.path("security").get(0).has("sessionBearer"));
    assertTrue(paths.path("/health").path("get").path("security").isEmpty());
    assertTrue(operation.path("responses").has("401"));
    assertTrue(operation.path("responses").has("400"));
    assertNull(getClass().getResource("/openapi.json"));
  }

  @Test
  void rejectsUnsupportedMediaAndEncoding() throws Exception {
    var wrongType =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/contracts/validate"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    assertEquals(415, client.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());
    var compressed =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/contracts/validate"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    assertEquals(415, client.send(compressed, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void tokenIsPrivateAndRotates() throws Exception {
    assertEquals(43, token.length());
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(directory.resolve("session.token")));
    Path other = directory.resolve("other.token");
    try {
      SessionToken first = new SessionToken(other.toString());
      String previous = Files.readString(other);
      SessionToken second = new SessionToken(other.toString());
      assertTrue(first.matches("Bearer " + previous));
      assertFalse(second.matches("Bearer " + previous));
      assertNotEquals(token, Files.readString(other));
    } finally {
      Files.deleteIfExists(other);
    }
    assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
  }
}
