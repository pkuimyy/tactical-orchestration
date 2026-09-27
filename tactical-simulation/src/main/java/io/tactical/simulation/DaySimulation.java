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
      String reason,
      BattleRuntime.Damage damage,
      DoctrinePlanner.Decision decision,
      BattleRuntime.StockChange stock) {
    public Event {
      participants = List.copyOf(participants);
    }
  }

  public record Result(
      int day,
      Scenario world,
      String stateHash,
      List<Event> events,
      Map<String, RegimentMemory> memory,
      List<UnitReport> units,
      IntelligenceState intelligence,
      List<OperationRuntime.State> operations) {
    public Result {
      events = List.copyOf(events);
      memory = Collections.unmodifiableMap(new TreeMap<>(memory));
      units = List.copyOf(units);
      operations = List.copyOf(operations);
    }
  }

  /** A completed equal-time batch, never a partially applied combat state. */
  public record Frame(
      int day,
      int tick,
      String phase,
      Scenario world,
      Map<Side, IntelligenceState.Knowledge> divisions,
      int eventCount,
      List<OperationRuntime.State> operations) {
    public Frame {
      divisions = Collections.unmodifiableMap(new TreeMap<>(divisions));
      operations = List.copyOf(operations);
    }
  }

  private static Frame frame(
      int day,
      int tick,
      String phase,
      Scenario world,
      IntelligenceState intelligence,
      Map<String, IntelligenceState.Knowledge> observations,
      int eventCount,
      List<OperationRuntime> operations) {
    var divisions = new TreeMap<>(intelligence.divisions());
    for (var r : world.regiments())
      if (r.role() == Role.DIVISION_HQ)
        divisions.put(
            r.side(),
            divisions
                .get(r.side())
                .merge(observations.getOrDefault(r.id(), IntelligenceState.Knowledge.empty())));
    return new Frame(
        day,
        tick,
        phase,
        world,
        divisions,
        eventCount,
        operations.stream().flatMap(op -> op.states().stream()).toList());
  }

  public record CompanyReport(
      String id,
      CompanyType type,
      Equipment equipment,
      int maxHp,
      int beforeHp,
      int hp,
      int organization) {}

  public record UnitReport(
      String id,
      String name,
      HexCoord before,
      HexCoord after,
      boolean destroyed,
      int organization,
      List<CompanyReport> companies) {
    public UnitReport {
      companies = List.copyOf(companies);
    }
  }

  public static List<UnitReport> reports(Scenario before, Scenario after) {
    return before.regiments().stream()
        .map(
            r -> {
              var now = after.regiments().stream().filter(n -> n.id().equals(r.id())).findFirst();
              var companies =
                  r.companies().stream()
                      .map(
                          c -> {
                            var next =
                                now.flatMap(
                                        n ->
                                            n.companies().stream()
                                                .filter(v -> v.id().equals(c.id()))
                                                .findFirst())
                                    .orElse(
                                        new Company(c.id(), c.type(), c.equipment(), c.maxHp(), 0));
                            return new CompanyReport(
                                c.id(),
                                c.type(),
                                c.equipment(),
                                c.maxHp(),
                                c.hp(),
                                next.hp(),
                                CombatRules.organization(next));
                          })
                      .toList();
              return new UnitReport(
                  r.id(),
                  r.name(),
                  r.position(),
                  now.map(Regiment::position).orElse(null),
                  now.isEmpty(),
                  now.map(CombatRules::organization).orElse(0),
                  companies);
            })
        .sorted(Comparator.comparing(UnitReport::id))
        .toList();
  }

  private static final class Task {
    final MovementOrder order;
    Regiment regiment;
    List<HexCoord> route;
    boolean withdrawing;
    int step, arrival, speed;
    boolean stopped, pending;
    String waitState = "";
    OperationRuntime operation;

    Task(MovementOrder order, Regiment regiment) {
      this.order = order;
      this.regiment = regiment;
      this.route = order.route();
    }

    HexCoord target() {
      return order.target() != null && !withdrawing
          ? order.target()
          : step < route.size() ? route.get(step) : regiment.position();
    }
  }

  public static List<MovementOrder> validate(
      Scenario world, Side side, List<MovementOrder> orders) {
    return OrderRules.validate(world, side, orders);
  }

  public Result resolve(
      Scenario input,
      int day,
      long seed,
      int maxIterations,
      List<MovementOrder> blue,
      List<MovementOrder> red) {
    return resolve(input, day, seed, maxIterations, blue, red, RegimentMemory.initial(input));
  }

  public Result resolve(
      Scenario input,
      int day,
      long seed,
      int maxIterations,
      List<MovementOrder> blue,
      List<MovementOrder> red,
      Map<String, RegimentMemory> memory) {
    return resolve(
        input,
        day,
        seed,
        maxIterations,
        blue,
        red,
        memory,
        null,
        null,
        IntelligenceState.initial(input));
  }

  public Result resolve(
      Scenario input,
      int day,
      long seed,
      int maxIterations,
      List<MovementOrder> blue,
      List<MovementOrder> red,
      Map<String, RegimentMemory> memory,
      OperationPlan blueOperation,
      OperationPlan redOperation,
      IntelligenceState intelligence) {
    return resolve(
        input,
        day,
        seed,
        maxIterations,
        blue,
        red,
        memory,
        blueOperation,
        redOperation,
        intelligence,
        null);
  }

  public Result resolve(
      Scenario input,
      int day,
      long seed,
      int maxIterations,
      List<MovementOrder> blue,
      List<MovementOrder> red,
      Map<String, RegimentMemory> memory,
      OperationPlan blueOperation,
      OperationPlan redOperation,
      IntelligenceState intelligence,
      java.util.function.Consumer<Frame> observer) {
    var operations = new ArrayList<OperationRuntime>();
    if (blueOperation != null) {
      blueOperation.validate(input, Side.BLUE, blue);
      operations.add(new OperationRuntime(blueOperation, blue));
    }
    if (redOperation != null) {
      redOperation.validate(input, Side.RED, red);
      operations.add(new OperationRuntime(redOperation, red));
    }
    if (day < 1 || maxIterations < 1 || maxIterations > 8)
      throw new ScenarioViolation("天数须为正数，事件迭代上限为 1–8");
    var commands = new ArrayList<>(validate(input, Side.BLUE, blue));
    commands.addAll(validate(input, Side.RED, red));
    Map<String, HexCoord> positions = new TreeMap<>();
    input.regiments().forEach(r -> positions.put(r.id(), r.position()));
    List<Event> events = new ArrayList<>();
    Set<String> reportedOutcomes = new HashSet<>();
    List<Task> tasks = new ArrayList<>();
    // Common environmental departure delay cannot reverse any relative movement advantage.
    int departure = Integer.parseInt(key(seed, day, "environment").substring(0, 2), 16) % 4;
    int deadline = departure + maxIterations * 15;
    var battle =
        new BattleRuntime(
            input,
            positions,
            memory,
            commands.stream()
                .filter(o -> operations.stream().noneMatch(op -> op.contains(o.regimentId())))
                .toList(),
            events,
            day,
            departure);
    if (observer != null)
      observer.accept(frame(day, 0, "PLANNING", input, intelligence, Map.of(), 0, operations));
    int capturedEvents = 0;
    battle.priorKnowledge = intelligence.regiments();
    battle.observeAll();
    for (var o : commands) {
      var r =
          input.regiments().stream()
              .filter(u -> u.id().equals(o.regimentId()))
              .findFirst()
              .orElseThrow();
      var t = new Task(o, r);
      tasks.add(t);
      t.operation = operations.stream().filter(op -> op.contains(r.id())).findFirst().orElse(null);
      if (t.operation != null) {
        t.pending = true;
        t.arrival = departure + t.operation.delay(input, seed, day, r.id());
        // Delivery is attempted immediately, independent of prerequisite completion.
        t.operation.ready(input, r.id());
        continue;
      }
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
      if (o.target() != null) t.arrival = departure + 15;
      else schedule(input, positions, t, departure, day, 0, events);
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
        var currentWorld = battle.world();
        for (var op : operations) op.outcomes(currentWorld, events);
        for (var t : tasks)
          if (!t.stopped && t.pending && t.arrival == tick) {
            String status = t.operation.ready(currentWorld, t.regiment.id());
            if (!status.equals(t.waitState)) {
              emit(
                  events,
                  day,
                  iteration,
                  tick,
                  t,
                  "OperationTransition",
                  status,
                  positions.get(t.regiment.id()),
                  t.target(),
                  List.of(t.regiment.id()),
                  t.operation.reason(t.regiment.id()));
              t.waitState = status;
            }
            if (status.equals("CANCELLED") || status.equals("HOLD")) {
              t.stopped = true;
              emit(
                  events,
                  day,
                  iteration,
                  tick,
                  t,
                  "OrderTransition",
                  "CANCELLED",
                  positions.get(t.regiment.id()),
                  t.target(),
                  List.of(t.regiment.id()),
                  "前置失败分支：" + status);
            } else if (status.startsWith("EXECUTING")) {
              t.pending = false;
              battle.activate(t.order);
              if (t.order.target() != null) t.arrival = tick + 15;
              else schedule(currentWorld, positions, t, tick, day, iteration, events);
            } else t.arrival = tick + 1;
          }
        List<Task> allDue =
            tasks.stream()
                .filter(t -> !t.stopped && !t.pending && t.arrival == tick)
                .sorted(
                    Comparator.comparing(
                        t -> key(seed, day, "schedule:" + tick + ":" + t.regiment.id())))
                .toList();
        var bombs =
            allDue.stream()
                .filter(t -> t.order.action() == MovementOrder.Action.BOMBARD && !t.withdrawing)
                .toList();
        var infrastructure =
            allDue.stream()
                .filter(
                    t ->
                        !t.withdrawing
                            && (t.order.action() == MovementOrder.Action.BUILD_BRIDGE
                                || t.order.action() == MovementOrder.Action.STRIKE_RELAY))
                .toList();
        // Equal-time movement intents were scheduled against the pre-action world.
        for (var t : infrastructure) {
          boolean success = battle.infrastructure(t.order);
          t.stopped = true;
          emit(
              events,
              day,
              iteration,
              tick,
              t,
              "WorldEvent",
              success ? t.order.action().name() : "INFRASTRUCTURE_FAILED",
              positions.get(t.regiment.id()),
              t.target(),
              List.of(t.regiment.id()),
              success ? "设施行动生效；通信图即时重建" : "执行时能力或目标已失效");
          emit(
              events,
              day,
              iteration,
              tick,
              t,
              "OrderTransition",
              success ? "COMPLETED" : "ACTION_FAILED",
              positions.get(t.regiment.id()),
              t.target(),
              List.of(t.regiment.id()),
              "设施行动结束");
        }
        var due =
            allDue.stream().filter(t -> !bombs.contains(t) && !infrastructure.contains(t)).toList();
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
            battle.moved(t.regiment.id());
            t.step++;
          }
        Set<BattleRuntime.Pair> contacts = new HashSet<>();
        participants.forEach(
            (id, peers) ->
                peers.stream()
                    .filter(p -> !p.equals(id))
                    .forEach(
                        p ->
                            contacts.add(
                                id.compareTo(p) < 0
                                    ? new BattleRuntime.Pair(
                                        id,
                                        p,
                                        "CONTESTED".equals(blocked.get(id))
                                                && moving.containsKey(p)
                                                && moving
                                                    .get(id)
                                                    .target()
                                                    .equals(moving.get(p).target())
                                            ? moving.get(id).target()
                                            : null)
                                    : new BattleRuntime.Pair(
                                        p,
                                        id,
                                        "CONTESTED".equals(blocked.get(id))
                                                && moving.containsKey(p)
                                                && moving
                                                    .get(id)
                                                    .target()
                                                    .equals(moving.get(p).target())
                                            ? moving.get(id).target()
                                            : null))));
        battle.fight(contacts, bombs.stream().map(t -> t.order).toList(), tick);
        for (var t : tasks) {
          if (!battle.alive(t.regiment.id())) t.stopped = true;
          else {
            t.regiment = battle.regiment(t.regiment.id());
            if (battle.lastEngaged.contains(t.regiment.id())) t.stopped = true;
          }
        }
        for (var t : bombs) {
          t.stopped = true;
          emit(
              events,
              day,
              iteration,
              tick,
              t,
              "OrderTransition",
              battle.alive(t.regiment.id())
                      && t.regiment.companies().stream()
                          .anyMatch(c -> c.hp() > 0 && c.type() == CompanyType.ARTILLERY)
                  ? "COMPLETED"
                  : "ACTION_FAILED",
              t.regiment.position(),
              t.order.target(),
              List.of(t.regiment.id()),
              "炮击行动结束");
        }
        for (var t : due)
          if (battle.alive(t.regiment.id())
              && blocked.containsKey(t.regiment.id())
              && !positions.containsValue(t.target())
              && targets.get(t.target()).stream().filter(v -> battle.alive(v.regiment.id())).count()
                  == 1
              && participants.get(t.regiment.id()).stream()
                  .anyMatch(id -> battle.units.get(id).side() != t.regiment.side())) {
            var from = positions.get(t.regiment.id());
            var to = t.target();
            positions.put(t.regiment.id(), to);
            battle.moved(t.regiment.id());
            emit(
                events,
                day,
                iteration,
                tick,
                t,
                "WorldEvent",
                "ADVANCED",
                from,
                to,
                List.of(t.regiment.id()),
                "守军被歼灭或驱离，唯一存活进攻团进入目标格");
            emit(
                events,
                day,
                iteration,
                tick,
                t,
                "OrderTransition",
                t.step + 1 == t.route.size() ? "COMPLETED" : "DEFERRED",
                from,
                to,
                List.of(t.regiment.id()),
                "交战后停止本日进攻；如有剩余路线需次日重新下令");
          }
        Set<Task> newWithdrawals = new HashSet<>();
        for (var id : battle.lastEngaged)
          if (battle.alive(id)) {
            Task t =
                tasks.stream().filter(v -> v.regiment.id().equals(id)).findFirst().orElse(null);
            if (t != null && t.withdrawing) continue;
            boolean failed =
                t != null && blocked.containsKey(id) && !positions.get(id).equals(t.target());
            var decision = battle.decision(id, failed, tick);
            if (decision.action().equals("WITHDRAW")) {
              if (t == null) {
                t =
                    new Task(
                        new MovementOrder(
                            "default-" + Integer.toUnsignedString(id.hashCode()), id, List.of()),
                        battle.regiment(id));
                tasks.add(t);
              }
              t.regiment = battle.regiment(id);
              t.route = decision.route();
              t.step = 0;
              t.withdrawing = true;
              newWithdrawals.add(t);
              t.stopped = false;
              schedule(battle.world(), positions, t, tick, day, iteration, events);
            }
          }
        for (var t : due)
          if (!t.stopped && !newWithdrawals.contains(t))
            schedule(battle.world(), positions, t, tick, day, iteration, events);
        var current = battle.world();
        operations.forEach(op -> op.outcomes(current, events));
        publishOutcomes(operations, reportedOutcomes, events, day, iteration, tick);
        battle.observeAll();
        if (observer != null && events.size() != capturedEvents) {
          observer.accept(
              frame(
                  day,
                  tick,
                  "ACTION",
                  current,
                  intelligence,
                  battle.observations,
                  events.size(),
                  operations));
          capturedEvents = events.size();
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
    battle.rest(deadline);
    Scenario world = battle.world();
    operations.forEach(op -> op.outcomes(world, events));
    publishOutcomes(operations, reportedOutcomes, events, day, maxIterations, deadline);
    battle.observeAll();
    if (observer != null)
      observer.accept(
          frame(
              day,
              deadline,
              "DAY_END",
              world,
              intelligence,
              battle.observations,
              events.size(),
              operations));
    var nextIntelligence = intelligence.advance(world, day + 1, battle.observations);
    var nextMemory = nextIntelligence.shareSupplies(battle.memory);
    var reportQueue = new ArrayList<>(intelligence.pending());
    for (var r : world.regiments())
      if (battle.observations.containsKey(r.id()))
        reportQueue.add(
            new IntelligenceState.Report(r.id(), r.side(), day, battle.observations.get(r.id())));
    var network = new CommunicationNetwork(world);
    int reportSequence = 0;
    for (var report : reportQueue)
      if (network.toDivision(report.source())) {
        events.add(
            new Event(
                1,
                "d" + (day + 1) + "-report-" + (++reportSequence),
                "ReportDelivery",
                "REPORT_DELIVERED",
                day + 1,
                0,
                0,
                0,
                "",
                report.source(),
                null,
                null,
                List.of(report.source()),
                "observedDay="
                    + report.observedDay()
                    + "; deliveredDay="
                    + (day + 1)
                    + "; recipient="
                    + report.side()
                    + " division and connected units",
                null,
                null,
                null));
      }

    if (observer != null)
      observer.accept(
          frame(
              day + 1,
              0,
              "REPORTS_AVAILABLE",
              world,
              nextIntelligence,
              Map.of(),
              events.size(),
              operations));
    return new Result(
        day,
        world,
        stateHash(day, world, nextMemory, nextIntelligence),
        events,
        nextMemory,
        reports(input, world),
        nextIntelligence,
        operations.stream().flatMap(op -> op.states().stream()).toList());
  }

  private static void publishOutcomes(
      List<OperationRuntime> operations,
      Set<String> reported,
      List<Event> events,
      int day,
      int iteration,
      int tick) {
    for (var op : operations)
      for (var state : op.states()) {
        String key = state.regimentId() + ":" + state.orderId();
        if (!state.fact().equals("UNKNOWN") && reported.add(key)) {
          events.add(
              new Event(
                  1,
                  "d" + day + "-e" + (events.size() + 1),
                  "OperationOutcome",
                  state.fact().equals("SUCCESS") ? "SUCCEEDED" : "FAILED",
                  day,
                  iteration,
                  tick,
                  0,
                  state.orderId(),
                  state.regimentId(),
                  state.resultPosition(),
                  state.resultPosition(),
                  List.of(state.regimentId()),
                  "operation="
                      + state.operationId()
                      + "; fact="
                      + state.fact()
                      + "; 任务结果与后续学说行动分别记录",
                  null,
                  null,
                  null));
        }
      }
  }

  public static String stateHash(int day, Scenario world, Map<String, RegimentMemory> memory) {
    var text = new StringBuilder(day + ":" + ScenarioHash.runtime(world));
    new TreeMap<>(memory)
        .forEach(
            (id, m) -> {
              var d = m.doctrine();
              text.append("\n")
                  .append(id)
                  .append('|')
                  .append(d.schemaVersion())
                  .append('|')
                  .append(d.template())
                  .append('|')
                  .append(d.withdrawBelowPercent())
                  .append('|')
                  .append(d.supplyId());
              for (var k : m.supplies())
                text.append(";")
                    .append(k.id())
                    .append('|')
                    .append(k.position().q())
                    .append('|')
                    .append(k.position().r())
                    .append('|')
                    .append(k.side())
                    .append('|')
                    .append(k.stock())
                    .append('|')
                    .append(k.observedDay());
            });
    return digest(text.toString());
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
    if (t.step == t.route.size()) {
      t.stopped = true;
      if (t.order.action() == MovementOrder.Action.REST && !t.withdrawing) {
        emit(
            events,
            day,
            iteration,
            tick,
            t,
            "OrderTransition",
            "WAITING",
            from,
            from,
            List.of(t.regiment.id()),
            "等待当日火力与补给条件确认");
        return;
      }
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
          t.route.isEmpty() ? "原地待命" : "到达路径终点");
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
            reason,
            null,
            null,
            null));
  }

  private static String key(long seed, int day, String domain) {
    return digest(seed + ":" + day + ":" + domain);
  }

  public static String stateHash(
      int day, Scenario world, Map<String, RegimentMemory> memory, IntelligenceState intelligence) {
    return digest(stateHash(day, world, memory) + "\n" + intelligence.toString());
  }

  static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
