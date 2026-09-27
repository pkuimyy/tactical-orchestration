package io.tactical.simulation;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import io.tactical.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DaySimulationTest {
  private Company company(String id, CompanyType type, Equipment equipment) {
    return new Company(id, type, equipment, 100, 100);
  }

  private Regiment unit(String id, Side side, int q, int r, Company... companies) {
    return new Regiment(id, id, side, Role.REGIMENT, new HexCoord(q, r), "", List.of(companies));
  }

  private Scenario map(Regiment... units) {
    var blank = ScenarioRules.blank("movement", 16, 3);
    return new Scenario(1, "movement", 16, 3, blank.cells(), List.of(), List.of(units), List.of());
  }

  private MovementOrder east(String id, int start, int row, int steps) {
    var route = new ArrayList<HexCoord>();
    for (int i = 1; i <= steps; i++) route.add(new HexCoord(start + i, row));
    return new MovementOrder("order-" + id, id, route);
  }

  private DaySimulation.Result run(Scenario world, long seed, MovementOrder... orders) {
    var blue =
        Arrays.stream(orders)
            .filter(
                o ->
                    world.regiments().stream()
                        .anyMatch(r -> r.id().equals(o.regimentId()) && r.side() == Side.BLUE))
            .toList();
    var red = Arrays.stream(orders).filter(o -> !blue.contains(o)).toList();
    return new DaySimulation().resolve(world, 1, seed, 4, blue, red);
  }

  private HexCoord position(DaySimulation.Result result, String id) {
    return result.world().regiments().stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow()
        .position();
  }

  @Test
  void slowestLivingCompanyAndEquipmentUpgradeControlWholeRegiment() {
    Company[] mixed = new Company[6];
    for (int i = 0; i < 4; i++) mixed[i] = company("c" + i, CompanyType.ARMOR, Equipment.TRACKED);
    for (int i = 4; i < 6; i++) mixed[i] = company("c" + i, CompanyType.INFANTRY, Equipment.FOOT);
    assertEquals(
        new HexCoord(2, 1),
        position(run(map(unit("a", Side.BLUE, 0, 1, mixed)), 42, east("a", 0, 1, 8)), "a"));
    for (int i = 4; i < 6; i++)
      mixed[i] = company("c" + i, CompanyType.INFANTRY, Equipment.MECHANIZED);
    assertEquals(
        new HexCoord(5, 1),
        position(run(map(unit("a", Side.BLUE, 0, 1, mixed)), 42, east("a", 0, 1, 8)), "a"));
    mixed[5] = new Company("c5", CompanyType.INFANTRY, Equipment.FOOT, 100, 0);
    assertEquals(
        new HexCoord(5, 1),
        position(run(map(unit("a", Side.BLUE, 0, 1, mixed)), 42, east("a", 0, 1, 8)), "a"));
  }

  @Test
  void everyTerrainAndEquipmentHasExplicitPassabilityAndRoadBehavior() {
    for (var type : Equipment.values())
      for (var terrain : Terrain.values()) {
        int speed = Mobility.speed(company("c", CompanyType.INFANTRY, type), terrain, false);
        assertTrue(speed >= 0 && speed <= 5);
        if (terrain == Terrain.MOUNTAIN) assertEquals(type == Equipment.FOOT ? 1 : 0, speed);
      }
    assertEquals(
        6,
        Mobility.speed(
            company("r", CompanyType.RECON, Equipment.MECHANIZED), Terrain.PLAIN, false));
    assertEquals(
        2, Mobility.speed(company("r", CompanyType.RECON, Equipment.FOOT), Terrain.PLAIN, false));
    assertEquals(
        5,
        Mobility.speed(company("a", CompanyType.ARMOR, Equipment.TRACKED), Terrain.FOREST, true));
  }

  @Test
  void oneImpassableCompanyOrBrokenBridgeBlocksWholeRegiment() {
    var base =
        map(
            unit(
                "a",
                Side.BLUE,
                0,
                1,
                company("a1", CompanyType.ARMOR, Equipment.TRACKED),
                company("a2", CompanyType.INFANTRY, Equipment.FOOT)));
    var cells =
        base.cells().stream()
            .map(
                c ->
                    c.position().equals(new HexCoord(1, 1))
                        ? new Cell(c.position(), Terrain.MOUNTAIN, 0)
                        : c)
            .toList();
    var mountain =
        new Scenario(1, base.name(), 16, 3, cells, base.edges(), base.regiments(), base.supplies());
    var result = run(mountain, 1, east("a", 0, 1, 3));
    assertEquals(new HexCoord(0, 1), position(result, "a"));
    assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("IMPASSABLE")));
    for (var bridge : Bridge.values()) {
      var river =
          new Scenario(
              1,
              base.name(),
              16,
              3,
              base.cells(),
              List.of(new Edge(new HexCoord(0, 1), new HexCoord(1, 1), true, bridge, true)),
              base.regiments(),
              base.supplies());
      assertEquals(
          new HexCoord(bridge == Bridge.INTACT ? 2 : 0, 1),
          position(run(river, 1, east("a", 0, 1, 3)), "a"));
    }
  }

  @Test
  void fasterEscapedReconCannotBeCaughtBySchedulingAcrossSeeds() {
    var world =
        map(
            unit("armor", Side.RED, 0, 1, company("a", CompanyType.ARMOR, Equipment.TRACKED)),
            unit("recon", Side.BLUE, 1, 1, company("r", CompanyType.RECON, Equipment.MECHANIZED)));
    for (int seed = 0; seed < 100; seed++) {
      var result = run(world, seed, east("armor", 0, 1, 10), east("recon", 1, 1, 10));
      assertEquals(new HexCoord(5, 1), position(result, "armor"));
      assertEquals(new HexCoord(7, 1), position(result, "recon"));
      assertFalse(result.events().stream().anyMatch(e -> e.kind().equals("CONTACT")));
    }
  }

  @Test
  void replayAndInsertionOrderAreStableButSeedChangesSchedule() {
    var a = unit("a", Side.BLUE, 0, 0, company("a1", CompanyType.RECON, Equipment.MECHANIZED));
    var b = unit("b", Side.BLUE, 0, 2, company("b1", CompanyType.ARMOR, Equipment.TRACKED));
    var result = run(map(a, b), 42, east("a", 0, 0, 8), east("b", 0, 2, 8));
    assertEquals(result, run(map(b, a), 42, east("b", 0, 2, 8), east("a", 0, 0, 8)));
    assertEquals(result, run(map(a, b), 42, east("a", 0, 0, 8), east("b", 0, 2, 8)));
    assertTrue(
        java.util.stream.LongStream.range(1, 20)
            .anyMatch(
                seed ->
                    !run(map(a, b), seed, east("a", 0, 0, 8), east("b", 0, 2, 8))
                        .events()
                        .equals(result.events())));
  }

  @Test
  void sameCellContestAndHeadOnCrossingNeverGrantFreeOccupation() {
    var a = unit("a", Side.BLUE, 0, 1, company("a1", CompanyType.ARMOR, Equipment.TRACKED));
    var b = unit("b", Side.RED, 2, 1, company("b1", CompanyType.ARMOR, Equipment.TRACKED));
    var left = east("a", 0, 1, 1);
    var right = new MovementOrder("order-b", "b", List.of(new HexCoord(1, 1)));
    for (int seed = 0; seed < 20; seed++) {
      var result = run(map(a, b), seed, left, right);
      assertEquals(a.position(), position(result, "a"));
      assertEquals(b.position(), position(result, "b"));
      assertEquals(result, run(map(b, a), seed, right, left));
      assertEquals(
          2,
          result.events().stream()
              .filter(e -> e.kind().equals("CONTACT") && e.reason().equals("CONTESTED"))
              .count());
    }
    b = unit("b", Side.RED, 1, 1, company("b1", CompanyType.ARMOR, Equipment.TRACKED));
    var crossed =
        run(map(a, b), 42, left, new MovementOrder("order-b", "b", List.of(a.position())));
    assertEquals(a.position(), position(crossed, "a"));
    assertEquals(b.position(), position(crossed, "b"));
  }

  @Test
  void blockedDeparturePropagatesToFollowersButSimultaneousVacatingIsAllowed() {
    var a = unit("a", Side.BLUE, 0, 1, company("a1", CompanyType.ARMOR, Equipment.TRACKED));
    var b = unit("b", Side.BLUE, 1, 1, company("b1", CompanyType.ARMOR, Equipment.TRACKED));
    var c = unit("c", Side.RED, 2, 1, company("c1", CompanyType.INFANTRY, Equipment.FOOT));
    var stopped = run(map(a, b, c), 42, east("a", 0, 1, 1), east("b", 1, 1, 1));
    assertEquals(a.position(), position(stopped, "a"));
    assertEquals(b.position(), position(stopped, "b"));
    var moving = run(map(a, b), 42, east("a", 0, 1, 1), east("b", 1, 1, 1));
    assertEquals(new HexCoord(1, 1), position(moving, "a"));
    assertEquals(new HexCoord(2, 1), position(moving, "b"));
  }

  @Test
  void invalidOrdersAndIterationBoundAreEnforced() {
    var world = map(unit("a", Side.BLUE, 0, 1, company("c", CompanyType.INFANTRY, Equipment.FOOT)));
    assertThrows(
        ScenarioViolation.class,
        () -> DaySimulation.validate(world, Side.RED, List.of(east("a", 0, 1, 1))));
    assertThrows(
        ScenarioViolation.class,
        () ->
            DaySimulation.validate(
                world,
                Side.BLUE,
                List.of(new MovementOrder("o", "a", List.of(new HexCoord(5, 1))))));
    var result =
        new DaySimulation().resolve(world, 1, 42, 1, List.of(east("a", 0, 1, 3)), List.of());
    assertEquals(new HexCoord(0, 1), position(result, "a"));
    assertEquals("DEFERRED", result.events().getLast().kind());
  }
}
