package io.tactical.application;

import io.tactical.core.*;
import io.tactical.core.Scenario.Side;
import io.tactical.simulation.*;
import java.util.*;

/**
 * Paired runs always start from exactly the same immutable revision. No score or significance
 * claim.
 */
public final class ExperimentRunner {
  public record Variant(String name, List<ExperimentSetup> days) {
    public Variant {
      if (name == null
          || name.isBlank()
          || name.length() > 40
          || days == null
          || days.isEmpty()
          || days.size() > 30
          || days.stream().anyMatch(Objects::isNull))
        throw new ScenarioViolation("方案需要名称及 1–30 天的命令表");
      days = List.copyOf(days);
    }
  }

  public record Goal(String regimentId, HexCoord position) {}

  public record Request(
      String requestId,
      String revisionId,
      List<Long> seeds,
      int maxIterations,
      Variant a,
      Variant b,
      Goal goal) {
    public Request {
      if (requestId == null
          || !requestId.matches("[A-Za-z0-9_-]{1,64}")
          || revisionId == null
          || seeds == null
          || seeds.isEmpty()
          || seeds.size() > 16
          || seeds.stream().anyMatch(Objects::isNull)
          || new HashSet<>(seeds).size() != seeds.size()
          || maxIterations < 1
          || maxIterations > 8
          || a == null
          || b == null
          || a.days().size() != b.days().size()
          || 2 * seeds.size() * a.days().size() > 256)
        throw new ScenarioViolation("实验需要唯一请求 ID、1–16 个不重复种子、等长 A/B 日程、1–8 轮；总运行天数最多 256");
      seeds = List.copyOf(seeds);
    }
  }

  public record CompanyLoss(
      Side side,
      String regimentId,
      String companyId,
      int initialHp,
      int finalHp,
      int damage,
      int recovered) {}

  public record Retreat(
      int day,
      String regimentId,
      String supplyId,
      List<HexCoord> route,
      HexCoord dayEndPosition,
      boolean reachedSupply) {}

  public record TaskResult(int day, Side side, String orderId, String status) {}

  public record Metrics(
      String goalStatus,
      Integer completionDay,
      int simulatedDays,
      List<CompanyLoss> companies,
      int blueDamage,
      int redDamage,
      long waits,
      long ineffective,
      long dependencyDisorders,
      long taskFailures,
      List<Retreat> retreats,
      List<TaskResult> tasks) {}

  public record Run(
      int index,
      String variant,
      long seed,
      String status,
      String error,
      List<BattleSession.Day> days,
      Metrics metrics) {
    public Run {
      days = List.copyOf(days);
    }
  }

  public static void validate(Scenario scenario, Request request) {
    request.a().days().getFirst().validate(scenario);
    request.b().days().getFirst().validate(scenario);
    var goal = request.goal();
    if (goal != null
        && (goal.position() == null
            || goal.regimentId() == null
            || scenario.regiments().stream().noneMatch(r -> r.id().equals(goal.regimentId()))
            || scenario.cells().stream().noneMatch(c -> c.position().equals(goal.position()))))
      throw new ScenarioViolation("实验目标须引用冻结场景中的团与格子");
  }

  public static Run run(Scenario scenario, Request request, int index) {
    return run(scenario, request, index, () -> false);
  }

  public static Run run(
      Scenario scenario, Request request, int index, java.util.function.BooleanSupplier cancelled) {
    var variant = index % 2 == 0 ? request.a() : request.b();
    long seed = request.seeds().get(index / 2);
    var session = new BattleSession(scenario, seed, request.maxIterations());
    var days = new ArrayList<BattleSession.Day>();
    String error = "";
    for (int i = 0; i < variant.days().size(); i++) {
      if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
        throw new java.util.concurrent.CancellationException();
      var setup = variant.days().get(i);
      try {
        session.submit(i + 1, Side.BLUE, 0, setup.blue(), setup.blueOperation());
        session.submit(i + 1, Side.RED, 0, setup.red(), setup.redOperation());
        session.commit(i + 1, Side.BLUE, 1);
        session.commit(i + 1, Side.RED, 1);
        days.add(session.resolve(i + 1));
      } catch (ScenarioViolation | StoreProblem invalid) {
        error = "第 " + (i + 1) + " 天命令不可执行：" + invalid.getMessage();
        break;
      }
    }
    return new Run(
        index,
        index % 2 == 0 ? "A" : "B",
        seed,
        error.isEmpty() ? "COMPLETED" : "INVALID_PLAN",
        error,
        days,
        metrics(scenario, request.goal(), days));
  }

  public static Metrics metrics(Scenario initial, Goal goal, List<BattleSession.Day> days) {
    var events = days.stream().flatMap(d -> d.result().events().stream()).toList();
    var end = days.isEmpty() ? initial : days.getLast().result().world();
    var companies = new ArrayList<CompanyLoss>();
    for (var r : initial.regiments())
      for (var c : r.companies()) {
        int finalHp =
            end.regiments().stream()
                .filter(n -> n.id().equals(r.id()))
                .flatMap(n -> n.companies().stream())
                .filter(n -> n.id().equals(c.id()))
                .mapToInt(Scenario.Company::hp)
                .findFirst()
                .orElse(0);
        int damage =
            events.stream()
                .filter(
                    e ->
                        e.regimentId().equals(r.id())
                            && e.damage() != null
                            && e.damage().targetCompanyId().equals(c.id()))
                .mapToInt(e -> Math.max(0, e.damage().beforeHp() - e.damage().afterHp()))
                .sum();
        companies.add(
            new CompanyLoss(
                r.side(), r.id(), c.id(), c.hp(), finalHp, damage, finalHp - c.hp() + damage));
      }
    var tasks = new ArrayList<TaskResult>();
    long disorders = 0;
    for (var day : days)
      for (var side : Side.values()) {
        var orders = side == Side.BLUE ? day.manifest().blue() : day.manifest().red();
        var plan =
            side == Side.BLUE ? day.manifest().blueOperation() : day.manifest().redOperation();
        for (var o : orders) {
          var local =
              day.result().events().stream()
                  .filter(
                      e -> e.regimentId().equals(o.regimentId()) && e.orderId().equals(o.orderId()))
                  .toList();
          var state =
              day.result().operations().stream()
                  .filter(
                      t -> t.orderId().equals(o.orderId()) && t.regimentId().equals(o.regimentId()))
                  .findFirst();
          String status =
              state
                  .map(
                      t ->
                          t.fact().equals("SUCCESS")
                              ? "SUCCEEDED"
                              : t.fact().equals("FAILED") ? "FAILED" : "INCOMPLETE")
                  .orElseGet(
                      () ->
                          local.stream()
                                  .anyMatch(
                                      e ->
                                          Set.of(
                                                  "BLOCKED",
                                                  "IMPASSABLE",
                                                  "ACTION_FAILED",
                                                  "CANCELLED")
                                              .contains(e.kind()))
                              ? "FAILED"
                              : local.stream()
                                      .anyMatch(
                                          e ->
                                              e.category().equals("OrderTransition")
                                                  && e.kind().equals("COMPLETED"))
                                  ? "SUCCEEDED"
                                  : "INCOMPLETE");
          tasks.add(new TaskResult(day.manifest().day(), side, o.orderId(), status));
        }
        if (plan != null)
          for (var node : plan.nodes()) {
            var order =
                orders.stream()
                    .filter(o -> o.orderId().equals(node.orderId()))
                    .findFirst()
                    .orElseThrow();
            int start =
                day.result().events().stream()
                    .filter(
                        e ->
                            e.orderId().equals(node.orderId())
                                && e.regimentId().equals(order.regimentId())
                                && e.kind().startsWith("EXECUTING"))
                    .mapToInt(DaySimulation.Event::tick)
                    .min()
                    .orElse(Integer.MAX_VALUE);
            if (start == Integer.MAX_VALUE) continue;
            for (String dep : node.after()) {
              var source =
                  orders.stream().filter(o -> o.orderId().equals(dep)).findFirst().orElseThrow();
              if (day.result().events().stream()
                  .noneMatch(
                      e ->
                          e.orderId().equals(dep)
                              && e.regimentId().equals(source.regimentId())
                              && e.category().equals("OperationOutcome")
                              && e.kind().equals("SUCCEEDED")
                              && e.tick() < start)) disorders++;
            }
          }
      }
    Integer completed = null;
    if (goal != null)
      for (var day : days)
        if (day.result().world().regiments().stream()
            .anyMatch(
                r -> r.id().equals(goal.regimentId()) && r.position().equals(goal.position()))) {
          completed = day.manifest().day();
          break;
        }
    var retreats =
        events.stream()
            .filter(e -> e.decision() != null && e.decision().action().equals("WITHDRAW"))
            .map(
                e -> {
                  var world =
                      days.stream()
                          .filter(d -> d.manifest().day() == e.day())
                          .findFirst()
                          .orElseThrow()
                          .result()
                          .world();
                  var position =
                      world.regiments().stream()
                          .filter(r -> r.id().equals(e.regimentId()))
                          .map(Scenario.Regiment::position)
                          .findFirst()
                          .orElse(null);
                  boolean reached =
                      world.supplies().stream()
                          .anyMatch(
                              s ->
                                  s.id().equals(e.decision().targetSupplyId())
                                      && s.position().equals(position));
                  return new Retreat(
                      e.day(),
                      e.regimentId(),
                      e.decision().targetSupplyId(),
                      e.decision().route(),
                      position,
                      reached);
                })
            .toList();
    return new Metrics(
        goal == null ? "NOT_CONFIGURED" : completed == null ? "NOT_REACHED" : "REACHED",
        completed,
        days.size(),
        List.copyOf(companies),
        companies.stream().filter(c -> c.side() == Side.BLUE).mapToInt(CompanyLoss::damage).sum(),
        companies.stream().filter(c -> c.side() == Side.RED).mapToInt(CompanyLoss::damage).sum(),
        events.stream().filter(e -> e.kind().startsWith("WAITING")).count(),
        events.stream()
            .filter(
                e ->
                    Set.of("BLOCKED", "IMPASSABLE", "ACTION_FAILED", "CANCELLED")
                        .contains(e.kind()))
            .count(),
        disorders,
        tasks.stream().filter(t -> t.status().equals("FAILED")).count(),
        retreats,
        List.copyOf(tasks));
  }
}
