package io.tactical.application;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import io.tactical.core.*;
import io.tactical.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DoctrineLabTest {
  private Regiment unit(String id, Side side, Role role, int q, int r, CompanyType type) {
    return new Regiment(
        id,
        id,
        side,
        role,
        new HexCoord(q, r),
        role == Role.REGIMENT && side == Side.BLUE ? "brigade" : "",
        List.of(new Company(id + "-c", type, Equipment.MECHANIZED, 100, 100)));
  }

  private Scenario world() {
    var base = ScenarioRules.blank("Doctrine Lab", 12, 4);
    var cells = base.cells().stream().map(c -> new Cell(c.position(), Terrain.CITY, 0)).toList();
    var brigade =
        new Regiment(
            "brigade",
            "brigade",
            Side.BLUE,
            Role.BRIGADE_HQ,
            new HexCoord(1, 0),
            "",
            List.of(
                new Company("signal-1", CompanyType.SIGNAL, Equipment.MECHANIZED, 100, 100),
                new Company("signal-2", CompanyType.SIGNAL, Equipment.MECHANIZED, 100, 100)));
    return new Scenario(
        1,
        base.name(),
        12,
        4,
        cells,
        List.of(),
        List.of(
            unit("hq", Side.BLUE, Role.DIVISION_HQ, 0, 0, CompanyType.SIGNAL),
            brigade,
            unit("scout", Side.BLUE, Role.REGIMENT, 4, 0, CompanyType.RECON),
            unit("enemy", Side.RED, Role.REGIMENT, 6, 0, CompanyType.ARMOR),
            unit("red-hq", Side.RED, Role.DIVISION_HQ, 11, 3, CompanyType.SIGNAL)),
        List.of(new Supply("rear", new HexCoord(1, 2), Side.BLUE, 200)));
  }

  private Scenario configured(ExperimentSetup setup, List<InitialKnowledge> knowledge) {
    var s = world();
    return new Scenario(
        1,
        s.name(),
        s.width(),
        s.height(),
        s.cells(),
        s.edges(),
        s.regiments(),
        s.supplies(),
        s.communicationNodes(),
        s.communicationRadius(),
        setup,
        knowledge);
  }

  private ExperimentSetup setup() {
    var order =
        new MovementOrder(
            "scout-task",
            "scout",
            List.of(new HexCoord(5, 0)),
            MovementOrder.Action.MOVE,
            null,
            new Doctrine(1, Doctrine.Template.BREAKTHROUGH, 42, "rear"));
    var plan =
        new OperationPlan(
            "op",
            "brigade",
            OperationPlan.Mode.COORDINATED,
            world().cells().stream().map(Cell::position).toList(),
            List.of(
                new OperationPlan.Node(order.orderId(), List.of(), OperationPlan.Fallback.HOLD)));
    return new ExperimentSetup(List.of(order), List.of(), plan, null);
  }

  private BattleSession.Day resolve(BattleSession session) {
    var view = session.view();
    session.submit(1, Side.BLUE, 0, view.blue().orders(), view.blue().operation());
    session.submit(1, Side.RED, 0, List.of());
    session.commit(1, Side.BLUE, 1);
    session.commit(1, Side.RED, 1);
    return session.resolve(1);
  }

  @Test
  void initialKnowledgeIsValidatedAndOnlyGivenToItsNamedObserver() {
    var scenario =
        configured(
            setup(),
            List.of(new InitialKnowledge("scout", List.of("enemy"), List.of("rear"), List.of())));
    var session = new BattleSession(ScenarioRules.normalize(scenario), 42, 4);
    assertEquals("rear", session.view().memory().get("scout").supplies().getFirst().id());
    assertFalse(
        session
            .projection(BattleSession.Perspective.DIVISION, Side.BLUE)
            .knowledge()
            .contacts()
            .containsKey("enemy"));
    var hqScenario =
        configured(
            setup(), List.of(new InitialKnowledge("hq", List.of("enemy"), List.of(), List.of())));
    assertTrue(
        new BattleSession(hqScenario, 42, 4)
            .projection(BattleSession.Perspective.DIVISION, Side.BLUE)
            .knowledge()
            .contacts()
            .containsKey("enemy"));
    assertThrows(
        ScenarioViolation.class,
        () ->
            ScenarioRules.normalize(
                configured(
                    setup(),
                    List.of(
                        new InitialKnowledge("scout", List.of("missing"), List.of(), List.of())))));
    assertNotEquals(ScenarioHash.sha256(scenario), ScenarioHash.sha256(hqScenario));
  }

  @Test
  void savedBlueprintKeepsDoctrineDagAndKnowledgeAcrossImportCopyAndFreeze() {
    var service = new ScenarioService();
    var input =
        configured(
            setup(),
            List.of(new InitialKnowledge("scout", List.of("enemy"), List.of("rear"), List.of())));
    var draft = service.importScenario(input);
    var rev = service.freeze(draft.id(), 1);
    var game = service.createGame(rev.id(), 42);
    var view = service.turn(game.id());
    assertEquals(input.setup().blue(), view.blue().orders());
    assertEquals(0, view.blue().version());
    assertFalse(view.blue().submitted());
    assertThrows(StoreProblem.class, () -> service.blueprint(game.id(), 1, 0, 0));
    service.submit(game.id(), 1, Side.BLUE, 0, view.blue().orders(), view.blue().operation());
    service.submit(game.id(), 1, Side.RED, 0, List.of());
    var saved = service.blueprint(game.id(), 1, 1, 1);
    assertEquals(draft.scenario(), saved.scenario());
    assertThrows(StoreProblem.class, () -> service.blueprint(game.id(), 1, 0, 1));
    var copied = service.copy(saved.id(), 1);
    assertEquals(input.setup(), copied.scenario().setup());
    assertEquals(input.initialKnowledge(), copied.scenario().initialKnowledge());
    var imported = service.importScenario(saved.scenario());
    assertEquals(rev.contentHash(), service.freeze(imported.id(), 1).contentHash());
    var reopened = service.createGame(service.freeze(saved.id(), 1).id(), 42);
    assertEquals(view, service.turn(reopened.id()));
  }

  @Test
  void replayNeverBorrowsFutureReportsAndDoesNotMutateSimulation() {
    var input = configured(setup(), List.of());
    var session = new BattleSession(input, 42, 4);
    var result = resolve(session);
    var start = session.replay(1, 0, BattleSession.Perspective.DIVISION, Side.BLUE);
    for (int i = 0; i < start.count(); i++) {
      var f = session.replay(1, i, BattleSession.Perspective.DIVISION, Side.BLUE);
      assertEquals(
          f.phase().equals("REPORTS_AVAILABLE"),
          f.view().knowledge().contacts().containsKey("enemy"));
      assertTrue(f.operations().isEmpty());
    }
    var omni = session.replay(1, 0, BattleSession.Perspective.OMNISCIENT, Side.BLUE);
    assertTrue(omni.count() > start.count());
    assertEquals(
        new HexCoord(4, 0),
        omni.view().world().regiments().stream()
            .filter(r -> r.id().equals("scout"))
            .findFirst()
            .orElseThrow()
            .position());
    var end = session.replay(1, omni.count() - 1, BattleSession.Perspective.OMNISCIENT, Side.BLUE);
    assertEquals(result.result().world(), end.view().world());
    assertEquals(result.result().events(), end.view().events());
    assertEquals(result, session.day(1));
    assertEquals(2, session.view().day());
    assertThrows(
        ScenarioViolation.class,
        () -> session.replay(1, omni.count(), BattleSession.Perspective.OMNISCIENT, Side.BLUE));
    var m = result.manifest();
    var withoutObserver =
        new DaySimulation()
            .resolve(
                input,
                1,
                42,
                4,
                m.blue(),
                m.red(),
                m.memory(),
                m.blueOperation(),
                m.redOperation(),
                m.intelligence());
    assertEquals(result.result(), withoutObserver);
  }

  @Test
  void markerUsesLivingCompanyMobilityAndHasAtMostOneBottleneck() {
    var s = world();
    var mixed =
        new Regiment(
            "mixed",
            "mixed",
            Side.BLUE,
            Role.REGIMENT,
            new HexCoord(2, 2),
            "brigade",
            List.of(
                new Company("tank", CompanyType.ARMOR, Equipment.TRACKED, 100, 100),
                new Company("foot", CompanyType.INFANTRY, Equipment.FOOT, 100, 100),
                new Company("dead", CompanyType.ARTILLERY, Equipment.TOWED, 100, 0)));
    var units = new ArrayList<>(s.regiments());
    units.add(mixed);
    var map =
        new Scenario(1, s.name(), s.width(), s.height(), s.cells(), s.edges(), units, s.supplies());
    var marker = UnitPresentation.markers(map).get("mixed");
    assertEquals(CompanyType.ARMOR, marker.main());
    assertEquals(Equipment.FOOT, marker.bottleneckEquipment());
    assertEquals(2, marker.speed());
  }
}
