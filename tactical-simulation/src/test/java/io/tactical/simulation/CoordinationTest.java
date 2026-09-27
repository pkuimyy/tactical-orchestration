package io.tactical.simulation;

import static io.tactical.core.Scenario.*;
import static org.junit.jupiter.api.Assertions.*;

import io.tactical.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoordinationTest {
  private HexCoord at(int q, int r) {
    return new HexCoord(q, r);
  }

  private Regiment unit(String id, Side side, Role role, int q, int r, CompanyType... types) {
    var companies = new ArrayList<Company>();
    for (int i = 0; i < types.length; i++)
      companies.add(
          new Company(
              id + i,
              types[i],
              types[i] == CompanyType.ARMOR ? Equipment.TRACKED : Equipment.MOTORIZED,
              100,
              100));
    return new Regiment(
        id,
        id,
        side,
        role,
        at(q, r),
        role == Role.REGIMENT && side == Side.BLUE ? "brigade" : "",
        companies);
  }

  private Scenario world() {
    var cells =
        ScenarioRules.blank("coordination", 16, 4).cells().stream()
            .map(
                c ->
                    new Cell(
                        c.position(),
                        c.position().equals(at(0, 0)) || c.position().equals(at(15, 3))
                            ? Terrain.CITY
                            : Terrain.PLAIN,
                        0))
            .toList();
    return ScenarioRules.normalize(
        new Scenario(
            1,
            "coordination",
            16,
            4,
            cells,
            List.of(new Edge(at(2, 1), at(3, 0), true, Bridge.DESTROYED, true)),
            List.of(
                unit("hq", Side.BLUE, Role.DIVISION_HQ, 0, 0, CompanyType.SIGNAL),
                unit(
                    "brigade",
                    Side.BLUE,
                    Role.BRIGADE_HQ,
                    1,
                    0,
                    CompanyType.SIGNAL,
                    CompanyType.SIGNAL),
                unit("engineer", Side.BLUE, Role.REGIMENT, 2, 0, CompanyType.ENGINEER),
                unit("armor", Side.BLUE, Role.REGIMENT, 2, 1, CompanyType.ARMOR),
                unit("enemy", Side.RED, Role.DIVISION_HQ, 15, 3, CompanyType.SIGNAL)),
            List.of()));
  }

  private List<MovementOrder> orders() {
    return List.of(
        new MovementOrder(
            "bridge", "engineer", List.of(), MovementOrder.Action.BUILD_BRIDGE, at(3, 0), null),
        new MovementOrder("cross", "armor", List.of(at(3, 0), at(4, 0))));
  }

  private OperationPlan plan(OperationPlan.Mode mode, OperationPlan.Fallback fallback) {
    return new OperationPlan(
        "op",
        "brigade",
        mode,
        world().cells().stream().map(Cell::position).toList(),
        List.of(
            new OperationPlan.Node("bridge", List.of(), OperationPlan.Fallback.HOLD),
            new OperationPlan.Node("cross", List.of("bridge"), fallback)));
  }

  private DaySimulation.Result run(long seed, OperationPlan.Mode mode) {
    var w = world();
    return new DaySimulation()
        .resolve(
            w,
            1,
            seed,
            4,
            orders(),
            List.of(),
            RegimentMemory.initial(w),
            plan(mode, OperationPlan.Fallback.HOLD),
            null,
            IntelligenceState.initial(w));
  }

  private Scenario replace(
      Scenario w, List<Regiment> units, List<CommunicationNode> nodes, int radius) {
    return new Scenario(
        1,
        w.name(),
        w.width(),
        w.height(),
        w.cells(),
        w.edges(),
        units,
        w.supplies(),
        nodes,
        radius);
  }

  private Regiment move(Regiment r, HexCoord p) {
    return new Regiment(r.id(), r.name(), r.side(), r.role(), p, r.brigadeId(), r.companies());
  }

  private DaySimulation.Event outcome(String kind) {
    return new DaySimulation.Event(
        1,
        "d1-e1",
        "OrderTransition",
        kind,
        1,
        1,
        15,
        0,
        "bridge",
        "engineer",
        at(2, 0),
        at(3, 0),
        List.of("engineer"),
        "test",
        null,
        null,
        null);
  }

  @Test
  void coordinatedBridgeWaitsForCompletionAndConfirmationThenCrosses() {
    var r = run(42, OperationPlan.Mode.COORDINATED);
    assertEquals(Bridge.INTACT, r.world().edges().getFirst().bridge());
    assertEquals(
        at(4, 0),
        r.world().regiments().stream()
            .filter(u -> u.id().equals("armor"))
            .findFirst()
            .orElseThrow()
            .position());
    var completion =
        r.events().stream()
            .filter(e -> e.orderId().equals("bridge") && e.kind().equals("COMPLETED"))
            .findFirst()
            .orElseThrow();
    var start =
        r.events().stream()
            .filter(e -> e.orderId().equals("cross") && e.kind().equals("EXECUTING"))
            .findFirst()
            .orElseThrow();
    assertTrue(start.tick() > completion.tick());
    assertEquals(
        "SUCCESS",
        r.operations().stream()
            .filter(s -> s.orderId().equals("cross"))
            .findFirst()
            .orElseThrow()
            .confirmed()
            .get("bridge"));
    assertEquals(r, run(42, OperationPlan.Mode.COORDINATED));
  }

  @Test
  void independentSchedulingNaturallyProducesBothOrdersAcrossSeeds() {
    var results = new HashSet<Boolean>();
    for (int seed = 0; seed < 48; seed++) {
      var r = run(seed, OperationPlan.Mode.INDEPENDENT);
      results.add(
          r.world().regiments().stream()
              .filter(u -> u.id().equals("armor"))
              .findFirst()
              .orElseThrow()
              .position()
              .equals(at(4, 0)));
    }
    assertEquals(Set.of(true, false), results);
  }

  @Test
  void rejectsCyclesDanglingReferencesCrossBrigadeAndOutsideTheater() {
    assertThrows(
        ScenarioViolation.class,
        () ->
            new OperationPlan(
                "op",
                "brigade",
                OperationPlan.Mode.COORDINATED,
                List.of(at(0, 0)),
                List.of(
                    new OperationPlan.Node("a", List.of("b"), OperationPlan.Fallback.HOLD),
                    new OperationPlan.Node("b", List.of("a"), OperationPlan.Fallback.HOLD))));
    assertThrows(
        ScenarioViolation.class,
        () ->
            new OperationPlan(
                "op",
                "brigade",
                OperationPlan.Mode.COORDINATED,
                List.of(at(0, 0)),
                List.of(
                    new OperationPlan.Node("a", List.of("missing"), OperationPlan.Fallback.HOLD))));
    var w = world();
    var p = plan(OperationPlan.Mode.COORDINATED, OperationPlan.Fallback.HOLD);
    var unrelated =
        w.regiments().stream()
            .map(
                r ->
                    r.id().equals("armor")
                        ? new Regiment(
                            r.id(),
                            r.name(),
                            r.side(),
                            r.role(),
                            r.position(),
                            "other",
                            r.companies())
                        : r)
            .toList();
    assertThrows(
        ScenarioViolation.class,
        () -> p.validate(replace(w, unrelated, List.of(), 5), Side.BLUE, orders()));
    var small = new OperationPlan("op", "brigade", p.mode(), List.of(at(2, 0)), p.nodes());
    assertThrows(ScenarioViolation.class, () -> small.validate(w, Side.BLUE, orders()));
  }

  @Test
  void worldSuccessDoesNotSubstituteForReceiverConfirmation() {
    var w = world();
    var runtime =
        new OperationRuntime(
            plan(OperationPlan.Mode.COORDINATED, OperationPlan.Fallback.HOLD), orders());
    assertEquals("WAITING_CONFIRMATION", runtime.ready(w, "armor"));
    var separated =
        replace(
            w,
            w.regiments().stream()
                .filter(r -> !r.id().equals("brigade"))
                .map(r -> r.id().equals("armor") ? move(r, at(12, 1)) : r)
                .toList(),
            List.of(),
            1);
    runtime.outcomes(separated, List.of(outcome("COMPLETED")));
    assertEquals("WAITING_CONFIRMATION", runtime.ready(separated, "armor"));
    assertTrue(
        runtime.states().stream()
            .filter(s -> s.orderId().equals("cross"))
            .findFirst()
            .orElseThrow()
            .confirmed()
            .isEmpty());
    var observed =
        replace(
            separated,
            separated.regiments().stream()
                .map(r -> r.id().equals("armor") ? move(r, at(3, 1)) : r)
                .toList(),
            List.of(),
            1);
    assertEquals("WAITING_CONFIRMATION", runtime.ready(observed, "armor"));
    var witness =
        new OperationRuntime(
            plan(OperationPlan.Mode.COORDINATED, OperationPlan.Fallback.HOLD), orders());
    witness.ready(w, "armor");
    witness.outcomes(observed, List.of(outcome("COMPLETED")));
    assertEquals("EXECUTING", witness.ready(observed, "armor"));
  }

  @Test
  void receivedTasksSurviveBrigadeLossButUndeliveredTasksDoNotAppear() {
    var w = world();
    var runtime =
        new OperationRuntime(
            plan(OperationPlan.Mode.COORDINATED, OperationPlan.Fallback.HOLD), orders());
    assertEquals("EXECUTING", runtime.ready(w, "engineer"));
    var lost =
        replace(
            w,
            w.regiments().stream().filter(r -> !r.id().equals("brigade")).toList(),
            List.of(),
            1);
    assertEquals("EXECUTING", runtime.ready(lost, "engineer"));
    assertEquals("WAITING_DELIVERY", runtime.ready(lost, "armor"));
  }

  @Test
  void confirmedFailureSelectsConfiguredBranch() {
    for (var fallback : OperationPlan.Fallback.values()) {
      var runtime = new OperationRuntime(plan(OperationPlan.Mode.COORDINATED, fallback), orders());
      runtime.ready(world(), "armor");
      runtime.outcomes(world(), List.of(outcome("ACTION_FAILED")));
      assertEquals(
          switch (fallback) {
            case HOLD -> "HOLD";
            case CANCEL -> "CANCELLED";
            case CONTINUE -> "EXECUTING_FALLBACK";
          },
          runtime.ready(world(), "armor"));
    }
  }

  @Test
  void destroyedArtilleryPrerequisiteUsesFallbackAndFacilityAttackChangesConnectivity() {
    var w = world();
    var units =
        new ArrayList<>(w.regiments().stream().filter(r -> !r.id().equals("engineer")).toList());
    units.add(
        new Regiment(
            "guns",
            "guns",
            Side.BLUE,
            Role.REGIMENT,
            at(2, 0),
            "brigade",
            List.of(new Company("gun-c", CompanyType.ARTILLERY, Equipment.TOWED, 100, 1))));
    units.add(unit("raider", Side.RED, Role.REGIMENT, 3, 0, CompanyType.RECON));
    var map = replace(w, units, List.of(), 5);
    var blue =
        List.of(
            new MovementOrder(
                "fire", "guns", List.of(), MovementOrder.Action.BOMBARD, at(4, 0), null),
            new MovementOrder("attack", "armor", List.of(at(3, 1))));
    var plan =
        new OperationPlan(
            "fire-op",
            "brigade",
            OperationPlan.Mode.COORDINATED,
            w.cells().stream().map(Cell::position).toList(),
            List.of(
                new OperationPlan.Node("fire", List.of(), OperationPlan.Fallback.HOLD),
                new OperationPlan.Node(
                    "attack", List.of("fire"), OperationPlan.Fallback.CONTINUE)));
    var result =
        new DaySimulation()
            .resolve(
                map,
                1,
                42,
                4,
                blue,
                List.of(new MovementOrder("raid", "raider", List.of(at(2, 0)))),
                RegimentMemory.initial(map),
                plan,
                null,
                IntelligenceState.initial(map));
    assertTrue(
        result.events().stream()
            .anyMatch(e -> e.kind().equals("EXECUTING_FALLBACK") && e.orderId().equals("attack")));
    assertEquals(
        "FAILED",
        result.operations().stream()
            .filter(n -> n.orderId().equals("attack"))
            .findFirst()
            .orElseThrow()
            .confirmed()
            .get("fire"));
    var relayMap =
        replace(
            w,
            List.of(
                unit("hq", Side.BLUE, Role.DIVISION_HQ, 0, 0, CompanyType.SIGNAL),
                unit("scout", Side.BLUE, Role.REGIMENT, 6, 0, CompanyType.RECON),
                unit("gun", Side.RED, Role.REGIMENT, 3, 1, CompanyType.ARTILLERY)),
            List.of(new CommunicationNode("relay", at(3, 0), Side.BLUE, 10)),
            3);
    assertTrue(new CommunicationNetwork(relayMap).toDivision("scout"));
    var damaged =
        new DaySimulation()
            .resolve(
                relayMap,
                1,
                42,
                4,
                List.of(),
                List.of(
                    new MovementOrder(
                        "strike",
                        "gun",
                        List.of(),
                        MovementOrder.Action.STRIKE_RELAY,
                        at(3, 0),
                        null)));
    assertEquals(0, damaged.world().communicationNodes().getFirst().hp());
    assertFalse(new CommunicationNetwork(damaged.world()).toDivision("scout"));
  }

  @Test
  void relayDestructionBreaksNetworkAndTerminalsCannotRelay() {
    var w = world();
    var units =
        List.of(
            unit("hq", Side.BLUE, Role.DIVISION_HQ, 0, 0, CompanyType.SIGNAL),
            unit("scout", Side.BLUE, Role.REGIMENT, 6, 0, CompanyType.RECON),
            unit("terminal", Side.BLUE, Role.REGIMENT, 9, 0, CompanyType.INFANTRY));
    var connected =
        replace(w, units, List.of(new CommunicationNode("relay", at(3, 0), Side.BLUE, 20)), 3);
    assertTrue(new CommunicationNetwork(connected).toDivision("scout"));
    assertFalse(new CommunicationNetwork(connected).toDivision("terminal"));
    var broken =
        replace(w, units, List.of(new CommunicationNode("relay", at(3, 0), Side.BLUE, 0)), 3);
    assertFalse(new CommunicationNetwork(broken).toDivision("scout"));
  }

  @Test
  void reportsAreLocalOnNAndSharedToHeadquartersAndUnitsOnNPlusOne() {
    var w = world();
    var units =
        List.of(
            unit("hq", Side.BLUE, Role.DIVISION_HQ, 0, 0, CompanyType.SIGNAL),
            unit("scout", Side.BLUE, Role.REGIMENT, 6, 0, CompanyType.RECON),
            unit("enemy", Side.RED, Role.REGIMENT, 7, 0, CompanyType.ARMOR));
    w = replace(w, units, List.of(new CommunicationNode("relay", at(3, 0), Side.BLUE, 20)), 3);
    var intel = IntelligenceState.initial(w);
    var scout = units.get(1);
    assertFalse(intel.divisions().get(Side.BLUE).contacts().containsKey("enemy"));
    assertTrue(intel.regiments().get("scout").contacts().containsKey("enemy"));
    var local =
        Map.of(
            "scout",
            IntelligenceState.observe(w, scout, 1, IntelligenceState.Knowledge.empty(), List.of()));
    var shared = intel.advance(w, 2, local);
    assertEquals(1, shared.divisions().get(Side.BLUE).contacts().get("enemy").observedDay());
    assertTrue(shared.regiments().get("hq").contacts().containsKey("enemy"));
    var broken =
        replace(w, units, List.of(new CommunicationNode("relay", at(3, 0), Side.BLUE, 0)), 3);
    var held = intel.advance(broken, 2, local);
    assertFalse(held.divisions().get(Side.BLUE).contacts().containsKey("enemy"));
    assertEquals(1, held.pending().size());
    var restored = held.advance(w, 3, Map.of());
    assertTrue(restored.divisions().get(Side.BLUE).contacts().containsKey("enemy"));
    assertTrue(restored.pending().isEmpty());
  }
}
