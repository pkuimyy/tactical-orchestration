package io.tactical.application;

import io.tactical.core.*;
import io.tactical.core.Scenario.Side;
import io.tactical.simulation.*;
import java.util.*;

/** Access is serialized by ScenarioService. Persisted inputs/results are immutable. */
public final class BattleSession {
  public record Batch(
      long version, boolean submitted, boolean committed, List<MovementOrder> orders) {
    public Batch {
      orders = List.copyOf(orders);
    }
  }

  public record View(
      int day, String status, int maxIterations, Scenario world, Batch blue, Batch red) {}

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
      List<MovementOrder> red) {
    public Manifest {
      blue = List.copyOf(blue);
      red = List.copyOf(red);
    }
  }

  public record Day(Manifest manifest, DaySimulation.Result result) {}

  private final long seed;
  private final int iterations;
  private final String initialHash;
  private Scenario world;
  private int completedDays;
  private final Map<Side, Batch> batches = new EnumMap<>(Side.class);
  private final Map<Integer, Day> history = new TreeMap<>();

  public BattleSession(Scenario world, long seed, int iterations) {
    if (iterations < 1 || iterations > 8) throw new ScenarioViolation("事件迭代上限为 1–8");
    this.world = world;
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
        batches.get(Side.RED));
  }

  public View submit(int day, Side side, long expectedVersion, List<MovementOrder> orders) {
    current(day);
    if (side == null) throw new ScenarioViolation("必须指定阵营");
    var normalized = DaySimulation.validate(world, side, orders);
    Batch old = batches.get(side);
    // Identical retry after response loss is safe; it cannot overwrite another submission.
    if (old.submitted()
        && old.orders().equals(normalized)
        && (expectedVersion == old.version() || expectedVersion == old.version() - 1))
      return view();
    if (old.committed()) throw conflict("该方命令已锁定");
    if (old.version() != expectedVersion) throw conflict("命令已更新，请重新载入");
    batches.put(side, new Batch(old.version() + 1, true, false, normalized));
    return view();
  }

  public View commit(int day, Side side, long expectedVersion) {
    current(day);
    if (side == null) throw new ScenarioViolation("必须指定阵营");
    Batch old = batches.get(side);
    if (!old.submitted() || old.version() != expectedVersion) throw conflict("请先提交并确认当前版本命令");
    batches.put(side, new Batch(old.version(), true, true, old.orders()));
    return view();
  }

  public Day resolve(int day) {
    if (history.containsKey(day)) return history.get(day);
    current(day);
    if (!view().status().equals("LOCKED")) throw conflict("双方命令确认锁定后才能结算");
    if (completedDays >= 60) throw new StoreProblem(StoreProblem.Kind.LIMIT, "M2 每场实验最多 60 天");
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
            ScenarioHash.sha256(world),
            blue,
            red);
    var result = new DaySimulation().resolve(world, day, seed, iterations, blue, red);
    var record = new Day(manifest, result);
    history.put(day, record);
    world = result.world();
    completedDays = day;
    reset();
    return record;
  }

  public Day day(int day) {
    var result = history.get(day);
    if (result == null) throw new StoreProblem(StoreProblem.Kind.NOT_FOUND, "该日尚未结算");
    return result;
  }

  private void current(int day) {
    if (completedDays >= 60) throw new StoreProblem(StoreProblem.Kind.LIMIT, "M2 每场实验最多 60 天");
    if (day != completedDays + 1) throw conflict("天数已变化，请重新载入；不能修改过去或未来的命令");
  }

  private void reset() {
    for (var side : Side.values()) batches.put(side, new Batch(0, false, false, List.of()));
  }

  private static StoreProblem conflict(String message) {
    return new StoreProblem(StoreProblem.Kind.CONFLICT, message);
  }
}
