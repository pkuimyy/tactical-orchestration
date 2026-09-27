package io.tactical.application;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import io.tactical.core.*;
import io.tactical.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class IntelligenceProjectionTest {
  private Regiment unit(String id, Side side, Role role, int q, int r) {
    return new Regiment(
        id,
        id,
        side,
        role,
        new HexCoord(q, r),
        "",
        List.of(
            new Company(
                id + "-c",
                role == Role.DIVISION_HQ ? CompanyType.SIGNAL : CompanyType.RECON,
                Equipment.MECHANIZED,
                100,
                100)));
  }

  private Scenario world() {
    var base = ScenarioRules.blank("partition", 16, 4);
    var cells =
        base.cells().stream()
            .map(
                c ->
                    new Cell(
                        c.position(),
                        c.position().equals(new HexCoord(0, 0))
                                || c.position().equals(new HexCoord(15, 3))
                            ? Terrain.CITY
                            : Terrain.PLAIN,
                        0))
            .toList();
    return new Scenario(
        1,
        "partition",
        16,
        4,
        cells,
        List.of(),
        List.of(
            unit("hq", Side.BLUE, Role.DIVISION_HQ, 0, 0),
            unit("scout", Side.BLUE, Role.REGIMENT, 6, 0),
            unit("enemy", Side.RED, Role.REGIMENT, 8, 0),
            unit("enemy-hq", Side.RED, Role.DIVISION_HQ, 15, 3)),
        List.of(new Supply("hidden", new HexCoord(8, 1), Side.BLUE, 123)),
        List.of(new CommunicationNode("relay", new HexCoord(3, 0), Side.BLUE, 10)),
        3);
  }

  private BattleSession.Day resolve(BattleSession session, int day, List<MovementOrder> blue) {
    session.submit(day, Side.BLUE, 0, blue);
    session.submit(day, Side.RED, 0, List.of());
    session.commit(day, Side.BLUE, 1);
    session.commit(day, Side.RED, 1);
    return session.resolve(day);
  }

  @Test
  void isolatedFriendUsesLastTrustedSnapshotAndDoesNotLeakLocalEnemyOrSupplies() {
    var session = new BattleSession(world(), 42, 4);
    var first =
        resolve(
            session, 1, List.of(new MovementOrder("out", "scout", List.of(new HexCoord(7, 0)))));
    assertTrue(
        first.result().intelligence().regiments().get("scout").contacts().containsKey("enemy"));
    var view = session.projection(BattleSession.Perspective.DIVISION, Side.BLUE);
    assertEquals(
        new HexCoord(6, 0),
        view.world().regiments().stream()
            .filter(r -> r.id().equals("scout"))
            .findFirst()
            .orElseThrow()
            .position());
    assertEquals(0, view.knowledge().contacts().get("scout").observedDay());
    assertFalse(view.knowledge().contacts().containsKey("enemy"));
    assertTrue(view.world().supplies().isEmpty());
    assertTrue(view.events().stream().noneMatch(e -> e.regimentId().equals("scout")));
    assertTrue(
        first.result().intelligence().pending().stream().anyMatch(r -> r.source().equals("scout")));
    for (int i = 0; i < 5; i++) {
      session.projection(BattleSession.Perspective.OMNISCIENT, Side.BLUE);
      session.projection(BattleSession.Perspective.DIVISION, Side.RED);
    }
    assertEquals(first, session.day(1));
    var second =
        resolve(
            session, 2, List.of(new MovementOrder("back", "scout", List.of(new HexCoord(6, 0)))));
    var shared = session.projection(BattleSession.Perspective.DIVISION, Side.BLUE);
    assertTrue(shared.knowledge().contacts().containsKey("enemy"));
    assertTrue(
        second.result().intelligence().pending().stream()
            .noneMatch(r -> r.source().equals("scout")));
    assertTrue(
        second.result().events().stream()
            .anyMatch(
                e ->
                    e.kind().equals("REPORT_DELIVERED")
                        && e.regimentId().equals("scout")
                        && e.reason().contains("observedDay=1")));
  }

  @Test
  void manifestIncludesAllKnowledgeInputsForExactSecondDayReplay() {
    var session = new BattleSession(world(), 42, 4);
    var first =
        resolve(
            session, 1, List.of(new MovementOrder("out", "scout", List.of(new HexCoord(7, 0)))));
    var second = resolve(session, 2, List.of());
    var m = second.manifest();
    var replay =
        new DaySimulation()
            .resolve(
                first.result().world(),
                m.day(),
                m.seed(),
                m.maxIterations(),
                m.blue(),
                m.red(),
                m.memory(),
                m.blueOperation(),
                m.redOperation(),
                m.intelligence());
    assertEquals(second.result(), replay);
    assertEquals(first.result().stateHash(), m.inputHash());
  }
}
