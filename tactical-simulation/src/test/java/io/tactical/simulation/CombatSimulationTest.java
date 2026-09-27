package io.tactical.simulation;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import io.tactical.core.*;
import io.tactical.core.MovementOrder.Action;
import java.util.*;
import org.junit.jupiter.api.Test;

class CombatSimulationTest {
  Company company(String id, CompanyType type, int hp) {
    int max = type == CompanyType.INFANTRY ? 160 : 100;
    return new Company(
        id, type, type == CompanyType.ARMOR ? Equipment.TRACKED : Equipment.FOOT, max, hp);
  }

  Regiment unit(String id, Side side, int q, int r, Company... companies) {
    return new Regiment(id, id, side, Role.REGIMENT, new HexCoord(q, r), "", List.of(companies));
  }

  Scenario map(List<Supply> supplies, Regiment... units) {
    var empty = ScenarioRules.blank("combat", 8, 4);
    return new Scenario(1, "combat", 8, 4, empty.cells(), List.of(), List.of(units), supplies);
  }

  MovementOrder move(String id, HexCoord to) {
    return new MovementOrder("move-" + id, id, List.of(to));
  }

  MovementOrder action(String id, Action action, HexCoord target) {
    return new MovementOrder("act-" + id, id, List.of(), action, target, null);
  }

  DaySimulation.Result run(Scenario s, List<MovementOrder> blue, List<MovementOrder> red) {
    return new DaySimulation().resolve(s, 1, 42, 4, blue, red);
  }

  Regiment armor(String id, Side side, int q, int r) {
    return unit(id, side, q, r, company(id + "c", CompanyType.ARMOR, 100));
  }

  int hp(DaySimulation.Result result, String id) {
    return result.world().regiments().stream()
        .filter(r -> r.id().equals(id))
        .flatMap(r -> r.companies().stream())
        .mapToInt(Company::hp)
        .sum();
  }

  @Test
  void organizationScalesFirepowerOnlyOnceAndArmorCountersAreSpecific() {
    var full = unit("a", Side.BLUE, 0, 1, company("a1", CompanyType.INFANTRY, 160));
    var half = unit("a", Side.BLUE, 0, 1, company("a1", CompanyType.INFANTRY, 80));
    var target = unit("b", Side.RED, 1, 1, company("b1", CompanyType.INFANTRY, 160));
    assertEquals(500, CombatRules.organization(half.companies().getFirst()));
    int whole =
        CombatRules.volley(full, target, 1, false, false, Terrain.PLAIN, 0).getFirst().damage();
    int damaged =
        CombatRules.volley(half, target, 1, false, false, Terrain.PLAIN, 0).getFirst().damage();
    assertEquals(whole / 2, damaged);
    var tank = armor("t", Side.RED, 1, 1);
    var at = unit("at", Side.BLUE, 0, 1, company("atc", CompanyType.ANTI_TANK, 100));
    int ineffective =
        CombatRules.volley(full, tank, 1, false, false, Terrain.PLAIN, 0).getFirst().damage();
    int effective =
        CombatRules.volley(at, tank, 1, false, false, Terrain.PLAIN, 0).getFirst().damage();
    assertTrue(effective > ineffective * 5);
    assertTrue(
        CombatRules.profile(full.companies().getFirst()).suggestedHp()
            > CombatRules.profile(tank.companies().getFirst()).suggestedHp());
    assertTrue(CombatRules.profile(tank.companies().getFirst()).protection() > 0);
  }

  @Test
  void mixedLineTakesCompanyDamageAndPreparedDefenderBenefits() {
    var attacker =
        unit(
            "a",
            Side.BLUE,
            1,
            1,
            company("a1", CompanyType.ARMOR, 100),
            company("a2", CompanyType.ARMOR, 100),
            company("a3", CompanyType.INFANTRY, 160));
    var defender =
        unit(
            "d",
            Side.RED,
            2,
            1,
            company("d1", CompanyType.ARMOR, 100),
            company("d2", CompanyType.ARMOR, 100),
            company("d3", CompanyType.INFANTRY, 160),
            company("d4", CompanyType.INFANTRY, 160),
            company("d5", CompanyType.INFANTRY, 160),
            company("d6", CompanyType.INFANTRY, 160));
    var input = map(List.of(), attacker, defender);
    var prepared =
        run(
            input,
            List.of(move("a", defender.position())),
            List.of(action("d", Action.DEFEND, null)));
    var unprepared =
        run(
            input,
            List.of(move("a", defender.position())),
            List.of(action("d", Action.REST, null)));
    assertTrue(hp(prepared, "d") > hp(unprepared, "d"));
    assertTrue(hp(prepared, "a") < 360);
    assertTrue(hp(prepared, "a") < hp(unprepared, "a"));
    assertTrue(
        prepared.events().stream()
            .anyMatch(e -> e.damage() != null && e.damage().targetCompanyId().startsWith("d")));
    assertEquals(
        6,
        prepared.world().regiments().stream()
            .filter(r -> r.id().equals("d"))
            .findFirst()
            .orElseThrow()
            .companies()
            .size());
    assertTrue(unprepared.events().stream().anyMatch(e -> e.kind().equals("REST_FAILED")));
  }

  @Test
  void distantArtilleryDoesNotTakeInventedCounterDamage() {
    var guns = unit("g", Side.BLUE, 0, 1, company("g1", CompanyType.ARTILLERY, 100));
    var infantry = unit("i", Side.RED, 3, 1, company("i1", CompanyType.INFANTRY, 160));
    var result =
        run(
            map(List.of(), guns, infantry),
            List.of(action("g", Action.BOMBARD, infantry.position())),
            List.of());
    assertEquals(100, hp(result, "g"));
    assertTrue(hp(result, "i") < 160);
    var enemyGuns = unit("i", Side.RED, 3, 1, company("i1", CompanyType.ARTILLERY, 100));
    assertTrue(
        hp(
                run(
                    map(List.of(), guns, enemyGuns),
                    List.of(action("g", Action.BOMBARD, enemyGuns.position())),
                    List.of()),
                "g")
            < 100);
  }

  @Test
  void
      failedBreakthroughWithdrawsAlongTimedRouteToKnownSupplyAndHiddenChangesDoNotAffectDecision() {
    var a = armor("a", Side.BLUE, 2, 1);
    var d = armor("d", Side.RED, 3, 1);
    var known = new Supply("known", new HexCoord(0, 1), Side.BLUE, 50);
    var memory = new TreeMap<>(RegimentMemory.initial(map(List.of(), a, d)));
    memory.put(
        "a",
        new RegimentMemory(
            Doctrine.standard(a),
            List.of(new RegimentMemory.KnownSupply("known", known.position(), Side.BLUE, 50, 0))));
    var input = map(List.of(known, new Supply("hidden", new HexCoord(7, 3), Side.BLUE, 900)), a, d);
    var orders = List.of(move("a", d.position()));
    var first = new DaySimulation().resolve(input, 1, 42, 4, orders, List.of(), memory);
    assertEquals(
        known.position(),
        first.world().regiments().stream()
            .filter(r -> r.id().equals("a"))
            .findFirst()
            .orElseThrow()
            .position());
    var changed = map(List.of(known, new Supply("hidden", new HexCoord(6, 3), Side.BLUE, 1)), a, d);
    var second = new DaySimulation().resolve(changed, 1, 42, 4, orders, List.of(), memory);
    var decisions = first.events().stream().filter(e -> e.decision() != null).toList();
    assertEquals(decisions, second.events().stream().filter(e -> e.decision() != null).toList());
    var retreat =
        decisions.stream()
            .filter(e -> e.regimentId().equals("a"))
            .findFirst()
            .orElseThrow()
            .decision();
    assertEquals("BREAKTHROUGH_FAILED", retreat.rule());
    assertEquals("known", retreat.targetSupplyId());
    assertEquals(
        2,
        first.events().stream()
            .filter(e -> e.regimentId().equals("a") && e.kind().equals("MOVED"))
            .count());
  }

  @Test
  void unknownOrUnreachableSupplyUsesExplicitFallbackAndOverrideIsRespected() {
    var a = armor("a", Side.BLUE, 2, 1);
    var d = armor("d", Side.RED, 3, 1);
    var world = map(List.of(new Supply("remote", new HexCoord(7, 3), Side.BLUE, 100)), a, d);
    var result = run(world, List.of(move("a", d.position())), List.of());
    assertTrue(
        result.events().stream()
            .anyMatch(
                e ->
                    e.regimentId().equals("a")
                        && e.decision() != null
                        && e.decision().action().equals("HOLD")
                        && e.decision().knowledge().isEmpty()));
    var known =
        new RegimentMemory(
            new Doctrine(1, Doctrine.Template.BREAKTHROUGH, 35, "unknown"),
            List.of(new RegimentMemory.KnownSupply("known", new HexCoord(1, 1), Side.BLUE, 10, 0)));
    var terrain =
        new DoctrinePlanner.TerrainMap(world.width(), world.height(), world.cells(), world.edges());
    assertEquals("HOLD", DoctrinePlanner.decide(terrain, a, known, Set.of(), true).action());
    var normal = new RegimentMemory(Doctrine.standard(a), known.supplies());
    assertEquals(
        "HOLD",
        DoctrinePlanner.decide(terrain, a, normal, Set.of(new HexCoord(1, 1)), true).action());
    assertEquals("WITHDRAW", DoctrinePlanner.decide(terrain, a, normal, Set.of(), true).action());
  }

  @Test
  void restConsumesStockAndNeverResurrectsDestroyedCompanies() {
    var a =
        unit(
            "a",
            Side.BLUE,
            1,
            1,
            company("a1", CompanyType.INFANTRY, 80),
            company("a2", CompanyType.ARMOR, 0));
    var world = map(List.of(new Supply("s", a.position(), Side.BLUE, 7)), a);
    var result = run(world, List.of(action("a", Action.REST, null)), List.of());
    assertEquals(87, hp(result, "a"));
    assertEquals("WAITING", result.events().get(1).kind());
    assertEquals("COMPLETED", result.events().getLast().kind());
    assertEquals(0, result.world().supplies().getFirst().stock());
    assertEquals(0, result.world().regiments().getFirst().companies().get(1).hp());
    assertEquals(
        80, hp(run(map(List.of(), a), List.of(action("a", Action.REST, null)), List.of()), "a"));
  }

  @Test
  void destroyedScreenIsRemovedAndOnlySurvivorAdvances() {
    var a =
        unit(
            "a",
            Side.BLUE,
            1,
            1,
            company("a1", CompanyType.ARMOR, 100),
            company("a2", CompanyType.ARMOR, 100));
    var d = unit("d", Side.RED, 2, 1, company("d1", CompanyType.INFANTRY, 1));
    var result = run(map(List.of(), a, d), List.of(move("a", d.position())), List.of());
    assertEquals(1, result.world().regiments().size());
    assertEquals(d.position(), result.world().regiments().getFirst().position());
    assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("DESTROYED")));
    assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("ADVANCED")));
    assertTrue(
        result.units().stream()
            .filter(r -> r.id().equals("d"))
            .findFirst()
            .orElseThrow()
            .destroyed());
  }

  @Test
  void collapsedButLivingDefenderIsDisplacedAndCombatIsReplayable() {
    var a = unit("a", Side.BLUE, 1, 1, company("a1", CompanyType.SIGNAL, 80));
    var d = unit("d", Side.RED, 2, 1, company("d1", CompanyType.INFANTRY, 25));
    var input = map(List.of(), a, d);
    var orders = List.of(move("a", d.position()));
    var result = run(input, orders, List.of());
    assertTrue(result.events().stream().anyMatch(e -> e.kind().equals("DISPLACED")));
    assertEquals(result, run(map(List.of(), d, a), orders, List.of()));
    assertEquals(result, run(input, orders, List.of()));
    assertEquals(25, d.companies().getFirst().hp());
  }

  @Test
  void simultaneousEmptyCellContestUsesContactRangeAndNoDefenderBonus() {
    var a = armor("a", Side.BLUE, 1, 1);
    var b = armor("b", Side.RED, 3, 1);
    var orders = List.of(move("a", new HexCoord(2, 1)));
    var enemy = List.of(move("b", new HexCoord(2, 1)));
    var result = run(map(List.of(), a, b), orders, enemy);
    assertTrue(hp(result, "a") < 100);
    assertEquals(hp(result, "a"), hp(result, "b"));
    assertEquals(
        a.position(),
        result.world().regiments().stream()
            .filter(r -> r.id().equals("a"))
            .findFirst()
            .orElseThrow()
            .position());
    assertEquals(result, run(map(List.of(), b, a), orders, enemy));
  }

  @Test
  void headquartersCasualtiesDoNotApplyDraftOnlySignalRequirementsAtRuntime() {
    var gun = unit("g", Side.BLUE, 0, 1, company("g1", CompanyType.ARTILLERY, 100));
    var hq =
        new Regiment(
            "hq",
            "hq",
            Side.RED,
            Role.DIVISION_HQ,
            new HexCoord(3, 1),
            "",
            List.of(
                company("a-signal", CompanyType.SIGNAL, 1),
                company("b-infantry", CompanyType.INFANTRY, 160)));
    var base = map(List.of(), gun, hq);
    var world =
        new Scenario(
            1,
            base.name(),
            8,
            4,
            base.cells().stream()
                .map(
                    c ->
                        c.position().equals(hq.position())
                            ? new Cell(c.position(), Terrain.CITY, 0)
                            : c)
                .toList(),
            List.of(),
            base.regiments(),
            List.of());
    var result = run(world, List.of(action("g", Action.BOMBARD, hq.position())), List.of());
    assertEquals(
        0,
        result.world().regiments().stream()
            .filter(r -> r.id().equals("hq"))
            .findFirst()
            .orElseThrow()
            .companies()
            .getFirst()
            .hp());
    assertDoesNotThrow(() -> ScenarioHash.runtime(result.world()));
    assertThrows(ScenarioViolation.class, () -> ScenarioRules.normalize(result.world()));
  }
}
