package io.tactical.core;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ScenarioRulesTest {
  private final Scenario blank = ScenarioRules.blank("test", 4, 4);

  private Scenario with(
      List<Cell> cells, List<Edge> edges, List<Regiment> regiments, List<Supply> supplies) {
    return new Scenario(1, "test", 4, 4, cells, edges, regiments, supplies);
  }

  private Regiment regiment(String id, Role role, List<Company> companies) {
    return new Regiment(id, id, Side.BLUE, role, new HexCoord(1, 1), "", companies);
  }

  private Company company(String id, CompanyType type, int hp) {
    return new Company(id, type, Equipment.FOOT, 100, hp);
  }

  @Test
  void axialNeighborsAndCanonicalHashes() {
    assertEquals(1, new HexCoord(1, 1).distance(new HexCoord(2, 0)));
    assertEquals(2, new HexCoord(0, 0).distance(new HexCoord(1, 1)));
    var a = new HexCoord(1, 1);
    var b = new HexCoord(2, 1);
    var cells = new ArrayList<>(blank.cells());
    Collections.reverse(cells);
    Scenario first =
        with(
            blank.cells(),
            List.of(new Edge(a, b, true, Bridge.INTACT, true)),
            List.of(),
            List.of());
    Scenario reversed =
        with(cells, List.of(new Edge(b, a, true, Bridge.INTACT, true)), List.of(), List.of());
    assertEquals(ScenarioHash.sha256(first), ScenarioHash.sha256(reversed));
    Scenario changed =
        with(cells, List.of(new Edge(b, a, true, Bridge.DESTROYED, true)), List.of(), List.of());
    assertNotEquals(ScenarioHash.sha256(first), ScenarioHash.sha256(changed));
    assertThrows(UnsupportedOperationException.class, () -> first.cells().clear());
    cells.clear();
    assertEquals(16, reversed.cells().size());
  }

  @Test
  void rejectsInvalidCoordinatesIncompleteMapsAndEdges() {
    assertThrows(ScenarioViolation.class, () -> ScenarioRules.blank("big", 17, 2));
    var cells = new ArrayList<>(blank.cells());
    cells.set(0, new Cell(new HexCoord(-1, 0), Terrain.PLAIN, 0));
    assertThrows(
        ScenarioViolation.class,
        () -> ScenarioRules.normalize(with(cells, List.of(), List.of(), List.of())));
    cells.set(0, cells.get(1));
    assertThrows(
        ScenarioViolation.class,
        () -> ScenarioRules.normalize(with(cells, List.of(), List.of(), List.of())));
    var a = new HexCoord(1, 1);
    var b = new HexCoord(2, 1);
    for (var edges :
        List.of(
            List.of(new Edge(a, b, false, Bridge.INTACT, false)),
            List.of(new Edge(a, new HexCoord(3, 3), true, Bridge.NONE, false)),
            List.of(
                new Edge(a, b, true, Bridge.NONE, false),
                new Edge(b, a, true, Bridge.NONE, false)))) {
      assertThrows(
          ScenarioViolation.class,
          () -> ScenarioRules.normalize(with(blank.cells(), edges, List.of(), List.of())));
    }
  }

  @Test
  void enforcesOccupancyHeadquartersAndCompanyLimits() {
    var ordinary = regiment("a", Role.REGIMENT, List.of(company("i", CompanyType.INFANTRY, 10)));
    assertEquals(
        1,
        ScenarioRules.normalize(with(blank.cells(), List.of(), List.of(ordinary), List.of()))
            .regiments()
            .size());
    var duplicate = regiment("b", Role.REGIMENT, List.of(company("j", CompanyType.ARMOR, 100)));
    assertThrows(
        ScenarioViolation.class,
        () ->
            ScenarioRules.normalize(
                with(blank.cells(), List.of(), List.of(ordinary, duplicate), List.of())));
    for (var companies :
        List.of(
            List.<Company>of(),
            List.of(company("i", CompanyType.INFANTRY, 101)),
            List.of(company("i", CompanyType.INFANTRY, 0)),
            Collections.nCopies(7, company("i", CompanyType.INFANTRY, 100)))) {
      var invalid = regiment("x", Role.REGIMENT, companies);
      assertThrows(
          ScenarioViolation.class,
          () ->
              ScenarioRules.normalize(with(blank.cells(), List.of(), List.of(invalid), List.of())));
    }
    var oneSignal = regiment("hq", Role.BRIGADE_HQ, List.of(company("s", CompanyType.SIGNAL, 100)));
    assertThrows(
        ScenarioViolation.class,
        () ->
            ScenarioRules.normalize(with(blank.cells(), List.of(), List.of(oneSignal), List.of())));
    var twoSignals =
        regiment(
            "hq",
            Role.BRIGADE_HQ,
            List.of(
                company("s1", CompanyType.SIGNAL, 100), company("s2", CompanyType.SIGNAL, 100)));
    assertDoesNotThrow(
        () ->
            ScenarioRules.normalize(
                with(blank.cells(), List.of(), List.of(twoSignals), List.of())));
    var division = regiment("hq", Role.DIVISION_HQ, List.of(company("s", CompanyType.SIGNAL, 100)));
    assertThrows(
        ScenarioViolation.class,
        () ->
            ScenarioRules.normalize(with(blank.cells(), List.of(), List.of(division), List.of())));
  }

  @Test
  void rejectsNullInputAndInvalidSupply() {
    assertThrows(
        ScenarioViolation.class,
        () -> new Scenario(1, "x", 2, 2, null, List.of(), List.of(), List.of()));
    assertThrows(
        ScenarioViolation.class,
        () ->
            ScenarioRules.normalize(
                with(
                    blank.cells(),
                    List.of(),
                    List.of(),
                    List.of(new Supply("s", new HexCoord(0, 0), Side.BLUE, -1)))));
    assertThrows(ScenarioViolation.class, () -> ScenarioRules.requirePlayable(blank));
  }
}
