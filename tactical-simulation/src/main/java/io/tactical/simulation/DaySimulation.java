package io.tactical.simulation;

import static io.tactical.core.Scenario.*;

import io.tactical.core.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Single-threaded, integer-time simulation. Resolve all equal-time intents before moving anyone.
 */
public final class DaySimulation {
  public static final String RANDOM_VERSION = "sha256-keyed-v1";
  public static final int DEFAULT_ITERATIONS = 4;

  public record Event(
      int schemaVersion,
      String id,
      String category,
      String kind,
      int day,
      int iteration,
      int tick,
      int speed,
      String orderId,
      String regimentId,
      HexCoord from,
      HexCoord to,
      List<String> participants,
      String reason) {
    public Event {
      participants = List.copyOf(participants);
    }
  }

  public record Result(int day, Scenario world, String stateHash, List<Event> events) {
    public Result {
      events = List.copyOf(events);
    }
  }

  private static final class Task {
    final MovementOrder order;
    final Regiment regiment;
    int step, arrival, speed;
    boolean stopped;

    Task(MovementOrder order, Regiment regiment) {
      this.order = order;
      this.regiment = regiment;
    }

    HexCoord target() {
      return order.route().get(step);
    }
  }

  public static List<MovementOrder> validate(
      Scenario world, Side side, List<MovementOrder> orders) {
    if (side == null || orders == null || orders.size() > 32)
      throw new ScenarioViolation("必须指定阵营及最多 32 条命令");
    Set<String> ids = new HashSet<>(), units = new HashSet<>();
    for (var o : orders) {
      if (o == null || !ids.add(o.orderId()) || !units.add(o.regimentId()))
        throw new ScenarioViolation("命令 ID 和被指挥团不得重复");
      var r =
          world.regiments().stream()
              .filter(u -> u.id().equals(o.regimentId()))
              .findFirst()
              .orElseThrow(() -> new ScenarioViolation("命令中的团不存在"));
      if (r.side() != side) throw new ScenarioViolation("不得向另一方部队下令");
      if (r.role() == Role.DIVISION_HQ && !o.route().isEmpty())
        throw new ScenarioViolation("城市师部不可移动");
      var from = r.position();
      for (var to : o.route()) {
        if (to.q() < 0
            || to.r() < 0
            || to.q() >= world.width()
            || to.r() >= world.height()
            || from.distance(to) != 1) throw new ScenarioViolation("路径必须由地图内的相邻格组成");
        from = to;
      }
    }
    return orders.stream().sorted(Comparator.comparing(MovementOrder::regimentId)).toList();
  }

  public Result resolve(
      Scenario input,
      int day,
      long seed,
      int maxIterations,
      List<MovementOrder> blue,
      List<MovementOrder> red) {
    if (day < 1 || maxIterations < 1 || maxIterations > 8)
      throw new ScenarioViolation("天数须为正数，事件迭代上限为 1–8");
    var commands = new ArrayList<>(validate(input, Side.BLUE, blue));
    commands.addAll(validate(input, Side.RED, red));
    Map<String, HexCoord> positions = new TreeMap<>();
    input.regiments().forEach(r -> positions.put(r.id(), r.position()));
    List<Event> events = new ArrayList<>();
    List<Task> tasks = new ArrayList<>();
    // Common environmental departure delay cannot reverse any relative movement advantage.
    int departure = Integer.parseInt(key(seed, day, "environment").substring(0, 2), 16) % 4;
    int deadline = departure + maxIterations * 15;
    for (var o : commands) {
      var r =
          input.regiments().stream()
              .filter(u -> u.id().equals(o.regimentId()))
              .findFirst()
              .orElseThrow();
      var t = new Task(o, r);
      tasks.add(t);
      emit(
          events,
          day,
          0,
          departure,
          t,
          "OrderTransition",
          "EXECUTING",
          positions.get(r.id()),
          positions.get(r.id()),
          List.of(r.id()),
          "双方命令已锁定");
      schedule(input, positions, t, departure, day, 0, events);
    }
    for (int iteration = 1; iteration <= maxIterations; iteration++) {
      int horizon = departure + iteration * 15;
      while (true) {
        int tick =
            tasks.stream()
                .filter(t -> !t.stopped)
                .mapToInt(t -> t.arrival)
                .min()
                .orElse(Integer.MAX_VALUE);
        if (tick > horizon) break;
        List<Task> due =
            tasks.stream()
                .filter(t -> !t.stopped && t.arrival == tick)
                .sorted(
                    Comparator.comparing(
                        t -> key(seed, day, "schedule:" + tick + ":" + t.regiment.id())))
                .toList();
        Map<String, Task> moving = new TreeMap<>();
        due.forEach(t -> moving.put(t.regiment.id(), t));
        Map<HexCoord, List<Task>> targets = new HashMap<>();
        due.forEach(t -> targets.computeIfAbsent(t.target(), ignored -> new ArrayList<>()).add(t));
        Map<String, String> blocked = new TreeMap<>();
        Map<String, Set<String>> participants = new TreeMap<>();
        // Same empty cell and opposite-edge crossing are symmetric conflicts, never a free
        // defender.
        for (var group : targets.values())
          if (group.size() > 1) {
            for (var a : group)
              for (var b : group)
                if (a != b) conflict(a, b.regiment.id(), "CONTESTED", blocked, participants);
          }
        for (var a : due)
          for (var b : due)
            if (a != b
                && a.target().equals(positions.get(b.regiment.id()))
                && b.target().equals(positions.get(a.regiment.id()))) {
              conflict(a, b.regiment.id(), "CROSSING", blocked, participants);
              conflict(b, a.regiment.id(), "CROSSING", blocked, participants);
            }
        // A blocked departure also blocks followers, reaching a fixed point before applying moves.
        boolean changed;
        do {
          changed = false;
          for (var t : due) {
            String id = t.regiment.id();
            if (blocked.containsKey(id)) continue;
            for (var occupant : positions.entrySet()) {
              if (occupant.getValue().equals(t.target())
                  && (!moving.containsKey(occupant.getKey())
                      || blocked.containsKey(occupant.getKey()))) {
                conflict(t, occupant.getKey(), "OCCUPIED", blocked, participants);
                changed = true;
              }
            }
          }
        } while (changed);
        for (var t : due) {
          String id = t.regiment.id();
          HexCoord from = positions.get(id), to = t.target();
          if (blocked.containsKey(id)) {
            t.stopped = true;
            var peers = participants.get(id).stream().sorted().toList();
            emit(
                events,
                day,
                iteration,
                tick,
                t,
                "WorldEvent",
                "CONTACT",
                from,
                to,
                peers,
                blocked.get(id));
            emit(
                events,
                day,
                iteration,
                tick,
                t,
                "OrderTransition",
                "BLOCKED",
                from,
                to,
                peers,
                blocked.get(id));
          } else {
            emit(
                events,
                day,
                iteration,
                tick,
                t,
                "WorldEvent",
                "MOVED",
                from,
                to,
                List.of(id),
                "按最慢有效连速度到达");
          }
        }
        for (var t : due) if (!t.stopped) positions.put(t.regiment.id(), t.target());
        for (var t : due)
          if (!t.stopped) {
            t.step++;
            schedule(input, positions, t, tick, day, iteration, events);
          }
      }
    }
    for (var t : tasks)
      if (!t.stopped)
        emit(
            events,
            day,
            maxIterations,
            deadline,
            t,
            "OrderTransition",
            "DEFERRED",
            positions.get(t.regiment.id()),
            t.target(),
            List.of(t.regiment.id()),
            "达到当日迭代上限；剩余路径需次日重新下令");
    var regiments =
        input.regiments().stream()
            .map(
                r ->
                    new Regiment(
                        r.id(),
                        r.name(),
                        r.side(),
                        r.role(),
                        positions.get(r.id()),
                        r.brigadeId(),
                        r.companies()))
            .toList();
    Scenario world =
        ScenarioRules.normalize(
            new Scenario(
                input.schemaVersion(),
                input.name(),
                input.width(),
                input.height(),
                input.cells(),
                input.edges(),
                regiments,
                input.supplies()));
    return new Result(day, world, digest(day + ":" + ScenarioHash.sha256(world)), events);
  }

  private static void schedule(
      Scenario input,
      Map<String, HexCoord> positions,
      Task t,
      int tick,
      int day,
      int iteration,
      List<Event> events) {
    HexCoord from = positions.get(t.regiment.id());
    if (t.step == t.order.route().size()) {
      t.stopped = true;
      emit(
          events,
          day,
          iteration,
          tick,
          t,
          "OrderTransition",
          "COMPLETED",
          from,
          from,
          List.of(t.regiment.id()),
          t.order.route().isEmpty() ? "原地待命" : "到达路径终点");
      return;
    }
    int speed = Mobility.speed(input, t.regiment, from, t.target());
    t.speed = speed;
    if (speed == 0) {
      t.stopped = true;
      emit(
          events,
          day,
          iteration,
          tick,
          t,
          "OrderTransition",
          "IMPASSABLE",
          from,
          t.target(),
          List.of(t.regiment.id()),
          "存在不可通行的有效连，或河流无完好桥梁");
    } else t.arrival = tick + 60 / speed;
  }

  private static void conflict(
      Task t,
      String other,
      String reason,
      Map<String, String> blocked,
      Map<String, Set<String>> participants) {
    blocked.putIfAbsent(t.regiment.id(), reason);
    var group = participants.computeIfAbsent(t.regiment.id(), ignored -> new TreeSet<>());
    group.add(t.regiment.id());
    group.add(other);
  }

  private static void emit(
      List<Event> events,
      int day,
      int iteration,
      int tick,
      Task task,
      String category,
      String kind,
      HexCoord from,
      HexCoord to,
      List<String> participants,
      String reason) {
    events.add(
        new Event(
            1,
            "d" + day + "-e" + (events.size() + 1),
            category,
            kind,
            day,
            iteration,
            tick,
            task.speed,
            task.order.orderId(),
            task.regiment.id(),
            from,
            to,
            participants,
            reason));
  }

  private static String key(long seed, int day, String domain) {
    return digest(seed + ":" + day + ":" + domain);
  }

  private static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
