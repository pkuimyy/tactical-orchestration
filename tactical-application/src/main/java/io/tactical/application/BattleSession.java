package io.tactical.application;

import io.tactical.core.*;
import io.tactical.core.Scenario.Side;
import io.tactical.simulation.*;
import java.util.*;

/** Access is serialized by ScenarioService. Persisted inputs/results are immutable. */
public final class BattleSession {
  public record Batch(
      long version,
      boolean submitted,
      boolean committed,
      List<MovementOrder> orders,
      OperationPlan operation) {
    public Batch {
      orders = List.copyOf(orders);
    }
  }

  public record View(
      int day,
      String status,
      int maxIterations,
      Scenario world,
      Batch blue,
      Batch red,
      Map<String, RegimentMemory> memory,
      List<DaySimulation.UnitReport> units) {}

  public record Manifest(
      int schemaVersion,
      String scenarioHash,
      String rulesVersion,
      String randomVersion,
      long seed,
      int day,
      int maxIterations,
      String inputHash,
      List<MovementOrder> blue,
      List<MovementOrder> red,
      Map<String, RegimentMemory> memory,
      OperationPlan blueOperation,
      OperationPlan redOperation,
      IntelligenceState intelligence) {
    public Manifest {
      blue = List.copyOf(blue);
      red = List.copyOf(red);
      memory = Collections.unmodifiableMap(new TreeMap<>(memory));
    }
  }

  public record Day(Manifest manifest, DaySimulation.Result result) {}

  private final long seed;
  private final int iterations;
  private final String initialHash;
  private Scenario world;
  private Map<String, RegimentMemory> memory;
  private int completedDays;
  private IntelligenceState intelligence;
  private final Map<Side, Batch> batches = new EnumMap<>(Side.class);
  private final Map<Integer, Day> history = new TreeMap<>();

  public BattleSession(Scenario world, long seed, int iterations) {
    if (iterations < 1 || iterations > 8) throw new ScenarioViolation("事件迭代上限为 1–8");
    this.world = world;
    this.memory = RegimentMemory.initial(world);
    this.intelligence = IntelligenceState.initial(world);
    this.seed = seed;
    this.iterations = iterations;
    initialHash = ScenarioHash.sha256(world);
    reset();
  }

  public View view() {
    return new View(
        completedDays + 1,
        completedDays >= 60
            ? "LIMIT_REACHED"
            : batches.values().stream().allMatch(Batch::committed) ? "LOCKED" : "PLANNING",
        iterations,
        world,
        batches.get(Side.BLUE),
        batches.get(Side.RED),
        memory,
        DaySimulation.reports(world, world));
  }

  public View submit(int day, Side side, long expectedVersion, List<MovementOrder> orders) {
    return submit(day, side, expectedVersion, orders, null);
  }

  public View submit(
      int day,
      Side side,
      long expectedVersion,
      List<MovementOrder> orders,
      OperationPlan operation) {
    current(day);
    if (side == null) throw new ScenarioViolation("必须指定阵营");
    var normalized = DaySimulation.validate(world, side, orders);
    if (operation != null) operation.validate(world, side, normalized);
    Batch old = batches.get(side);
    // Identical retry after response loss is safe; it cannot overwrite another submission.
    if (old.submitted()
        && old.orders().equals(normalized)
        && Objects.equals(old.operation(), operation)
        && (expectedVersion == old.version() || expectedVersion == old.version() - 1))
      return view();
    if (old.committed()) throw conflict("该方命令已锁定");
    if (old.version() != expectedVersion) throw conflict("命令已更新，请重新载入");
    batches.put(side, new Batch(old.version() + 1, true, false, normalized, operation));
    return view();
  }

  public View commit(int day, Side side, long expectedVersion) {
    current(day);
    if (side == null) throw new ScenarioViolation("必须指定阵营");
    Batch old = batches.get(side);
    if (!old.submitted() || old.version() != expectedVersion) throw conflict("请先提交并确认当前版本命令");
    batches.put(side, new Batch(old.version(), true, true, old.orders(), old.operation()));
    return view();
  }

  public Day resolve(int day) {
    if (history.containsKey(day)) return history.get(day);
    current(day);
    if (!view().status().equals("LOCKED")) throw conflict("双方命令确认锁定后才能结算");
    if (completedDays >= 60) throw new StoreProblem(StoreProblem.Kind.LIMIT, "每场实验最多 60 天");
    var blue = batches.get(Side.BLUE).orders();
    var red = batches.get(Side.RED).orders();
    var manifest =
        new Manifest(
            1,
            initialHash,
            RuleSet.VERSION,
            DaySimulation.RANDOM_VERSION,
            seed,
            day,
            iterations,
            DaySimulation.stateHash(completedDays, world, memory, intelligence),
            blue,
            red,
            memory,
            batches.get(Side.BLUE).operation(),
            batches.get(Side.RED).operation(),
            intelligence);
    var result =
        new DaySimulation()
            .resolve(
                world,
                day,
                seed,
                iterations,
                blue,
                red,
                memory,
                manifest.blueOperation(),
                manifest.redOperation(),
                intelligence);
    var record = new Day(manifest, result);
    history.put(day, record);
    world = result.world();
    memory = result.memory();
    intelligence = result.intelligence();
    completedDays = day;
    reset();
    return record;
  }

  public enum Perspective {
    DIVISION,
    OMNISCIENT
  }

  public record PlayerView(
      int day,
      Perspective perspective,
      Side side,
      Scenario world,
      IntelligenceState.Knowledge knowledge,
      List<CommunicationNetwork.Node> nodes,
      List<CommunicationNetwork.Link> links,
      List<DaySimulation.UnitReport> units,
      List<DaySimulation.Event> events,
      int pendingReports) {}

  public PlayerView projection(Perspective perspective, Side side) {
    if (perspective == null || side == null) throw new ScenarioViolation("需要观察视角与阵营");
    var knowledge = intelligence.divisions().get(side);
    Scenario projected = world;
    List<DaySimulation.Event> events =
        completedDays == 0 ? List.of() : history.get(completedDays).result().events();
    if (perspective == Perspective.DIVISION) {
      projected =
          new Scenario(
              world.schemaVersion(),
              world.name(),
              world.width(),
              world.height(),
              world.cells(),
              knowledge.edges().values().stream().map(IntelligenceState.KnownEdge::edge).toList(),
              knowledge.contacts().values().stream()
                  .filter(c -> !c.destroyed())
                  .map(IntelligenceState.Contact::unit)
                  .toList(),
              knowledge.supplies().values().stream()
                  .map(s -> new Scenario.Supply(s.id(), s.position(), s.side(), s.stock()))
                  .toList(),
              knowledge.relays().values().stream().map(IntelligenceState.KnownRelay::node).toList(),
              world.communicationRadius());
      events = knowledge.events();
    }
    var network = new CommunicationNetwork(projected);
    return new PlayerView(
        completedDays + 1,
        perspective,
        side,
        projected,
        knowledge,
        network.nodes(),
        network.links(),
        DaySimulation.reports(projected, projected),
        events,
        perspective == Perspective.OMNISCIENT ? intelligence.pending().size() : -1);
  }

  public Day day(int day) {
    var result = history.get(day);
    if (result == null) throw new StoreProblem(StoreProblem.Kind.NOT_FOUND, "该日尚未结算");
    return result;
  }

  private void current(int day) {
    if (completedDays >= 60) throw new StoreProblem(StoreProblem.Kind.LIMIT, "每场实验最多 60 天");
    if (day != completedDays + 1) throw conflict("天数已变化，请重新载入；不能修改过去或未来的命令");
  }

  private void reset() {
    for (var side : Side.values()) batches.put(side, new Batch(0, false, false, List.of(), null));
  }

  private static StoreProblem conflict(String message) {
    return new StoreProblem(StoreProblem.Kind.CONFLICT, message);
  }
}
