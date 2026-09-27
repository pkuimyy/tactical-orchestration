package io.tactical.server;

import static org.junit.jupiter.api.Assertions.*;

import io.tactical.application.*;
import io.tactical.application.ExperimentRunner.*;
import io.tactical.core.*;
import io.tactical.core.Scenario.*;
import io.tactical.simulation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ExperimentRegressionTest {
  static final ObjectMapper JSON = new ObjectMapper();

  record Fixture(
      String id,
      Scenario scenario,
      List<ExperimentSetup> days,
      List<Long> seeds,
      int maxIterations,
      List<String> assertions) {}

  static List<Fixture> fixtures() throws Exception {
    return List.of(
        JSON.readValue(
            ExperimentRegressionTest.class.getResourceAsStream(
                "/scenarios/m6-regression-suite.json"),
            Fixture[].class));
  }

  static Request request(
      ScenarioService.Revision revision, ExperimentSetup a, ExperimentSetup b, List<Long> seeds) {
    return new Request(
        "test",
        revision.id(),
        seeds,
        6,
        new Variant("A", List.of(a)),
        new Variant("B", List.of(b)),
        null);
  }

  static ScenarioService.Revision freeze(ScenarioService service, Scenario s) {
    var draft = service.importScenario(s);
    return service.freeze(draft.id(), 1);
  }

  static MovementOrder find(ExperimentSetup setup, String id) {
    return setup.blue().stream().filter(o -> o.orderId().equals(id)).findFirst().orElseThrow();
  }

  @TestFactory
  List<DynamicTest> sevenGroupsHaveFixedInputsAssertionsAndRepeatableLogs() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    for (var f : fixtures())
      for (long seed : f.seeds())
        tests.add(
            DynamicTest.dynamicTest(
                f.id() + "/" + seed,
                () -> {
                  var s = ScenarioRules.normalize(f.scenario());
                  ScenarioRules.requirePlayable(s);
                  var variant = new Variant("baseline", f.days());
                  var req =
                      new Request(
                          "matrix",
                          "fixture",
                          List.of(seed),
                          f.maxIterations(),
                          variant,
                          variant,
                          null);
                  var run = ExperimentRunner.run(s, req, 0);
                  assertEquals("COMPLETED", run.status(), run.error());
                  assertEquals(
                      run,
                      ExperimentRunner.run(s, req, 0),
                      "entire event/state/manifest/metric equality");
                  var day = run.days().getFirst();
                  var events = day.result().events();
                  var end = day.result().world();
                  switch (f.id()) {
                    case "river_crossing" -> {
                      assertEquals(
                          new HexCoord(4, 0),
                          end.regiments().stream()
                              .filter(r -> r.id().equals("blue-armor"))
                              .findFirst()
                              .orElseThrow()
                              .position());
                      assertEquals(0, run.metrics().dependencyDisorders());
                      assertTrue(run.metrics().waits() > 0);
                    }
                    case "fire_preparation" -> {
                      int fire =
                          events.stream()
                              .filter(
                                  e -> e.orderId().equals("fire") && e.kind().equals("SUCCEEDED"))
                              .mapToInt(DaySimulation.Event::tick)
                              .findFirst()
                              .orElseThrow();
                      int cross =
                          events.stream()
                              .filter(
                                  e -> e.orderId().equals("cross") && e.kind().equals("EXECUTING"))
                              .mapToInt(DaySimulation.Event::tick)
                              .findFirst()
                              .orElseThrow();
                      assertTrue(fire < cross);
                      assertEquals(0, run.metrics().dependencyDisorders());
                      assertTrue(run.metrics().redDamage() > 0);
                      assertTrue(
                          run.metrics().companies().stream()
                              .filter(c -> c.regimentId().equals("blue-guns"))
                              .allMatch(c -> c.damage() == 0));
                    }
                    case "armor_withdrawal" -> {
                      assertTrue(
                          run.metrics().retreats().stream()
                              .anyMatch(r -> r.supplyId().equals("rear")));
                      assertEquals(
                          new HexCoord(1, 1),
                          end.regiments().stream()
                              .filter(r -> r.id().equals("assault"))
                              .findFirst()
                              .orElseThrow()
                              .position());
                    }
                    case "recon_pursuit" -> {
                      assertFalse(events.stream().anyMatch(e -> e.kind().equals("CONTACT")));
                      assertTrue(
                          events.stream()
                              .anyMatch(e -> e.regimentId().equals("recon") && e.speed() == 6));
                      assertTrue(
                          events.stream()
                              .anyMatch(e -> e.regimentId().equals("armor") && e.speed() == 5));
                    }
                    case "line_holding" -> {
                      var line =
                          s.regiments().stream()
                              .filter(r -> r.id().equals("line"))
                              .findFirst()
                              .orElseThrow();
                      assertEquals(
                          2,
                          line.companies().stream()
                              .filter(c -> c.type() == CompanyType.ARMOR)
                              .count());
                      assertEquals(
                          4,
                          line.companies().stream()
                              .filter(
                                  c ->
                                      c.type() == CompanyType.INFANTRY
                                          && c.equipment() == Equipment.FOOT)
                              .count());
                      assertTrue(run.metrics().blueDamage() > 0);
                      assertTrue(run.metrics().redDamage() > 0);
                    }
                    case "communication_connected", "communication_partition" -> {
                      assertFalse(
                          day.manifest()
                              .intelligence()
                              .divisions()
                              .get(Side.BLUE)
                              .contacts()
                              .containsKey("enemy"));
                      assertTrue(
                          day.manifest()
                              .intelligence()
                              .regiments()
                              .get("scout")
                              .contacts()
                              .containsKey("enemy"));
                      assertEquals(
                          f.id().equals("communication_connected"),
                          day.result()
                              .intelligence()
                              .divisions()
                              .get(Side.BLUE)
                              .contacts()
                              .containsKey("enemy"));
                      if (f.id().equals("communication_partition"))
                        assertFalse(day.result().intelligence().pending().isEmpty());
                    }
                    case "screening_regiment" -> {
                      assertFalse(end.regiments().stream().anyMatch(r -> r.id().equals("screen")));
                      assertTrue(events.stream().anyMatch(e -> e.kind().equals("DESTROYED")));
                      assertEquals(
                          new HexCoord(2, 1),
                          end.regiments().stream()
                              .filter(r -> r.id().equals("attack"))
                              .findFirst()
                              .orElseThrow()
                              .position());
                    }
                    case "screening_plain", "screening_mountain" ->
                        assertTrue(events.stream().anyMatch(e -> e.kind().equals("DISPLACED")));
                    default -> fail("fixture lacks semantic assertions");
                  }
                  for (var c : run.metrics().companies()) {
                    assertTrue(c.recovered() >= 0);
                    assertEquals(c.initialHp() - c.damage() + c.recovered(), c.finalHp());
                  }
                  var golden =
                      JSON.readTree(
                              ExperimentRegressionTest.class.getResourceAsStream(
                                  "/scenarios/m6-regression-expected.json"))
                          .get("cases")
                          .get(f.id() + "-" + seed);
                  assertEquals(golden.get("stateHash").asString(), day.result().stateHash());
                  String jsonl =
                      events.stream()
                          .map(JSON::writeValueAsString)
                          .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
                  assertEquals(
                      golden.get("eventsSha256").asString(),
                      HexFormat.of()
                          .formatHex(
                              java.security.MessageDigest.getInstance("SHA-256")
                                  .digest(
                                      jsonl.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                  Path out = Path.of("target/m6-regression");
                  Files.createDirectories(out);
                  Files.writeString(
                      out.resolve(f.id() + "-" + seed + ".json"), JSON.writeValueAsString(run));
                  Files.writeString(
                      out.resolve(f.id() + "-" + seed + ".jsonl"),
                      events.stream()
                          .map(JSON::writeValueAsString)
                          .collect(java.util.stream.Collectors.joining("\n", "", "\n")));
                }));
    return tests;
  }

  @Test
  void pairedSeedsExposeBothIndependentOrdersAndDoNotMutateFrozenInput() throws Exception {
    var f = fixtures().getFirst();
    var service = new ScenarioService();
    var revision = freeze(service, f.scenario());
    var a = f.days().getFirst();
    var p = a.blueOperation();
    var b =
        new ExperimentSetup(
            a.blue(),
            a.red(),
            new OperationPlan(
                p.id(), p.brigadeId(), OperationPlan.Mode.INDEPENDENT, p.theater(), p.nodes()),
            a.redOperation());
    var seeds = java.util.stream.LongStream.range(0, 16).boxed().toList();
    var req = request(revision, a, b, seeds);
    ExperimentRunner.validate(revision.scenario(), req);
    var independentResults = new HashSet<Boolean>();
    for (int index = 0; index < 32; index++) {
      var first = ExperimentRunner.run(revision.scenario(), req, index);
      assertEquals(first, ExperimentRunner.run(revision.scenario(), req, index));
      assertEquals(seeds.get(index / 2).longValue(), first.seed());
      if (index % 2 == 0) assertEquals(0, first.metrics().dependencyDisorders());
      else independentResults.add(first.metrics().dependencyDisorders() == 0);
    }
    assertEquals(Set.of(true, false), independentResults);
    assertEquals(revision.contentHash(), ScenarioHash.sha256(revision.scenario()));
  }

  @Test
  void malformedAndLaterInvalidPlansCannotLookSuccessful() throws Exception {
    var f = fixtures().getFirst();
    var a = new Variant("A", f.days());
    assertThrows(
        ScenarioViolation.class, () -> new Request("bad", "rev", List.of(1L, 1L), 6, a, a, null));
    assertThrows(
        ScenarioViolation.class,
        () -> new Request("../outside", "rev", List.of(1L), 6, a, a, null));
    var days =
        new Variant(
            "late invalid",
            List.of(
                f.days().getFirst(),
                new ExperimentSetup(
                    List.of(new MovementOrder("missing", "missing", List.of())),
                    List.of(),
                    null,
                    null)));
    var req = new Request("late", "rev", List.of(42L), 6, days, days, null);
    var result = ExperimentRunner.run(ScenarioRules.normalize(f.scenario()), req, 0);
    assertEquals("INVALID_PLAN", result.status());
    assertEquals(1, result.days().size());
  }

  @Test
  void metricsDistinguishRecoveryRetreatArrivalAndFirstObjectiveDay() throws Exception {
    var f =
        fixtures().stream()
            .filter(v -> v.id().equals("armor_withdrawal"))
            .findFirst()
            .orElseThrow();
    var rest =
        new ExperimentSetup(
            List.of(
                new MovementOrder(
                    "rest", "assault", List.of(), MovementOrder.Action.REST, null, null)),
            List.of(),
            null,
            null);
    var variant = new Variant("withdraw then rest", List.of(f.days().getFirst(), rest));
    var req =
        new Request(
            "rest",
            "fixture",
            List.of(42L),
            6,
            variant,
            variant,
            new Goal("assault", new HexCoord(1, 1)));
    var run = ExperimentRunner.run(ScenarioRules.normalize(f.scenario()), req, 0);
    assertEquals("COMPLETED", run.status());
    assertEquals("REACHED", run.metrics().goalStatus());
    assertEquals(1, run.metrics().completionDay());
    assertTrue(run.metrics().retreats().getFirst().reachedSupply());
    assertEquals(new HexCoord(1, 1), run.metrics().retreats().getFirst().dayEndPosition());
    assertTrue(run.metrics().companies().stream().anyMatch(c -> c.recovered() > 0));
    var first = ExperimentRunner.metrics(f.scenario(), req.goal(), List.of(run.days().getFirst()));
    assertEquals(
        first.blueDamage(), run.metrics().blueDamage(), "rest must not erase cumulative damage");
    var huge = new Variant("30 days", java.util.Collections.nCopies(30, ExperimentSetup.empty()));
    assertThrows(
        ScenarioViolation.class,
        () -> new Request("limit", "fixture", List.of(1L, 2L, 3L, 4L, 5L), 6, huge, huge, null));
  }

  @Test
  void archiveRoundTripKeepsDraftsCommandsHistoryAndReplayAndRejectsCorruption(@TempDir Path dir)
      throws Exception {
    String gameId;
    BattleSession.View turn;
    BattleSession.Replay replay;
    ScenarioService.Saved saved;
    try (var ignored = new AutoCloseableArchive(dir)) {
      var service = ignored.archive.scenarioService();
      var f = fixtures().getFirst();
      var rev = freeze(service, f.scenario());
      gameId = service.createGame(rev.id(), 42, 6).id();
      var setup = f.days().getFirst();
      service.submit(gameId, 1, Side.BLUE, 0, setup.blue(), setup.blueOperation());
      service.submit(gameId, 1, Side.RED, 0, setup.red(), setup.redOperation());
      service.commit(gameId, 1, Side.BLUE, 1);
      service.commit(gameId, 1, Side.RED, 1);
      service.resolve(gameId, 1);
      service.submit(gameId, 2, Side.BLUE, 0, List.of());
      service.commit(gameId, 2, Side.BLUE, 1);
      turn = service.turn(gameId);
      replay = service.replay(gameId, 1, 1, BattleSession.Perspective.DIVISION, Side.BLUE);
      saved = service.save();
      assertThrows(Exception.class, () -> new LocalArchive(dir.toString()));
    }
    try (var reopened = new AutoCloseableArchive(dir)) {
      var service = reopened.archive.scenarioService();
      assertEquals(saved, service.save());
      assertEquals(turn, service.turn(gameId));
      assertEquals(
          replay, service.replay(gameId, 1, 1, BattleSession.Perspective.DIVISION, Side.BLUE));
      service.persistWith(
          value -> {
            throw new IllegalStateException("disk full");
          });
      assertThrows(
          IllegalStateException.class, () -> service.submit(gameId, 2, Side.RED, 0, List.of()));
      assertEquals(saved, service.save(), "failed write must roll back pending order state");
    }
    Files.writeString(dir.resolve("scenarios.json"), "broken");
    try (var reopened = new AutoCloseableArchive(dir)) {
      assertThrows(IllegalStateException.class, reopened.archive::scenarioService);
    }
    assertEquals("broken", Files.readString(dir.resolve("scenarios.json")));
  }

  static class AutoCloseableArchive implements AutoCloseable {
    final LocalArchive archive;

    AutoCloseableArchive(Path path) throws Exception {
      archive = new LocalArchive(path.toString());
    }

    public void close() throws Exception {
      archive.close();
    }
  }
}
