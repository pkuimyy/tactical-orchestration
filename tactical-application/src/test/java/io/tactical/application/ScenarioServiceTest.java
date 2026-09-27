package io.tactical.application;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import io.tactical.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ScenarioServiceTest {
  private Scenario playable() {
    Scenario empty = ScenarioRules.blank("lab", 4, 4);
    var cells = empty.cells().stream().map(c -> new Cell(c.position(), Terrain.CITY, 0)).toList();
    var blue =
        new Regiment(
            "blue",
            "蓝方师部",
            Side.BLUE,
            Role.DIVISION_HQ,
            new HexCoord(0, 0),
            "",
            List.of(new Company("b1", CompanyType.SIGNAL, Equipment.FOOT, 100, 100)));
    var red =
        new Regiment(
            "red",
            "红方师部",
            Side.RED,
            Role.DIVISION_HQ,
            new HexCoord(3, 3),
            "",
            List.of(new Company("r1", CompanyType.SIGNAL, Equipment.FOOT, 100, 100)));
    return new Scenario(1, "lab", 4, 4, cells, List.of(), List.of(blue, red), List.of());
  }

  @Test
  void freezeDeduplicatesAndGamesNeverInheritLaterDraftEdits() {
    var service = new ScenarioService();
    var draft = service.importScenario(playable());
    var revision = service.freeze(draft.id(), draft.version());
    assertEquals(revision, service.freeze(draft.id(), draft.version()));
    var a = service.createGame(revision.id(), 42);
    var b = service.createGame(revision.id(), 42);
    assertNotEquals(a.id(), b.id());
    assertEquals(a.initialState(), b.initialState());
    assertEquals(0, a.day());
    var original = draft.scenario();
    var changed =
        new Scenario(
            1,
            "renamed",
            4,
            4,
            original.cells(),
            original.edges(),
            original.regiments(),
            List.of(new Supply("s", new HexCoord(1, 1), Side.BLUE, 50)));
    service.update(draft.id(), draft.version(), changed);
    assertEquals(original, service.game(a.id()).initialState());
    assertEquals(original, service.revision(revision.id()).scenario());
    assertEquals(a.contentHash(), ScenarioHash.sha256(original));
    assertNotEquals(revision.contentHash(), service.freeze(draft.id(), 2).contentHash());
    assertEquals(6, service.events(draft.id()).size());
  }

  @Test
  void importExportRetainsHashAndRejectsStaleWrites() {
    var service = new ScenarioService();
    var draft = service.importScenario(playable());
    var imported = service.importScenario(draft.scenario());
    assertEquals(
        service.freeze(draft.id(), 1).contentHash(),
        service.freeze(imported.id(), 1).contentHash());
    service.update(draft.id(), 1, draft.scenario());
    assertEquals(
        StoreProblem.Kind.CONFLICT,
        assertThrows(StoreProblem.class, () -> service.update(draft.id(), 1, draft.scenario()))
            .kind());
    assertThrows(StoreProblem.class, () -> service.freeze(draft.id(), 1));
    assertThrows(StoreProblem.class, () -> service.createGame("missing", 1));
  }

  @Test
  void concurrentUpdatesHaveExactlyOneWinner() throws Exception {
    var service = new ScenarioService();
    var draft = service.importScenario(playable());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Boolean> write =
          () -> {
            start.await();
            try {
              service.update(draft.id(), 1, draft.scenario());
              return true;
            } catch (StoreProblem conflict) {
              return false;
            }
          };
      var a = pool.submit(write);
      var b = pool.submit(write);
      start.countDown();
      assertNotEquals(a.get(), b.get());
      assertEquals(2, service.get(draft.id()).version());
    }
  }

  @Test
  void resourceLimitsAreEnforcedAndEmptyDraftCannotFreeze() {
    var service = new ScenarioService();
    var draft = service.create("empty", 2, 2);
    assertThrows(ScenarioViolation.class, () -> service.freeze(draft.id(), 1));
    for (int i = 1; i < 32; i++) service.create("draft", 2, 2);
    assertEquals(
        StoreProblem.Kind.LIMIT,
        assertThrows(StoreProblem.class, () -> service.create("over", 2, 2)).kind());
  }
}
