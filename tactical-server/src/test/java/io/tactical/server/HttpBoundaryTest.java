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
    assertTrue(response.body().contains("M2"));
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
  }

  @Test
  void movementRoutesAreCodeDocumentedAndInputsBounded() throws Exception {
    var spec = request("/api/v1/openapi", "GET", "", "Bearer " + token).body();
    for (var path :
        new String[] {
          "/api/v1/games/{id}/turn",
          "/api/v1/games/{id}/orders/{side}",
          "/api/v1/games/{id}/commit/{side}",
          "/api/v1/games/{id}/resolve",
          "/api/v1/games/{id}/days/{day}/events",
          "/api/v1/scenarios/{id}/copy"
        }) assertTrue(spec.contains(path), path);
    assertTrue(spec.contains("MovementOrder"));
    assertTrue(spec.contains("Manifest"));
    assertEquals(
        200, request("/api/v1/presets/recon-pursuit", "GET", "", "Bearer " + token).statusCode());
    assertEquals(
        400,
        request(
                "/api/v1/games",
                "POST",
                "{\"revisionId\":\"unused\",\"seed\":42,\"maxIterations\":9}",
                "Bearer " + token)
            .statusCode());
    assertEquals(
        400,
        request("/api/v1/games/unused/resolve", "POST", "{\"day\":0}", "Bearer " + token)
            .statusCode());
    assertEquals(
        400,
        request(
                "/api/v1/games/unused/orders/BLUE",
                "PUT",
                "{\"day\":1,\"expectedVersion\":0,\"orders\":[{\"orderId\":\"invalid id\",\"regimentId\":\"a\",\"route\":[]}]}",
                "Bearer " + token)
            .statusCode());
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
  void scenarioLifecycleOverHttpAndStaticClient() throws Exception {
    for (String path : new String[] {"/", "/index.html", "/app.js", "/style.css"}) {
      var response = request(path, "GET", "", null);
      assertEquals(200, response.statusCode(), path);
      assertTrue(
          response
              .headers()
              .firstValue("Content-Security-Policy")
              .orElseThrow()
              .contains("default-src 'self'"));
    }
    assertEquals(401, request("/api/v1/scenarios", "GET", "", null).statusCode());
    var mapper = new tools.jackson.databind.ObjectMapper();
    var preset =
        mapper.readTree(
            request("/api/v1/presets/river-valley", "GET", "", "Bearer " + token).body());
    var imported =
        request("/api/v1/scenarios/import", "POST", preset.toString(), "Bearer " + token);
    assertEquals(201, imported.statusCode(), imported.body());
    String id = mapper.readTree(imported.body()).path("id").asText();
    var frozen =
        request(
            "/api/v1/scenarios/" + id + "/revisions",
            "POST",
            "{\"expectedVersion\":1}",
            "Bearer " + token);
    assertEquals(200, frozen.statusCode(), frozen.body());
    var revision = mapper.readTree(frozen.body());
    String revisionId = revision.path("id").asText();
    String creation = "{\"revisionId\":\"" + revisionId + "\",\"seed\":42}";
    var a = request("/api/v1/games", "POST", creation, "Bearer " + token);
    var b = request("/api/v1/games", "POST", creation, "Bearer " + token);
    assertEquals(201, a.statusCode());
    assertEquals(201, b.statusCode());
    var first = mapper.readTree(a.body());
    var second = mapper.readTree(b.body());
    assertNotEquals(first.path("id"), second.path("id"));
    assertEquals(first.path("initialState"), second.path("initialState"));
    String exported =
        request("/api/v1/scenarios/" + id + "/export", "GET", "", "Bearer " + token).body();
    var clone =
        mapper.readTree(
            request("/api/v1/scenarios/import", "POST", exported, "Bearer " + token).body());
    var clonedRevision =
        mapper.readTree(
            request(
                    "/api/v1/scenarios/" + clone.path("id").asText() + "/revisions",
                    "POST",
                    "{\"expectedVersion\":1}",
                    "Bearer " + token)
                .body());
    assertEquals(revision.path("contentHash"), clonedRevision.path("contentHash"));
    ((tools.jackson.databind.node.ObjectNode) preset).put("name", "Edited draft");
    var update = mapper.createObjectNode().put("expectedVersion", 1).set("scenario", preset);
    assertEquals(
        200,
        request("/api/v1/scenarios/" + id, "PUT", update.toString(), "Bearer " + token)
            .statusCode());
    assertEquals(
        409,
        request("/api/v1/scenarios/" + id, "PUT", update.toString(), "Bearer " + token)
            .statusCode());
    assertEquals(
        409,
        request("/api/v1/revisions/" + revisionId, "PUT", exported, "Bearer " + token)
            .statusCode());
    assertEquals(
        first,
        mapper.readTree(
            request("/api/v1/games/" + first.path("id").asText(), "GET", "", "Bearer " + token)
                .body()));
    assertEquals(
        405,
        request("/api/v1/games/" + first.path("id").asText(), "PUT", exported, "Bearer " + token)
            .statusCode());
    var spec = mapper.readTree(request("/api/v1/openapi", "GET", "", "Bearer " + token).body());
    assertTrue(spec.path("paths").has("/api/v1/scenarios/{id}/revisions"));
    assertTrue(spec.path("components").path("schemas").has("Scenario"));
  }

  @Test
  void invalidScenarioImportsReturnActionableErrors() throws Exception {
    var mapper = new tools.jackson.databind.ObjectMapper();
    String preset = request("/api/v1/presets/river-valley", "GET", "", "Bearer " + token).body();
    for (int kind = 0; kind < 5; kind++) {
      var input = (tools.jackson.databind.node.ObjectNode) mapper.readTree(preset);
      if (kind == 0) input.put("width", 17);
      if (kind == 1)
        ((tools.jackson.databind.node.ObjectNode) input.path("regiments").get(1))
            .set("position", input.path("regiments").get(0).path("position"));
      if (kind == 2)
        ((tools.jackson.databind.node.ObjectNode) input.path("cells").get(0).path("position"))
            .put("q", -1);
      if (kind == 3)
        ((tools.jackson.databind.node.ObjectNode)
                input.path("regiments").get(0).path("companies").get(0))
            .put("hp", 101);
      if (kind == 4) input.putNull("cells");
      var response =
          request("/api/v1/scenarios/import", "POST", input.toString(), "Bearer " + token);
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains("INVALID_SCENARIO"));
    }
    var empty =
        request(
            "/api/v1/scenarios",
            "POST",
            "{\"name\":\"blank\",\"width\":3,\"height\":3}",
            "Bearer " + token);
    assertEquals(201, empty.statusCode());
    var id = mapper.readTree(empty.body()).path("id").asText();
    var freeze =
        request(
            "/api/v1/scenarios/" + id + "/revisions",
            "POST",
            "{\"expectedVersion\":1}",
            "Bearer " + token);
    assertEquals(400, freeze.statusCode());
    assertTrue(freeze.body().contains("师部"));
    assertEquals(
        404, request("/api/v1/scenarios/missing", "GET", "", "Bearer " + token).statusCode());
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
